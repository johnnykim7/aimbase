"""CR-166, CR-167 생성기 시험. TC-TTS-151 ~ 166(T3-5)."""

import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from functools import partial
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
SILENT_PEAK = 0.01
# 59 와 맥에서 모델이 무음을 낼 때 잰 최대 진폭(-98.9 dB)
MODEL_SILENCE_PEAK = 10 ** (-98.9 / 20)


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


DIE_TEXT = "죽는 문장"


class ProcessFakeModel(FakeModel):
    """모델 프로세스 안에서 도는 가짜. 만든 글과, 씨앗을 맞힌 직후 첫 난수를 파일에 남긴다."""

    def __init__(self, outputs, delay, text_log):
        super().__init__(outputs, delay)
        self.text_log = text_log

    def generate_voice_clone(self, **kwargs):
        if kwargs["text"] == DIE_TEXT:
            os._exit(9)
        with open(self.text_log, "a", encoding="utf-8") as f:
            f.write(json.dumps(kwargs["text"], ensure_ascii=False) + "\n")
        # 씨앗은 모델 프로세스 안에서 맞추므로 시험 프로세스에서 가로챌 수 없다. 그 씨앗의 첫 난수로 확인한다.
        with open(self.text_log + ".draws", "a", encoding="utf-8") as f:
            f.write(repr(random.random()) + "\n")
        return super().generate_voice_clone(**kwargs)


def fake_loader(model_path, marker, load_delay=0.0, gen_delay=0.0, die_loads=0, too_short=False):
    """모델 프로세스 안에서 부른다. 싣는 횟수를 marker 에 남기고, 처음 die_loads 번은 싣다가 죽는다."""
    with open(marker, "a", encoding="utf-8") as f:
        f.write(model_path + "\n")
    if sum(1 for _ in open(marker, encoding="utf-8")) <= die_loads:
        os._exit(9)
    time.sleep(load_delay)
    return ProcessFakeModel([tone(0.1)] * 3 if too_short else [], gen_delay, marker + ".texts")


