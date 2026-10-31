"""CR-166 같은 목소리 음성 생성기(tts-sidecar). api 만 부른다(CR-166 3.1, 3.2).

음색 하나는 voices/<이름>/ 폴더 하나이고, 같은 음색 같은 글이면 씨앗 순서가 같아 다시 만들어도 같은 소리가 난다(BIZ-134).
한 번에 한 문장만 만든다(BIZ-133). api 가 한 작업씩 보내지만 생성기 안에서도 잠가 둔다.
"""

import json
import logging
import os
import random
import subprocess
import tempfile
import threading
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
from fastapi import FastAPI
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel

log = logging.getLogger("tts-sidecar")

MODEL_ID = os.getenv("TTS_MODEL_ID", "Qwen/Qwen3-TTS-12Hz-1.7B-Base")
VOICES_DIR = Path(os.getenv("TTS_VOICES_DIR", str(Path(__file__).resolve().parent / "voices")))
THREADS = int(os.getenv("TTS_THREADS", "8"))

MIN_DURATION_SEC = 0.25
SILENT_PEAK = 1e-5
REQUIRED_FIELDS = ("ref_audio", "ref_text", "language", "seed", "attempts", "tokens_per_char",
                   "min_new_tokens", "max_new_tokens", "generation", "postprocess")
REQUIRED_POSTPROCESS = ("filter", "sample_rate", "bitrate")


class GenerationFailed(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class Voice:
    name: str
    ref_audio: Path
    ref_text: str
    language: str
    seed: int
    attempts: int
    tokens_per_char: int
    min_new_tokens: int
    max_new_tokens: int
    generation: dict
    postprocess: dict


def load_voice(folder):
    folder = Path(folder)
    spec_path = folder / "voice.json"
    if not spec_path.is_file():
        raise ValueError(f"voice {folder.name}: voice.json not found")
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    missing = [k for k in REQUIRED_FIELDS if k not in spec]
    if missing:
        raise ValueError(f"voice {folder.name}: missing {', '.join(missing)}")
    missing = [k for k in REQUIRED_POSTPROCESS if k not in spec["postprocess"]]
    if missing:
        raise ValueError(f"voice {folder.name}: missing postprocess {', '.join(missing)}")
    ref_audio = folder / spec["ref_audio"]
    if not ref_audio.is_file():
        raise ValueError(f"voice {folder.name}: {spec['ref_audio']} not found")
    return Voice(name=folder.name, ref_audio=ref_audio, ref_text=spec["ref_text"], language=spec["language"],
                 seed=int(spec["seed"]), attempts=int(spec["attempts"]),
                 tokens_per_char=int(spec["tokens_per_char"]), min_new_tokens=int(spec["min_new_tokens"]),
                 max_new_tokens=int(spec["max_new_tokens"]), generation=dict(spec["generation"]),
                 postprocess=dict(spec["postprocess"]))


def load_voices(root):
    folders = sorted(p for p in Path(root).iterdir() if p.is_dir())
    if not folders:
        raise ValueError(f"no voice under {root}")
    return {f.name: load_voice(f) for f in folders}


def max_new_tokens(text, voice):
    # 글자 수는 코드 포인트로 센다(BIZ-135). 상한까지 가면 끝맺지 못하고 늘어진 것으로 본다.
    return max(voice.min_new_tokens, min(voice.max_new_tokens, len(text) * voice.tokens_per_char))


def check_audio(wav, sample_rate, token_limit, samples_per_token):
    wav = np.asarray(wav, dtype=np.float32).reshape(-1)
    if wav.size == 0:
        return "empty_audio"
    if not np.isfinite(wav).all():
        return "non_finite_audio"
    if float(np.max(np.abs(wav))) < SILENT_PEAK:
        return "silent_audio"
    if wav.size / sample_rate < MIN_DURATION_SEC:
        return "too_short"
    # 토큰 하나가 소리 samples_per_token 표본으로 풀린다. 만든 토큰 수를 소리 길이로 거꾸로 센다.
    # 상한까지 가면 끝맺음 표시 없이 상한보다 하나 적은 토큰이 나오고, 스스로 끝맺으면 그보다 적다(qwen-tts 0.1.1 실측).
    if wav.size / samples_per_token > token_limit - 1.5:
        return "hit_token_limit"
    return None


def ffmpeg_command(src, dst, postprocess):
    return ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
            "-af", postprocess["filter"], "-ar", str(postprocess["sample_rate"]), "-ac", "1",
            "-codec:a", "libmp3lame", "-b:a", postprocess["bitrate"], str(dst)]


def encode_mp3(wav, sample_rate, postprocess):
    """만든 소리를 299개와 같은 뒤처리로 MP3 로 만든다. (MP3, 길이 초)"""
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "raw.wav"
        dst = Path(tmp) / "out.mp3"
        sf.write(src, np.asarray(wav, dtype=np.float32), sample_rate, subtype="PCM_16")
        subprocess.run(ffmpeg_command(src, dst, postprocess), check=True, capture_output=True)
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                "-of", "default=noprint_wrappers=1:nokey=1", str(dst)],
                               check=True, capture_output=True, text=True)
        return dst.read_bytes(), round(float(probe.stdout.strip()), 3)


