#!/bin/bash
# 맥 음성 엔진 기동 스크립트.
#
# 기동 로직은 이 파일에만 둔다. launchd(plist)는 이 스크립트를 부르기만 한다.
set -e

cd "$(dirname "$0")"

# 인증 키 등 비밀값은 .env 에서 읽는다 (git 미추적).
if [ -f .env ]; then
  set -a; . ./.env; set +a
fi

PORT="${TTS_PORT:-8391}"
# 공유기 포트 전달(8391)로 서버가 직접 부르므로 전체 인터페이스에 묶는다.
HOST="${TTS_HOST:-0.0.0.0}"

# launchd 로 기동하면 PATH 가 /usr/bin:/bin 뿐이라 MP3 인코딩에 쓰는 ffmpeg(homebrew)를 못 찾는다.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

# 모델(약 4GB)을 받는 자리. 사용자 캐시는 launchd 에서도 읽힌다.
export HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"

exec .venv/bin/uvicorn app:app \
  --app-dir src \
  --host "$HOST" \
  --port "$PORT" \
  --timeout-keep-alive 600
