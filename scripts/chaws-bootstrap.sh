#!/bin/bash
# 운영 chaws 회사용 Aimbase 를 처음 세운 뒤 한 번씩 하는 일(CR-162, 운용 가이드 § 2-10).
# ./deploy.sh chaws-be 가 서버 build/ 에 올리고, 서버 폴더(/home/ubuntu/aimbase)에서 돌린다.
#   ssh chaws 'cd /home/ubuntu/aimbase && bash build/chaws-bootstrap.sh <단계>'
# 비밀값은 secrets/ 와 agent.env 에만 쓰고 화면과 명령 인자에 싣지 않는다. 이미 된 일은 건너뛴다.
#   admin        첫 관리자(tenant_admins)와 CLI 허용 테넌트
#   tenant       테넌트 bp_wms, 실행기 전용 키(agent.env), bp-wms 키(secrets/bp-wms-api-key)
#   connections  로그인된 계정마다 연결 하나와 ROUND_ROBIN 묶음. 계정은 실행기의 claude-accounts 아래
#                로그인 파일이 있는 폴더다. 계정을 새로 로그인한 뒤 다시 돌리면 연결을 더하고 묶음 구성원을 맞춘다.
#                ACCOUNTS="work01 work02" 처럼 주면 그 목록을 쓴다
#   workflow     워크플로우 정의(표준 입력)를 등록한다. 단계 opinion 의 연결은 work01
#   smoke        bp-wms 가 부르는 길로 워크플로우를 한 번 돌린다. work01 로그인 뒤에 한다
set -euo pipefail
umask 077

STEP="${1:?단계를 주세요: admin | tenant | connections | workflow | smoke}"
API="http://127.0.0.1:8280/api/v1"
TENANT="bp_wms"
ADMIN_EMAIL="platform-admin@aimbase.local"
RUNNER="cli-runner-bp-wms"
ACCOUNTS="${ACCOUNTS:-}"
GROUP="$RUNNER-rr"
WORKFLOW_CONNECTION="$RUNNER-work01"
HDR="secrets/.auth-header"
trap 'rm -f "$HDR"' EXIT
mkdir -p secrets

sql() { docker compose exec -T db psql -U platform -d "$1" -v ON_ERROR_STOP=1 -tAq; }

# 토큰은 머리 파일로만 curl 에 넘긴다(-H @파일).
login() {
  python3 - "$ADMIN_EMAIL" <<'PY' | curl -fsS -H 'Content-Type: application/json' -d @- "$API/auth/login" \
    | python3 -c 'import json,sys; print("Authorization: Bearer " + json.load(sys.stdin)["data"]["access_token"])' > "$HDR"
import json, sys
print(json.dumps({"email": sys.argv[1], "password": open("secrets/admin-password").read().strip()}))
PY
}

# 테넌트 API 는 그 테넌트로 들어간 토큰(CR-096)으로 부른다.
login_tenant() {
  login
  curl -fsS -X POST -H @"$HDR" "$API/platform/tenants/$TENANT/impersonate" \
    | python3 -c 'import json,sys; print("Authorization: Bearer " + json.load(sys.stdin)["data"]["access_token"])' > "$HDR.t"
  mv "$HDR.t" "$HDR"
}

# 응답 본문에는 연결의 실행기 호출 키가 실려 오므로 찍지 않는다. 실패면 message 만 찍는다.
call() {
  local code
  code=$(curl -sS -o secrets/.resp -w '%{http_code}' -H @"$HDR" -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" "$@")
  case "$code" in 2*) ;; *) python3 -c 'import json,sys; b=json.load(open("secrets/.resp")); print("  message:", b.get("message") or b.get("error"), file=sys.stderr)' 2>&1 >&2 || true ;; esac
  rm -f secrets/.resp
  echo "$code"
}
status() { curl -s -o /dev/null -w '%{http_code}' -H @"$HDR" -H "X-Tenant-Id: $TENANT" "$1"; }

step_admin() {
  [ -s secrets/admin-password ] || openssl rand -hex 16 > secrets/admin-password
  if [ "$(echo "select count(*) from tenant_admins where email = '$ADMIN_EMAIL';" | sql aimbase_master)" = 0 ]; then
    echo "create extension if not exists pgcrypto;" | sql postgres
    H=$({ printf "\\\\set pw '%s'\n" "$(cat secrets/admin-password)"; echo "select crypt(:'pw', gen_salt('bf', 10));"; } | sql postgres)
    printf "insert into tenant_admins (email, password_hash, display_name, role) values ('%s', '%s', 'platform admin', 'super_admin');\n" "$ADMIN_EMAIL" "$H" | sql aimbase_master
    echo "[admin] 첫 관리자 $ADMIN_EMAIL 만듦. 비밀번호는 secrets/admin-password"
  else
    echo "[admin] 첫 관리자 있음"
  fi
  echo "insert into global_config (config_key, config_value, description, updated_by) values ('llm.anthropic-cli.enabled-tenants', '$TENANT', 'CLI 연결을 쓰는 테넌트(BIZ-099, CR-162)', 'CR-162') on conflict (config_key) do nothing;" | sql aimbase_master
  echo "[admin] CLI 허용 테넌트: $(echo "select config_value from global_config where config_key = 'llm.anthropic-cli.enabled-tenants';" | sql aimbase_master)"
  login
  echo "[admin] 관리자 로그인 됨"
}

