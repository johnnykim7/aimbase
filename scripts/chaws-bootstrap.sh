#!/bin/bash
# 운영 chaws 회사용 Aimbase 를 처음 세운 뒤 한 번씩 하는 일(CR-162, CR-173, 운용 가이드 § 2-10).
# ./deploy.sh chaws-be, chaws-agent-wes-cs 가 서버 build/ 에 올리고, 서버 폴더(/home/ubuntu/aimbase)에서 돌린다.
#   ssh chaws 'cd /home/ubuntu/aimbase && bash build/chaws-bootstrap.sh <단계> [대상]'
# 대상은 실행기 하나와 그 테넌트다. 주지 않으면 wms 다.
#   wms     테넌트 bp_wms, 실행기 aimbase-agent(cli-runner-bp-wms), 비밀값 agent.env, 소비앱 키 secrets/bp-wms-api-key
#   wes-cs  테넌트 bp_wes, 실행기 aimbase-agent-wes-cs(cli-runner-bp-wes-cs), 비밀값 agent-wes-cs.env,
#           소비앱 키 secrets/bp-wes-api-key. CS 상세 AI 대화(위젯)용 연결 -001 을 더 둔다
# 비밀값은 secrets/ 와 실행기 비밀값 파일에만 쓰고 화면과 명령 인자에 싣지 않는다. 이미 된 일은 건너뛴다.
#   admin        첫 관리자(tenant_admins)와 CLI 허용 테넌트에 대상 테넌트
#   tenant       대상 테넌트와 DB, 실행기 호출 키와 실행기 전용 키(실행기 비밀값 파일), 소비앱 키
#   connections  로그인된 계정마다 연결 하나와 ROUND_ROBIN 묶음. 계정은 실행기의 claude-accounts 아래
#                로그인 파일이 있는 폴더다. 계정을 새로 로그인한 뒤 다시 돌리면 연결을 더하고 묶음 구성원을 맞춘다.
#                ACCOUNTS="work01 work02" 처럼 주면 그 목록을 쓴다. 위젯 연결의 계정은 WIDGET_ACCOUNT(기본 work01)
#   widget       위젯 허용 주소(widget.allowed-origins)에 대상의 화면 주소. WIDGET_ORIGIN 으로 바꿀 수 있다
#   workflow     워크플로우 정의(표준 입력)를 등록한다. 단계 opinion 의 연결은 work01
#   smoke        소비앱이 부르는 길로 한 번 불러 본다. work01 로그인 뒤에 한다
set -euo pipefail
umask 077

STEP="${1:?단계를 주세요: admin | tenant | connections | widget | workflow | smoke}"
TARGET="${2:-wms}"
API="http://127.0.0.1:8280/api/v1"
ADMIN_EMAIL="platform-admin@aimbase.local"
ACCOUNTS="${ACCOUNTS:-}"
WIDGET_CONNECTION=""
case "$TARGET" in
  wms)
    TENANT="bp_wms"; APP="bp-wms"; LABEL="bp-wms"; TAG="CR-162"
    RUNNER="cli-runner-bp-wms"; AGENT_SERVICE="aimbase-agent"; AGENT_ENV="agent.env"
    TOOL_MODE="AIMBASE"; EXPOSED_TOOLS="get_current_time"
    WIDGET_ORIGIN="${WIDGET_ORIGIN:-}" ;;
  wes-cs)
    # 연결 설정은 59 의 cli-runner-bp-wes-cs-* 와 같다. CLI 가 bash 로 psql 을 불러 운영 3PL DB 를 읽는다.
    TENANT="bp_wes"; APP="bp-wes"; LABEL="bp-wes CS"; TAG="CR-173"
    RUNNER="cli-runner-bp-wes-cs"; AGENT_SERVICE="aimbase-agent-wes-cs"; AGENT_ENV="agent-wes-cs.env"
    TOOL_MODE="NATIVE"; EXPOSED_TOOLS="bash get_current_time"
    # 위젯은 연결 묶음을 보내지 못해 연결 하나로 간다(bp-wes WES_CS_AIMBASE_WIDGET_CONNECTION_ID).
    WIDGET_CONNECTION="$RUNNER-001"
    WIDGET_ORIGIN="${WIDGET_ORIGIN:-https://admin.banpoom.co.kr}" ;;
  *) echo "대상: wms | wes-cs"; exit 1 ;;
esac
WIDGET_ACCOUNT="${WIDGET_ACCOUNT:-work01}"
APP_KEY_FILE="secrets/$APP-api-key"
GROUP="$RUNNER-rr"
WORKFLOW_CONNECTION="$RUNNER-work01"
HDR="secrets/.auth-header"
trap 'rm -f "$HDR" secrets/.app-header' EXIT
mkdir -p secrets

