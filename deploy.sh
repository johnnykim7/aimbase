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
#         ./deploy.sh agent   (러너 1개 — aimbase-agent 만)
#         ./deploy.sh agents  (러너 전체, 아래 AGENT_SERVICES)
#         ./deploy.sh mcp     (BE + 러너 전체 — MCP 프로토콜 변경 시 필수)
#         ./deploy.sh sandbox (스킬 실행 컨테이너 소스 동기화 + 이미지 재빌드 + 재기동)
#
# 우진비드 AWS 서버(/opt/wjbid/aimbase). 나중에 생길 AXOPM AWS 와 구분해 wjbid 로 부른다. compose 는 docker-compose.wjbid.yml, 화면은 없다.
#         ./deploy.sh wjbid-sandbox (스킬 실행 컨테이너)
#         ./deploy.sh wjbid-be      (api)
#         ./deploy.sh wjbid-agent   (러너)
#         ./deploy.sh wjbid         (위 셋을 이 순서로)
#
# 운영 3PL 서버 chaws(/home/ubuntu/aimbase, CR-162). compose 는 docker-compose.chaws.yml, 화면은 없다. 운영 3PL 컨테이너와 같은 서버라 그쪽 폴더와 compose 를 쓰지 않는다.
# 운영에 올릴 커밋을 CHAWS_COMMIT 으로 준다(예: CHAWS_COMMIT=42bbfba ./deploy.sh chaws-be). 그 커밋을 받은 별도 worktree 에서 빌드한다.
#         ./deploy.sh chaws-init  (처음 한 번: 폴더, 비밀값 파일, 망 aimbase-apps. 있는 파일은 덮지 않는다)
#         ./deploy.sh chaws-be    (DB, Redis, api)
#         ./deploy.sh chaws-agent (실행기)
#         ./deploy.sh chaws       (be, agent 를 이 순서로)
#
# ⚠️ CR-124 이후: BE 와 러너는 MCP transport(Streamable HTTP)가 맞아야 도구 호출이 된다.
#    한쪽만 올리면 그 사이 BE↔agent 도구 호출이 실패하므로, MCP 관련 배포는
#    반드시 `./deploy.sh mcp` 로 BE + 러너 전체를 연속 배포할 것.
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

# CR-147: 스킬 스크립트 실행 컨테이너. 열쇠가 비면 컨테이너가 기동을 거부하므로 서버 .env 를 먼저 본다.
# api 가 실행 컨테이너 망에 붙는 것은 api 를 다시 만들 때다(./deploy.sh be).
deploy_sandbox() {
  echo "[sandbox] 소스 동기화"
  if ! ssh_run "grep -Eq '^SKILL_SANDBOX_API_KEY=.+' $REMOTE/.env"; then
    echo "서버 $REMOTE/.env 에 SKILL_SANDBOX_API_KEY 가 없습니다. 운용 가이드대로 넣은 뒤 다시 실행하세요."
    exit 1
  fi
  ssh_run "mkdir -p $REMOTE/skill-sandbox"
  scp_send skill-sandbox/Dockerfile skill-sandbox/server.py "$SERVER:$REMOTE/skill-sandbox/"
  scp_send docker-compose.prod.yml "$SERVER:$REMOTE/docker-compose.yml"

  echo "[sandbox] 이미지 빌드와 재시작"
  ssh_run "cd $REMOTE && docker compose up -d --build skill-sandbox"
  echo "[sandbox] 완료: $REMOTE/skill-sandbox/"
}

deploy_agent() {
  echo "━━━ Agent(Runner) 빌드 ━━━"
  cd backend
  ./gradlew aimbase-agent:bootJar
  cd ..

  echo "━━━ Agent 배포 & 재빌드/재시작 ━━━"
  ssh_run "mkdir -p $REMOTE/backend/aimbase-agent/build/libs"
  AGENT_JAR=$(find backend/aimbase-agent/build/libs -name '*.jar' ! -name '*plain*')
  scp_send "$AGENT_JAR" "$SERVER:$REMOTE/backend/aimbase-agent/build/libs/$(basename "$AGENT_JAR")"
  scp_send backend/aimbase-agent/Dockerfile "$SERVER:$REMOTE/backend/aimbase-agent/Dockerfile"
  scp_send docker-compose.prod.yml "$SERVER:$REMOTE/docker-compose.yml"
  ssh_run "cd $REMOTE && docker compose build aimbase-agent && docker compose up -d --force-recreate aimbase-agent"
  echo "✅ Agent 완료 → $REMOTE/backend/aimbase-agent/build/libs/"
}

