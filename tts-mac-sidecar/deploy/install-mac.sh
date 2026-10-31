#!/bin/bash
# 맥 음성 엔진을 맥에 상주시킨다(launchd).
#
# launchd 가 기동한 프로세스는 ~/Documents 를 읽지 못한다(macOS 개인정보 보호, CR-133 실측).
# 그래서 실행본은 저장소 밖 TTS_HOME 에 두고, 소스나 목소리를 고치면 이 스크립트를 다시 돌린다.
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${TTS_HOME:-$HOME/Server/tts-mac-sidecar}"
PYTHON="${TTS_PYTHON:-/Library/Frameworks/Python.framework/Versions/3.10/bin/python3}"
LABEL=com.aimbase.tts
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

mkdir -p "$TARGET/logs"
rsync -a --delete --exclude __pycache__ "$SRC/src/" "$TARGET/src/"
rsync -a --delete "$SRC/voices/" "$TARGET/voices/"
cp "$SRC/run.sh" "$SRC/requirements.lock.txt" "$TARGET/"

# 비밀값은 처음 한 번만 옮긴다. 키는 TTS_API_KEY 한 줄이다
if [ ! -f "$TARGET/.env" ]; then
  [ -f "$SRC/.env" ] || { echo "$SRC/.env 에 TTS_API_KEY 를 먼저 둘 것" >&2; exit 1; }
  cp "$SRC/.env" "$TARGET/.env"; chmod 600 "$TARGET/.env"
fi

# 동작이 확인된 판 그대로 설치한다. requirements.txt 는 직접 쓰는 것만 적혀 있어 다시 받으면 판이 바뀐다
[ -x "$TARGET/.venv/bin/python" ] || "$PYTHON" -m venv "$TARGET/.venv"
if ! cmp -s "$TARGET/requirements.lock.txt" "$TARGET/.venv/installed.lock.txt"; then
  "$TARGET/.venv/bin/pip" install -q -r "$TARGET/requirements.lock.txt"
  cp "$TARGET/requirements.lock.txt" "$TARGET/.venv/installed.lock.txt"
fi

sed "s|__TARGET__|$TARGET|g" "$SRC/deploy/$LABEL.plist" > "$PLIST"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
# bootout 은 앞 프로세스가 끝나기를 기다리지 않는다. 남아 있는 동안 bootstrap 하면 "Input/output error" 로 실패한다
for _ in $(seq 1 60); do
  launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break
  sleep 1
done
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed: $TARGET (log: $TARGET/logs/sidecar.log)"
