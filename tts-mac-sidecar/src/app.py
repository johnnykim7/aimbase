"""
Aimbase TTS Mac Sidecar (CR-165) - 복제 목소리 음성 합성 (Qwen3-TTS, MLX)

독립 프로세스로 동작하며 Aimbase 는 HTTP 로만 호출한다. 맥 GPU(MLX) 전용이다.

  TTS_API_KEY          X-Api-Key 머리글로 요구할 키. 공유기 포트 전달로 인터넷에 열리므로
                       반드시 설정한다. 비어 있으면 기동하지 않는다.
  TTS_MODEL            mlx-audio 모델 (기본 mlx-community/Qwen3-TTS-12Hz-1.7B-Base-bf16)
  TTS_VOICES_DIR       목소리 폴더 (기본 ../voices). 목소리 하나가 폴더 하나(voice.json, ref.wav)
  TTS_MAX_CHUNK_CHARS  한 번에 만드는 글 길이 상한 (기본 200)
  TTS_SPEED_MIN/MAX    speed 범위 (기본 0.5 ~ 2.0, ffmpeg atempo 한 단으로 되는 범위)
"""

import hmac
import io
import json
import logging
import os
import re
import subprocess
import threading
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
log = logging.getLogger("tts")

API_KEY = os.getenv("TTS_API_KEY", "").strip()
if not API_KEY:
    raise RuntimeError(
        "TTS_API_KEY 가 비어 있다. 이 엔진은 공유기 포트 전달로 인터넷에 열리므로 "
        "인증 없이 기동하지 않는다. .env 또는 환경변수에 키를 설정할 것."
    )

MODEL_REPO = os.getenv("TTS_MODEL", "mlx-community/Qwen3-TTS-12Hz-1.7B-Base-bf16")
VOICES_DIR = Path(os.getenv("TTS_VOICES_DIR", str(Path(__file__).resolve().parent.parent / "voices")))
# 엔진의 한 번 생성 상한(2,048 토큰, 소리 약 170초)에 닿지 않는 길이
MAX_CHUNK_CHARS = int(os.getenv("TTS_MAX_CHUNK_CHARS", "200"))
SPEED_MIN = float(os.getenv("TTS_SPEED_MIN", "0.5"))
SPEED_MAX = float(os.getenv("TTS_SPEED_MAX", "2.0"))
NORMAL_SPEED = 1.0


@dataclass(frozen=True)
class Voice:
    id: str
    ref_audio: str
    ref_text: str
    language: str
    seed: int
    temperature: float | None
    top_k: int | None
    top_p: float | None
    repetition_penalty: float | None


def load_voices(directory: Path) -> dict[str, Voice]:
    """voices/<id>/voice.json 과 ref.wav 를 읽는다. 폴더 모양은 CR-166 tts-sidecar 와 같다."""
    voices: dict[str, Voice] = {}
    if not directory.is_dir():
        log.warning("목소리 폴더가 없다: %s", directory)
        return voices
    for folder in sorted(p for p in directory.iterdir() if p.is_dir()):
        spec_path = folder / "voice.json"
        if not spec_path.is_file():
            continue
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
        ref_audio = folder / spec.get("ref_audio", "ref.wav")
        if not ref_audio.is_file():
            log.warning("목소리 %s: 견본 소리가 없어 건너뛴다", folder.name)
            continue
        gen = spec.get("generation", {})
        voices[folder.name] = Voice(
            id=folder.name,
            ref_audio=str(ref_audio),
            ref_text=spec["ref_text"],
            language=spec.get("language", "auto").lower(),
            seed=int(spec["seed"]),
            temperature=gen.get("temperature"),
            top_k=gen.get("top_k"),
            top_p=gen.get("top_p"),
            repetition_penalty=gen.get("repetition_penalty"),
        )
    return voices


# 마침표 뒤에 공백이 올 때만 문장 끝으로 본다. 8.5% 같은 소수를 자르지 않기 위해서다.
_SENTENCE_END = re.compile(r"(?<=[.?!])\s+|\n+")


def split_text(text: str, max_chars: int) -> list[str]:
    """문장 끝에서 나눠 max_chars 안쪽 묶음으로 만든다. 문장 끝 없이 긴 글은 max_chars 에서 자른다."""
    chunks: list[str] = []
    current = ""
    for sentence in (s.strip() for s in _SENTENCE_END.split(text)):
        if not sentence:
            continue
        while len(sentence) > max_chars:
            if current:
                chunks.append(current)
                current = ""
            chunks.append(sentence[:max_chars])
            sentence = sentence[max_chars:].lstrip()
        if not sentence:
            continue
        if current and len(current) + 1 + len(sentence) > max_chars:
            chunks.append(current)
            current = sentence
        else:
            current = f"{current} {sentence}" if current else sentence
    if current:
        chunks.append(current)
    return chunks


def build_ffmpeg_command(speed: float) -> list[str]:
    """mlx-audio 의 Qwen3 는 speed 를 받지 않아 MP3 로 바꿀 때 atempo 로 빠르기를 바꾼다."""
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "wav", "-i", "pipe:0"]
    if speed != NORMAL_SPEED:
        cmd += ["-filter:a", f"atempo={speed:g}"]
    cmd += ["-codec:a", "libmp3lame", "-q:a", "4", "-f", "mp3", "pipe:1"]
    return cmd