# 운영 러너 전체 — docker-compose.prod.yml 의 aimbase-agent* 서비스 목록과 일치해야 한다.
AGENT_SERVICES="aimbase-agent aimbase-agent-axopm aimbase-agent-workmap aimbase-agent-wes aimbase-agent-wes-cs aimbase-agent-lexflow aimbase-agent-bp-office"

deploy_agents() {
  echo "━━━ Agent(Runner) 빌드 ━━━"
  cd backend
  ./gradlew aimbase-agent:bootJar
  cd ..

  echo "━━━ Agent jar/Dockerfile 전송 ━━━"
  ssh_run "mkdir -p $REMOTE/backend/aimbase-agent/build/libs"
  AGENT_JAR=$(find backend/aimbase-agent/build/libs -name '*.jar' ! -name '*plain*')
  scp_send "$AGENT_JAR" "$SERVER:$REMOTE/backend/aimbase-agent/build/libs/$(basename "$AGENT_JAR")"
  scp_send backend/aimbase-agent/Dockerfile "$SERVER:$REMOTE/backend/aimbase-agent/Dockerfile"
  scp_send docker-compose.prod.yml "$SERVER:$REMOTE/docker-compose.yml"

  # 러너마다 이미지가 별도로 명명된다 — compose 에 image: 미지정이라 서비스명 기반으로
  # aimbase-aimbase-agent / -wes / -workmap / -axopm 4개가 각각 만들어진다.
  # 따라서 build 를 서비스별로 전부 돌려야 한다. 1개만 빌드하면 나머지는 옛 이미지로
  # 재기동되어 새 jar 가 반영되지 않는다(CR-124 transport 불일치가 이 경로로 발생).
  echo "━━━ 러너 전체 재빌드/재기동: $AGENT_SERVICES ━━━"
  ssh_run "cd $REMOTE && docker compose build $AGENT_SERVICES && docker compose up -d --force-recreate $AGENT_SERVICES"
  echo "✅ Agent 전체 완료 → $AGENT_SERVICES"
}

# CR-124: MCP transport 변경은 BE 와 러너가 함께 올라가야 한다.
# 순서 = BE 먼저(서버가 새 엔드포인트를 열어둔 뒤 러너가 붙도록).
deploy_mcp() {
  echo "════ MCP 동시 배포: BE + 러너 전체 ════"
  deploy_be
  deploy_agents
  echo "════ 완료 — /mcp Streamable 엔드포인트로 BE·러너 정렬됨 ════"
}

# 우진비드 AWS 서버는 소스와 빌드 도구 없이 이미지와 환경 파일만 있다. 여기서 만든 jar 와 Dockerfile 을 build/ 아래로 보내
# 서버에서 이미지를 만든다. 바꾸기 전 이미지는 pre-<시각> 태그로 남겨 되돌릴 수 있게 한다.
WJBID_SERVER="ubuntu@54.116.150.247"
WJBID_SSH_KEY="$HOME/.ssh/wjbid-prod.pem"
WJBID_REMOTE="/opt/wjbid/aimbase"
WJBID_HEALTH_URL="http://127.0.0.1:8280/actuator/health"
WJBID_STAMP="$(date +%Y%m%d-%H%M%S)"

wjbid_run()  { ssh -i "$WJBID_SSH_KEY" -o IdentitiesOnly=yes -o ConnectTimeout=10 "$WJBID_SERVER" "$@"; }
wjbid_send() { scp -q -i "$WJBID_SSH_KEY" -o IdentitiesOnly=yes "$@"; }

# api 와 실행 컨테이너가 같이 읽는 열쇠는 sandbox.env 한 줄이다. api.env 를 실행 컨테이너에 붙이지 않기 위해 따로 둔다.
wjbid_require_sandbox_key() {
  if ! wjbid_run "grep -Eq '^SKILL_SANDBOX_API_KEY=.+' $WJBID_REMOTE/sandbox.env 2>/dev/null"; then
    echo "우진비드 AWS $WJBID_REMOTE/sandbox.env 에 SKILL_SANDBOX_API_KEY 가 없습니다. 운용 가이드대로 넣은 뒤 다시 실행하세요."
    exit 1
  fi
}