def load_model():
    import torch
    from qwen_tts import Qwen3TTSModel

    torch.set_num_threads(THREADS)
    return Qwen3TTSModel.from_pretrained(MODEL_ID, device_map="cpu", dtype=torch.float32)


def seed_everything(seed):
    random.seed(seed)
    np.random.seed(seed)
    try:
        import torch
    except ImportError:
        return
    torch.manual_seed(seed)


@dataclass
class Synthesis:
    audio: bytes
    duration_sec: float
    elapsed_sec: float
    attempt: int


class Engine:
    def __init__(self, voices, model_loader=load_model, encoder=encode_mp3):
        self.voices = voices
        self._model_loader = model_loader
        self._encoder = encoder
        self._model = None
        self._prompts = {}
        self._samples_per_token = None
        self._lock = threading.Lock()
        self.load_error = None

    @property
    def ready(self):
        return self._model is not None

    def load(self):
        try:
            started = time.monotonic()
            model = self._model_loader()
            prompts = {}
            for name, voice in self.voices.items():
                prompts[name] = model.create_voice_clone_prompt(
                    ref_audio=str(voice.ref_audio), ref_text=voice.ref_text, x_vector_only_mode=False)
            self._prompts = prompts
            # 이름은 12Hz 지만 1920 표본(24kHz 에서 12.5토큰/초)이다. 짐작하지 않고 모델 설정에서 읽는다.
            self._samples_per_token = int(model.model.speech_tokenizer.get_decode_upsample_rate())
            self._model = model
            log.info("model and %d voices ready in %.1fs", len(prompts), time.monotonic() - started)
        except Exception as e:
            self.load_error = f"{type(e).__name__}: {e}"
            log.exception("model load failed")

    def synthesize(self, voice_name, text):
        voice = self.voices[voice_name]
        token_limit = max_new_tokens(text, voice)
        with self._lock:
            started = time.monotonic()
            reason = None
            for attempt in range(1, voice.attempts + 1):
                seed_everything(voice.seed + attempt - 1)
                wavs, sample_rate = self._model.generate_voice_clone(
                    text=text, language=voice.language, voice_clone_prompt=self._prompts[voice_name],
                    max_new_tokens=token_limit, **voice.generation)
                wav = wavs[0] if wavs else np.zeros(0, dtype=np.float32)
                reason = check_audio(wav, sample_rate, token_limit, self._samples_per_token)
                log.info("voice=%s attempt=%d samples=%d tokens=%.1f limit=%d", voice_name, attempt,
                         np.asarray(wav).size, np.asarray(wav).size / self._samples_per_token, token_limit)
                if reason is None:
                    audio, duration = self._encoder(wav, sample_rate, voice.postprocess)
                    return Synthesis(audio=audio, duration_sec=duration,
                                     elapsed_sec=round(time.monotonic() - started, 3), attempt=attempt)
                log.warning("voice=%s attempt=%d rejected: %s", voice_name, attempt, reason)
            raise GenerationFailed(reason)


class SynthesizeRequest(BaseModel):
    voice: str = ""
    text: str = ""


def _error(status, code):
    return JSONResponse(status_code=status, content={"error": code})


def create_app(engine, load_in_background=True):
    # 모델을 올리는 동안(약 7초)에도 /health 가 503 으로 답하도록 뒤에서 올린다.
    @asynccontextmanager
    async def lifespan(_app):
        if load_in_background:
            threading.Thread(target=engine.load, name="tts-model-load", daemon=True).start()
        yield

    app = FastAPI(title="Aimbase TTS Sidecar", lifespan=lifespan)

    @app.get("/health")
    def health():
        if engine.ready:
            return {"status": "ok", "voices": sorted(engine.voices)}
        if engine.load_error:
            return JSONResponse(status_code=503, content={"status": "failed", "error": engine.load_error})
        return JSONResponse(status_code=503, content={"status": "loading"})

    # 동기 함수라 FastAPI 가 작업 스레드에서 돌린다. 생성 중에도 /health 는 바로 답한다.
    @app.post("/synthesize")
    def synthesize(req: SynthesizeRequest):
        if not engine.ready:
            return _error(503, "tts_generator_unavailable")
        if req.voice not in engine.voices:
            return _error(404, f"voice_not_found:{req.voice}")
        if not req.text.strip():
            return _error(400, "text_required")
        try:
            result = engine.synthesize(req.voice, req.text)
        except GenerationFailed as e:
            return _error(422, f"generation_failed:{e.reason}")
        except Exception:
            log.exception("synthesize failed voice=%s", req.voice)
            return _error(500, "generation_failed:internal_error")
        return Response(content=result.audio, media_type="audio/mpeg", headers={
            "X-Duration-Sec": str(result.duration_sec),
            "X-Elapsed-Sec": str(result.elapsed_sec),
            "X-Attempt": str(result.attempt),
        })

    return app


def build_app():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    return create_app(Engine(load_voices(VOICES_DIR)))
