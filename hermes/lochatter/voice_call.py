"""Assistant voice call (2.0).

The phone places the call. This process answers it with aiortc when that library is installed.
Opus stays on the NAS: energy VAD cuts utterances, the hub transcribes and speaks, and Hermes
handles the text the same way it handles a typed question. The chat server only relays
signaling and call.caption. It never sees the audio.

Model names are not guessed. Set LOCHATTER_STT_MODEL and LOCHATTER_TTS_MODEL (or the same
keys in HERMES_HOME/.env) to whatever the hub actually serves. The hub key is OPENAI_API_KEY
or LOCHATTER_STT_KEY. LOCHATTER_HUB_BASE defaults to https://hub.example.com/v1.
"""

from __future__ import annotations

import asyncio
import io
import logging
import os
import struct
import uuid
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

GREETING = "我在，你说。"
MAX_PENDING = 3
MAX_TTS = 8
FRAME_MS = 20
SAMPLE_RATE = 16000
FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS // 1000
FRAME_BYTES = FRAME_SAMPLES * 2

_STOPS = set("。！？!?；;\n")
_COMMAS = set("，,")


def split_ready(text: str, final: bool, allow_comma: bool) -> Tuple[List[str], str]:
    """Cut finished sentences off the front. The unfinished tail stays for the next delta."""
    seps = _STOPS | (_COMMAS if allow_comma else set())
    ready: List[str] = []
    start = 0
    i = 0
    while i < len(text):
        ch = text[i]
        if ch in seps:
            piece = text[start:i + 1].strip()
            short_comma = ch in _COMMAS and len(piece) < 8
            if piece and not short_comma:
                ready.append(piece)
                start = i + 1
        i += 1
    rest = text[start:]
    if final:
        tail = rest.strip()
        if tail:
            ready.append(tail)
        rest = ""
    return ready, rest


def limit_sentences(pieces: List[str], n: int = MAX_TTS) -> Tuple[List[str], bool]:
    if len(pieces) <= n:
        return pieces, False
    return pieces[:n], True


class Speaker:
    """Turns growing agent text into sentences, once each."""

    def __init__(self) -> None:
        self.reset()

    def reset(self) -> None:
        self.queued = 0
        self.buf = ""
        self.first = True

    def consume(self, text: str, final: bool) -> List[str]:
        text = (text or "").strip()
        if not text:
            return []
        if self.buf and not text.startswith(self.buf) and not self.buf.startswith(text):
            self.queued = 0
            self.buf = ""
            self.first = True
        self.buf = text
        if self.queued > len(text):
            self.queued = 0
        fresh = text[self.queued:]
        ready, rest = split_ready(fresh, final, allow_comma=self.first)
        self.queued = len(text) - len(rest)
        if ready:
            self.first = False
        return ready


class TurnGate:
    """One agent run per utterance. A retry after failure may claim again; a barge-in may not."""

    def __init__(self) -> None:
        self.generation = 0
        self.started: Dict[str, Tuple[str, int]] = {}

    def interrupt(self) -> int:
        self.generation += 1
        for key, state in list(self.started.items()):
            if state[0] == "running":
                self.started[key] = ("cancelled", state[1])
        return self.generation

    def stale(self, gen: int) -> bool:
        return gen != self.generation

    def running(self) -> int:
        return sum(1 for state in self.started.values() if state[0] == "running")

    def claim(self, utter_id: str) -> Optional[int]:
        state = self.started.get(utter_id)
        if state and state[0] in ("running", "done", "cancelled"):
            return None
        self.started[utter_id] = ("running", self.generation)
        return self.generation

    def finish(self, utter_id: str, gen: int, ok: bool) -> None:
        state = self.started.get(utter_id)
        if not state or state[1] != gen or state[0] != "running":
            return
        if ok:
            self.started[utter_id] = ("done", gen)
        else:
            self.started.pop(utter_id, None)


def rms(pcm: bytes) -> int:
    n = len(pcm) // 2
    if n <= 0:
        return 0
    acc = 0
    for i in range(0, n * 2, 2):
        sample = int.from_bytes(pcm[i:i + 2], "little", signed=True)
        acc += sample * sample
    return int((acc / n) ** 0.5)


