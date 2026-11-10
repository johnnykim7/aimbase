#!/usr/bin/env bash
# 서버 판정용 DB 조회. 개발기 Aimbase WES 테넌트 DB 를 SQL 은 표준입력으로 받아 머리행 있는 탭 구분으로 낸다.
# DB 비밀번호는 개발기 안에서만 읽고 이 맥으로 옮기지 않는다.
set -euo pipefail
host="${TESTBOOK_DEV_SSH:-therecommerce@59.8.160.12}"
key="${TESTBOOK_DEV_SSH_KEY:-$HOME/.ssh/id_ed25519}"
db="${TESTBOOK_DEV_DB:-aimbase_bp_wes}"
exec ssh -i "$key" -o IdentitiesOnly=yes -o BatchMode=yes "$host" \
  "LC_ALL=C.UTF-8 PGPASSWORD=\$(docker exec aimbase-api-1 printenv DB_PASSWORD) psql -h localhost -U platform -d $db -X -q -A -F \"\$(printf '\t')\" -P footer=off -P null=NULL -v ON_ERROR_STOP=1"
