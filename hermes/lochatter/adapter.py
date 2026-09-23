"""lochatter platform adapter: Hermes joins the two-person chat as the named assistant.

Transport: one outbound WebSocket to ``<LOCHATTER_URL>/ws`` with ``Authorization: Bearer <LOCHATTER_TOKEN>``
(the token comes from ``chatterctl bot token`` on the chat server; the server maps it to reserved user 0).
The server only forwards frames the assistant is allowed to see: ``msg.new`` for messages the humans
@-mentioned it in (``to == "bot"``, always plaintext), its own replies, and ``del`` / ``clear`` control
entries (so it can drop cached media). End-to-end encrypted chat between the two humans never reaches this
process.

Outbound: ``msg.send`` text (streamed by ``edit``-ing the same bubble), ``card`` for proactive/cron
delivery, images/files via ``POST /media``.

Design notes (1.3.0):
* The WebSocket read loop never awaits Hermes: every inbound question is handled in its own task, so acks
  and pongs keep flowing while Hermes is busy (interrupts, session locks).
* Sends are idempotent: the same content sent again within ``SEND_REUSE_WINDOW`` reuses the message id; the
  server de-duplicates by id and returns the original seq, so a retry can never produce a second bubble.
* An ack that misses ``ACK_TIMEOUT`` while the socket is still open is treated as delivered (the server
  acks only after it stored the row); only a broken socket is reported as retryable.
* The sync cursor is persisted to ``$HERMES_HOME/lochatter.seq`` so questions asked while Hermes restarts
  are still answered.

Env: LOCHATTER_TOKEN (required), LOCHATTER_URL (default https://chat.example.com), LOCHATTER_HOME_CHANNEL.
"""

import asyncio
import hashlib
import json
import logging
import mimetypes
import os
import re
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import quote as _urlquote

try:
    import httpx
    HTTPX_AVAILABLE = True
except ImportError:  # pragma: no cover
    HTTPX_AVAILABLE = False
    httpx = None  # type: ignore[assignment]

try:
    import websockets
    WS_AVAILABLE = True
except ImportError:  # pragma: no cover
    WS_AVAILABLE = False
    websockets = None  # type: ignore[assignment]

from gateway.config import Platform, PlatformConfig
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent, MessageType
from gateway.platforms._shared import (
    get_scoped_secret as _get_scoped_secret, seed_extra_from_env as _seed_extra_from_env, send_error,
)
from gateway.platforms.helpers import MessageDeduplicator

logger = logging.getLogger(__name__)