class EnergyVad:
    """20 ms energy VAD. While the assistant is speaking, speech comes back as 'barge'."""

    def __init__(self) -> None:
        self.pending = bytearray()
        self.speech = bytearray()
        self.in_speech = False
        self.voice_ms = 0
        self.silence_ms = 0
        self.utterance = b""

    def push(self, pcm: bytes, playing: bool) -> Optional[str]:
        self.pending += pcm
        event = None
        while len(self.pending) >= FRAME_BYTES:
            frame = bytes(self.pending[:FRAME_BYTES])
            del self.pending[:FRAME_BYTES]
            loud = rms(frame) >= (380 if playing else 500)
            if not self.in_speech:
                if loud:
                    self.voice_ms += FRAME_MS
                    self.speech += frame
                    need = 200 if playing else 60
                    if self.voice_ms >= need:
                        self.in_speech = True
                        event = "barge" if playing else "start"
                else:
                    self.voice_ms = 0
                    self.speech.clear()
                continue
            self.speech += frame
            if loud:
                self.silence_ms = 0
            else:
                self.silence_ms += FRAME_MS
            too_long = len(self.speech) >= SAMPLE_RATE * 2 * 20
            if self.silence_ms >= 600 or too_long:
                pcm_out = bytes(self.speech)
                self._reset()
                if len(pcm_out) >= SAMPLE_RATE * 2 * 300 // 1000:
                    self.utterance = pcm_out
                    return "end"
                event = None
        return event

    def _reset(self) -> None:
        self.in_speech = False
        self.voice_ms = 0
        self.silence_ms = 0
        self.speech.clear()


def wav_bytes(pcm: bytes, rate: int = SAMPLE_RATE) -> bytes:
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF", 36 + len(pcm), b"WAVE", b"fmt ", 16,
        1, 1, rate, rate * 2, 2, 16, b"data", len(pcm),
    )
    return header + pcm


def _read_env_file() -> Dict[str, str]:
    home = os.path.expanduser(os.environ.get("HERMES_HOME", "~/.hermes"))
    found: Dict[str, str] = {}
    for path in (os.path.join(home, ".env"), os.path.join(home, "config.env")):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                for line in handle:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, value = line.split("=", 1)
                    found[key.strip()] = value.strip().strip('"').strip("'")
        except OSError:
            continue
    return found


class HubAudio:
    def __init__(self) -> None:
        env = _read_env_file()
        self.base = (
            os.environ.get("LOCHATTER_HUB_BASE")
            or os.environ.get("OPENAI_BASE_URL")
            or env.get("LOCHATTER_HUB_BASE")
            or env.get("OPENAI_BASE_URL")
            or "https://hub.example.com/v1"
        ).rstrip("/")
        self.key = (
            os.environ.get("LOCHATTER_STT_KEY")
            or os.environ.get("LOCHATTER_TTS_KEY")
            or os.environ.get("OPENAI_API_KEY")
            or os.environ.get("HERMES_CUSTOM_HUB_API_JVM_INK_API_KEY")
            or env.get("LOCHATTER_STT_KEY")
            or env.get("OPENAI_API_KEY")
            or env.get("HERMES_CUSTOM_HUB_API_JVM_INK_API_KEY")
            or ""
        )
        self.stt_model = os.environ.get("LOCHATTER_STT_MODEL") or env.get("LOCHATTER_STT_MODEL") or ""
        self.tts_model = os.environ.get("LOCHATTER_TTS_MODEL") or env.get("LOCHATTER_TTS_MODEL") or ""
        self.voice = os.environ.get("LOCHATTER_TTS_VOICE") or env.get("LOCHATTER_TTS_VOICE") or ""

    @property
    def ready(self) -> bool:
        return bool(self.key)


