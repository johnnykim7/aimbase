"""CR-165 맥 음성 엔진 시험(TC-TTS-231 ~ 237). 실제 모델은 TC-TTS-237 에서만 쓴다."""

import os
import subprocess
import sys
import threading
import time

import numpy as np
import pytest
from fastapi.testclient import TestClient

import app as tts_app
from conftest import ROOT, TEST_KEY

VOICE = "ko-female-01"
SAMPLE_RATE = 24000


class FakeEngine:
    """한 번 생성에 0.2초 걸리는 가짜 엔진. 생성 구간을 적어 둔다."""

    def __init__(self, seconds: float = 0.2):
        self.seconds = seconds
        self.calls: list[str] = []
        self.spans: list[tuple[float, float]] = []

    loaded = True
    sample_rate = SAMPLE_RATE

    def ensure_loaded(self) -> None:
        pass

    def generate(self, text, voice):
        start = time.monotonic()
        time.sleep(self.seconds)
        self.calls.append(text)
        self.spans.append((start, time.monotonic()))
        return np.zeros(int(SAMPLE_RATE * 0.1), dtype=np.float32)


@pytest.fixture
def fake_engine(monkeypatch):
    engine = FakeEngine()
    monkeypatch.setattr(tts_app, "engine", engine)
    return engine


@pytest.fixture
def client():
    # lifespan(모델 올리기)을 돌리지 않으려고 with 없이 쓴다.
    return TestClient(tts_app.app)


def post(client, body, key=TEST_KEY):
    headers = {"X-Api-Key": key} if key is not None else {}
    return client.post("/synthesize", json=body, headers=headers)


@pytest.mark.parametrize("key, expected", [(None, 401), ("wrong", 401), (TEST_KEY, 200)])
def test_tc231_api_key(client, fake_engine, key, expected):
    res = post(client, {"text": "안녕하세요.", "voice": VOICE}, key=key)

    assert res.status_code == expected
    if expected == 200:
        assert res.headers["content-type"] == "audio/mpeg"
        assert len(res.content) > 0


@pytest.mark.parametrize("body, expected", [
    ({"text": "  ", "voice": VOICE}, 400),
    ({"text": "안녕", "voice": "no-such-voice"}, 404),
    ({"text": "안녕", "voice": VOICE, "speed": 0.4}, 400),
    ({"text": "안녕", "voice": VOICE, "speed": 2.1}, 400),
])
def test_tc232_request_checks(client, fake_engine, body, expected):
    assert post(client, body).status_code == expected
    assert fake_engine.calls == []


def test_tc233_splits_long_text_at_sentence_ends():
    sentences = [f"{i}번째 문장은 회의 내용을 정리한 글이고 금액은 12억 4천만 원, 성장은 8.5% 입니다." for i in range(12)]
    text = " ".join(sentences)
    assert len(text) >= 500

    chunks = tts_app.split_text(text, 200)

    assert all(len(c) <= 200 for c in chunks)
    assert " ".join(chunks) == text
    assert all(c.endswith("입니다.") for c in chunks)
    assert any("8.5%" in c for c in chunks)


def test_tc233_cuts_text_without_sentence_end_at_limit():
    text = "가" * 250

    chunks = tts_app.split_text(text, 200)

    assert [len(c) for c in chunks] == [200, 50]


def test_tc233_splits_at_newline_and_question_mark():
    assert tts_app.split_text("첫 줄\n둘째 줄인가요? 셋째!", 10) == ["첫 줄", "둘째 줄인가요?", "셋째!"]


@pytest.mark.parametrize("speed, expected", [(0.5, "atempo=0.5"), (1.0, None), (1.5, "atempo=1.5")])
def test_tc234_speed_filter(speed, expected):
    cmd = tts_app.build_ffmpeg_command(speed)

    if expected is None:
        assert "-filter:a" not in cmd
    else:
        assert cmd[cmd.index("-filter:a") + 1] == expected


def test_tc234_encodes_mp3_with_speed():
    audio = np.zeros(SAMPLE_RATE, dtype=np.float32)

    mp3 = tts_app.encode_mp3(audio, SAMPLE_RATE, 1.5)

    assert mp3[:3] == b"ID3" or mp3[0] == 0xFF


def test_tc235_generates_one_at_a_time(fake_engine):
    results = []

    def call():
        results.append(post(TestClient(tts_app.app), {"text": "안녕하세요.", "voice": VOICE}).status_code)

    threads = [threading.Thread(target=call) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert results == [200, 200]
    (s1, e1), (s2, e2) = sorted(fake_engine.spans)
    assert e1 <= s2


def test_tc236_refuses_to_start_without_key():
    env = {**os.environ, "TTS_API_KEY": ""}
    r = subprocess.run([sys.executable, "-c", "import app"], cwd=ROOT / "src", env=env,
                       capture_output=True, text=True)

    assert r.returncode != 0
    assert "TTS_API_KEY" in r.stderr


def test_health_lists_voices(client, fake_engine):
    body = client.get("/health").json()

    assert body["voices"] == [VOICE]
    assert body["loaded"] is True


@pytest.mark.skipif(os.getenv("TTS_E2E") != "1", reason="실제 모델(약 4GB, GPU)이 필요하다. TTS_E2E=1 로 돌린다")
def test_tc237_same_text_same_sound():
    engine = tts_app.QwenEngine(tts_app.MODEL_REPO)
    engine.ensure_loaded()
    voice = tts_app.VOICES[VOICE]
    text = "주간 회의는 2026년 11월 7일 오후 2시에 시작합니다."

    first = engine.generate(text, voice)
    second = engine.generate(text, voice)

    assert np.array_equal(first, second)
