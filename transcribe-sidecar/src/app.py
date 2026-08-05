"""
Aimbase Transcribe Sidecar — 회의녹음 배치 전사 (faster-whisper)

독립 프로세스로 동작하며 BE 는 HTTP 로만 호출한다.
백엔드는 두 가지이며 환경변수로 고른다.

  WHISPER_BACKEND      mlx | faster   (기본 auto → Apple Silicon 이면 mlx)
    mlx    : lightning-whisper-mlx. Apple Silicon GPU 를 쓴다. 맥 전용.
    faster : faster-whisper(CTranslate2). CPU/CUDA. 리눅스 이관 시 이쪽.

  WHISPER_MODEL        모델 크기 (기본 large-v3)
  WHISPER_BATCH_SIZE   mlx 전용 배치 크기 (기본 12)
  WHISPER_DEVICE       faster 전용: auto | cpu | cuda      (기본 auto)
  WHISPER_COMPUTE_TYPE faster 전용: int8 | float16 | ...   (기본 auto)

  TRANSCRIBE_API_KEY   X-Api-Key 헤더로 요구할 키. 공유기 포트포워딩으로
                       인터넷에 노출되므로 반드시 설정한다. 미설정이면 기동을 거부한다.
"""

import hmac
import logging
import os
import platform
import shutil
import tempfile
import time
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile, WebSocket
from fastapi.responses import FileResponse
from pydantic import BaseModel

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
log = logging.getLogger("transcribe")

MODEL_SIZE = os.getenv("WHISPER_MODEL", "large-v3")
DEVICE = os.getenv("WHISPER_DEVICE", "auto")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "auto")
BATCH_SIZE = int(os.getenv("WHISPER_BATCH_SIZE", "12"))


def _resolve_backend() -> str:
    """Apple Silicon 이면 mlx, 아니면 faster-whisper."""
    configured = os.getenv("WHISPER_BACKEND", "auto")
    if configured != "auto":
        return configured
    return "mlx" if (platform.system() == "Darwin" and platform.machine() == "arm64") else "faster"


BACKEND = _resolve_backend()

API_KEY = os.getenv("TRANSCRIBE_API_KEY", "").strip()
if not API_KEY:
    raise RuntimeError(
        "TRANSCRIBE_API_KEY 가 비어 있다. 이 사이드카는 포트포워딩으로 인터넷에 노출되므로 "
        "인증 없이 기동하지 않는다. run.sh 또는 환경변수에 키를 설정할 것."
    )

app = FastAPI(title="Aimbase Transcribe Sidecar", version="0.1.0")

_model = None


def _require_api_key(provided: str | None) -> None:
    """상수 시간 비교로 키를 검증한다. 실패 시 401."""
    if not provided or not hmac.compare_digest(provided, API_KEY):
        raise HTTPException(status_code=401, detail="invalid or missing X-Api-Key")


def _resolve_compute_type(device: str) -> str:
    if COMPUTE_TYPE != "auto":
        return COMPUTE_TYPE
    # CPU 에서 float16 은 지원되지 않는다. GPU 면 float16 이 가장 빠르다.
    return "float16" if device == "cuda" else "int8"


def get_model():
    """모델은 최초 요청 때 한 번만 로드하고 프로세스 수명 동안 재사용한다."""
    global _model
    if _model is not None:
        return _model

    started = time.time()
    if BACKEND == "mlx":
        from lightning_whisper_mlx import LightningWhisperMLX

        log.info("모델 로드 시작: %s (backend=mlx, batch_size=%d)", MODEL_SIZE, BATCH_SIZE)
        _model = LightningWhisperMLX(model=MODEL_SIZE, batch_size=BATCH_SIZE, quant=None)
    else:
        from faster_whisper import WhisperModel

        device = DEVICE
        if device == "auto":
            try:
                import torch

                device = "cuda" if torch.cuda.is_available() else "cpu"
            except ImportError:
                device = "cpu"

        compute_type = _resolve_compute_type(device)
        log.info("모델 로드 시작: %s (backend=faster, device=%s, compute_type=%s)",
                 MODEL_SIZE, device, compute_type)
        _model = WhisperModel(MODEL_SIZE, device=device, compute_type=compute_type)

    log.info("모델 로드 완료 (%.1fs)", time.time() - started)
    return _model


class Segment(BaseModel):
    start: float
    end: float
    text: str


class TranscribeResponse(BaseModel):
    text: str
    language: str
    duration: float
    elapsed: float
    speed_ratio: float
    segments: list[Segment]


