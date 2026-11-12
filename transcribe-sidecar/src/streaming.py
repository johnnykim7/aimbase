"""
CR-135: 실시간 스트리밍 STT (WebSocket).

물류센터 작업자가 PDA/휴대폰 헤드셋으로 말하면 즉시 텍스트가 나오는 경로.
배치 전사(/transcribe)와 달리 말하는 도중에 부분 결과를 계속 돌려준다.

파이프라인:
    브라우저 MediaRecorder(WebM/Opus, timeslice)
      → WS 바이너리
      → FFmpeg 실시간 디코드(s16le / 16kHz / mono)
      → min_chunk_size 초 단위 PCM 버퍼링
      → Whisper(MLX) + LocalAgreement 확정
      → {type:"transcript", text:확정, buffer:미확정}

확정/미확정을 나누는 이유: 실시간 전사는 뒷말이 나오면 앞말 해석이 바뀐다.
LocalAgreement 는 연속 추론에서 일치한 부분만 확정하고 나머지는 buffer 로 보낸다.
UI 는 확정을 검정, buffer 를 회색으로 표시하면 된다.

★ 원본(whisper_streaming_web)은 online 객체가 전역이라 동시 접속 시 모든 사용자의
  오디오가 한 버퍼에 섞인다. 여기서는 연결마다 독립 인스턴스를 만든다.

엔진 코드는 src/vendor (ÚFAL, MIT) 를 재사용한다.
"""

import asyncio
import logging
import os
import sys
import time
from pathlib import Path

import numpy as np
from fastapi import WebSocket, WebSocketDisconnect

sys.path.insert(0, str(Path(__file__).parent / "vendor"))

log = logging.getLogger("transcribe.stream")

SAMPLE_RATE = 16000
CHANNELS = 1
BYTES_PER_SAMPLE = 2  # s16le

# 짧을수록 반응이 빠르지만 문맥이 짧아 정확도가 떨어지고 추론 횟수가 늘어난다.
# 실측(같은 문장, "내일 오전 10시에 물류팀과 재고 실사 일정 조율하기"):
#   1초 → "…물류팀과 최고실사일정 조율아기! 해"  (빠르지만 뒤가 뭉개짐)
#   2초 → "…물류팀과"                            (끝말 유실)
#   3초 → "…물류팀과 재고 실사일정"              (가장 정확)
# 지연과 정확도의 트레이드오프라 용도에 맞춰 조절한다.
MIN_CHUNK_SEC = float(os.getenv("STREAM_MIN_CHUNK_SEC", "2.0"))
# 실시간은 지연이 생명이라 배치(large-v3)보다 가벼운 모델을 기본으로 쓴다.
STREAM_MODEL = os.getenv("STREAM_MODEL", "large-v3-turbo")
STREAM_LANG = os.getenv("STREAM_LANG", "ko")
# Silero VAD 로 무음을 걸러 불필요한 추론을 줄일 수 있으나 기본은 끈다.
# vendor 의 VACOnlineASRProcessor 는 MediaRecorder 처럼 잘게 들어오는 입력에서
# "no online update, only VAD voice" 상태로 멈춰 확정 텍스트가 나오지 않는다(실측).
# 무음 구간 비용이 문제가 되면 그때 다시 켜고 튜닝할 것.
USE_VAD = os.getenv("STREAM_VAD", "false").lower() == "true"
# 무음 판정 임계값(RMS). VAD 를 끈 상태에서 환각 폭주를 막는 최소 방어선이다.
# 물류센터처럼 배경음이 있으면 올려야 할 수 있다.
SILENCE_RMS = float(os.getenv("STREAM_SILENCE_RMS", "0.005"))

BYTES_PER_CHUNK = int(SAMPLE_RATE * MIN_CHUNK_SEC) * BYTES_PER_SAMPLE

_asr = None
_asr_lock = asyncio.Lock()


async def get_asr():
    """ASR 모델은 무겁고 스레드 안전하므로 프로세스에서 하나만 공유한다."""
    global _asr
    async with _asr_lock:
        if _asr is None:
            # 올리는 동안 이벤트 루프를 막으면 배치 전사와 /health 까지 멈춘다.
            _asr = await asyncio.get_running_loop().run_in_executor(None, _load_asr)
    return _asr


