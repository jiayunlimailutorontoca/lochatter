"""State-machine tests for the assistant call. No aiortc, no network."""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))

import voice_call as v


def check(cond, msg):
    if not cond:
        raise SystemExit("FAIL " + msg)


def test_sentences():
    ready, rest = v.split_ready("我在，你说。后面", False, True)
    check(ready == ["我在，你说。"] and rest == "后面", str(ready) + "/" + rest)
    ready, rest = v.split_ready("这句话已经够长，后面还有。尾巴", False, True)
    check(ready == ["这句话已经够长，", "后面还有。"] and rest == "尾巴", str(ready))
    ready, rest = v.split_ready("没标点", True, False)
    check(ready == ["没标点"] and rest == "", ready)
    speaker = v.Speaker()
    check(speaker.consume("今天", False) == [], "hold a fragment")
    check(speaker.consume("今天天气不错。明天再说", False) == ["今天天气不错。"], "one sentence")
    check(speaker.consume("今天天气不错。明天再说", True) == ["明天再说"], "final tail once")
    pieces, trimmed = v.limit_sentences(["a"] * 10, 8)
    check(len(pieces) == 8 and trimmed, "tts cap")


def test_gate():
    gate = v.TurnGate()
    gen = gate.claim("u1")
    check(gen == 0, "first claim")
    check(gate.claim("u1") is None, "no double agent run")
    gate.finish("u1", gen, False)
    check(gate.claim("u1") == 0, "failed turn can be retried")
    gate.finish("u1", 0, True)
    check(gate.claim("u1") is None, "done turn is not repeated")
    gen2 = gate.claim("u2")
    gate.interrupt()
    gate.finish("u2", gen2, True)
    check(gate.claim("u2") is None, "interrupted turn is not marked done and is not retried")
    check(gate.claim("u3") == gate.generation, "next turn uses the new generation")


def test_vad():
    vad = v.EnergyVad()
    check(vad.push(v._tone(200, 0), False) is None, "silence")
    event = vad.push(v._tone(80, 8000), False)
    check(event == "start", event)
    event = vad.push(v._tone(700, 0), False)
    check(event == "end" and len(vad.utterance) > 0, "utterance ended")
    playing = v.EnergyVad()
    check(playing.push(v._tone(200, 8000), True) == "barge", "barge-in")


if __name__ == "__main__":
    test_sentences()
    test_gate()
    test_vad()
    print("voice_call tests ok")