wjbid_sync_compose() {
  wjbid_send docker-compose.wjbid.yml "$WJBID_SERVER:$WJBID_REMOTE/docker-compose.yml.new"
  wjbid_run "cd $WJBID_REMOTE && diff -u docker-compose.yml docker-compose.yml.new || true"
  # 서버의 backup/ 은 root 소유(DB 덤프)라 쓸 수 없어 따로 둔다.
  wjbid_run "cd $WJBID_REMOTE && mkdir -p deploy-backup && cp -p docker-compose.yml deploy-backup/docker-compose.yml.$WJBID_STAMP && mv docker-compose.yml.new docker-compose.yml"
}

wjbid_keep_image() {
  wjbid_run "docker image inspect $1:latest >/dev/null 2>&1 && docker tag $1:latest $1:pre-$WJBID_STAMP || true"
  echo "[wjbid] 되돌리기: docker tag $1:pre-$WJBID_STAMP $1:latest && cd $WJBID_REMOTE && docker compose up -d --force-recreate $2"
}

wjbid_wait_healthy() {
  if ! wjbid_run "for i in \$(seq 1 180); do s=\$(docker inspect --format '{{.State.Health.Status}}' \$(cd $WJBID_REMOTE && docker compose ps -q $1) 2>/dev/null); [ \"\$s\" = healthy ] && exit 0; sleep 1; done; exit 1"; then
    echo "[wjbid] $1 이 180초 안에 healthy 가 되지 않았습니다."
    wjbid_run "cd $WJBID_REMOTE && docker compose logs --tail 80 $1" || true
    exit 1
  fi
  echo "[wjbid] $1 healthy"
}

deploy_wjbid_sandbox() {
  echo "[wjbid-sandbox] 소스 전송"
  wjbid_require_sandbox_key
  wjbid_run "mkdir -p $WJBID_REMOTE/build/skill-sandbox"
  wjbid_send skill-sandbox/Dockerfile skill-sandbox/server.py "$WJBID_SERVER:$WJBID_REMOTE/build/skill-sandbox/"
  wjbid_sync_compose
  wjbid_keep_image aimbase-skill-sandbox skill-sandbox
  echo "[wjbid-sandbox] 이미지 빌드와 재시작"
  wjbid_run "cd $WJBID_REMOTE && docker compose up -d --build skill-sandbox"
  wjbid_wait_healthy skill-sandbox
}

deploy_wjbid_be() {
  echo "[wjbid-be] 빌드"
  (cd backend && ./gradlew platform-core:bootJar)
  wjbid_require_sandbox_key
  FAT_JAR=$(find backend/platform-core/build/libs -name '*.jar' ! -name '*plain*')
  wjbid_run "mkdir -p $WJBID_REMOTE/build/backend/platform-core/build/libs"
  # Dockerfile 이 libs 의 *.jar 를 모두 복사하므로 이름을 하나로 고정해 덮어쓴다.
  wjbid_send "$FAT_JAR" "$WJBID_SERVER:$WJBID_REMOTE/build/backend/platform-core/build/libs/platform-core.jar"
  wjbid_send backend/platform-core/Dockerfile "$WJBID_SERVER:$WJBID_REMOTE/build/backend/platform-core/Dockerfile"
  wjbid_sync_compose
  wjbid_keep_image aimbase-api api
  echo "[wjbid-be] 이미지 빌드와 api 재시작"
  wjbid_run "cd $WJBID_REMOTE && docker compose build api && docker compose up -d --force-recreate api"
  wjbid_wait_healthy api
}

deploy_wjbid_agent() {
  echo "[wjbid-agent] 빌드"
  (cd backend && ./gradlew aimbase-agent:bootJar)
  AGENT_JAR=$(find backend/aimbase-agent/build/libs -name '*.jar' ! -name '*plain*')
  wjbid_run "mkdir -p $WJBID_REMOTE/build/backend/aimbase-agent/build/libs"
  wjbid_send "$AGENT_JAR" "$WJBID_SERVER:$WJBID_REMOTE/build/backend/aimbase-agent/build/libs/aimbase-agent.jar"
  wjbid_send backend/aimbase-agent/Dockerfile "$WJBID_SERVER:$WJBID_REMOTE/build/backend/aimbase-agent/Dockerfile"
  wjbid_sync_compose
  wjbid_keep_image aimbase-aimbase-agent aimbase-agent
  echo "[wjbid-agent] 이미지 빌드와 러너 재시작"
  wjbid_run "cd $WJBID_REMOTE && docker compose build aimbase-agent && docker compose up -d --force-recreate aimbase-agent"
  wjbid_wait_healthy aimbase-agent
}

