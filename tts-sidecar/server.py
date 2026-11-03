"""CR-166 같은 목소리 음성 생성기(tts-sidecar). api 만 부른다(CR-166 3.1, 3.2).

음색 하나는 voices/<이름>/ 폴더 하나이고, 같은 음색 같은 글이면 씨앗 순서가 같아 다시 만들어도 같은 소리가 난다(BIZ-134).
한 번에 한 문장만 만든다(BIZ-133). api 가 한 작업씩 보내지만 생성기 안에서도 잠가 둔다.

CR-167: 창구 프로세스와 모델 프로세스로 나눈다. 모델은 만들 문장이 있을 때만 싣고, 일이 없으면 모델 프로세스를 끝내
메모리를 서버에 돌려준다(BIZ-139). 모델 프로세스가 죽으면 정한 시간 동안 다시 싣지 않는다(BIZ-140).
"""

import json
import logging
import math
import multiprocessing
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

DEFAULT_IDLE_UNLOAD_SECONDS = 300
DEFAULT_LOAD_RETRY_SECONDS = 600
IDLE_CHECK_SECONDS = 5
LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"

# 생성기 모델 상태(T1-5). 저장하지 않고 /health 로만 보인다.
DOWNLOADING = "downloading"
FAILED = "failed"
UNLOADED = "unloaded"
LOADING = "loading"
READY = "ready"
BACKOFF = "backoff"
UNAVAILABLE_STATES = (DOWNLOADING, FAILED, BACKOFF)

MIN_DURATION_SEC = 0.25
SILENT_PEAK = 1e-5
REQUIRED_FIELDS = ("ref_audio", "ref_text", "language", "seed", "attempts", "tokens_per_char",
                   "min_new_tokens", "max_new_tokens", "generation", "postprocess")
REQUIRED_POSTPROCESS = ("filter", "sample_rate", "bitrate")


class ModelUnavailable(Exception):
    pass


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


def load_model(model_path):
    import torch
    from qwen_tts import Qwen3TTSModel

    torch.set_num_threads(THREADS)
    return Qwen3TTSModel.from_pretrained(model_path, device_map="cpu", dtype=torch.float32)


def model_snapshot():
    """받아 둔 모델 폴더를 돌려준다. 없으면 받는다. 망이 끊겨도 받아 둔 것이 있으면 그 폴더를 준다."""
    if Path(MODEL_ID).is_dir():
        return MODEL_ID
    from huggingface_hub import snapshot_download

    return snapshot_download(MODEL_ID)


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
    def __init__(self, voices, model_loader, encoder=encode_mp3):
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


def model_process(conn, voices_dir, model_path, model_loader=load_model, encoder=encode_mp3):
    """모델 프로세스 본체. 창구가 연결을 끊으면 끝난다."""
    logging.basicConfig(level=logging.INFO, format=LOG_FORMAT)
    # 저장소 이름으로 부르면 실을 때마다 huggingface.co 에 묻는다. 필요할 때 싣는 구조에서 싣기가 외부 망에 걸리면 안 된다.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    engine = Engine(load_voices(Path(voices_dir)), model_loader=lambda: model_loader(model_path), encoder=encoder)
    engine.load()
    if engine.load_error:
        conn.send(("load_failed", engine.load_error))
        return
    conn.send(("ready", None))
    while True:
        try:
            voice_name, text = conn.recv()
        except EOFError:
            return
        try:
            conn.send(("ok", engine.synthesize(voice_name, text)))
        except GenerationFailed as e:
            conn.send(("rejected", e.reason))
        except Exception:
            log.exception("synthesize failed voice=%s", voice_name)
            conn.send(("error", None))


