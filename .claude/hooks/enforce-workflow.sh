#!/bin/bash
# 워크플로우 강제화 hook
# - 소스 코드 수정 시 설계 갱신 여부 리마인드 (Edit/Write)
# - main/master 브랜치 직접 커밋/푸시 차단 (Bash)

INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')

# --- Edit/Write: 소스 코드 수정 시 설계 캐스케이드 리마인드 ---
if [[ "$TOOL_NAME" == "Edit" || "$TOOL_NAME" == "Write" ]]; then
  FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

  # docs/, .claude/, .github/ 등 비소스 경로는 제외
  if echo "$FILE_PATH" | grep -qE '/(docs|\.claude|\.github)/'; then
    exit 0
  fi

  # 소스 코드 파일만 대상
  if echo "$FILE_PATH" | grep -qE '\.(java|kt|ts|tsx|js|jsx|py|go|rs|sql|yaml|yml|xml|gradle|properties)$'; then
    echo "REMINDER: 소스 코드 수정 전 확인 — 이 변경이 중규모 이상(새 API·테이블·화면 구조 변경)이라면, 코드 수정 전에 설계 캐스케이드(T1→T2→T3→CR_변경_이력)를 먼저 수행해야 합니다." >&2
  fi

  exit 0
fi

# --- Bash: git 명령어 검사 ---
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

if ! echo "$COMMAND" | grep -qE '^git '; then
  exit 0
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

# main/master에서 직접 커밋 차단
if echo "$COMMAND" | grep -qE '^git commit' && [[ "$BRANCH" == "main" || "$BRANCH" == "master" ]]; then
  echo "BLOCKED: main/master 브랜치에 직접 커밋 금지. develop 또는 feat/ 브랜치에서 작업하세요." >&2
  exit 2
fi

# main/master로 직접 push 차단
if echo "$COMMAND" | grep -qE 'git push.*(origin main|origin master)' && [[ "$BRANCH" == "main" || "$BRANCH" == "master" ]]; then
  echo "BLOCKED: main/master로 직접 push 금지. PR을 통해 머지하세요." >&2
  exit 2
fi

# force push 차단
if echo "$COMMAND" | grep -qE 'git push .* (-f|--force)( |$)'; then
  echo "BLOCKED: force push 금지. 사용자에게 확인을 요청하세요." >&2
  exit 2
fi

exit 0