# 운영 3PL 서버 chaws 는 jar 와 Dockerfile 을 build/ 로 보내 서버에서 이미지를 만든다.
# jar, Dockerfile, compose, 설치 스크립트는 맥 작업 폴더가 아니라 CHAWS_COMMIT 을 받은 worktree 에서 가져간다.
# 작업 폴더에는 다른 창의 덜 된 변경이 섞여 있고, 운영에는 어느 커밋인지 알 수 있는 것만 올린다.
# DB 와 Redis 는 이미 떠 있으면 다시 만들지 않는다. api, 실행기는 --no-deps 로 그것만 다시 만든다.
CHAWS_SERVER="ubuntu@3.39.94.112"
CHAWS_SSH_KEY="$HOME/.ssh/id_ed25519_therecommerce"
CHAWS_REMOTE="/home/ubuntu/aimbase"
CHAWS_STAMP="$(date +%Y%m%d-%H%M%S)"
CHAWS_WORKTREE="$HOME/.cache/aimbase-chaws-build"

chaws_run()  { ssh -i "$CHAWS_SSH_KEY" -o IdentitiesOnly=yes -o ConnectTimeout=10 "$CHAWS_SERVER" "$@"; }
chaws_send() { scp -q -i "$CHAWS_SSH_KEY" -o IdentitiesOnly=yes "$@"; }

