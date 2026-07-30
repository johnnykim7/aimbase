#!/bin/bash
# 전사 사이드카 기동 스크립트.
#
# 기동 로직은 이 파일에만 둔다. launchd(plist) / systemd(unit) / Docker 는
# 이 스크립트를 호출만 하므로, 유지보수 대상은 여기 하나다.
#
# 주의: lightning-whisper-mlx 는 모델을 CWD 기준 ./mlx_models 에 받는다.
#       따라서 반드시 스크립트 디렉터리에서 실행해야 재다운로드(2.9G)를 피한다.
set -e

cd "$(dirname "$0")"

# 인증 키 등 비밀값은 .env 에서 읽는다 (git 미추적).
if [ -f .env ]; then
  set -a; . ./.env; set +a
fi

PORT="${TRANSCRIBE_PORT:-8291}"
# 공유기 포트포워딩(8291)으로 서버가 직접 호출하므로 전체 인터페이스에 바인딩한다.
HOST="${TRANSCRIBE_HOST:-0.0.0.0}"

export WHISPER_BACKEND="${WHISPER_BACKEND:-auto}"
export WHISPER_MODEL="${WHISPER_MODEL:-large-v3}"
export WHISPER_BATCH_SIZE="${WHISPER_BATCH_SIZE:-12}"

exec .venv/bin/uvicorn app:app \
  --app-dir src \
  --host "$HOST" \
  --port "$PORT" \
  --timeout-keep-alive 600