class ModelHost:
    """창구 쪽에서 모델 프로세스를 띄우고 끝낸다. 같은 프로세스에서 지우면 PyTorch 를 불러온 몫이 남고,
    메모리 상한에 걸렸을 때 창구까지 함께 죽어 까닭을 답하지 못한다(CR-167 3.1)."""

    def __init__(self, voices, voices_dir, idle_seconds, retry_seconds, model_source=model_snapshot,
                 target=model_process):
        self.voices = voices
        self.last_error = None
        self._voices_dir = str(voices_dir)
        self._idle_seconds = idle_seconds
        self._retry_seconds = retry_seconds
        self._model_source = model_source
        self._target = target
        # 부모의 스레드와 잠금을 물려받지 않게 fork 를 쓰지 않는다.
        self._context = multiprocessing.get_context("spawn")
        self._model_path = None
        self._prepare_failed = False
        self._process = None
        self._conn = None
        self._loaded = False
        self._last_used = 0.0
        self._backoff_until = None
        self._lock = threading.Lock()
        self._closed = threading.Event()

    @property
    def state(self):
        if self._prepare_failed:
            return FAILED
        if self._model_path is None:
            return DOWNLOADING
        if self._backoff_until is not None and time.monotonic() < self._backoff_until:
            return BACKOFF
        if self._process is None:
            return UNLOADED
        return READY if self._loaded else LOADING

    @property
    def retry_after(self):
        return max(1, math.ceil(self._backoff_until - time.monotonic()))

    def start(self):
        threading.Thread(target=self.prepare, name="tts-model-files", daemon=True).start()
        threading.Thread(target=self._watch_idle, name="tts-model-idle", daemon=True).start()

    def close(self):
        self._closed.set()
        self._end_process()

    def prepare(self):
        try:
            self._model_path = self._model_source()
            log.info("model files at %s", self._model_path)
        except Exception as e:
            self.last_error = f"{type(e).__name__}: {e}"
            self._prepare_failed = True
            log.exception("model files not ready")

    def synthesize(self, voice_name, text):
        # 싣거나 만드는 동안 온 문장은 이 잠금 앞에서 기다린다(BIZ-133, BIZ-139).
        with self._lock:
            # 앞 문장이 모델 프로세스를 죽였으면 기다리던 문장도 다시 싣지 않고 끝난다(BIZ-140).
            if self.state in UNAVAILABLE_STATES:
                raise ModelUnavailable(self.last_error)
            try:
                if self._process is None:
                    self._load()
                self._conn.send((voice_name, text))
                kind, detail = self._conn.recv()
            except (EOFError, OSError) as e:
                reason = self._exit_reason(e)
                self._give_up(reason)
                raise ModelUnavailable(reason) from None
            self._last_used = time.monotonic()
        if kind == "rejected":
            raise GenerationFailed(detail)
        if kind == "error":
            raise RuntimeError("generation failed in model process")
        return detail

    def unload_if_idle(self):
        # 문장을 만드는 동안에는 잠겨 있어 내리지 않는다.
        if not self._lock.acquire(blocking=False):
            return
        try:
            if self._loaded and time.monotonic() - self._last_used >= self._idle_seconds:
                self._end_process()
                log.info("model unloaded after %ds idle", self._idle_seconds)
        finally:
            self._lock.release()

    def _load(self):
        started = time.monotonic()
        conn, child_conn = self._context.Pipe()
        process = self._context.Process(target=self._target, name="tts-model", daemon=True,
                                        args=(child_conn, self._voices_dir, self._model_path))
        process.start()
        self._process, self._conn = process, conn
        # 창구 쪽 사본을 닫아야 모델 프로세스가 죽었을 때 recv 가 EOFError 로 깨어난다.
        child_conn.close()
        kind, detail = self._conn.recv()
        if kind == "load_failed":
            self._give_up(detail)
            raise ModelUnavailable(detail)
        self._loaded = True
        self._last_used = time.monotonic()
        log.info("model process %d ready in %.1fs", self._process.pid, time.monotonic() - started)

    def _exit_reason(self, error):
        if self._process is None:
            return f"model process not started: {error}"
        self._process.join(5)
        return f"model process exited: {self._process.exitcode}"

    def _give_up(self, reason):
        self.last_error = reason
        self._backoff_until = time.monotonic() + self._retry_seconds
        log.error("%s. no reload for %ds", reason, self._retry_seconds)
        self._end_process()

    def _end_process(self):
        process, conn = self._process, self._conn
        self._process, self._conn, self._loaded = None, None, False
        if conn is not None:
            conn.close()
        if process is not None:
            process.kill()
            process.join()

    def _watch_idle(self):
        interval = min(IDLE_CHECK_SECONDS, self._idle_seconds / 2)
        while not self._closed.wait(interval):
            self.unload_if_idle()


class SynthesizeRequest(BaseModel):
    voice: str = ""
    text: str = ""


def _error(status, code):
    return JSONResponse(status_code=status, content={"error": code})


def create_app(host, start_in_background=True):
    @asynccontextmanager
    async def lifespan(_app):
        if start_in_background:
            host.start()
        yield
        host.close()

    app = FastAPI(title="Aimbase TTS Sidecar", lifespan=lifespan)

    @app.get("/health")
    def health():
        state = host.state
        if state == DOWNLOADING:
            return JSONResponse(status_code=503, content={"status": DOWNLOADING})
        if state == FAILED:
            return JSONResponse(status_code=503, content={"status": FAILED, "error": host.last_error})
        if state == BACKOFF:
            return JSONResponse(status_code=503, content={"status": BACKOFF, "error": host.last_error,
                                                          "retry_after": host.retry_after})
        return {"status": "ok", "voices": sorted(host.voices), "model": state}

    # 동기 함수라 FastAPI 가 작업 스레드에서 돌린다. 싣거나 만드는 중에도 /health 는 바로 답한다.
    @app.post("/synthesize")
    def synthesize(req: SynthesizeRequest):
        if host.state in UNAVAILABLE_STATES:
            return _error(503, "tts_generator_unavailable")
        if req.voice not in host.voices:
            return _error(404, f"voice_not_found:{req.voice}")
        if not req.text.strip():
            return _error(400, "text_required")
        try:
            result = host.synthesize(req.voice, req.text)
        except ModelUnavailable:
            return _error(503, "tts_generator_unavailable")
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


def seconds_setting(name, default):
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except ValueError:
        value = 0
    if value < 1:
        raise ValueError(f"{name} must be a positive integer: {raw!r}")
    return value


def build_app():
    logging.basicConfig(level=logging.INFO, format=LOG_FORMAT)
    return create_app(ModelHost(load_voices(VOICES_DIR), VOICES_DIR,
                                idle_seconds=seconds_setting("TTS_IDLE_UNLOAD_SECONDS", DEFAULT_IDLE_UNLOAD_SECONDS),
                                retry_seconds=seconds_setting("TTS_LOAD_RETRY_SECONDS", DEFAULT_LOAD_RETRY_SECONDS)))