# 비밀값은 서버에서 만들고 화면에 찍지 않는다. 이미 있는 파일은 그대로 둔다.
# 실행기 전용 키(AIMBASE_RUNNER_SERVER_MCP_API_KEY)는 bp_wms 테넌트를 만든 뒤 발급해 agent.env 에 더한다(운용 가이드 § 2-10).
deploy_chaws_init() {
  echo "[chaws-init] 폴더, 비밀값 파일, 망"
  chaws_run "set -e; umask 077; cd $CHAWS_REMOTE 2>/dev/null || { mkdir -p $CHAWS_REMOTE; cd $CHAWS_REMOTE; }
    mkdir -p build deploy-backup secrets claude-accounts/work01 claude-accounts/work02 claude-accounts/work03 workspace aimbase-storage
    if [ -f db.env ] && [ -f api.env ]; then echo '[chaws-init] db.env, api.env 있음. 그대로 둔다'
    elif [ -f db.env ] || [ -f api.env ]; then echo '[chaws-init] db.env 와 api.env 가운데 하나만 있습니다. DB 비밀번호가 어긋나니 손으로 맞추세요.'; exit 1
    else P=\$(openssl rand -hex 24)
      printf 'POSTGRES_USER=platform\nPOSTGRES_PASSWORD=%s\nPOSTGRES_DB=aimbase_master\n' \"\$P\" > db.env
      printf 'MASTER_DB_USERNAME=platform\nMASTER_DB_PASSWORD=%s\nTENANT_DB_USERNAME=platform\nTENANT_DB_PASSWORD=%s\nJWT_SECRET=%s\n' \"\$P\" \"\$P\" \"\$(openssl rand -hex 32)\" > api.env
      echo '[chaws-init] db.env, api.env 만듦'
    fi
    [ -f agent.env ] || { printf 'AIMBASE_RUNNER_API_KEY=%s\n' \"\$(openssl rand -hex 32)\" > agent.env; echo '[chaws-init] agent.env 만듦'; }
    docker network inspect aimbase-apps >/dev/null 2>&1 || docker network create aimbase-apps
    docker run --rm -v $CHAWS_REMOTE:/w --entrypoint chown pgvector/pgvector:pg16 -R 999 /w/claude-accounts /w/workspace /w/aimbase-storage"
  echo "[chaws-init] 완료"
}

chaws_checkout() {
  if [ -z "$CHAWS_COMMIT" ]; then
    echo "운영에 올릴 커밋을 CHAWS_COMMIT=<커밋> 으로 주세요."
    exit 1
  fi
  if ! CHAWS_SHA=$(git rev-parse -q --verify "$CHAWS_COMMIT^{commit}"); then
    echo "커밋 $CHAWS_COMMIT 을 찾지 못했습니다."
    exit 1
  fi
  git worktree prune
  if [ -d "$CHAWS_WORKTREE" ]; then
    if [ -n "$(git -C "$CHAWS_WORKTREE" status --porcelain)" ]; then
      echo "$CHAWS_WORKTREE 에 고친 파일이 있습니다. 커밋에 없는 것이 섞이니 비우고 다시 실행하세요."
      exit 1
    fi
    git -C "$CHAWS_WORKTREE" checkout -q --detach "$CHAWS_SHA"
  else
    git worktree add -q --detach "$CHAWS_WORKTREE" "$CHAWS_SHA"
  fi
  echo "[chaws] 커밋 ${CHAWS_SHA:0:9} 에서 빌드"
}

# $1 모듈(platform-core, aimbase-agent). worktree 에서 빌드한 jar 와 그 커밋의 Dockerfile 을 서버 build/ 로 보낸다.
chaws_build_send() {
  local jar
  (cd "$CHAWS_WORKTREE/backend" && ./gradlew "$1:bootJar")
  jar=$(find "$CHAWS_WORKTREE/backend/$1/build/libs" -name '*.jar' ! -name '*plain*')
  chaws_run "mkdir -p $CHAWS_REMOTE/build/backend/$1/build/libs"
  # Dockerfile 이 libs 의 *.jar 를 모두 복사하므로 이름을 하나로 고정해 덮어쓴다.
  chaws_send "$jar" "$CHAWS_SERVER:$CHAWS_REMOTE/build/backend/$1/build/libs/$1.jar"
  chaws_send "$CHAWS_WORKTREE/backend/$1/Dockerfile" "$CHAWS_SERVER:$CHAWS_REMOTE/build/backend/$1/Dockerfile"
}

# 지금 무엇이 도는지 서버에서 볼 수 있게 서비스별 커밋을 남긴다.
chaws_record() {
  chaws_run "echo '$CHAWS_STAMP $1 $CHAWS_SHA' >> $CHAWS_REMOTE/deploy-backup/deployed.log"
}

chaws_require_env() {
  if ! chaws_run "cd $CHAWS_REMOTE && [ -f db.env ] && [ -f api.env ] && [ -f agent.env ] && docker network inspect aimbase-apps >/dev/null 2>&1"; then
    echo "chaws $CHAWS_REMOTE 에 db.env, api.env, agent.env 또는 망 aimbase-apps 가 없습니다. ./deploy.sh chaws-init 을 먼저 실행하세요."
    exit 1
  fi
}

chaws_sync_compose() {
  chaws_send "$CHAWS_WORKTREE/docker-compose.chaws.yml" "$CHAWS_SERVER:$CHAWS_REMOTE/docker-compose.yml.new"
  chaws_run "cd $CHAWS_REMOTE && { [ -f docker-compose.yml ] && diff -u docker-compose.yml docker-compose.yml.new || true; }"
  chaws_run "cd $CHAWS_REMOTE && { [ -f docker-compose.yml ] && cp -p docker-compose.yml deploy-backup/docker-compose.yml.$CHAWS_STAMP || true; } && mv docker-compose.yml.new docker-compose.yml"
}

chaws_keep_image() {
  chaws_run "docker image inspect $1:latest >/dev/null 2>&1 && docker tag $1:latest $1:pre-$CHAWS_STAMP || true"
  echo "[chaws] 되돌리기: docker tag $1:pre-$CHAWS_STAMP $1:latest && cd $CHAWS_REMOTE && docker compose up -d --no-deps --force-recreate $2"
}

chaws_wait_healthy() {
  if ! chaws_run "for i in \$(seq 1 180); do s=\$(docker inspect --format '{{.State.Health.Status}}' \$(cd $CHAWS_REMOTE && docker compose ps -q $1) 2>/dev/null); [ \"\$s\" = healthy ] && exit 0; sleep 1; done; exit 1"; then
    echo "[chaws] $1 이 180초 안에 healthy 가 되지 않았습니다."
    chaws_run "cd $CHAWS_REMOTE && docker compose logs --tail 80 $1" || true
    exit 1
  fi
  echo "[chaws] $1 healthy"
}

deploy_chaws_be() {
  chaws_require_env
  chaws_checkout
  chaws_build_send platform-core
  chaws_send "$CHAWS_WORKTREE/scripts/chaws-bootstrap.sh" "$CHAWS_SERVER:$CHAWS_REMOTE/build/chaws-bootstrap.sh"
  chaws_sync_compose
  echo "[chaws-be] DB, Redis"
  chaws_run "cd $CHAWS_REMOTE && docker compose up -d db redis"
  chaws_wait_healthy db
  chaws_wait_healthy redis
  chaws_keep_image aimbase-api api
  echo "[chaws-be] 이미지 빌드와 api 재시작"
  chaws_run "cd $CHAWS_REMOTE && nice -n 10 docker compose build api && docker compose up -d --no-deps --force-recreate api"
  chaws_wait_healthy api
  chaws_record api
}

deploy_chaws_agent() {
  chaws_require_env
  if ! chaws_run "grep -Eq '^AIMBASE_RUNNER_SERVER_MCP_API_KEY=.+' $CHAWS_REMOTE/agent.env && grep -Eq '^AGENT_API_KEY=.+' $CHAWS_REMOTE/agent.env"; then
    echo "chaws $CHAWS_REMOTE/agent.env 에 bp_wms 실행기 전용 키가 없습니다. 운용 가이드 § 2-10 대로 발급해 넣은 뒤 다시 실행하세요."
    exit 1
  fi
  chaws_checkout
  chaws_build_send aimbase-agent
  chaws_sync_compose
  chaws_keep_image aimbase-aimbase-agent aimbase-agent
  echo "[chaws-agent] 이미지 빌드와 실행기 재시작"
  chaws_run "cd $CHAWS_REMOTE && nice -n 10 docker compose build aimbase-agent && docker compose up -d --no-deps --force-recreate aimbase-agent"
  chaws_wait_healthy aimbase-agent
  chaws_record aimbase-agent
}

case "$TARGET" in
  chaws-init)  deploy_chaws_init; exit 0 ;;
  chaws-be)    deploy_chaws_be; echo "배포 완료: 운영 chaws $CHAWS_SERVER db, redis, api"; exit 0 ;;
  chaws-agent) deploy_chaws_agent; echo "배포 완료: 운영 chaws $CHAWS_SERVER aimbase-agent"; exit 0 ;;
  chaws)       deploy_chaws_be; deploy_chaws_agent; echo "배포 완료: 운영 chaws $CHAWS_SERVER db, redis, api, aimbase-agent"; exit 0 ;;