# 발급 응답에서 키만 꺼내 파일에 쓴다.
issue_key() {
  python3 - "$1" "$TENANT" <<'PY' | curl -fsS -H @"$HDR" -H 'Content-Type: application/json' -d @- "$API/platform/api-keys" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["apiKey"])'
import json, sys
print(json.dumps({"name": sys.argv[1], "domainApp": sys.argv[2], "tenantId": sys.argv[2]}))
PY
}

step_tenant() {
  login
  if [ "$(status "$API/platform/tenants/$TENANT")" = 200 ]; then
    echo "[tenant] 테넌트 $TENANT 있음"
  else
    [ -s "secrets/$TENANT-admin-password" ] || openssl rand -hex 16 > "secrets/$TENANT-admin-password"
    code=$(python3 - "$TENANT" <<'PY' | call -X POST -d @- "$API/platform/tenants"
import json, sys
t = sys.argv[1]
print(json.dumps({"tenantId": t, "name": "bp-wms", "adminEmail": "bp-wms-admin@aimbase.local",
                  "initialAdminPassword": open(f"secrets/{t}-admin-password").read().strip(), "domainApp": t}))
PY
)
    echo "[tenant] 테넌트 $TENANT 만들기 응답 $code"
    [ "$code" = 201 ] || exit 1
  fi
  if grep -Eq '^AIMBASE_RUNNER_SERVER_MCP_API_KEY=.+' agent.env; then
    echo "[tenant] 실행기 전용 키 있음"
  else
    K=$(issue_key "$TENANT 실행기 MCP 키 (CR-162)")
    printf 'AIMBASE_RUNNER_SERVER_MCP_API_KEY=%s\nAGENT_API_KEY=%s\n' "$K" "$K" >> agent.env
    echo "[tenant] 실행기 전용 키를 agent.env 에 넣음"
  fi
  if [ -s secrets/bp-wms-api-key ]; then
    echo "[tenant] bp-wms 키 있음"
  else
    issue_key "bp-wms-app (CR-162)" > secrets/bp-wms-api-key
    echo "[tenant] bp-wms 키를 secrets/bp-wms-api-key 에 넣음"
  fi
}

# 계정 폴더는 실행 계정 999 소유라 서버 계정으로는 못 읽는다. 실행기 컨테이너 안에서 센다.
logged_in_accounts() {
  docker compose exec -T aimbase-agent sh -c \
    'for d in /home/appuser/.claude-accounts/*/; do [ -s "$d.credentials.json" ] && basename "$d"; done; true' \
    | sort | paste -sd ' ' -
}

step_connections() {
  if [ -z "$ACCOUNTS" ]; then
    ACCOUNTS=$(logged_in_accounts)
    [ -n "${ACCOUNTS// /}" ] || { echo "[connections] 로그인된 계정이 없음. 계정 로그인 뒤 다시 돌린다"; exit 1; }
    echo "[connections] 로그인된 계정: $ACCOUNTS"
  fi
  login_tenant
  for a in $ACCOUNTS; do
    id="$RUNNER-$a"
    if [ "$(status "$API/connections/$id")" = 200 ]; then echo "[connections] $id 있음"; continue; fi
    code=$(python3 - "$id" "$a" "$RUNNER" <<'PY' | call -X POST -d @- "$API/connections"
import json, sys
cid, acct, runner = sys.argv[1:4]
key = [l.split("=", 1)[1].strip() for l in open("agent.env") if l.startswith("AIMBASE_RUNNER_API_KEY=")][0]
print(json.dumps({"id": cid, "name": f"bp-wms CLI {acct}", "adapter": "anthropic-cli", "type": "llm",
                  "config": {"model": "claude-sonnet-4-5", "tool_mode": "AIMBASE", "agent_name": runner,
                             "config_dir": f"/home/appuser/.claude-accounts/{acct}",
                             "exposed_tools": ["get_current_time"], "runner_api_key": key}}))
PY
)
    echo "[connections] $id 만들기 응답 $code"
  done
  body=$(python3 - "$GROUP" "$RUNNER" $ACCOUNTS <<'PY'
import json, sys
gid, runner, accts = sys.argv[1], sys.argv[2], sys.argv[3:]
print(json.dumps({"id": gid, "name": "bp-wms CLI 계정 묶음", "adapter": "anthropic-cli", "strategy": "ROUND_ROBIN",
                  "members": [{"connection_id": f"{runner}-{a}", "priority": i + 1} for i, a in enumerate(accts)],
                  "isDefault": False}))
PY
)
  if [ "$(status "$API/connection-groups/$GROUP")" = 200 ]; then
    code=$(printf '%s' "$body" | call -X PUT -d @- "$API/connection-groups/$GROUP")
    echo "[connections] 묶음 $GROUP 구성원을 $ACCOUNTS 로 맞춤 응답 $code"
  else
    code=$(printf '%s' "$body" | call -X POST -d @- "$API/connection-groups")
    echo "[connections] 묶음 $GROUP 만들기 응답 $code"
  fi
}

