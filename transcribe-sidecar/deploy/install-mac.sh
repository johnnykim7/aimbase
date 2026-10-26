#!/bin/bash
# 전사 사이드카를 맥에 상주시킨다(launchd).
#
# launchd 가 띄운 프로세스는 ~/Documents 를 읽지 못한다(macOS 개인정보 보호,
# 2026-10-26 실측 "Operation not permitted", 종료코드 78). 그래서 실행본은 저장소 밖
# TRANSCRIBE_HOME 에 두고, 소스를 고치면 이 스크립트를 다시 돌린다.
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${TRANSCRIBE_HOME:-$HOME/Server/transcribe-sidecar}"
PYTHON="${TRANSCRIBE_PYTHON:-/Library/Frameworks/Python.framework/Versions/3.10/bin/python3}"
LABEL=com.aimbase.transcribe
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

mkdir -p "$TARGET/logs"
rsync -a --delete --exclude __pycache__ "$SRC/src/" "$TARGET/src/"
cp "$SRC/run.sh" "$SRC/requirements.lock.txt" "$TARGET/"

# 비밀값과 모델(약 2.9G)은 처음 한 번만 옮긴다
[ -f "$TARGET/.env" ] || { cp "$SRC/.env" "$TARGET/.env"; chmod 600 "$TARGET/.env"; }
[ -d "$TARGET/mlx_models" ] || cp -R "$SRC/mlx_models" "$TARGET/mlx_models"

# 동작이 확인된 판 그대로 설치한다. requirements.txt 는 하한만 적혀 있어 새로 받으면 판이 바뀐다
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