esac

case "$TARGET" in
  wjbid-sandbox) deploy_wjbid_sandbox; echo "배포 완료: 우진비드 AWS $WJBID_SERVER skill-sandbox"; exit 0 ;;
  wjbid-be)      deploy_wjbid_be; echo "배포 완료: 우진비드 AWS $WJBID_SERVER api"; exit 0 ;;
  wjbid-agent)   deploy_wjbid_agent; echo "배포 완료: 우진비드 AWS $WJBID_SERVER aimbase-agent"; exit 0 ;;
  # && 로 잇으면 함수 안에서 set -e 가 꺼져 실패해도 다음 단계로 간다. 한 줄씩 부른다.
  wjbid)       deploy_wjbid_sandbox; deploy_wjbid_be; deploy_wjbid_agent; echo "배포 완료: 우진비드 AWS $WJBID_SERVER skill-sandbox, api, aimbase-agent"; exit 0 ;;
esac

case "$TARGET" in
  fe)    deploy_fe ;;
  be)    deploy_be ;;
  rag)   deploy_rag ;;
  agent)  deploy_agent ;;
  agents) deploy_agents ;;
  mcp)    deploy_mcp ;;
  sandbox) deploy_sandbox ;;
  all)   deploy_fe && deploy_rag && deploy_be ;;
  *)     echo "사용법: ./deploy.sh [fe|be|rag|agent|agents|mcp|sandbox|all|wjbid-sandbox|wjbid-be|wjbid-agent|wjbid|chaws-init|chaws-be|chaws-agent|chaws]"; exit 1 ;;
esac

echo ""
echo "✅ 배포 완료 → http://59.8.160.12:3200"