step_workflow() {
  login_tenant
  python3 -c '
import json, sys
wf = json.load(sys.stdin)
for s in wf["steps"]:
    if s["id"] == "opinion":
        s["config"]["connection_id"] = sys.argv[1]
print(json.dumps(wf))' "$WORKFLOW_CONNECTION" > secrets/.workflow.json
  id=$(python3 -c 'import json; print(json.load(open("secrets/.workflow.json"))["id"])')
  if [ "$(status "$API/workflows/$id")" = 200 ]; then
    code=$(call -X PUT -d @secrets/.workflow.json "$API/workflows/$id")
    echo "[workflow] $id 고침 응답 $code"
  else
    code=$(call -X POST -d @secrets/.workflow.json "$API/workflows")
    echo "[workflow] $id 등록 응답 $code"
  fi
  rm -f secrets/.workflow.json
}

# bp-wms 가 부르는 길 그대로 본다. aimbase-apps 망 안의 컨테이너가 bp-wms 키로 aimbase-api 를 부른다.
# 입력은 시험용으로 지은 차수 후보 둘이다. 운영 WMS 자료는 읽지 않는다.
step_smoke() {
  printf 'X-API-Key: %s\nX-Tenant-Id: %s\nContent-Type: application/json\n' "$(cat secrets/bp-wms-api-key)" "$TENANT" > secrets/.wms-header
  python3 -c '
import json
plan = {"planAt": "2026-10-27T14:00", "nextPlanAt": "2026-10-27T15:00", "remainingPlanTimes": ["15:00", "16:00"],
        "floorNo": 2, "unassignedOrderCount": 120, "stockHoldOrderCount": 3, "ruleKey": "A",
        "candidates": [
          {"key": "A", "name": "전체", "orderCount": 120, "itemQty": 180, "singleBasketCount": 6, "multiBasketCount": 2,
           "crossZoneBasketCount": 1, "cartCount": 3, "finishAt": "2026-10-27T15:20", "withinCutoff": True, "dueBeforeNextCount": 40,
           "zones": [{"zoneNo": 1, "workerCount": 2, "finishAt": "2026-10-27T15:20"}]},
          {"key": "B", "name": "마감 임박분", "orderCount": 70, "itemQty": 95, "singleBasketCount": 4, "multiBasketCount": 1,
           "crossZoneBasketCount": 0, "cartCount": 2, "finishAt": "2026-10-27T14:50", "withinCutoff": True, "dueBeforeNextCount": 40,
           "zones": [{"zoneNo": 1, "workerCount": 2, "finishAt": "2026-10-27T14:50"}]}]}
print(json.dumps({"planJson": json.dumps(plan, ensure_ascii=False)}, ensure_ascii=False))' > secrets/.smoke.json
  img=$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q api)")
  wms() { docker run --rm --user 0 --network aimbase-apps -v "$PWD/secrets:/s:ro" --entrypoint curl "$img" -sS -H @/s/.wms-header "$@"; }
  run=$(wms -X POST -d @/s/.smoke.json http://aimbase-api:8080/api/v1/workflows/wms-wave-plan-opinion/run \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["id"])')
  echo "[smoke] 실행 $run"
  for _ in $(seq 1 60); do
    wms "http://aimbase-api:8080/api/v1/workflows/runs/$run" > secrets/.smoke-run.json
    st=$(python3 -c 'import json; print(json.load(open("secrets/.smoke-run.json"))["data"]["status"])')
    case "$st" in completed|failed|cancelled) break ;; esac
    sleep 5
  done
  python3 -c '
import json
d = json.load(open("secrets/.smoke-run.json"))["data"]
print("[smoke] 상태", d["status"])
o = (d.get("stepResults") or {}).get("opinion") or {}
print("[smoke] 결과", json.dumps(o.get("structured_data") or o.get("error") or d.get("error"), ensure_ascii=False)[:600])'
  rm -f secrets/.wms-header secrets/.smoke.json secrets/.smoke-run.json
}

case "$STEP" in
  admin) step_admin ;;
  tenant) step_tenant ;;
  connections) step_connections ;;
  workflow) step_workflow ;;
  smoke) step_smoke ;;
  *) echo "단계: admin | tenant | connections | workflow | smoke"; exit 1 ;;
esac