def _load_asr():
    from whisper_online import MLXWhisper

    log.info("스트리밍 ASR 로드 시작: %s (lang=%s)", STREAM_MODEL, STREAM_LANG)
    started = time.time()
    asr = MLXWhisper(lan=STREAM_LANG, modelsize=STREAM_MODEL)
    # MLXWhisper 는 모델 경로만 기억하고 가중치는 첫 추론에서 올린다(실측 첫 추론 8.5초).
    # 여기서 한 번 돌려 둬야 첫 사용자의 첫 말이 그 시간을 떠안지 않는다.
    asr.transcribe(np.zeros(SAMPLE_RATE, dtype=np.float32))
    log.info("스트리밍 ASR 준비 완료 (%.1fs)", time.time() - started)
    _keep_resident()
    return asr


def _keep_resident():
    """
    오래 쉬면 맥이 모델 메모리를 스왑으로 내보내 첫 말의 추론이 다시 느려진다.
    올린 가중치만큼 묶어 두면 내보내지 않는다. 묶지 못해도 받아쓰기는 그대로 돈다.
    """
    import mlx.core as mx

    try:
        weights = mx.get_active_memory()
        mx.set_wired_limit(weights)
        log.info("스트리밍 ASR 메모리 %.1fGB 를 묶어 둠", weights / 1e9)
    except Exception:
        log.exception("스트리밍 ASR 메모리를 묶지 못했다. 오래 쉰 뒤 첫 말이 느릴 수 있다")


async def preload():
    """기동 때 부른다. 실패해도 서버는 떠 있고 첫 연결에서 다시 올린다."""
    try:
        await get_asr()
    except Exception:
        log.exception("스트리밍 ASR 을 미리 올리지 못했다. 첫 연결에서 다시 시도한다")


def asr_ready() -> bool:
    return _asr is not None


_vad_ready = False


def _ensure_vad_trusted():
    """
    vendor 의 VACOnlineASRProcessor 는 torch.hub.load(...) 를 trust_repo 없이 부른다.
    그러면 torch 가 stdin 으로 신뢰 여부를 되묻는데, 서버에는 stdin 이 없어
    EOFError 로 연결이 끊긴다. 미리 받아 캐시에 넣어두면 그 질문 자체가 사라진다.
    """
    global _vad_ready
    if _vad_ready:
        return
    import torch

    torch.hub.load(repo_or_dir="snakers4/silero-vad", model="silero_vad", trust_repo=True)
    _vad_ready = True


def _new_processor(asr):
    """
    연결마다 새로 만든다. OnlineASRProcessor 는 오디오 버퍼와 확정 상태를 들고 있어
    공유하면 사용자들의 발화가 뒤섞인다.
    """
    from whisper_online import OnlineASRProcessor, VACOnlineASRProcessor

    if USE_VAD:
        _ensure_vad_trusted()
        return VACOnlineASRProcessor(MIN_CHUNK_SEC, asr)
    return OnlineASRProcessor(asr)


async def _start_ffmpeg():
    """WebM/Opus 를 받아 raw PCM 으로 실시간 디코드한다."""
    return await asyncio.create_subprocess_exec(
        "ffmpeg",
        "-loglevel", "error",
        "-i", "pipe:0",
        "-f", "s16le",
        "-acodec", "pcm_s16le",
        "-ac", str(CHANNELS),
        "-ar", str(SAMPLE_RATE),
        "pipe:1",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )


