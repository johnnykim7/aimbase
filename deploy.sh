#!/bin/bash
# deploy.sh - FE + BE + RAG 통합 배포 스크립트 (CI/CD 도입 전 임시)
# 배포 디렉토리: /home/therecommerce/aimbase/
#   ├── platform-core.jar        (BE jar)
#   ├── docker-compose.yml       (prod compose)
#   ├── .env                     (서버에서 관리, 건드리지 않음)
#   ├── frontend/                (FE 정적 파일, 외부 nginx가 서빙)
#   └── python-sidecar/          (RAG 소스, 서버에서 이미지 빌드)
#
# 사용법: ./deploy.sh         (FE + BE + RAG 모두)
#         ./deploy.sh fe      (FE만)
#         ./deploy.sh be      (BE만 — jar 교체 + api 재기동)
#         ./deploy.sh rag     (RAG 소스 동기화 + 이미지 재빌드 + 재기동)
set -e

TARGET="${1:-all}"
SERVER="therecommerce@59.8.160.12"
SSH_KEY="$HOME/.ssh/id_ed25519"
REMOTE="/home/therecommerce/aimbase"

# ── 헬퍼 함수 ───────────────────────────────────────────
ssh_run() { ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o IdentitiesOnly=yes "$SERVER" "$@"; }
scp_send() { scp -i "$SSH_KEY" -o StrictHostKeyChecking=no -o IdentitiesOnly=yes "$@"; }

deploy_fe() {
  echo "━━━ FE 빌드 ━━━"
  cd frontend
  npm run build
  cd ..

  echo "━━━ FE 배포 ━━━"
  ssh_run "mkdir -p $REMOTE/frontend"
  ssh_run "rm -rf $REMOTE/frontend/*"
  scp_send -r frontend/dist/. "$SERVER:$REMOTE/frontend/"
  echo "✅ FE 완료 → $REMOTE/frontend/"
}

deploy_be() {
  echo "━━━ BE 빌드 ━━━"
  cd backend
  ./gradlew platform-core:bootJar
  cd ..

  echo "━━━ BE 배포 & api 재빌드/재시작 ━━━"
  ssh_run "mkdir -p $REMOTE/backend/platform-core/build/libs $REMOTE/workspace $REMOTE/claude-accounts"
  FAT_JAR=$(find backend/platform-core/build/libs -name '*.jar' ! -name '*plain*')
  scp_send "$FAT_JAR" "$SERVER:$REMOTE/backend/platform-core/build/libs/platform-core.jar"
  scp_send backend/platform-core/Dockerfile "$SERVER:$REMOTE/backend/platform-core/Dockerfile"
  scp_send docker-compose.prod.yml "$SERVER:$REMOTE/docker-compose.yml"
  ssh_run "cd $REMOTE && docker compose build api && docker compose up -d --force-recreate api"
  echo "✅ BE 완료 → $REMOTE/backend/platform-core/build/libs/platform-core.jar"
}

deploy_rag() {
  echo "━━━ RAG 소스 동기화 ━━━"
  ssh_run "mkdir -p $REMOTE/python-sidecar"
  # 소스 파일만 전송 (data/log/tests 제외)
  scp_send python-sidecar/Dockerfile "$SERVER:$REMOTE/python-sidecar/"
  scp_send python-sidecar/pyproject.toml "$SERVER:$REMOTE/python-sidecar/"
  scp_send -r python-sidecar/src "$SERVER:$REMOTE/python-sidecar/"
  scp_send docker-compose.prod.yml "$SERVER:$REMOTE/docker-compose.yml"

  echo "━━━ RAG 이미지 빌드 & 재시작 ━━━"
  ssh_run "cd $REMOTE && docker compose up -d --build rag-pipeline"
  echo "✅ RAG 완료 → $REMOTE/python-sidecar/"
}

case "$TARGET" in
  fe)  deploy_fe ;;
  be)  deploy_be ;;
  rag) deploy_rag ;;
  all) deploy_fe && deploy_rag && deploy_be ;;
  *)   echo "사용법: ./deploy.sh [fe|be|rag|all]"; exit 1 ;;
esac

echo ""
echo "✅ 배포 완료 → http://59.8.160.12:3200"
