#!/usr/bin/env bash
# 서버 판정용 로그인. 개발기 Aimbase WES 테넌트의 위젯 토큰을 받아 마지막 줄에 낸다.
# WES 서버와 같게 user_ref 없이 받는다. user_ref 를 실으면 대화가 그 이름의 PC 실행기로 가서 연결의 공용 실행기를 시험하지 못한다(CR-075).
# 계정 이름은 판정 실행기의 줄 서기에만 쓴다.
# 테넌트 서버 열쇠는 WES 서버 컨테이너 안에서만 읽어 쓰고 이 맥으로 옮기지 않는다. 받는 것은 30분짜리 위젯 토큰뿐이다.
set -euo pipefail
account="${1:?계정 이름}"
case "$account" in *[!A-Za-z0-9._-]*) echo "계정 이름은 영문, 숫자, 점, 밑줄, 붙임표만 쓴다" >&2; exit 2 ;; esac
host="${TESTBOOK_DEV_SSH:-therecommerce@59.8.160.12}"
key="${TESTBOOK_DEV_SSH_KEY:-$HOME/.ssh/id_ed25519}"
origin="${TESTBOOK_WIDGET_ORIGIN:-http://59.8.160.12:3201}"
body="{\"origin\":\"$origin\",\"scopes\":[\"chat:stream\"],\"ttl_seconds\":1800}"
ssh -i "$key" -o IdentitiesOnly=yes -o BatchMode=yes "$host" \
  "curl -sS -X POST http://localhost:8280/api/v1/sessions/issue-widget-token \
     -H \"X-API-Key: \$(docker exec bp-wes-api-1 printenv AIMBASE_API_KEY)\" \
     -H \"X-Tenant-Id: \$(docker exec bp-wes-api-1 printenv AIMBASE_TENANT_ID || echo bp_wes)\" \
     -H 'Content-Type: application/json' -d '$body'" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); t=(d.get("data") or {}).get("token"); print(t) if t else sys.exit("토큰 발급 실패: " + str(d)[:200])'