async def handle_stream(websocket: WebSocket, session_id: str):
    """WS 연결 하나를 끝까지 처리한다. 예외는 전부 여기서 흡수한다."""
    asr = await get_asr()
    online = _new_processor(asr)
    ffmpeg = await _start_ffmpeg()

    pcm_buffer = bytearray()
    confirmed = ""
    loop = asyncio.get_running_loop()

    async def decode_loop():
        """
        FFmpeg stdout 에서 PCM 을 읽어 청크 단위로 추론하고 결과를 밀어낸다.
        말 끝 신호 뒤에도 FFmpeg 가 stdout 을 닫을 때까지 읽는다. 그 전에 멈추면
        stdin 을 닫으며 FFmpeg 가 내보낸 마지막 말소리가 버려져 끝말이 잘린다(실측).
        """
        nonlocal pcm_buffer, confirmed
        while True:
            try:
                chunk = await ffmpeg.stdout.read(4096)
            except Exception:
                break
            if not chunk:
                break

            pcm_buffer.extend(chunk)
            if len(pcm_buffer) < BYTES_PER_CHUNK:
                continue

            pcm = np.frombuffer(bytes(pcm_buffer), dtype=np.int16).astype(np.float32) / 32768.0
            pcm_buffer = bytearray()

            # 무음 청크는 추론에 넣지 않는다. 넣으면 Whisper 가 환각을 일으켜
            # 같은 단어를 끝없이 반복한다("조율 조율 조율…") — 실측으로 확인.
            if float(np.sqrt(np.mean(pcm ** 2))) < SILENCE_RMS:
                continue

            try:
                # 추론은 CPU/GPU 를 오래 잡으므로 이벤트 루프를 막지 않게 스레드로 뺀다.
                def infer():
                    online.insert_audio_chunk(pcm)
                    return online.process_iter()

                result = await loop.run_in_executor(None, infer)
                text = (result[2] or "").strip()
            except Exception as e:
                log.warning("[%s] 추론 실패: %s", session_id, e)
                continue

            if text and not _looks_hallucinated(text):
                confirmed += (" " if confirmed else "") + text

            pending = _pending_text(online)
            if _looks_hallucinated(pending):
                pending = ""
            try:
                await websocket.send_json({
                    "type": "transcript",
                    "text": confirmed,      # 확정 — 바뀌지 않는다
                    "delta": text,          # 이번 청크에서 새로 확정된 부분
                    "buffer": pending,      # 미확정 — 다음 추론에서 바뀔 수 있다
                })
            except Exception:
                break

    decoder = asyncio.create_task(decode_loop())

    try:
        while True:
            data = await websocket.receive_bytes()
            # 클라이언트가 빈 프레임을 보내면 "말 끝났다"는 신호로 본다.
            # 소켓을 계속 열어둔 채 최종 결과만 받고 싶을 때 쓴다.
            if not data:
                break
            ffmpeg.stdin.write(data)
            await ffmpeg.stdin.drain()
    except WebSocketDisconnect:
        log.info("[%s] 연결 종료", session_id)
    except Exception as e:
        log.warning("[%s] 수신 중단: %s", session_id, e)
    finally:
        # stdin 을 닫아야 FFmpeg 가 남은 프레임을 flush 하고 stdout 을 닫는다.
        try:
            ffmpeg.stdin.close()
        except Exception:
            pass
        try:
            await asyncio.wait_for(decoder, timeout=10)
        except Exception:
            decoder.cancel()
        try:
            ffmpeg.kill()
        except Exception:
            pass
        # 남은 버퍼를 마지막으로 확정해서 끝말이 잘리지 않게 한다.
        # 단 finish() 는 잔여 오디오가 짧거나 무음이면 환각을 낸다("조조조…", "자막제작").
        # 반복 패턴이면 버린다 — 끝말을 조금 잃는 편이 쓰레기를 남기는 것보다 낫다.
        def finish():
            # 청크 하나에 못 미쳐 추론에 들어가지 못한 마지막 말소리도 넣는다.
            if pcm_buffer:
                rest = np.frombuffer(bytes(pcm_buffer), dtype=np.int16).astype(np.float32) / 32768.0
                if float(np.sqrt(np.mean(rest ** 2))) >= SILENCE_RMS:
                    online.insert_audio_chunk(rest)
            return online.finish()

        try:
            tail = ((await loop.run_in_executor(None, finish))[2] or "").strip()
            if tail and not _looks_hallucinated(tail):
                confirmed += (" " if confirmed else "") + tail
            await websocket.send_json({"type": "final", "text": confirmed})
        except Exception:
            pass


# Whisper 가 무음·잔여 오디오에서 뱉는 상투구. 학습 데이터(자막)에서 온다.
_HALLUCINATION_PHRASES = ("자막제작", "시청해주셔서 감사합니다", "구독과 좋아요")


def _looks_hallucinated(text: str) -> bool:
    """
    같은 글자/단어가 비정상적으로 반복되면 환각으로 본다.
    실측 사례: "일정조조조조조…", "조율 조율 조율…", "자막제작 자막제작…".
    """
    t = (text or "").strip()
    if not t:
        return False

    if any(p in t for p in _HALLUCINATION_PHRASES):
        return True

    # 같은 단어가 5회 이상 연속 반복
    words = t.split()
    if len(words) >= 5:
        run = 1
        for i in range(1, len(words)):
            run = run + 1 if words[i] == words[i - 1] else 1
            if run >= 5:
                return True

    # 같은 글자가 10회 이상 연속 반복(띄어쓰기 없는 한글 폭주)
    compact = t.replace(" ", "")
    if len(compact) >= 10:
        run = 1
        for i in range(1, len(compact)):
            run = run + 1 if compact[i] == compact[i - 1] else 1
            if run >= 10:
                return True

    return False


def _pending_text(online) -> str:
    """아직 확정되지 않은(다음 추론에서 바뀔 수 있는) 텍스트."""
    try:
        inner = getattr(online, "online", online)
        return (inner.to_flush(inner.transcript_buffer.buffer)[2] or "").strip()
    except Exception:
        return ""