class VoiceTest(unittest.TestCase):

    def setUp(self):
        self.voice = server.load_voice(REPO_VOICES / "field-voice-07")

    def test_151_token_limit(self):
        self.assertEqual(server.max_new_tokens("1번.", self.voice), 48)
        self.assertEqual(server.max_new_tokens("가" * 15, self.voice), 120)
        self.assertEqual(server.max_new_tokens("가" * 300, self.voice), 1600)

    def test_152_checks(self):
        self.assertEqual(server.check_audio(np.zeros(0), SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK), "empty_audio")
        bad = tone(1.0)
        bad[10] = np.nan
        self.assertEqual(server.check_audio(bad, SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK), "non_finite_audio")
        self.assertEqual(server.check_audio(np.zeros(SR), SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK), "silent_audio")
        self.assertEqual(server.check_audio(tone(0.2), SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK), "too_short")
        self.assertEqual(server.check_audio(tone(4.0), SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK), "hit_token_limit")
        at_limit = np.full(47 * SAMPLES_PER_TOKEN, 0.3, dtype=np.float32)
        self.assertEqual(server.check_audio(at_limit, SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK), "hit_token_limit")
        self.assertIsNone(server.check_audio(at_limit[:46 * SAMPLES_PER_TOKEN], SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK))
        self.assertIsNone(server.check_audio(tone(3.0), SR, 48, SAMPLES_PER_TOKEN, SILENT_PEAK))

    def test_152_silent_peak_from_voice(self):
        self.assertAlmostEqual(self.voice.silent_peak, 0.01)
        check = partial(server.check_audio, sample_rate=SR, token_limit=48, samples_per_token=SAMPLES_PER_TOKEN,
                        silent_peak=self.voice.silent_peak)
        self.assertEqual(check(tone(1.0, peak=MODEL_SILENCE_PEAK)), "silent_audio")
        self.assertEqual(check(tone(1.0, peak=0.009)), "silent_audio")
        self.assertIsNone(check(tone(1.0, peak=0.011)))

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

    def test_167_take_moves_seed(self):
        engine, _ = self.make([np.zeros(SR), tone(1.0)])
        with mock.patch.object(server, "seed_everything") as seeded:
            result = engine.synthesize("field-voice-07", "에스, 팔십이번", take=1)
        self.assertEqual([c.args[0] for c in seeded.call_args_list], [20261034, 20261035])
        self.assertEqual(result.attempt, 2)

    def test_167_take_zero_keeps_seed(self):
        engine, _ = self.make([tone(1.0)])
        with mock.patch.object(server, "seed_everything") as seeded:
            engine.synthesize("field-voice-07", "에스, 팔십이번", take=0)
        self.assertEqual([c.args[0] for c in seeded.call_args_list], [20261031])

    def test_153_model_silence_retries_with_next_seed(self):
        engine, _ = self.make([tone(0.56, peak=MODEL_SILENCE_PEAK), tone(1.0)])
        with mock.patch.object(server, "seed_everything") as seeded:
            result = engine.synthesize("field-voice-07", "일")
        self.assertEqual([c.args[0] for c in seeded.call_args_list], [20261031, 20261032])
        self.assertEqual(result.attempt, 2)

    def test_156_one_at_a_time(self):
        engine, model = self.make([tone(1.0), tone(1.0)], delay=0.3)
        threads = [threading.Thread(target=engine.synthesize, args=("field-voice-07", "1번.")) for _ in range(2)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(len(model.calls), 2)
        self.assertEqual(model.max_active, 1)


def wait_until(predicate, seconds=8):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False


class HostCase(unittest.TestCase):

    def make(self, idle=60, retry=60, source=lambda: "/fake/model", **fake):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.marker = os.path.join(tmp.name, "loads")
        loader = partial(fake_loader, marker=self.marker, **fake)
        host = server.ModelHost(server.load_voices(REPO_VOICES), REPO_VOICES, idle_seconds=idle, retry_seconds=retry,
                                model_source=source,
                                target=partial(server.model_process, model_loader=loader, encoder=fake_encoder))
        self.addCleanup(host.close)
        return host

    def ready_host(self, **kwargs):
        host = self.make(**kwargs)
        host.prepare()
        return host

    def loads(self):
        if not os.path.exists(self.marker):
            return 0
        with open(self.marker, encoding="utf-8") as f:
            return sum(1 for _ in f)

    def texts(self):
        with open(self.marker + ".texts", encoding="utf-8") as f:
            return [json.loads(line) for line in f]


class HostTest(HostCase):

    def test_157_starts_without_model(self):
        host = self.ready_host()
        client = TestClient(server.create_app(host, start_in_background=False))
        resp = client.get("/health")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), {"status": "ok", "voices": ["field-voice-07"], "model": "unloaded"})
        self.assertIsNone(host._process)
        self.assertEqual(self.loads(), 0)

    def test_158_first_sentence_loads_model(self):
        host = self.ready_host()
        result = host.synthesize("field-voice-07", "1번.")
        self.assertEqual(result.audio, b"ID3fake")
        self.assertEqual(host.state, server.READY)
        host.synthesize("field-voice-07", "2번.")
        self.assertEqual(self.loads(), 1)
        self.assertEqual(self.texts(), ["1번.", "2번."])

    def test_159_waits_while_loading(self):
        host = self.ready_host(load_delay=1.0)
        results = []
        threads = [threading.Thread(target=lambda: results.append(host.synthesize("field-voice-07", "1번.")))
                   for _ in range(2)]
        for t in threads:
            t.start()
        self.assertTrue(wait_until(lambda: host.state == server.LOADING))
        client = TestClient(server.create_app(host, start_in_background=False))
        self.assertEqual(client.get("/health").json()["model"], "loading")
        for t in threads:
            t.join()
        self.assertEqual(len(results), 2)
        self.assertEqual(self.loads(), 1)

    def test_160_unloads_after_idle(self):
        host = self.make(idle=1)
        host.start()
        self.assertTrue(wait_until(lambda: host.state == server.UNLOADED))
        host.synthesize("field-voice-07", "1번.")
        process = host._process
        self.assertTrue(process.is_alive())
        self.assertTrue(wait_until(lambda: host.state == server.UNLOADED))
        self.assertFalse(process.is_alive())
        host.synthesize("field-voice-07", "2번.")
        self.assertEqual(self.loads(), 2)

    def test_161_keeps_model_while_generating(self):
        host = self.make(idle=1, gen_delay=2.5)
        host.start()
        self.assertTrue(wait_until(lambda: host.state == server.UNLOADED))
        worker = threading.Thread(target=host.synthesize, args=("field-voice-07", "1번."))
        worker.start()
        self.assertTrue(wait_until(lambda: host.state == server.READY))
        worker_process = host._process
        worker.join()
        # 만드는 동안 쉬는 시간을 넘겼어도 그동안 내리지 않았고, 끝난 때부터 다시 잰다.
        self.assertIs(host._process, worker_process)
        self.assertEqual(host.state, server.READY)
        self.assertTrue(wait_until(lambda: host.state == server.UNLOADED))

    def test_162_load_death_goes_to_backoff(self):
        host = self.ready_host(die_loads=1)
        client = TestClient(server.create_app(host, start_in_background=False))
        resp = client.post("/synthesize", json={"voice": "field-voice-07", "text": "1번."})
        self.assertEqual((resp.status_code, resp.json()), (503, {"error": "tts_generator_unavailable"}))
        resp = client.get("/health")
        self.assertEqual(resp.status_code, 503)
        self.assertEqual(resp.json()["status"], "backoff")
        self.assertIn("exited: 9", resp.json()["error"])
        self.assertGreater(resp.json()["retry_after"], 0)

    def test_163_reloads_after_retry_time(self):
        host = self.ready_host(retry=2, die_loads=1)
        with self.assertRaises(server.ModelUnavailable):
            host.synthesize("field-voice-07", "1번.")
        with self.assertRaises(server.ModelUnavailable):
            host.synthesize("field-voice-07", "1번.")
        self.assertEqual(self.loads(), 1)
        self.assertTrue(wait_until(lambda: host.state == server.UNLOADED))
        self.assertEqual(host.synthesize("field-voice-07", "1번.").audio, b"ID3fake")
        self.assertEqual(self.loads(), 2)

    def test_164_death_while_generating(self):
        host = self.ready_host()
        with self.assertRaises(server.ModelUnavailable) as ctx:
            host.synthesize("field-voice-07", DIE_TEXT)
        self.assertIn("exited: 9", str(ctx.exception))
        self.assertEqual(host.state, server.BACKOFF)

    def test_164_waiting_sentence_does_not_reload(self):
        host = self.ready_host(load_delay=0.5)
        results = []

        def call(text):
            try:
                results.append(host.synthesize("field-voice-07", text))
            except server.ModelUnavailable as e:
                results.append(e)

        first = threading.Thread(target=call, args=(DIE_TEXT,))
        first.start()
        self.assertTrue(wait_until(lambda: host.state == server.LOADING))
        second = threading.Thread(target=call, args=("1번.",))
        second.start()
        first.join()
        second.join()
        self.assertTrue(all(isinstance(r, server.ModelUnavailable) for r in results))
        self.assertEqual(self.loads(), 1)

    def test_165_settings(self):
        for raw in ("0", "-5", "abc", ""):
            with mock.patch.dict(os.environ, {"TTS_IDLE_UNLOAD_SECONDS": raw}):
                with self.assertRaisesRegex(ValueError, "TTS_IDLE_UNLOAD_SECONDS"):
                    server.seconds_setting("TTS_IDLE_UNLOAD_SECONDS", 300)
        with mock.patch.dict(os.environ, {"TTS_IDLE_UNLOAD_SECONDS": "60"}):
            self.assertEqual(server.seconds_setting("TTS_IDLE_UNLOAD_SECONDS", 300), 60)
        self.assertEqual(server.seconds_setting("TTS_NOT_SET", 300), 300)

    def test_166_model_files(self):
        gate = threading.Event()

        def source():
            gate.wait(5)
            return "/fake/model"

        host = self.make(source=source)
        client = TestClient(server.create_app(host, start_in_background=False))
        preparing = threading.Thread(target=host.prepare)
        preparing.start()
        resp = client.get("/health")
        self.assertEqual((resp.status_code, resp.json()), (503, {"status": "downloading"}))
        self.assertEqual(client.post("/synthesize", json={"voice": "field-voice-07", "text": "1번."}).status_code, 503)
        gate.set()
        preparing.join()
        self.assertEqual(client.get("/health").json()["model"], "unloaded")

    def test_166_model_files_failed(self):
        def source():
            raise OSError("no network")

        host = self.make(source=source)
        host.prepare()
        resp = TestClient(server.create_app(host, start_in_background=False)).get("/health")
        self.assertEqual(resp.status_code, 503)
        self.assertEqual(resp.json(), {"status": "failed", "error": "OSError: no network"})

    def test_166_local_folder_is_used_as_is(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(server, "MODEL_ID", tmp):
            self.assertEqual(server.model_snapshot(), tmp)


class AppTest(HostCase):

    def client(self, **fake):
        return TestClient(server.create_app(self.ready_host(**fake), start_in_background=False))

    def test_synthesize(self):
        resp = self.client().post("/synthesize", json={"voice": "field-voice-07", "text": " \"3번\"\n "})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.headers["content-type"], "audio/mpeg")
        self.assertEqual(resp.content, b"ID3fake")
        self.assertEqual(resp.headers["x-attempt"], "1")
        self.assertEqual(resp.headers["x-duration-sec"], "1.0")
        self.assertEqual(self.texts(), [" \"3번\"\n "])

    def draws(self):
        with open(self.marker + ".texts.draws", encoding="utf-8") as f:
            return [float(line) for line in f]

    def test_167_take_reaches_model_process(self):
        client = self.client()
        resp = client.post("/synthesize", json={"voice": "field-voice-07", "text": "에스, 팔십이번"})
        self.assertEqual(resp.status_code, 200)
        resp = client.post("/synthesize", json={"voice": "field-voice-07", "text": "에스, 팔십이번", "take": 2})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(self.draws(), [random.Random(20261031).random(), random.Random(20261037).random()])

    def test_167_negative_take(self):
        client = self.client()
        resp = client.post("/synthesize", json={"voice": "field-voice-07", "text": "에스, 팔십이번", "take": -1})
        self.assertEqual((resp.status_code, resp.json()), (400, {"error": "take_out_of_range"}))
        self.assertEqual(self.loads(), 0)

    def test_synthesize_errors(self):
        client = self.client(too_short=True)
        resp = client.post("/synthesize", json={"voice": "nope", "text": "1번."})
        self.assertEqual((resp.status_code, resp.json()), (404, {"error": "voice_not_found:nope"}))
        resp = client.post("/synthesize", json={"voice": "field-voice-07", "text": "  "})
        self.assertEqual((resp.status_code, resp.json()), (400, {"error": "text_required"}))
        self.assertEqual(self.loads(), 0)
        resp = client.post("/synthesize", json={"voice": "field-voice-07", "text": "1번."})
        self.assertEqual((resp.status_code, resp.json()), (422, {"error": "generation_failed:too_short"}))


if __name__ == "__main__":
    unittest.main()