sql() { docker compose exec -T db psql -U platform -d "$1" -v ON_ERROR_STOP=1 -tAq; }

# 쉼표로 나열한 master 설정값에 값 하나를 더하고 결과를 찍는다. 이미 있거나 전체 허용(*)이면 그대로 둔다.
add_to_list() {
  { printf "\\\\set k '%s'\n\\\\set v '%s'\n\\\\set d '%s'\n\\\\set by '%s'\n" "$1" "$2" "$3" "$TAG"
    cat <<'SQL'
insert into global_config (config_key, config_value, description, updated_by) values (:'k', :'v', :'d', :'by')
on conflict (config_key) do update
  set config_value = case when btrim(global_config.config_value) = '' then excluded.config_value
                          else global_config.config_value || ',' || excluded.config_value end,
      updated_by = excluded.updated_by, updated_at = now()
  where btrim(global_config.config_value) <> '*'
    and not (excluded.config_value = any (string_to_array(replace(global_config.config_value, ' ', ''), ',')));
select config_value from global_config where config_key = :'k';
SQL
  } | sql aimbase_master
}

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
  echo "[admin] CLI 허용 테넌트: $(add_to_list llm.anthropic-cli.enabled-tenants "$TENANT" 'CLI 연결을 쓰는 테넌트(BIZ-099, CR-162)')"
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
    code=$(python3 - "$TENANT" "$APP" <<'PY' | call -X POST -d @- "$API/platform/tenants"
import json, sys
t, app = sys.argv[1:3]
print(json.dumps({"tenantId": t, "name": app, "adminEmail": f"{app}-admin@aimbase.local",
                  "initialAdminPassword": open(f"secrets/{t}-admin-password").read().strip(), "domainApp": t}))
PY
)
    echo "[tenant] 테넌트 $TENANT 만들기 응답 $code"
    [ "$code" = 201 ] || exit 1
  fi
  touch "$AGENT_ENV"
  if grep -Eq '^AIMBASE_RUNNER_API_KEY=.+' "$AGENT_ENV"; then
    echo "[tenant] 실행기 호출 키 있음"
  else
    printf 'AIMBASE_RUNNER_API_KEY=%s\n' "$(openssl rand -hex 32)" >> "$AGENT_ENV"
    echo "[tenant] 실행기 호출 키를 $AGENT_ENV 에 넣음"
  fi
  if grep -Eq '^AIMBASE_RUNNER_SERVER_MCP_API_KEY=.+' "$AGENT_ENV"; then
    echo "[tenant] 실행기 전용 키 있음"
  else
    K=$(issue_key "$TENANT 실행기 MCP 키 ($TAG)")
    printf 'AIMBASE_RUNNER_SERVER_MCP_API_KEY=%s\nAGENT_API_KEY=%s\n' "$K" "$K" >> "$AGENT_ENV"
    echo "[tenant] 실행기 전용 키를 $AGENT_ENV 에 넣음"
  fi
  if [ -s "$APP_KEY_FILE" ]; then
    echo "[tenant] $APP 키 있음"
  else
    issue_key "$APP-app ($TAG)" > "$APP_KEY_FILE"
    echo "[tenant] $APP 키를 $APP_KEY_FILE 에 넣음"
  fi
}

# 계정 폴더는 실행 계정 999 소유라 서버 계정으로는 못 읽는다. 실행기 컨테이너 안에서 센다.
logged_in_accounts() {
  docker compose exec -T "$AGENT_SERVICE" sh -c \
    'for d in /home/appuser/.claude-accounts/*/; do [ -s "$d.credentials.json" ] && basename "$d"; done; true' \
    | sort | paste -sd ' ' -
}

# 연결 하나를 없으면 만든다. 인자는 연결 ID, 계정 폴더 이름, 연결 이름.
ensure_connection() {
  if [ "$(status "$API/connections/$1")" = 200 ]; then echo "[connections] $1 있음"; return; fi
  code=$(python3 - "$1" "$2" "$3" "$RUNNER" "$AGENT_ENV" "$TOOL_MODE" $EXPOSED_TOOLS <<'PY' | call -X POST -d @- "$API/connections"
import json, sys
cid, acct, name, runner, env_file, tool_mode = sys.argv[1:7]
key = [l.split("=", 1)[1].strip() for l in open(env_file) if l.startswith("AIMBASE_RUNNER_API_KEY=")][0]
print(json.dumps({"id": cid, "name": name, "adapter": "anthropic-cli", "type": "llm",
                  "config": {"model": "claude-sonnet-4-5", "tool_mode": tool_mode, "agent_name": runner,
                             "config_dir": f"/home/appuser/.claude-accounts/{acct}",
                             "exposed_tools": sys.argv[7:], "runner_api_key": key}}))
PY
)
  echo "[connections] $1 만들기 응답 $code"
}