def _tone(ms: int, amp: int) -> bytes:
    samples = (ms // FRAME_MS) * FRAME_SAMPLES
    return b"".join(int(amp).to_bytes(2, "little", signed=True) for _ in range(samples))


class AssistantCall:
    """One WebRTC session with a phone. Constructed only after aiortc imports."""

    def __init__(self, adapter: Any, call_id: str, peer_id: int) -> None:
        self.adapter = adapter
        self.call_id = call_id
        self.peer_id = peer_id
        self.gate = TurnGate()
        self.vad = EnergyVad()
        self.speaker = Speaker()
        self.hub = HubAudio()
        self.offer_q: asyncio.Queue = asyncio.Queue()
        self.turn_event = asyncio.Event()
        self.turn: Dict[str, Any] = {}
        self.tasks: List[asyncio.Task] = []
        self.agent_tasks: List[asyncio.Task] = []
        self.pc = None
        self.player = None
        self.http = None
        self.closed = False
        self.muted = False
        self.reply_gen = -1
        self.voice_mid = ""
        self._greeted = False
        self._resampler = None
        self._pending_ice: List[Dict[str, Any]] = []
        self._remote_set = False
        self._tts_count = 0
        self._hung = False

    def offer_turn(self, frame: Dict[str, Any]) -> None:
        self.turn = frame
        self.turn_event.set()

    def note_reply(self, text: str, final: bool, metadata: Optional[Dict[str, Any]]) -> None:
        if self.closed or self.reply_gen < 0 or self.gate.stale(self.reply_gen):
            return
        mid = None
        if isinstance(metadata, dict):
            mid = metadata.get("lochatter_reply_to") or metadata.get("reply_to_message_id")
        if self.voice_mid and mid and str(mid) != self.voice_mid:
            return
        pieces = self.speaker.consume(text, final)
        pieces, trimmed = limit_sentences(pieces)
        gen = self.reply_gen
        if pieces:
            self._agent(asyncio.create_task(self._speak_pieces(pieces, gen)))
        if trimmed:
            self._agent(asyncio.create_task(self._caption("assistant", "后面几句先不念了", "final", "error")))

    def _track(self, task: asyncio.Task) -> None:
        self.tasks.append(task)
        task.add_done_callback(lambda t: self.tasks.remove(t) if t in self.tasks else None)

    def _agent(self, task: asyncio.Task) -> None:
        self.agent_tasks.append(task)
        task.add_done_callback(lambda t: self.agent_tasks.remove(t) if t in self.agent_tasks else None)

    async def run(self) -> None:
        try:
            await self._run()
        finally:
            await self.close()

    async def _run(self) -> None:
        try:
            import httpx
            from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
        except ImportError:
            logger.warning("aiortc/httpx missing; assistant call %s rejected", self.call_id[:8])
            await self._signal({"t": "call.reject", "callId": self.call_id, "reason": "unavailable"})
            return
        self.http = httpx.AsyncClient(timeout=httpx.Timeout(30.0))
        await self._signal({"t": "turn.get"})
        try:
            await asyncio.wait_for(self.turn_event.wait(), 2)
        except asyncio.TimeoutError:
            pass
        self.pc = RTCPeerConnection(configuration=RTCConfiguration(iceServers=_ice_servers(self.turn)))
        self.player = _make_player()
        self.pc.addTrack(self.player)
        self.pc.on("track", self._on_track)
        self.pc.on("connectionstatechange", self._on_state)
        await self._signal({"t": "call.accept", "callId": self.call_id})
        try:
            sdp = await asyncio.wait_for(self.offer_q.get(), 20)
        except asyncio.TimeoutError:
            await self._signal({"t": "call.hangup", "callId": self.call_id, "reason": "timeout"})
            return
        if self.closed or not sdp:
            return
        await self.pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="offer"))
        self._remote_set = True
        for iced in self._pending_ice:
            await self._apply_ice(iced)
        self._pending_ice.clear()
        await self.pc.setLocalDescription(await self.pc.createAnswer())
        for _ in range(30):
            if self.pc.iceGatheringState == "complete":
                break
            await asyncio.sleep(0.05)
        await self._signal({
            "t": "call.sdp", "callId": self.call_id, "type": "answer",
            "sdp": self.pc.localDescription.sdp,
        })
        while not self.closed:
            await asyncio.sleep(0.5)

    async def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.offer_q.put_nowait("")
        except Exception:  # noqa: BLE001
            pass
        self.gate.interrupt()
        current = asyncio.current_task()
        for task in list(self.tasks) + list(self.agent_tasks):
            if task is not current and not task.done():
                task.cancel()
        pc = self.pc
        self.pc = None
        if pc is not None:
            try:
                await pc.close()
            except Exception:  # noqa: BLE001
                pass
        http = self.http
        self.http = None
        if http is not None:
            try:
                await http.aclose()
            except Exception:  # noqa: BLE001
                pass
        if getattr(self.adapter, "_call", None) is self:
            self.adapter._call = None

    async def on_signal(self, frame: Dict[str, Any]) -> None:
        kind = frame.get("t")
        if kind in ("call.hangup", "call.reject"):
            self._hung = True
            await self.close()
            return
        if kind == "call.sdp" and frame.get("type") == "offer":
            await self.offer_q.put(frame.get("sdp") or "")
            return
        if kind == "call.ice":
            await self._add_ice(frame)
            return
        if kind == "call.media":
            self.muted = frame.get("audio") is False

    def _on_track(self, track: Any) -> None:
        if getattr(track, "kind", "") == "audio":
            self._track(asyncio.create_task(self._consume(track)))

    def _on_state(self) -> None:
        pc = self.pc
        if pc is None:
            return
        state = pc.connectionState
        if state == "connected" and not self._greeted:
            self._greeted = True
            self._agent(asyncio.create_task(self._speak_pieces([GREETING], self.gate.generation)))
        elif state == "failed" and not self.closed:
            self._track(asyncio.create_task(self._give_up("failed")))

    async def _give_up(self, reason: str) -> None:
        if self._hung or self.closed:
            return
        self._hung = True
        await self._signal({"t": "call.hangup", "callId": self.call_id, "reason": reason})
        await self.close()

    async def _consume(self, track: Any) -> None:
        while not self.closed:
            try:
                frame = await track.recv()
            except Exception:  # noqa: BLE001
                return
            if self.muted:
                continue
            pcm = self._pcm16k(frame)
            if not pcm:
                continue
            playing = self.is_playing()
            event = self.vad.push(pcm, playing)
            if event == "barge":
                await self.interrupt()
            elif event == "end":
                if playing or self.is_playing():
                    await self.interrupt()
                utter = self.vad.utterance
                self._agent(asyncio.create_task(self._utter(utter, uuid.uuid4().hex, False)))

    def is_playing(self) -> bool:
        if self._tts_count > 0:
            return True
        queue = getattr(self.player, "queue", None)
        return queue is not None and not queue.empty()

    async def interrupt(self) -> None:
        self.gate.interrupt()
        self.reply_gen = -1
        self._drop_audio()
        current = asyncio.current_task()
        for task in list(self.agent_tasks):
            if task is not current and not task.done():
                task.cancel()

    async def _utter(self, pcm: bytes, utter_id: str, retried: bool) -> None:
        if self.closed:
            return
        if self.gate.running() >= MAX_PENDING:
            await self._caption("user", "说得太快了，等这句说完", "final", "error")
            return
        gen = self.gate.claim(utter_id)
        if gen is None:
            return
        await self._caption("user", "", "partial", "thinking")
        text = ""
        ok = False
        try:
            text = await self._transcribe(pcm, gen)
            ok = bool(text)
        except Exception as exc:  # noqa: BLE001
            logger.info("voice stt failed: %s", type(exc).__name__)
        self.gate.finish(utter_id, gen, ok and not self.gate.stale(gen))
        if self.closed or self.gate.stale(gen):
            return
        if not ok:
            if not retried:
                await self._utter(pcm, utter_id, True)
            else:
                await self._caption("user", "没听清，再说一次", "final", "error")
            return
        await self._caption("user", text, "final", "thinking")
        self.reply_gen = gen
        self.speaker.reset()
        try:
            event = self.adapter.voice_event(text, self.peer_id)
            self.voice_mid = str(getattr(event, "message_id", "") or "")
            await self.adapter.handle_message(event)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.info("voice agent failed: %s", type(exc).__name__)
            if not self.gate.stale(gen):
                await self._caption("assistant", "没想好，再说一次", "final", "error")
        finally:
            if self.reply_gen == gen:
                self.reply_gen = -1

    async def _transcribe(self, pcm: bytes, gen: int) -> str:
        if self.hub.ready and self.hub.stt_model and self.http is not None:
            try:
                text = await self._transcribe_hub(pcm, gen)
                if text:
                    return text
            except Exception as exc:  # noqa: BLE001
                logger.info("hub stt failed: %s", type(exc).__name__)
        if self.gate.stale(gen):
            return ""
        return await self._transcribe_chat(pcm)

    async def _transcribe_hub(self, pcm: bytes, gen: int) -> str:
        data = {"language": "zh", "model": self.hub.stt_model}
        files = {"file": ("speech.wav", wav_bytes(pcm), "audio/wav")}
        response = await self.http.post(
            self.hub.base + "/audio/transcriptions",
            headers={"Authorization": "Bearer " + self.hub.key},
            data=data, files=files,
        )
        if self.gate.stale(gen):
            return ""
        if response.status_code >= 300:
            raise RuntimeError("stt http %s" % response.status_code)
        return str(response.json().get("text") or "").strip()

    async def _transcribe_chat(self, pcm: bytes) -> str:
        """The hub model list has no whisper. The chat server already proxies SenseVoice at POST /stt."""
        if self.http is None:
            raise RuntimeError("no http")
        env = _read_env_file()
        token = os.environ.get("LOCHATTER_TOKEN") or env.get("LOCHATTER_TOKEN") or ""
        base = (os.environ.get("LOCHATTER_URL") or env.get("LOCHATTER_URL") or "https://chat.example.com").rstrip("/")
        if not token:
            raise RuntimeError("no lochatter token")
        files = {"file": ("speech.wav", wav_bytes(pcm), "audio/wav")}
        data = {"language": "zh"}
        response = await self.http.post(
            base + "/stt",
            headers={"Authorization": "Bearer " + token},
            data=data, files=files,
        )
        if response.status_code >= 300:
            raise RuntimeError("chat stt http %s" % response.status_code)
        return str(response.json().get("text") or "").strip()

    async def _speak_pieces(self, pieces: List[str], gen: int) -> None:
        self._tts_count += 1
        try:
            for piece in pieces:
                if self.closed or self.gate.stale(gen):
                    return
                await self._caption("assistant", piece, "partial", "speaking")
                audio = None
                try:
                    audio = await self._tts(piece, gen)
                except Exception as exc:  # noqa: BLE001
                    logger.info("voice tts failed: %s", type(exc).__name__)
                if self.gate.stale(gen) or self.closed:
                    return
                if audio:
                    await self._play(self._to_pcm48(audio), gen)
                    if self.gate.stale(gen) or self.closed:
                        return
                    await self._caption("assistant", piece, "final", "idle")
                else:
                    # Hub has no speech model yet. The phone reads this caption aloud.
                    await self._caption("assistant", piece, "final", "speaking")
        finally:
            self._tts_count = max(0, self._tts_count - 1)

    async def _tts(self, text: str, gen: int) -> Optional[bytes]:
        if not self.hub.ready or self.http is None:
            raise RuntimeError("hub key missing")
        payload: Dict[str, Any] = {"input": text, "response_format": "mp3"}
        if self.hub.tts_model:
            payload["model"] = self.hub.tts_model
        if self.hub.voice:
            payload["voice"] = self.hub.voice
        async with self.http.stream(
            "POST", self.hub.base + "/audio/speech",
            headers={"Authorization": "Bearer " + self.hub.key},
            json=payload,
        ) as response:
            if response.status_code >= 300:
                raise RuntimeError("tts http %s" % response.status_code)
            buf = bytearray()
            async for chunk in response.aiter_bytes():
                if self.closed or self.gate.stale(gen):
                    await response.aclose()
                    return None
                buf += chunk
        return bytes(buf)

    async def _play(self, pcm: bytes, gen: int) -> None:
        player = self.player
        if player is None:
            return
        frame_bytes = 960 * 2
        for offset in range(0, len(pcm), frame_bytes):
            if self.closed or self.gate.stale(gen):
                self._drop_audio()
                return
            chunk = pcm[offset:offset + frame_bytes]
            if len(chunk) < frame_bytes:
                chunk += b"\x00" * (frame_bytes - len(chunk))
            await player.queue.put(chunk)

    def _drop_audio(self) -> None:
        queue = getattr(self.player, "queue", None)
        if queue is None:
            return
        while not queue.empty():
            try:
                queue.get_nowait()
            except Exception:  # noqa: BLE001
                return

    def _pcm16k(self, frame: Any) -> bytes:
        try:
            import av
        except ImportError:
            return b""
        if self._resampler is None:
            self._resampler = av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)
        out = bytearray()
        for item in self._resampler.resample(frame):
            out += bytes(item.planes[0])
        return bytes(out)

    def _to_pcm48(self, audio: bytes) -> bytes:
        import av
        container = av.open(io.BytesIO(audio))
        resampler = av.AudioResampler(format="s16", layout="mono", rate=48000)
        out = bytearray()
        for frame in container.decode(audio=0):
            for item in resampler.resample(frame):
                out += bytes(item.planes[0])
        for item in resampler.resample(None):
            out += bytes(item.planes[0])
        return bytes(out)

    async def _add_ice(self, frame: Dict[str, Any]) -> None:
        if not self._remote_set or self.pc is None:
            self._pending_ice.append(frame)
            return
        await self._apply_ice(frame)

    async def _apply_ice(self, frame: Dict[str, Any]) -> None:
        raw = str(frame.get("candidate") or "")
        if not raw or self.pc is None:
            return
        try:
            from aiortc.sdp import candidate_from_sdp
            line = raw.split(":", 1)[1] if raw.startswith("candidate:") else raw
            cand = candidate_from_sdp(line)
            cand.sdpMid = frame.get("sdpMid")
            idx = frame.get("sdpMLineIndex")
            cand.sdpMLineIndex = int(idx) if idx is not None else 0
            await self.pc.addIceCandidate(cand)
        except Exception as exc:  # noqa: BLE001
            logger.info("voice ice skipped: %s", type(exc).__name__)

    async def _caption(self, who: str, text: str, state: str, phase: str) -> None:
        await self._signal({
            "t": "call.caption", "callId": self.call_id, "who": who,
            "text": text[:2000], "state": state, "phase": phase,
        })

    async def _signal(self, frame: Dict[str, Any]) -> None:
        send = getattr(self.adapter, "_send_signal", None)
        if send is None:
            return
        await send(frame)