def _voice_module():
    """Load voice_call.py from this directory. Hermes may not have the folder on sys.path."""
    cached = getattr(_voice_module, "mod", None)
    if cached is not None:
        return cached
    import importlib.util
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "voice_call.py")
    spec = importlib.util.spec_from_file_location("lochatter_voice_call", path)
    if spec is None or spec.loader is None:
        raise ImportError(path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    _voice_module.mod = mod
    return mod

DEFAULT_URL = "https://chat.example.com"
CHAT_ID = "chat"  # there is exactly one conversation
MAX_MESSAGE_LENGTH = 18_000  # server MaxTextChars is 20 000; leave headroom for markdown
RECONNECT_BACKOFF = [2, 5, 10, 30, 60]
PING_EVERY = 25.0
PONG_DEADLINE = 15.0
ACK_TIMEOUT = 30.0
LATE_ACK_TTL = 120.0  # how long an ack without a waiter is remembered
DEDUP_WINDOW_SECONDS = 600
SEND_REUSE_WINDOW = 120.0  # same content sent again within this window reuses the same message id
QUESTION_WINDOW = 900.0  # last-resort reply pairing: most recent question within 15 minutes
TYPING_EVERY = 4.0  # typing heartbeat while a question is being worked on
EDIT_COALESCE = 0.7  # streamed edits closer than this are merged
SEQ_WRITE_EVERY = 2.0  # sync-cursor file is rewritten at most this often
STICKER_TEXT = "[表情]"
DROPPED_PREFIX = "dropped:"  # synthetic id returned for filtered control chatter
KIND_TO_TYPE = {"image": MessageType.PHOTO, "video": MessageType.VIDEO, "audio": MessageType.VOICE, "file": MessageType.DOCUMENT}
_TRUTHY = ("1", "true", "yes")
_INBOUND_KINDS = ("text", "image", "video", "audio", "file", "sticker", "location")
_PROACTIVE_KEYS = ("cron", "is_cron", "cron_job", "notification", "is_notification", "proactive", "is_proactive", "card")
_PROACTIVE_SOURCES = ("cron", "notification", "proactive", "scheduled", "reminder")

# Hermes gateway status/control lines that only make sense on a terminal-like platform; they go to the log,
# never into the chat. Matched against the start of the whitespace-stripped text; the emoji variation
# selector (U+FE0F) after a symbol is optional because different Hermes versions emit it or not.
_CONTROL_PATTERNS: Tuple["re.Pattern[str]", ...] = tuple(re.compile(p) for p in (
    r"^↪️? Redirected current run",
    r"^⚡️? Interrupting current task",
    r"^⚡️? Stopped\.",
    r"^⚡️? Interrupted during API call",
    r"^⏳️? Another Hermes process",
    r"^⏳️? Still waiting for the other Hermes process",
    r"^Stopped waiting for another Hermes process",
    r"^⚠️? Message delivery failed",
    r"^💾️? Self-improvement review:",
    r"^⏳️? Working [—–-]",
    r"^🐍️? Running code",
    r"^💻️? Running",
    r"^📚️? Reading skill",
    r"^⚙️? ",
))

# Gateway command aliases (the phones offer these in the "/" menu). Applied before Hermes sees the text.
_COMMAND_ALIASES: Dict[str, str] = {
    "/clear": "/new", "/新对话": "/new", "/新会话": "/new",
    "/停止": "/stop", "/停": "/stop",
    "/帮助": "/help", "/状态": "/status", "/命令": "/commands",
    "/重试": "/retry", "/撤回": "/undo", "/压缩": "/compress", "/记忆": "/memory",
    "/排队": "/queue", "/插一句": "/steer", "/顺便问": "/btw",
}
# Chinese has no word spaces, so `/排队明天提醒我` is accepted as `/queue 明天提醒我`.
_COMMAND_ALIASES_WITH_ARG = ("/排队", "/插一句", "/顺便问")


def _ws_url(base: str) -> str:
    base = base.rstrip("/")
    if base.startswith("https://"):
        return "wss://" + base[len("https://"):] + "/ws"
    if base.startswith("http://"):
        return "ws://" + base[len("http://"):] + "/ws"
    return "wss://" + base + "/ws"


def _secret(name: str, default: str = "") -> str:
    """Profile ``.env`` value. ``external_fallback`` matters: startup gates (enablement, is_connected)
    run before a secret scope is installed and the container does not export ``.env`` into os.environ."""
    v = _get_scoped_secret(name, None, external_fallback=True)
    return (v if v is not None else default) or default


def _token_of(extra: Dict[str, Any]) -> str:
    return str(extra.get("token") or _secret("LOCHATTER_TOKEN")).strip()


def _server_url(extra: Dict[str, Any]) -> str:
    return str(extra.get("server") or _secret("LOCHATTER_URL", DEFAULT_URL)).rstrip("/")


def _int(v: Any, default: int = 0) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def _hermes_home() -> str:
    return os.path.expanduser(os.environ.get("HERMES_HOME", "~/.hermes"))


def _seq_path() -> str:
    return os.path.join(_hermes_home(), "lochatter.seq")


def _cache_dir() -> str:
    return os.path.join(_hermes_home(), "image_cache", "lochatter")


def _read_seq(path: str) -> Optional[int]:
    """Persisted sync cursor, or None when the file does not exist / is unreadable."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = f.read().strip()
    except OSError:
        return None
    if not raw:
        return None
    try:
        return max(0, int(raw))
    except ValueError:
        return None


def _write_seq(path: str, seq: int) -> bool:
    try:
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        tmp = f"{path}.{os.getpid()}.tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(f"{int(seq)}\n")
        os.replace(tmp, path)
        return True
    except OSError as e:
        logger.debug("lochatter: cannot persist seq to %s: %s", path, e)
        return False


def _is_control_chatter(text: str) -> bool:
    """True for Hermes's own status lines (interrupt/steer/lock/delivery notices) that must not reach the chat."""
    t = (text or "").strip()
    if not t:
        return False
    return any(p.match(t) for p in _CONTROL_PATTERNS)


def _normalize_command(text: str) -> str:
    """Strip whitespace and map command aliases (``/clear``, Chinese menu entries) onto Hermes's own commands."""
    t = (text or "").strip()
    if not t.startswith("/"):
        return t
    parts = t.split(None, 1)
    head = parts[0]
    rest = parts[1].strip() if len(parts) > 1 else ""
    target = _COMMAND_ALIASES.get(head) or _COMMAND_ALIASES.get(head.lower())
    if target is None:
        for alias in _COMMAND_ALIASES_WITH_ARG:
            if head.startswith(alias) and len(head) > len(alias):
                target = _COMMAND_ALIASES[alias]
                rest = f"{head[len(alias):]} {rest}".strip()
                break
    if target is None:
        return t
    return f"{target} {rest}" if rest else target


def _parse_sticker(text: str) -> Optional[Tuple[str, str, int, int]]:
    """``bqb|<path>|<w>|<h>`` or ``media|<mediaId>|<w>|<h>`` -> (kind, ref, w, h); None when malformed."""
    parts = (text or "").strip().split("|")
    if len(parts) < 2:
        return None
    kind, ref = parts[0].strip(), parts[1].strip()
    if kind not in ("bqb", "media") or not ref:
        return None
    if kind == "bqb":
        ref = ref.lstrip("/")
        if not ref or ".." in ref.split("/") or ref.startswith("\\") or "\\" in ref:
            return None
    w = _int(parts[2]) if len(parts) > 2 else 0
    h = _int(parts[3]) if len(parts) > 3 else 0
    return kind, ref, max(0, w), max(0, h)


def _location_text(text: str) -> str:
    """``lat,lng|accuracyMeters|address|live`` (plaintext location) -> ``[位置] <address> (<lat>,<lng>，精度 <acc> 米)``;
    ``[位置]`` when the coordinates are missing or garbled. Accuracy / address are optional."""
    parts = [p.strip() for p in (text or "").strip().split("|")]
    coords = parts[0].split(",", 1)
    if len(coords) != 2:
        return "[位置]"
    lat, lng = coords[0].strip(), coords[1].strip()
    try:
        if not (abs(float(lat)) <= 90 and abs(float(lng)) <= 180):
            return "[位置]"
    except ValueError:
        return "[位置]"
    acc = parts[1] if len(parts) > 1 else ""
    try:
        acc = str(int(round(float(acc)))) if acc else ""
    except ValueError:
        acc = ""
    address = parts[2] if len(parts) > 2 else ""
    where = f"{lat},{lng}" + (f"，精度 {acc} 米" if acc else "")
    return f"[位置] {address} ({where})" if address else f"[位置] ({where})"


def _is_proactive(metadata: Optional[Dict[str, Any]]) -> bool:
    """Best-effort detection of cron / notification delivery from whatever metadata Hermes passes along."""
    if not isinstance(metadata, dict):
        return False
    for k in _PROACTIVE_KEYS:
        v = metadata.get(k)
        if isinstance(v, str):
            if v.strip().lower() in _TRUTHY:
                return True
        elif v:
            return True
    for k in ("source", "origin", "trigger", "kind", "type"):
        v = metadata.get(k)
        if isinstance(v, str) and v.strip().lower() in _PROACTIVE_SOURCES:
            return True
    return False


def _ws_open(ws: Any) -> bool:
    """True while the websockets connection is OPEN (works for both the new and the legacy client classes)."""
    if ws is None:
        return False
    state = getattr(ws, "state", None)
    name = getattr(state, "name", None)
    if isinstance(name, str):
        return name == "OPEN"
    closed = getattr(ws, "closed", None)
    if isinstance(closed, bool):
        return not closed
    return True


class _RecentValues:
    """Small ``key -> value`` cache whose entries expire after ``ttl`` seconds (purged lazily)."""

    def __init__(self, ttl: float, max_size: int = 512):
        self._ttl = ttl
        self._max = max_size
        self._d: Dict[Any, Tuple[Any, float]] = {}

    def _purge(self) -> None:
        now = time.monotonic()
        if len(self._d) > self._max:
            self._d.clear()
            return
        for k in [k for k, (_, at) in self._d.items() if now - at > self._ttl]:
            self._d.pop(k, None)

    def get(self, key: Any) -> Any:
        self._purge()
        e = self._d.get(key)
        return e[0] if e is not None else None

    def pop(self, key: Any) -> Any:
        self._purge()
        e = self._d.pop(key, None)
        return e[0] if e is not None else None

    def put(self, key: Any, value: Any) -> None:
        self._purge()
        self._d[key] = (value, time.monotonic())

    def clear(self) -> None:
        self._d.clear()

    def __len__(self) -> int:
        return len(self._d)


def check_requirements() -> bool:
    return HTTPX_AVAILABLE and WS_AVAILABLE and bool(_secret("LOCHATTER_TOKEN").strip())


def validate_config(config) -> bool:
    return bool(_token_of(getattr(config, "extra", {}) or {}))


def is_connected(config) -> bool:
    return bool(_token_of(getattr(config, "extra", {}) or {}))


class LochatterAdapter(BasePlatformAdapter):
    """Outbound WebSocket client; the chat server treats us as reserved user 0."""

    MAX_MESSAGE_LENGTH = MAX_MESSAGE_LENGTH

    def __init__(self, config: PlatformConfig):
        super().__init__(config=config, platform=Platform("lochatter"))
        extra = config.extra or {}
        self._server: str = _server_url(extra)
        self._token: str = _token_of(extra)
        self._ws = None
        self._ws_task: Optional[asyncio.Task] = None
        self._http: Optional["httpx.AsyncClient"] = None
        self._dedup = MessageDeduplicator(max_size=2000, ttl_seconds=DEDUP_WINDOW_SECONDS)
        self._acks: Dict[str, asyncio.Future] = {}
        self._late_acks = _RecentValues(LATE_ACK_TTL)  # acks that arrived after their waiter gave up
        self._sent_ids = _RecentValues(SEND_REUSE_WINDOW)  # content key -> message id(s) for idempotent sends
        self._me: int = 0
        self._bot_name: str = "助手"
        self._names: Dict[int, str] = {}
        self._last_pong: float = 0.0
        self._send_lock = asyncio.Lock()
        # Sync cursor; persisted so questions asked during a restart are still picked up.
        self._last_seq: int = 0
        self._seq_path: str = _seq_path()
        self._saved_seq: Optional[int] = _read_seq(self._seq_path)
        self._seq_written_at: float = 0.0
        self._seq_dirty: bool = False
        self._seq_flush_task: Optional[asyncio.Task] = None
        # Questions are handled in their own tasks so the read loop never blocks.
        self._tasks: "set[asyncio.Task]" = set()
        self._typing_tasks: Dict[str, asyncio.Task] = {}  # question id -> typing heartbeat
        # Last-resort reply pairing when Hermes gives us neither reply_to nor our metadata back.
        self._last_question: Optional[str] = None
        self._last_question_at: float = 0.0
        # Streamed edits are coalesced per message id.
        self._edits: Dict[str, Dict[str, Any]] = {}
        self._edit_last: Dict[str, float] = {}
        # message id -> cached media file, so `del` / `clear` can drop the file.
        self._media_files: Dict[str, str] = {}
        self._call = None

    # ---- lifecycle ---------------------------------------------------------

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        if not (HTTPX_AVAILABLE and WS_AVAILABLE):
            logger.warning("[%s] httpx/websockets missing", self.name)
            return False
        if not self._token:
            logger.warning("[%s] LOCHATTER_TOKEN not configured", self.name)
            return False
        self._http = httpx.AsyncClient(
            base_url=self._server, headers={"Authorization": f"Bearer {self._token}"}, timeout=httpx.Timeout(60.0))
        self._ws_task = asyncio.create_task(self._run())
        self._mark_connected()
        logger.info("[%s] dialing %s", self.name, _ws_url(self._server))
        self._wire_plugin_handlers(None)
        return True

    async def disconnect(self) -> None:
        self._running = False
        self._mark_disconnected()
        call = self._call
        if call is not None:
            try:
                await call.close()
            except Exception:  # noqa: BLE001
                pass
            self._call = None
        await self._cancel_all(list(self._typing_tasks.values()))
        self._typing_tasks.clear()
        await self._cancel_all([p["task"] for p in self._edits.values() if p.get("task")])
        self._edits.clear()
        await self._cancel_all(list(self._tasks))
        self._tasks.clear()
        if self._ws_task:
            self._ws_task.cancel()
            try:
                await self._ws_task
            except (asyncio.CancelledError, Exception):
                pass
            self._ws_task = None
        if self._seq_flush_task:
            self._seq_flush_task.cancel()
            self._seq_flush_task = None
        self._write_seq_now()
        if self._http:
            try:
                await self._http.aclose()
            except Exception:  # noqa: BLE001
                pass
            self._http = None
        self._dedup.clear()
        self._sent_ids.clear()
        self._late_acks.clear()
        logger.info("[%s] disconnected", self.name)

    @staticmethod
    async def _cancel_all(tasks: List[asyncio.Task]) -> None:
        live = [t for t in tasks if t and not t.done()]
        for t in live:
            t.cancel()
        if live:
            try:
                await asyncio.gather(*live, return_exceptions=True)
            except Exception:  # noqa: BLE001
                pass

    async def _run(self) -> None:
        backoff = 0
        while self._running:
            started = time.monotonic()
            try:
                await self._session()
            except asyncio.CancelledError:
                return
            except Exception as e:  # noqa: BLE001
                if not self._running:
                    return
                msg = str(e)
                if "401" in msg:
                    self._set_fatal_error("lochatter_unauthorized", "chat server rejected the token (401); run `chatterctl bot token` again", retryable=False)
                    logger.error("[%s] token rejected; not retrying", self.name)
                    return
                logger.warning("[%s] socket error: %s", self.name, e)
            if not self._running:
                return
            if time.monotonic() - started > 60:
                backoff = 0
            delay = RECONNECT_BACKOFF[min(backoff, len(RECONNECT_BACKOFF) - 1)]
            logger.info("[%s] reconnecting in %ds", self.name, delay)
            await asyncio.sleep(delay)
            backoff += 1

    async def _session(self) -> None:
        url = _ws_url(self._server)
        async with websockets.connect(
            url, additional_headers={"Authorization": f"Bearer {self._token}"},
            max_size=256 * 1024, ping_interval=None, open_timeout=20, close_timeout=5,
        ) as ws:
            self._ws = ws
            self._last_pong = time.monotonic()
            hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=20))
            if hello.get("t") != "hello":
                raise RuntimeError(f"expected hello, got {hello.get('t')}")
            self._me = _int((hello.get("user") or {}).get("id"))
            bot = hello.get("bot") or {}
            self._bot_name = bot.get("name") or (hello.get("user") or {}).get("name") or self._bot_name
            self._names[self._me] = self._bot_name
            self._learn_names(hello.get("users"))
            server_seq = _int(hello.get("lastSeq"))
            logger.info("[%s] connected as '%s' (id %s), server seq %s, humans %s", self.name, self._bot_name, self._me,
                        server_seq, {k: v for k, v in self._names.items() if k != self._me})
            # Catch up on mentions that arrived while we were away (server filters to what we may see).
            if self._last_seq:
                if server_seq > self._last_seq:
                    await ws.send(json.dumps({"t": "sync", "since": self._last_seq}))
            elif self._saved_seq is not None and self._saved_seq < server_seq:
                # Hermes restarted: resume from the persisted cursor so questions asked meanwhile get answered.
                self._last_seq = self._saved_seq
                logger.info("[%s] resuming from persisted seq %s", self.name, self._saved_seq)
                await ws.send(json.dumps({"t": "sync", "since": self._last_seq}))
            else:
                self._last_seq = server_seq  # first connect without a cursor: do not replay history
                self._seq_dirty = True
                self._write_seq_now()
            self._saved_seq = None
            keep = asyncio.create_task(self._keepalive(ws))
            try:
                async for raw in ws:
                    self._last_pong = time.monotonic()
                    try:
                        frame = json.loads(raw)
                    except json.JSONDecodeError:
                        continue
                    if not isinstance(frame, dict):
                        continue
                    try:
                        await self._on_frame(ws, frame)
                    except asyncio.CancelledError:
                        raise
                    except Exception as e:  # noqa: BLE001
                        logger.warning("[%s] frame %s failed: %s", self.name, frame.get("t"), e)
            finally:
                keep.cancel()
                self._ws = None
                for f in self._acks.values():
                    if not f.done():
                        f.set_exception(ConnectionError("socket closed"))
                self._acks.clear()
                self._write_seq_now()

    async def _keepalive(self, ws) -> None:
        while True:
            await asyncio.sleep(PING_EVERY)
            if time.monotonic() - self._last_pong > PING_EVERY + PONG_DEADLINE:
                logger.warning("[%s] no frames for %.0fs; dropping socket", self.name, time.monotonic() - self._last_pong)
                await ws.close(code=4000, reason="stale")
                return
            try:
                await ws.send(json.dumps({"t": "ping", "ts": int(time.time() * 1000)}))
            except Exception:  # noqa: BLE001
                return

    def _learn_names(self, users: Any) -> None:
        if not isinstance(users, list):
            return
        for u in users:
            if not isinstance(u, dict):
                continue
            uid = _int(u.get("id"), -1)
            name = str(u.get("name") or "").strip()
            if uid >= 0 and name:
                self._names[uid] = name

    # ---- sync cursor -----------------------------------------------------------

    def _advance_seq(self, seq: int) -> None:
        if seq <= self._last_seq:
            return
        self._last_seq = seq
        self._seq_dirty = True
        elapsed = time.monotonic() - self._seq_written_at
        if elapsed >= SEQ_WRITE_EVERY:
            self._write_seq_now()
        elif self._seq_flush_task is None or self._seq_flush_task.done():
            try:
                self._seq_flush_task = asyncio.get_running_loop().create_task(self._flush_seq_later(SEQ_WRITE_EVERY - elapsed))
            except RuntimeError:  # no running loop: write straight away
                self._write_seq_now()

    async def _flush_seq_later(self, delay: float) -> None:
        try:
            await asyncio.sleep(max(0.05, delay))
        except asyncio.CancelledError:
            return
        self._write_seq_now()

    def _write_seq_now(self) -> None:
        if not self._seq_dirty or self._last_seq <= 0:
            return
        if _write_seq(self._seq_path, self._last_seq):
            self._seq_dirty = False
        self._seq_written_at = time.monotonic()

    # ---- inbound -------------------------------------------------------------

    async def _on_frame(self, ws, f: Dict[str, Any]) -> None:
        t = f.get("t")
        if t == "msg.ack":
            fid = f.get("id")
            fut = self._acks.pop(fid, None) if fid else None
            if fut is not None and not fut.done():
                fut.set_result(f)
            elif fid:
                self._late_acks.put(fid, f)  # waiter already gave up; remember so a retry still counts
                logger.debug("[%s] late ack for %s", self.name, str(fid)[:8])
            self._advance_seq(_int(f.get("seq")))
            return
        if t == "msg.new":
            self._on_message(f.get("msg") or {})
            return
        if t == "msg.batch":
            for m in f.get("messages") or []:
                if isinstance(m, dict):
                    self._on_message(m)
            if f.get("hasMore") and not f.get("before"):
                await ws.send(json.dumps({"t": "sync", "since": self._last_seq}))
            return
        if t == "bot":
            name = str(f.get("name") or "").strip()
            if name and name != self._bot_name:
                logger.info("[%s] renamed to '%s'", self.name, name)
            self._bot_name = name or self._bot_name
            self._names[self._me] = self._bot_name
            return
        if t == "shared":
            return  # humans' shared key/value store; not ours
        if t == "turn.creds":
            call = self._call
            if call is not None:
                call.offer_turn(f)
            return
        if t == "call.invite":
            await self._on_call_invite(f)
            return
        if isinstance(t, str) and t.startswith("call."):
            call = self._call
            if call is not None and f.get("callId") == call.call_id:
                await call.on_signal(f)
            return
        if t == "error":
            ref = f.get("ref")
            fut = self._acks.pop(ref, None) if ref else None
            if fut and not fut.done():
                fut.set_exception(RuntimeError(f"{f.get('code')}: {f.get('message')}"))
            else:
                logger.warning("[%s] server error %s: %s", self.name, f.get("code"), f.get("message"))
            return
        # pong / presence / typing / keys / read / call.*: nothing to do

    def _on_message(self, m: Dict[str, Any]) -> None:
        """Cheap, synchronous triage of an inbound message; the real work runs in a task."""
        self._advance_seq(_int(m.get("seq")))
        kind = m.get("kind") or "text"
        if kind in ("del", "clear", "recall"):
            self._on_deleted(kind, str(m.get("text") or ""))
            return
        if _int(m.get("from"), -1) == self._me:
            return
        if m.get("to") != "bot":
            return  # defensive: the server should not send these
        if kind not in _INBOUND_KINDS:
            return
        mid = str(m.get("id") or uuid.uuid4().hex)
        if self._dedup.is_duplicate(mid):
            return
        text = str(m.get("text") or "")
        if text.startswith("e2e:"):
            return  # never happens by contract; refuse anyway
        self._last_question = mid
        self._last_question_at = time.monotonic()
        task = asyncio.create_task(self._process(m, mid, kind))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _process(self, m: Dict[str, Any], mid: str, kind: str) -> None:
        """Fetch media, build the MessageEvent and hand it to Hermes, off the read loop."""
        self._typing_tasks[mid] = asyncio.create_task(self._typing_loop())
        try:
            event = await self._build_event(m, mid, kind)
        except asyncio.CancelledError:
            self._stop_typing(mid)
            raise
        except Exception:  # noqa: BLE001
            logger.exception("[%s] building event for %s failed", self.name, mid[:8])
            self._stop_typing(mid)
            return
        try:
            await self.handle_message(event)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            logger.exception("[%s] handle_message failed for %s", self.name, mid[:8])
        finally:
            self._stop_typing(mid)

    async def _build_event(self, m: Dict[str, Any], mid: str, kind: str) -> MessageEvent:
        text = str(m.get("text") or "").strip()
        uid = str(_int(m.get("from"), -1))
        uname = self._names.get(_int(uid, -1), f"user{uid}")
        media_urls: List[str] = []
        media_types: List[str] = []
        media = m.get("media") or {}
        event_type = KIND_TO_TYPE.get(kind, MessageType.TEXT)
        if kind == "sticker":
            st = _parse_sticker(text)
            text = STICKER_TEXT
            event_type = MessageType.PHOTO
            got: Optional[Tuple[str, str]] = None
            if st is None:
                logger.debug("[%s] unparsable sticker text", self.name)
            elif st[0] == "bqb":
                got = await self._fetch_sticker(st[1], mid)
            else:
                ref = st[1]
                info = media if str(media.get("id") or "") == ref else {"id": ref, "mime": media.get("mime") or ""}
                path = await self._fetch_media(info, mid)
                if path:
                    got = (path, media.get("mime") or mimetypes.guess_type(path)[0] or "image/webp")
            if got:
                media_urls.append(got[0])
                media_types.append(got[1] if got[1].startswith("image/") else "image/webp")
            else:
                event_type = MessageType.TEXT
        elif kind == "location":
            text = _location_text(text)  # plaintext by contract (the server refuses e2e blobs addressed to us)
        elif kind != "text" and media.get("id"):
            path = await self._fetch_media(media, mid)
            if path:
                media_urls.append(path)
                media_types.append(media.get("mime") or "application/octet-stream")
            if not text:
                text = {"image": "[图片]", "video": "[视频]", "audio": "[语音]", "file": f"[文件] {media.get('name') or ''}"}.get(kind, "")
        elif kind == "text":
            text = _normalize_command(text)
        reply = m.get("reply") or {}
        if reply.get("text") and not str(reply["text"]).startswith("e2e:") and not text.startswith("/"):
            text = f"（回复「{str(reply['text'])[:200]}」）\n{text}"
        source = self.build_source(chat_id=CHAT_ID, chat_name="lochatter", chat_type="dm", user_id=uid, user_name=uname, message_id=mid)
        ts = datetime.fromtimestamp(_int(m.get("ts")) / 1000, tz=timezone.utc) if m.get("ts") else datetime.now(tz=timezone.utc)
        event = MessageEvent(
            text=text, message_type=event_type, source=source, message_id=mid,
            raw_message=m, timestamp=ts, media_urls=media_urls, media_types=media_types)
        # Pair replies with the question they answer: Hermes may hand this metadata back to send().
        try:
            meta = getattr(event, "metadata", None)
            if not isinstance(meta, dict):
                meta = {}
                setattr(event, "metadata", meta)
            meta["lochatter_reply_to"] = mid
        except Exception:  # noqa: BLE001
            pass
        logger.debug("[%s] mention from %s: %s", self.name, uname, text[:80])
        return event

    def _on_deleted(self, kind: str, target: str) -> None:
        """``del`` (text = deleted message id) / ``clear``: drop cached media so nothing outlives the chat."""
        try:
            if kind == "clear":
                self._media_files.clear()
                cache = _cache_dir()
                if os.path.isdir(cache):
                    for name in os.listdir(cache):
                        p = os.path.join(cache, name)
                        try:
                            if os.path.isfile(p):
                                os.remove(p)
                        except OSError:
                            pass
                logger.debug("[%s] chat cleared; media cache wiped", self.name)
                return
            path = self._media_files.pop(target, None)
            if path:
                try:
                    os.remove(path)
                except OSError:
                    pass
                logger.debug("[%s] message %s deleted; cached media removed", self.name, target[:8])
        except Exception as e:  # noqa: BLE001
            logger.debug("[%s] delete handling failed: %s", self.name, e)

    async def _fetch_media(self, media: Dict[str, Any], message_id: Optional[str] = None) -> Optional[str]:
        if not self._http:
            return None
        mid = str(media.get("id") or "")
        if not mid:
            return None
        ext = mimetypes.guess_extension(media.get("mime") or "") or ""
        name = media.get("name") or f"{mid[:12]}{ext}"
        cache = _cache_dir()
        os.makedirs(cache, exist_ok=True)
        path = os.path.join(cache, f"{mid[:16]}-{os.path.basename(name)}")
        if os.path.exists(path):
            if message_id:
                self._media_files[message_id] = path
            return path
        try:
            async with self._http.stream("GET", f"/media/{mid}") as r:
                if r.status_code != 200:
                    logger.warning("[%s] media %s -> HTTP %d", self.name, mid[:12], r.status_code)
                    return None
                with open(path, "wb") as f:
                    async for chunk in r.aiter_bytes(64 * 1024):
                        f.write(chunk)
            if message_id:
                self._media_files[message_id] = path
            return path
        except Exception as e:  # noqa: BLE001
            logger.warning("[%s] media fetch failed: %s", self.name, e)
            return None

    async def _fetch_sticker(self, rel: str, message_id: Optional[str] = None) -> Optional[Tuple[str, str]]:
        """Download a library sticker (``${server}/stickers/<path>``, no auth) into the media cache -> (path, mime)."""
        if not self._http:
            return None
        ext = os.path.splitext(rel)[1].lower()
        if len(ext) > 6 or not ext:
            ext = ".jpg"
        key = hashlib.sha1(rel.encode("utf-8")).hexdigest()[:16]
        cache = _cache_dir()
        os.makedirs(cache, exist_ok=True)
        path = os.path.join(cache, f"{key}-sticker{ext}")
        mime = mimetypes.guess_type(path)[0] or "image/jpeg"
        if os.path.exists(path):
            if message_id:
                self._media_files[message_id] = path
            return path, mime
        url_path = "/stickers/" + (rel if "%" in rel else _urlquote(rel, safe="/"))
        try:
            async with self._http.stream("GET", url_path, headers={"Authorization": ""}, follow_redirects=True) as r:
                if r.status_code != 200:
                    logger.warning("[%s] sticker %s -> HTTP %d", self.name, rel[:60], r.status_code)
                    return None
                ct = (r.headers.get("content-type") or "").split(";")[0].strip().lower()
                if ct.startswith("image/"):
                    mime = ct
                with open(path, "wb") as f:
                    async for chunk in r.aiter_bytes(64 * 1024):
                        f.write(chunk)
            if message_id:
                self._media_files[message_id] = path
            return path, mime
        except Exception as e:  # noqa: BLE001
            logger.warning("[%s] sticker fetch failed: %s", self.name, e)
            return None

    # ---- typing heartbeat --------------------------------------------------------

    async def _typing_loop(self) -> None:
        try:
            while True:
                await self.send_typing(CHAT_ID)
                await asyncio.sleep(TYPING_EVERY)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            pass

    def _stop_typing(self, question_id: Optional[str]) -> None:
        """Stop the heartbeat for one question. A send we cannot pair with any question (``None``) stops
        every heartbeat; a known id that has no heartbeat any more (already answered once) is a no-op."""
        if question_id is None:
            targets = list(self._typing_tasks.values())
            self._typing_tasks.clear()
        else:
            t = self._typing_tasks.pop(question_id, None)
            if t is None and len(self._typing_tasks) == 1:
                # Hermes quoted something we do not track, but exactly one question is in flight: this is its answer.
                _, t = self._typing_tasks.popitem()
            targets = [t] if t else []
        for t in targets:
            if not t.done():
                t.cancel()

    # ---- outbound ------------------------------------------------------------

    def _quote(self, reply_to: Optional[str], metadata: Optional[Dict[str, Any]] = None) -> Optional[str]:
        """Explicit reply target, else the question id we stashed in the event metadata, else (last resort)
        the most recent question seen in the last 15 minutes."""
        if reply_to:
            return str(reply_to)
        if isinstance(metadata, dict):
            # Our own stash first; then Hermes's stream consumer, which copies the inbound message id into
            # ``reply_to_message_id`` on every send of that turn.
            r = metadata.get("lochatter_reply_to") or metadata.get("reply_to_message_id")
            if r:
                return str(r)
        if self._last_question and time.monotonic() - self._last_question_at < QUESTION_WINDOW:
            return self._last_question
        return None

    async def _send_frame(self, frame: Dict[str, Any], *, wait_ack: bool = True) -> Dict[str, Any]:
        """Write one frame and wait for its ack.

        Raises ConnectionError only when the frame could not be written or the socket died before the ack
        (those are safe to retry: ids are stable). An ack that simply takes longer than ACK_TIMEOUT while the
        socket stays open is reported as delivered, because the server acks only after storing the row."""
        fid = frame.get("id")
        if wait_ack and fid:
            late = self._late_acks.pop(fid)
            if late is not None:
                logger.debug("[%s] %s already acked (late ack)", self.name, str(fid)[:8])
                return late
        ws = self._ws
        if ws is None:
            raise ConnectionError("not connected")
        fut: Optional[asyncio.Future] = None
        if wait_ack and fid:
            fut = asyncio.get_running_loop().create_future()
            self._acks[fid] = fut
        try:
            async with self._send_lock:
                await ws.send(json.dumps(frame, ensure_ascii=False))
        except asyncio.CancelledError:
            if fid:
                self._acks.pop(fid, None)
            raise
        except Exception as e:  # noqa: BLE001
            if fid:
                self._acks.pop(fid, None)
            raise ConnectionError(f"send failed: {e}") from e
        if fut is None:
            return {}
        try:
            return await asyncio.wait_for(fut, timeout=ACK_TIMEOUT)
        except asyncio.TimeoutError:
            if self._ws is ws and _ws_open(ws):
                logger.warning("[%s] no ack for %s within %.0fs but socket is open; treating as delivered",
                               self.name, str(fid)[:8], ACK_TIMEOUT)
                return {"id": fid, "unconfirmed": True}
            raise ConnectionError("socket closed before ack")
        finally:
            if fid:
                self._acks.pop(fid, None)

    @staticmethod
    def _dropped() -> SendResult:
        return SendResult(success=True, message_id=f"{DROPPED_PREFIX}{uuid.uuid4().hex[:12]}")

    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        content = (content or "").strip()
        if not content:
            return SendResult(success=False, error="empty")
        if _is_control_chatter(content):
            logger.debug("[%s] dropped control chatter: %s", self.name, content[:60])
            return self._dropped()
        if self._call is not None:
            self._call.note_reply(content, True, metadata)
        kind = "card" if _is_proactive(metadata) else "text"
        # A card (cron / notification) answers nobody: only an explicit reply target may be attached to it.
        quote = self._quote(reply_to, metadata) if kind == "text" else (str(reply_to) if reply_to else None)
        self._stop_typing(quote)
        parts = self.truncate_message(content, self.MAX_MESSAGE_LENGTH)
        key = ("text", chat_id, hashlib.sha1(content.encode("utf-8")).hexdigest(), quote, kind)
        ids = self._sent_ids.get(key)
        if not isinstance(ids, list) or len(ids) != len(parts):
            ids = [str(uuid.uuid4()) for _ in parts]
            self._sent_ids.put(key, ids)
        else:
            logger.info("[%s] repeated send within %.0fs; reusing id %s", self.name, SEND_REUSE_WINDOW, ids[0][:8])
        sent: List[str] = []
        try:
            for i, part in enumerate(parts):
                frame = {"t": "msg.send", "id": ids[i], "kind": kind, "text": part}
                if quote and i == 0:
                    frame["replyTo"] = quote
                await self._send_frame(frame)
                sent.append(ids[i])
            return SendResult(success=True, message_id=sent[-1], continuation_message_ids=tuple(sent[:-1]))
        except ConnectionError as e:
            return SendResult(success=False, error=str(e) or "disconnected", retryable=True)
        except Exception as e:  # noqa: BLE001
            return SendResult(success=False, error=str(e))

    async def edit_message(self, chat_id: str, message_id: str, content: str, *, finalize: bool = False) -> SendResult:
        content = (content or "").strip()
        if not content or len(content) > self.MAX_MESSAGE_LENGTH:
            return SendResult(success=False, error="unsupported length")
        if not message_id or str(message_id).startswith(DROPPED_PREFIX):
            return SendResult(success=True, message_id=message_id)
        if _is_control_chatter(content):
            logger.debug("[%s] dropped control chatter edit: %s", self.name, content[:60])
            return SendResult(success=True, message_id=message_id)
        if self._call is not None:
            self._call.note_reply(content, finalize, None)
        message_id = str(message_id)
        pend = self._edits.get(message_id)
        if finalize:
            if pend is not None:
                self._edits.pop(message_id, None)
                t = pend.get("task")
                if t and not t.done():
                    t.cancel()
            return await self._edit_now(message_id, content)
        if pend is not None:
            pend["content"] = content  # coalesce: only the newest text goes out
            return SendResult(success=True, message_id=message_id)
        elapsed = time.monotonic() - self._edit_last.get(message_id, 0.0)
        if elapsed >= EDIT_COALESCE:
            return await self._edit_now(message_id, content)
        pend = {"content": content}
        pend["task"] = asyncio.create_task(self._edit_later(message_id, EDIT_COALESCE - elapsed))
        self._edits[message_id] = pend
        return SendResult(success=True, message_id=message_id)

    async def _edit_later(self, message_id: str, delay: float) -> None:
        try:
            await asyncio.sleep(max(0.0, delay))
        except asyncio.CancelledError:
            return
        pend = self._edits.pop(message_id, None)
        if pend is None:
            return
        try:
            r = await self._edit_now(message_id, pend["content"])
            if not r.success:
                logger.debug("[%s] coalesced edit of %s failed: %s", self.name, message_id[:8], r.error)
        except asyncio.CancelledError:
            return
        except Exception as e:  # noqa: BLE001
            logger.debug("[%s] coalesced edit of %s raised: %s", self.name, message_id[:8], e)

    async def _edit_now(self, message_id: str, content: str) -> SendResult:
        self._edit_last[message_id] = time.monotonic()
        if len(self._edit_last) > 500:
            cutoff = time.monotonic() - 600
            for k in [k for k, at in self._edit_last.items() if at < cutoff]:
                self._edit_last.pop(k, None)
        try:
            await self._send_frame({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "edit", "text": f"{message_id}|{content}"})
            return SendResult(success=True, message_id=message_id)
        except Exception as e:  # noqa: BLE001
            return SendResult(success=False, error=str(e))

    async def delete_message(self, chat_id: str, message_id: str) -> bool:
        if not message_id or str(message_id).startswith(DROPPED_PREFIX):
            return True
        pend = self._edits.pop(str(message_id), None)
        if pend is not None:
            t = pend.get("task")
            if t and not t.done():
                t.cancel()
        try:
            await self._send_frame({"t": "msg.send", "id": str(uuid.uuid4()), "kind": "del", "text": str(message_id)})
            return True
        except Exception:  # noqa: BLE001
            return False

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        ws = self._ws
        if ws is None:
            return
        try:
            async with self._send_lock:
                await ws.send(json.dumps({"t": "typing"}))
        except Exception:  # noqa: BLE001
            pass

    async def _upload(self, path: str, mime: Optional[str] = None, **params) -> Dict[str, Any]:
        if not self._http:
            raise ConnectionError("http client not ready")
        mime = mime or mimetypes.guess_type(path)[0] or "application/octet-stream"
        q = {k: v for k, v in params.items() if v}
        # the phones render images through their thumbnail; give them one so the bubble sizes right
        if mime.startswith("image/") and "thumb" not in q:
            try:
                from PIL import Image
                import tempfile
                with Image.open(path) as im:
                    im.thumbnail((480, 480))
                    tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
                    im.convert("RGB").save(tmp.name, "JPEG", quality=80)
                    tw, th = im.size
                with open(tmp.name, "rb") as f:
                    r = await self._http.post("/media", params={"w": tw, "h": th}, content=f.read(), headers={"Content-Type": "image/jpeg"})
                os.remove(tmp.name)
                if r.status_code in (200, 201):
                    q["thumb"] = r.json()["id"]
            except Exception as e:  # noqa: BLE001
                logger.debug("[%s] thumbnail skipped: %s", self.name, e)
        with open(path, "rb") as f:
            r = await self._http.post("/media", params=q, content=f.read(), headers={"Content-Type": mime})
        if r.status_code not in (200, 201):
            raise RuntimeError(f"upload HTTP {r.status_code}: {r.text[:200]}")
        return r.json()

    async def _send_media(self, kind: str, path: str, caption: Optional[str], reply_to: Optional[str],
                          metadata: Optional[Dict[str, Any]] = None, **params) -> SendResult:
        try:
            info = await self._upload(path, **params)
            quote = self._quote(reply_to, metadata)
            self._stop_typing(quote)
            caption = (caption or "")[:4000]
            key = ("media", kind, str(info["id"]), hashlib.sha1(caption.encode("utf-8")).hexdigest(), quote)
            mid = self._sent_ids.get(key)
            if not isinstance(mid, str):
                mid = str(uuid.uuid4())
                self._sent_ids.put(key, mid)
            else:
                logger.info("[%s] repeated media send; reusing id %s", self.name, mid[:8])
            frame: Dict[str, Any] = {"t": "msg.send", "id": mid, "kind": kind, "mediaId": info["id"]}
            if caption:
                frame["text"] = caption
            if quote:
                frame["replyTo"] = quote
            await self._send_frame(frame)
            return SendResult(success=True, message_id=mid)
        except ConnectionError as e:
            return SendResult(success=False, error=str(e) or "disconnected", retryable=True)
        except Exception as e:  # noqa: BLE001
            return SendResult(success=False, error=str(e))

    async def send_image_file(self, chat_id: str, image_path: Optional[str] = None, caption: Optional[str] = None,
                              reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                              file_path: Optional[str] = None, **kwargs) -> SendResult:
        path = image_path or file_path
        if not path:
            return SendResult(success=False, error="no image path")
        w = h = None
        try:
            from PIL import Image  # ships with Hermes
            with Image.open(path) as im:
                w, h = im.size
        except Exception:  # noqa: BLE001
            pass
        return await self._send_media("image", path, caption, reply_to, metadata, w=w, h=h)

    async def send_image(self, chat_id: str, image_url: str, caption: Optional[str] = None,
                         reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        if os.path.exists(image_url):
            return await self.send_image_file(chat_id, image_url, caption, reply_to, metadata)
        if not self._http:
            return SendResult(success=False, error="http client not ready")
        try:
            r = await self._http.get(image_url, headers={"Authorization": ""}, follow_redirects=True)
            r.raise_for_status()
            tmp = os.path.join("/tmp", f"lochatter-{uuid.uuid4().hex[:8]}{mimetypes.guess_extension(r.headers.get('content-type', '').split(';')[0]) or '.jpg'}")
            with open(tmp, "wb") as f:
                f.write(r.content)
            try:
                return await self.send_image_file(chat_id, tmp, caption, reply_to, metadata)
            finally:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
        except Exception as e:  # noqa: BLE001
            return SendResult(success=False, error=str(e))

    async def send_document(self, chat_id: str, file_path: str, caption: Optional[str] = None,
                            file_name: Optional[str] = None, reply_to: Optional[str] = None,
                            metadata: Optional[Dict[str, Any]] = None, **kwargs) -> SendResult:
        return await self._send_media("file", file_path, caption, reply_to, metadata, name=file_name or os.path.basename(file_path))

    async def send_voice(self, chat_id: str, audio_path: Optional[str] = None, caption: Optional[str] = None,
                         reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                         file_path: Optional[str] = None, **kwargs) -> SendResult:
        path = audio_path or file_path
        if not path:
            return SendResult(success=False, error="no audio path")
        mime = mimetypes.guess_type(path)[0] or "audio/mp4"
        d = None
        try:
            from mutagen import File as _MFile  # optional
            mf = _MFile(path)
            if mf is not None and getattr(mf, "info", None) is not None:
                d = int(float(mf.info.length) * 1000)
        except Exception:  # noqa: BLE001
            pass
        return await self._send_media("audio", path, caption, reply_to, metadata, mime=mime, d=d)

    async def send_video(self, chat_id: str, video_path: Optional[str] = None, caption: Optional[str] = None,
                         reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                         file_path: Optional[str] = None, **kwargs) -> SendResult:
        path = video_path or file_path
        if not path:
            return SendResult(success=False, error="no video path")
        return await self._send_media("video", path, caption, reply_to, metadata, name=os.path.basename(path))

    def voice_event(self, text: str, human_id: int) -> MessageEvent:
        """A spoken line, handed to Hermes as if it had been typed to the assistant."""
        mid = "voice-" + uuid.uuid4().hex[:16]
        uname = self._names.get(human_id, "user%s" % human_id)
        source = self.build_source(
            chat_id=CHAT_ID, chat_name="lochatter", chat_type="dm",
            user_id=str(human_id), user_name=uname, message_id=mid,
        )
        event = MessageEvent(
            text=text, message_type=MessageType.TEXT, source=source, message_id=mid,
            raw_message={"id": mid, "from": human_id, "kind": "text", "text": text, "to": "bot"},
            timestamp=datetime.now(tz=timezone.utc),
        )
        try:
            meta = getattr(event, "metadata", None)
            if not isinstance(meta, dict):
                meta = {}
                setattr(event, "metadata", meta)
            meta["lochatter_reply_to"] = mid
        except Exception:  # noqa: BLE001
            pass
        self._last_question = mid
        self._last_question_at = time.monotonic()
        return event

    async def _send_signal(self, frame: Dict[str, Any]) -> None:
        ws = self._ws
        if ws is None:
            return
        try:
            async with self._send_lock:
                await ws.send(json.dumps(frame, ensure_ascii=False))
        except Exception as exc:  # noqa: BLE001
            logger.warning("[%s] signal %s failed: %s", self.name, frame.get("t"), exc)

    async def _on_call_invite(self, frame: Dict[str, Any]) -> None:
        call_id = str(frame.get("callId") or "")
        if not call_id or not frame.get("bot"):
            return
        if frame.get("video"):
            await self._send_signal({"t": "call.reject", "callId": call_id, "reason": "voice_only"})
            return
        if self._call is not None:
            await self._send_signal({"t": "call.reject", "callId": call_id, "reason": "busy"})
            return
        try:
            call = _voice_module().AssistantCall(self, call_id, _int(frame.get("from"), -1))
        except Exception as exc:  # noqa: BLE001
            logger.warning("[%s] voice call unavailable: %s", self.name, type(exc).__name__)
            await self._send_signal({"t": "call.reject", "callId": call_id, "reason": "unavailable"})
            return
        self._call = call
        task = asyncio.create_task(call.run())
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        return {"name": "lochatter", "type": "dm", "chat_id": CHAT_ID}


# ---- plugin registration ------------------------------------------------------

def _env_enablement() -> Optional[dict]:
    token = _secret("LOCHATTER_TOKEN").strip()
    if not token:
        return None
    seed: Dict[str, Any] = {}
    try:
        seed = _seed_extra_from_env((("LOCHATTER_URL", "server", lambda v: v.rstrip("/")),),
                                    home_env="LOCHATTER_HOME_CHANNEL", home_default=CHAT_ID) or {}
    except Exception:  # noqa: BLE001
        seed = {}
    server = seed.pop("server", None) or _secret("LOCHATTER_URL", DEFAULT_URL).rstrip("/")
    return {"token": token, "server": server, **seed}


async def _standalone_send(pconfig, chat_id: str, message: str, *, thread_id: Optional[str] = None,
                           media_files: Optional[List[str]] = None, force_document: bool = False) -> Dict[str, Any]:
    """Cron delivery without a live gateway adapter: one short-lived socket, one ``card`` (title = first line)."""
    if not (HTTPX_AVAILABLE and WS_AVAILABLE):
        return send_error("lochatter standalone send: websockets/httpx missing")
    extra = getattr(pconfig, "extra", {}) or {}
    token = _token_of(extra)
    if not token:
        return send_error("lochatter standalone send: LOCHATTER_TOKEN not configured")
    text = (message or "").strip()[:MAX_MESSAGE_LENGTH]
    if not text:
        return send_error("lochatter standalone send: empty message")
    url = _ws_url(_server_url(extra))
    mid = str(uuid.uuid4())
    try:
        async with websockets.connect(url, additional_headers={"Authorization": f"Bearer {token}"}, open_timeout=20) as ws:
            await asyncio.wait_for(ws.recv(), timeout=20)  # hello
            await ws.send(json.dumps({"t": "msg.send", "id": mid, "kind": "card", "text": text}, ensure_ascii=False))
            end = time.time() + ACK_TIMEOUT
            while time.time() < end:
                f = json.loads(await asyncio.wait_for(ws.recv(), timeout=max(0.1, end - time.time())))
                if not isinstance(f, dict):
                    continue
                if f.get("t") == "msg.ack" and f.get("id") == mid:
                    return {"success": True, "platform": "lochatter", "chat_id": CHAT_ID, "message_id": mid}
                if f.get("t") == "error" and f.get("ref") == mid:
                    return send_error(f"lochatter: {f.get('message')}")
        return send_error("lochatter: no ack")
    except Exception as e:  # noqa: BLE001
        return send_error(f"lochatter standalone send failed: {e}")


def register(ctx) -> None:
    ctx.register_platform(
        name="lochatter", label="lochatter", adapter_factory=lambda cfg: LochatterAdapter(cfg),
        check_fn=check_requirements, validate_config=validate_config, is_connected=is_connected,
        required_env=["LOCHATTER_TOKEN"], install_hint="websockets + httpx ship with Hermes",
        env_enablement_fn=_env_enablement,
        cron_deliver_env_var="LOCHATTER_HOME_CHANNEL",
        standalone_sender_fn=_standalone_send,
        allowed_users_env="LOCHATTER_ALLOWED_USERS", allow_all_env="LOCHATTER_ALLOW_ALL_USERS",
        max_message_length=MAX_MESSAGE_LENGTH, emoji="💞",
        pii_safe=True,
        allow_update_command=False,
        platform_hint=(
            "You are the household assistant inside lochatter, a private chat between two partners. "
            "You live on your own page there; you only see what they say to you on that page or explicitly "
            "@-mention you with. Everything else between them is end-to-end encrypted and invisible to you. "
            "Both of them read your replies. You know both their names (each message tells you who is "
            "speaking); address them by name when it feels natural. Answer in the language they used "
            "(usually Chinese), warmly and concisely. Your replies render Markdown on their phones: bold, "
            "italic, lists, headers, inline code, code blocks, links and blockquotes all display properly, "
            "while tables come out as plain text, so light Markdown is fine but avoid tables. "
            "Do not narrate your tool use."
        ))
