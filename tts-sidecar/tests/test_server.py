"""CR-166 생성기 시험. TC-TTS-151 ~ 156(T3-5)."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

SR = 24000
SAMPLES_PER_TOKEN = 1920
REPO_VOICES = Path(__file__).resolve().parent.parent / "voices"


def tone(seconds, peak=0.3):
    t = np.arange(int(SR * seconds)) / SR
    return (peak * np.sin(2 * np.pi * 200 * t)).astype(np.float32)


class FakeModel:
    """generate_voice_clone 이 차례로 돌려줄 소리를 받는다."""

    def __init__(self, outputs, delay=0.0):
        self.outputs = list(outputs)
        self.delay = delay
        self.calls = []
        self.active = 0
        self.max_active = 0
        self._guard = threading.Lock()
        self.model = SimpleNamespace(speech_tokenizer=SimpleNamespace(get_decode_upsample_rate=lambda: SAMPLES_PER_TOKEN))

    def create_voice_clone_prompt(self, ref_audio, ref_text, x_vector_only_mode):
        return {"ref_audio": ref_audio, "ref_text": ref_text}

    def generate_voice_clone(self, **kwargs):
        with self._guard:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            self.calls.append(kwargs)
            out = self.outputs.pop(0) if self.outputs else tone(1.0)
        time.sleep(self.delay)
        with self._guard:
            self.active -= 1
        return [out], SR


def fake_encoder(wav, sample_rate, postprocess):
    return b"ID3fake", round(len(wav) / sample_rate, 3)


class VoiceTest(unittest.TestCase):

    def setUp(self):
        self.voice = server.load_voice(REPO_VOICES / "field-voice-07")

    def test_151_token_limit(self):
        self.assertEqual(server.max_new_tokens("1번.", self.voice), 48)
        self.assertEqual(server.max_new_tokens("가" * 15, self.voice), 120)
        self.assertEqual(server.max_new_tokens("가" * 300, self.voice), 1600)

    def test_152_checks(self):
        self.assertEqual(server.check_audio(np.zeros(0), SR, 48, SAMPLES_PER_TOKEN), "empty_audio")
        bad = tone(1.0)
        bad[10] = np.nan
        self.assertEqual(server.check_audio(bad, SR, 48, SAMPLES_PER_TOKEN), "non_finite_audio")
        self.assertEqual(server.check_audio(np.zeros(SR), SR, 48, SAMPLES_PER_TOKEN), "silent_audio")
        self.assertEqual(server.check_audio(tone(0.2), SR, 48, SAMPLES_PER_TOKEN), "too_short")
        self.assertEqual(server.check_audio(tone(4.0), SR, 48, SAMPLES_PER_TOKEN), "hit_token_limit")
        at_limit = np.full(47 * SAMPLES_PER_TOKEN, 0.3, dtype=np.float32)
        self.assertEqual(server.check_audio(at_limit, SR, 48, SAMPLES_PER_TOKEN), "hit_token_limit")
        self.assertIsNone(server.check_audio(at_limit[:46 * SAMPLES_PER_TOKEN], SR, 48, SAMPLES_PER_TOKEN))
        self.assertIsNone(server.check_audio(tone(3.0), SR, 48, SAMPLES_PER_TOKEN))

    def test_154_voice_load_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "v1"
            folder.mkdir()
            spec = json.loads((REPO_VOICES / "field-voice-07" / "voice.json").read_text(encoding="utf-8"))
            del spec["seed"]
            (folder / "voice.json").write_text(json.dumps(spec), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "missing seed"):
                server.load_voice(folder)

            spec["seed"] = 1
            (folder / "voice.json").write_text(json.dumps(spec), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "ref.wav not found"):
                server.load_voice(folder)

            with self.assertRaisesRegex(ValueError, "no voice"):
                server.load_voices(Path(tmp) / "v1")

    def test_155_postprocess_command(self):
        cmd = server.ffmpeg_command("in.wav", "out.mp3", self.voice.postprocess)
        self.assertEqual(cmd, [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", "in.wav",
            "-af", "silenceremove=start_periods=1:start_duration=0:start_threshold=-50dB,areverse,"
                   "silenceremove=start_periods=1:start_duration=0:start_threshold=-50dB,areverse,"
                   "atempo=0.90,apad=pad_dur=0.05,adelay=50",
            "-ar", "24000", "-ac", "1", "-codec:a", "libmp3lame", "-b:a", "96k", "out.mp3"])

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg 없음")
    def test_155_postprocess_output(self):
        audio, duration = server.encode_mp3(tone(1.0), SR, self.voice.postprocess)
        with tempfile.NamedTemporaryFile(suffix=".mp3") as f:
            f.write(audio)
            f.flush()
            info = json.loads(subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels,bit_rate",
                 "-of", "json", f.name], check=True, capture_output=True, text=True).stdout)["streams"][0]
        self.assertEqual(info["codec_name"], "mp3")
        self.assertEqual(info["sample_rate"], "24000")
        self.assertEqual(info["channels"], 1)
        self.assertEqual(info["bit_rate"], "96000")
        # atempo 0.90 으로 늘고 앞뒤 0.05초가 붙는다.
        self.assertAlmostEqual(duration, 1.0 / 0.9 + 0.1, delta=0.1)


class EngineTest(unittest.TestCase):

    def make(self, outputs, delay=0.0):
        model = FakeModel(outputs, delay)
        engine = server.Engine(server.load_voices(REPO_VOICES), model_loader=lambda: model, encoder=fake_encoder)
        engine.load()
        return engine, model

    def test_153_seed_order(self):
        engine, model = self.make([np.zeros(SR), tone(1.0)])
        with mock.patch.object(server, "seed_everything") as seeded:
            result = engine.synthesize("field-voice-07", "1번.")
        self.assertEqual([c.args[0] for c in seeded.call_args_list], [20261031, 20261032])
        self.assertEqual(result.attempt, 2)
        self.assertEqual(model.calls[0]["max_new_tokens"], 48)
        self.assertEqual(model.calls[0]["temperature"], 0.65)
        self.assertEqual(model.calls[0]["subtalker_top_k"], 30)
        self.assertEqual(model.calls[0]["language"], "Korean")

    def test_153_all_attempts_fail(self):
        engine, _ = self.make([np.zeros(SR)] * 3)
        with self.assertRaises(server.GenerationFailed) as ctx:
            engine.synthesize("field-voice-07", "1번.")
        self.assertEqual(ctx.exception.reason, "silent_audio")

    def test_156_one_at_a_time(self):
        engine, model = self.make([tone(1.0), tone(1.0)], delay=0.3)
        threads = [threading.Thread(target=engine.synthesize, args=("field-voice-07", "1번.")) for _ in range(2)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(len(model.calls), 2)
        self.assertEqual(model.max_active, 1)


class AppTest(unittest.TestCase):

    def setUp(self):
        self.model = FakeModel([])
        self.engine = server.Engine(server.load_voices(REPO_VOICES), model_loader=lambda: self.model,
                                    encoder=fake_encoder)
        self.client = TestClient(server.create_app(self.engine, load_in_background=False))

    def test_health_before_and_after_load(self):
        resp = self.client.get("/health")
        self.assertEqual(resp.status_code, 503)
        self.assertEqual(self.client.post("/synthesize", json={"voice": "field-voice-07", "text": "1번."}).json(),
                         {"error": "tts_generator_unavailable"})
        self.engine.load()
        resp = self.client.get("/health")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["voices"], ["field-voice-07"])

    def test_synthesize(self):
        self.engine.load()
        resp = self.client.post("/synthesize", json={"voice": "field-voice-07", "text": " \"3번\"\n "})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.headers["content-type"], "audio/mpeg")
        self.assertEqual(resp.content, b"ID3fake")
        self.assertEqual(resp.headers["x-attempt"], "1")
        self.assertEqual(resp.headers["x-duration-sec"], "1.0")
        self.assertEqual(self.model.calls[0]["text"], " \"3번\"\n ")

    def test_synthesize_errors(self):
        self.engine.load()
        resp = self.client.post("/synthesize", json={"voice": "nope", "text": "1번."})
        self.assertEqual((resp.status_code, resp.json()), (404, {"error": "voice_not_found:nope"}))
        resp = self.client.post("/synthesize", json={"voice": "field-voice-07", "text": "  "})
        self.assertEqual((resp.status_code, resp.json()), (400, {"error": "text_required"}))
        self.model.outputs = [tone(0.1)] * 3
        resp = self.client.post("/synthesize", json={"voice": "field-voice-07", "text": "1번."})
        self.assertEqual((resp.status_code, resp.json()), (422, {"error": "generation_failed:too_short"}))


if __name__ == "__main__":
    unittest.main()