def _ice_servers(creds: Dict[str, Any]) -> list:
    from aiortc import RTCIceServer
    urls = creds.get("urls") or []
    user = creds.get("username") or ""
    password = creds.get("credential") or ""
    servers = []
    for url in urls:
        if user and str(url).startswith("turn:"):
            servers.append(RTCIceServer(urls=url, username=user, credential=password))
        else:
            servers.append(RTCIceServer(urls=url))
    if not servers:
        servers.append(RTCIceServer(urls="stun:stun.l.google.com:19302"))
    return servers


def _make_player():
    import fractions
    import av
    from aiortc import MediaStreamTrack

    class PcmOut(MediaStreamTrack):
        kind = "audio"

        def __init__(self) -> None:
            super().__init__()
            self.queue: asyncio.Queue = asyncio.Queue(maxsize=50)
            self._ts = 0

        async def recv(self):
            try:
                pcm = await asyncio.wait_for(self.queue.get(), 0.02)
            except asyncio.TimeoutError:
                pcm = b"\x00" * (960 * 2)
            frame = av.AudioFrame(format="s16", layout="mono", samples=len(pcm) // 2)
            frame.planes[0].update(pcm)
            frame.sample_rate = 48000
            frame.pts = self._ts
            frame.time_base = fractions.Fraction(1, 48000)
            self._ts += frame.samples
            return frame

    return PcmOut()