def _transcribe_mlx(model, path: str, language: str | None):
    """lightning-whisper-mlx: segments 는 [start, end, text] 리스트로 나온다."""
    kwargs = {"language": language} if language else {}
    result = model.transcribe(audio_path=path, **kwargs)
    segments = []
    for seg in result.get("segments", []):
        if isinstance(seg, (list, tuple)) and len(seg) >= 3:
            # MLX 는 시간을 1/100 초 단위 정수로 준다.
            segments.append(Segment(start=seg[0] / 100.0, end=seg[1] / 100.0, text=str(seg[2]).strip()))
        elif isinstance(seg, dict):
            segments.append(Segment(start=seg["start"], end=seg["end"], text=seg["text"].strip()))
        else:
            log.warning("알 수 없는 세그먼트 포맷 무시: %r", seg)
    return segments, result.get("language") or language or "unknown"


def _transcribe_faster(model, path: str, language: str | None, beam_size: int):
    """faster-whisper: segments 는 지연 평가라 리스트로 소비해야 추론이 돈다."""
    segments_iter, info = model.transcribe(
        path,
        language=language or None,
        beam_size=beam_size,
        vad_filter=True,
    )
    segments = [Segment(start=s.start, end=s.end, text=s.text.strip()) for s in segments_iter]
    return segments, info.language


@app.get("/health")
def health():
    return {
        "status": "ok",
        "backend": BACKEND,
        "model": MODEL_SIZE,
        "device": DEVICE,
        "loaded": _model is not None,
    }


@app.get("/stream-test")
def stream_test():
    """실시간 STT 확인용 테스트 페이지. 브라우저에서 마이크로 바로 검증한다."""
    return FileResponse(Path(__file__).parent / "static" / "stream.html")


@app.websocket("/stream")
async def stream(websocket: WebSocket):
    """
    CR-135: 실시간 스트리밍 STT.

    브라우저가 MediaRecorder 청크(WebM)를 바이너리로 계속 보내면
    확정/미확정 텍스트를 JSON 으로 돌려준다.

    인증: WS 는 커스텀 헤더를 못 붙이는 클라이언트가 많아 쿼리 파라미터로 받는다.
          ws://host:8291/stream?api_key=...
    """
    from streaming import handle_stream

    provided = websocket.query_params.get("api_key")
    if not provided or not hmac.compare_digest(provided, API_KEY):
        # accept 전에 닫으면 클라이언트가 이유를 모르므로 정책 위반 코드로 명시한다.
        await websocket.close(code=1008, reason="invalid or missing api_key")
        return

    await websocket.accept()
    session_id = websocket.query_params.get("session_id", "anonymous")
    log.info("[CR-135] 스트리밍 연결: session=%s", session_id)
    await handle_stream(websocket, session_id)


@app.post("/transcribe", response_model=TranscribeResponse)
async def transcribe(
    file: UploadFile = File(...),
    language: str | None = Form(None),
    beam_size: int = Form(5),
    x_api_key: str | None = Header(None, alias="X-Api-Key"),
):
    """오디오 파일 전체를 전사한다. 회의녹음 배치용이라 응답까지 오래 걸릴 수 있다."""
    _require_api_key(x_api_key)

    suffix = Path(file.filename or "audio").suffix or ".bin"
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            shutil.copyfileobj(file.file, tmp)
            tmp_path = tmp.name

        model = get_model()
        started = time.time()
        if BACKEND == "mlx":
            segments, detected_lang = _transcribe_mlx(model, tmp_path, language)
        else:
            segments, detected_lang = _transcribe_faster(model, tmp_path, language, beam_size)
        elapsed = time.time() - started
        # MLX 는 duration 을 주지 않으므로 마지막 세그먼트 끝을 쓴다.
        duration = segments[-1].end if segments else 0.0

        log.info(
            "전사 완료: %s (%.1fs 오디오, %.1fs 소요, %.1fx)",
            file.filename, duration, elapsed, duration / elapsed if elapsed else 0,
        )

        return TranscribeResponse(
            text=" ".join(s.text for s in segments).strip(),
            language=detected_lang,
            duration=duration,
            elapsed=elapsed,
            speed_ratio=duration / elapsed if elapsed else 0.0,
            segments=segments,
        )
    except HTTPException:
        raise  # 401 등 의도한 상태코드는 그대로 내보낸다.
    except Exception as e:
        log.exception("전사 실패: %s", file.filename)
        raise HTTPException(status_code=500, detail=f"transcribe failed: {e}") from e
    finally:
        if tmp_path:
            Path(tmp_path).unlink(missing_ok=True)