step_connections() {
  if [ -z "$ACCOUNTS" ]; then
    ACCOUNTS=$(logged_in_accounts)
    [ -n "${ACCOUNTS// /}" ] || { echo "[connections] 로그인된 계정이 없음. 계정 로그인 뒤 다시 돌린다"; exit 1; }
    echo "[connections] 로그인된 계정: $ACCOUNTS"
  fi
  login_tenant
  for a in $ACCOUNTS; do
    ensure_connection "$RUNNER-$a" "$a" "$LABEL CLI $a"
  done
  [ -z "$WIDGET_CONNECTION" ] || ensure_connection "$WIDGET_CONNECTION" "$WIDGET_ACCOUNT" "$LABEL 위젯 $WIDGET_ACCOUNT"
  body=$(python3 - "$GROUP" "$RUNNER" "$LABEL" $ACCOUNTS <<'PY'
import json, sys
gid, runner, label, accts = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
print(json.dumps({"id": gid, "name": f"{label} CLI 계정 묶음", "adapter": "anthropic-cli", "strategy": "ROUND_ROBIN",
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

# api 는 이 값을 5분까지 묵혀 두고 읽는다(PlatformSettingsService).
step_widget() {
  [ -n "$WIDGET_ORIGIN" ] || { echo "[widget] 대상 $TARGET 은 위젯 화면 주소가 없음. WIDGET_ORIGIN 으로 준다"; exit 1; }
  echo "[widget] 위젯 허용 주소: $(add_to_list widget.allowed-origins "$WIDGET_ORIGIN" '위젯을 여는 화면 주소(CR-058)')"
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

# 소비앱이 부르는 길 그대로 본다. aimbase-apps 망 안의 컨테이너가 소비앱 키로 aimbase-api 를 부른다.
app() {
  printf 'X-API-Key: %s\nX-Tenant-Id: %s\nContent-Type: application/json\n' "$(cat "$APP_KEY_FILE")" "$TENANT" > secrets/.app-header
  local img
  img=$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q api)")
  docker run --rm --user 0 --network aimbase-apps -v "$PWD/secrets:/s:ro" --entrypoint curl "$img" -sS -H @/s/.app-header "$@"
}

# 입력은 시험용으로 지은 차수 후보 둘이다. 운영 WMS 자료는 읽지 않는다.
smoke_wms() {
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
  run=$(app -X POST -d @/s/.smoke.json http://aimbase-api:8080/api/v1/workflows/wms-wave-plan-opinion/run \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["id"])')
  echo "[smoke] 실행 $run"
  for _ in $(seq 1 60); do
    app "http://aimbase-api:8080/api/v1/workflows/runs/$run" > secrets/.smoke-run.json
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
  rm -f secrets/.smoke.json secrets/.smoke-run.json
}

# 실행기의 DB 계정을 보고, bp-wes 가 부르는 길(묶음, stream=false)로 CLI 가 그 계정으로 psql 을 부르게 한다.
smoke_wes_cs() {
  echo "[smoke] 실행기 DB 계정: $(docker compose exec -T "$AGENT_SERVICE" psql -tAc 'select current_user' 2>&1 | head -c 300)"
  python3 - "$GROUP" <<'PY' > secrets/.smoke.json
import json, sys
print(json.dumps({"connection_group_id": sys.argv[1], "stream": False,
                  "messages": [{"role": "user", "content": "Bash 도구로 psql -tAc 'select current_user' 를 실행하고 출력 한 줄만 답하세요."}]},
                 ensure_ascii=False))
PY
  app --max-time 330 -w '\n%{http_code}' -X POST -d @/s/.smoke.json http://aimbase-api:8080/api/v1/chat/completions > secrets/.smoke-run.txt
  python3 -c '
import json
body, _, code = open("secrets/.smoke-run.txt").read().rpartition("\n")
print("[smoke] 응답", code)
try:
    b = json.loads(body)
except ValueError:
    b = {"body": body}
print("[smoke] 결과", json.dumps(b.get("data") or b, ensure_ascii=False)[:600])'
  rm -f secrets/.smoke.json secrets/.smoke-run.txt
}

step_smoke() {
  case "$TARGET" in
    wms) smoke_wms ;;
    wes-cs) smoke_wes_cs ;;
  esac
}

case "$STEP" in
  admin) step_admin ;;
  tenant) step_tenant ;;
  connections) step_connections ;;
  widget) step_widget ;;
  workflow) step_workflow ;;
  smoke) step_smoke ;;
  *) echo "단계: admin | tenant | connections | widget | workflow | smoke"; exit 1 ;;
esac