def encode_mp3(audio: np.ndarray, sample_rate: int, speed: float) -> bytes:
    wav = io.BytesIO()
    sf.write(wav, audio, sample_rate, format="WAV", subtype="PCM_16")
    r = subprocess.run(build_ffmpeg_command(speed), input=wav.getvalue(), capture_output=True, check=False)
    if r.returncode != 0:
        raise RuntimeError("ffmpeg 실패: " + r.stderr.decode("utf-8", "replace")[-500:])
    return r.stdout


class QwenEngine:
    """mlx-audio Qwen3-TTS Base 복제 생성. 모델(약 4GB)은 처음 올릴 때 받는다."""

    def __init__(self, repo: str):
        self.repo = repo
        self.model = None

    @property
    def loaded(self) -> bool:
        return self.model is not None

    @property
    def sample_rate(self) -> int:
        return self.model.sample_rate

    def ensure_loaded(self) -> None:
        if self.model is None:
            from mlx_audio.tts.utils import load_model

            started = time.time()
            self.model = load_model(self.repo)
            log.info("모델 올림: %s (%.1fs)", self.repo, time.time() - started)
            keep_resident()

    def generate(self, text: str, voice: Voice) -> np.ndarray:
        import mlx.core as mx

        # 묶음마다 씨앗을 다시 둔다. 같은 글은 앞 묶음과 상관없이 같은 소리가 난다.
        mx.random.seed(voice.seed)
        results = self.model.generate(
            text=text,
            ref_audio=voice.ref_audio,
            ref_text=voice.ref_text,
            lang_code=voice.language,
            temperature=voice.temperature,
            top_k=voice.top_k,
            top_p=voice.top_p,
            repetition_penalty=voice.repetition_penalty,
        )
        audio = [np.array(r.audio) for r in results]
        if not audio:
            raise RuntimeError("엔진이 소리를 만들지 않았다(첫 걸음에 끝). 목소리 견본을 확인할 것")
        return np.concatenate(audio)


def keep_resident() -> None:
    """
    오래 쉬면 맥이 모델 메모리를 스왑으로 내보내 다음 첫 읽기가 24초 걸렸다(위젯 대기 20초 초과).
    올린 가중치만큼 묶어 두면 내보내지 않는다. 묶지 못해도 엔진은 그대로 돈다.
    """
    import mlx.core as mx

    try:
        weights = mx.get_active_memory()
        mx.set_wired_limit(weights)
        log.info("모델 메모리 %.1fGB 를 묶어 둠", weights / 1e9)
    except Exception:
        log.exception("모델 메모리를 묶지 못했다. 오래 쉰 뒤 첫 읽기가 느릴 수 있다")


VOICES = load_voices(VOICES_DIR)
engine = QwenEngine(MODEL_REPO)
# GPU 메모리(1.7B 에 최대 약 11.5GB)를 두 생성이 나눠 쓰지 않게 한 번에 하나만 만든다.
_generate_lock = threading.Lock()


def _warm_up() -> None:
    try:
        with _generate_lock:
            engine.ensure_loaded()
    except Exception:
        log.exception("모델을 올리지 못했다. 첫 요청에서 다시 시도한다")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    log.info("목소리 %d개: %s", len(VOICES), ", ".join(VOICES) or "-")
    threading.Thread(target=_warm_up, name="tts-warm-up", daemon=True).start()
    yield


app = FastAPI(title="Aimbase TTS Mac Sidecar", version="0.1.0", lifespan=lifespan)


class SynthesizeRequest(BaseModel):
    text: str = ""
    voice: str = ""
    speed: float = NORMAL_SPEED


def _require_api_key(provided: str | None) -> None:
    """상수 시간 비교로 키를 검증한다. 실패 시 401."""
    if not provided or not hmac.compare_digest(provided, API_KEY):
        raise HTTPException(status_code=401, detail="invalid or missing X-Api-Key")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": MODEL_REPO,
        "loaded": engine.loaded,
        "voices": sorted(VOICES),
    }


@app.post("/synthesize")
def synthesize(req: SynthesizeRequest, x_api_key: str | None = Header(default=None)):
    _require_api_key(x_api_key)
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text is empty")
    if not SPEED_MIN <= req.speed <= SPEED_MAX:
        raise HTTPException(status_code=400, detail=f"speed must be {SPEED_MIN} ~ {SPEED_MAX}")
    voice = VOICES.get(req.voice)
    if voice is None:
        raise HTTPException(status_code=404, detail=f"unknown voice: {req.voice}")

    chunks = split_text(text, MAX_CHUNK_CHARS)
    try:
        with _generate_lock:
            started = time.time()
            engine.ensure_loaded()
            audio = np.concatenate([engine.generate(c, voice) for c in chunks])
            sample_rate = engine.sample_rate
            elapsed = time.time() - started
        mp3 = encode_mp3(audio, sample_rate, req.speed)
    except Exception as e:
        log.exception("생성 실패: voice=%s, %d자", voice.id, len(text))
        raise HTTPException(status_code=500, detail=f"generation failed: {e}") from e

    log.info("생성: voice=%s, %d자, 묶음 %d, 소리 %.1fs, %.1fs 걸림, speed=%g",
             voice.id, len(text), len(chunks), len(audio) / sample_rate, elapsed, req.speed)
    return Response(content=mp3, media_type="audio/mpeg")
