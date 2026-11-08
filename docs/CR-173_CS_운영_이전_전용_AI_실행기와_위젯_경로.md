# CR-173: CS 운영 이전 전용 AI 실행기와 위젯 경로

- 발번: 2026-11-09
- 원본 요구사항: `docs/origins/원본_요구사항_CR173_CS_운영_이전_실행기와_위젯_경로_20261109.md`
- 요청: 컨타 3plcore-22478 브리핑(11-09 05:05). bp-wes CR-WES-368 CS 수집과 AI 초안 운영 이전의 aimbase 몫
- 근거: bp-wes origin/dev `docs/협업/요청_CR-WES-368_운영_이전.md` aimbase 절, `docs/CR/CR-WES-368_CS_수집과_AI_초안_운영_이전_설계.md` 3절 3, 4, 5, 10번과 7절. 사용자 승인 2026-11-09 04:15 `/전승`(구현 준비 시작, 운영 반영은 건마다 승인)
- 앞 건: CR-162(운영 회사용 Aimbase 와 설치 스크립트), CR-WMS-907(실행기 읽기 전용 계정과 망), CR-172(위젯 파일 교차 출처)
- 규모: 소. 운영 구성 파일, 배포 스크립트, 설치 스크립트만 바꾼다. 새 창구, 화면, 테이블, 마이그레이션, Java 변경 없음
- 상태: 진행중(2026-11-09 07:17 운영 chaws 7절 1번부터 9번 끝. 10번 위젯 허용 주소와 nginx, DNS 는 설계 7절 13번 차례에 따로)

---

## 1. 배경

bp-wes 의 CS 수집과 AI 초안을 운영으로 옮긴다(CR-WES-368). 59 에서는 bp-wes 가 Aimbase 테넌트 `bp_wes` 의 연결 `cli-runner-bp-wes-cs-*` 로 CS 초안을 만들고, BO2 CS 상세의 AI 질문 칸이 위젯으로 같은 실행기에 붙는다. 운영 chaws Aimbase 에는 테넌트 `bp_wms` 와 실행기 `aimbase-agent`(`claude_ro`) 뿐이고, 위젯 허용 주소가 비어 있고, api 는 서버 안 `127.0.0.1:8280` 에만 열려 있다(CR-172 6.4).

59 의 기준값(11-09 읽기 전용 조회): CLI 허용 테넌트 `*`. 연결 `cli-runner-bp-wes-cs-001`, `-work01`, `-work02`, `-work03` 은 모두 `anthropic-cli`, 설정 `model=claude-sonnet-4-5`, `tool_mode=NATIVE`, `agent_name=cli-runner-bp-wes-cs`, `exposed_tools=[bash, get_current_time]`. 묶음 `cli-runner-bp-wes-cs-rr` 은 ROUND_ROBIN, 구성원 work01 부터 work03, 가중치 1.

## 2. 변경

| 순번 | 파일 | 변경 |
|---|---|---|
| 1 | `docker-compose.chaws.yml` | 실행기 `aimbase-agent-wes-cs`. 테넌트 `bp_wes`, 일꾼 2, `mem_limit 1536m`, `PGUSER wes_cs_ai`, 망 default 와 `bp-infra_default`, 폴더 `claude-accounts-wes-cs`, `workspace-wes-cs`, 포트 8298과 8299(망 안). 이미지 이름을 `aimbase-aimbase-agent-wes-cs` 로 따로 둔다. 비밀값 파일 `agent-wes-cs.env`, `agent-wes-cs-db.env` 는 `required: false` 라 처음 세우기 전에도 다른 서비스 배포가 멈추지 않는다 |
| 2 | `deploy.sh` | `chaws-init` 이 새 폴더 셋을 만든다. 새 대상 `chaws-bootstrap`(설치 스크립트만 서버에 둔다, api 를 다시 만들지 않는다), `chaws-agent-wes-cs`(폴더, 망, 키 파일을 점검하고 이 실행기만 다시 만든다). `chaws` 묶음은 그대로 api 와 `aimbase-agent` 다 |
| 3 | `scripts/chaws-bootstrap.sh` | 둘째 인자로 대상 `wms`(기본, 지금과 같음) 또는 `wes-cs`. 단계 `widget` 을 더했고 `smoke` 는 대상마다 다르다. CLI 허용 테넌트와 허용 주소는 덮어쓰지 않고 덧붙인다(`*` 면 그대로 두고, 이미 있으면 두 번 넣지 않는다) |
| 4 | `docs/guides/aimbase-ops-guide.md` | § 2-10 「bp-wes CS 실행기」, 「위젯 공개 주소」, v3.23.0 |

폴더를 나눈 까닭: `aimbase-agent` 의 계정과 작업 폴더에는 지난 대화와 산출물에 `claude_ro` 접속값과 조회 결과가 남아 있다. CS 실행기가 같은 폴더를 쓰면 개인정보 칸을 뺀 `wes_cs_ai` 로 나눈 의미가 없어진다.

59 와 다른 점은 둘이다. 계정은 work01, work02 둘이다(브리핑). 위젯 연결 `-001` 의 계정 폴더는 59 의 `local-agent-cs` 대신 `WIDGET_ACCOUNT`(기본 work01)다. 운영에는 `local-agent-cs` 폴더가 없다.

## 3. 설치 스크립트가 만드는 것 (대상 wes-cs)

`bash build/chaws-bootstrap.sh <단계> wes-cs`. 다시 돌려도 같은 결과다.

| 순번 | 단계 | 만드는 것 |
|---|---|---|
| 1 | `admin` | `llm.anthropic-cli.enabled-tenants` 에 `bp_wes` 를 더한다(운영은 `bp_wms,bp_wes` 가 된다) |
| 2 | `tenant` | 테넌트 `bp_wes` 와 그 DB. `agent-wes-cs.env`(실행기 호출 키, `bp_wes` 실행기 전용 키), `secrets/bp-wes-api-key`(bp-wes 가 부르는 소비앱 키) |
| 3 | `connections` | 실행기 안에서 로그인된 계정마다 `cli-runner-bp-wes-cs-<계정>`, 위젯 연결 `cli-runner-bp-wes-cs-001`, 묶음 `cli-runner-bp-wes-cs-rr`(ROUND_ROBIN). 설정은 1절의 59 값과 같다. 로그인된 계정이 없으면 아무것도 만들지 않고 멈춘다 |
| 4 | `widget` | `widget.allowed-origins` 에 `https://admin.banpoom.co.kr`(`WIDGET_ORIGIN` 으로 바꿀 수 있다) |
| 5 | `smoke` | 실행기의 DB 계정이 `wes_cs_ai` 인지 찍고, bp-wes 가 부르는 길(망 `aimbase-apps`, 소비앱 키, 묶음, `stream=false`)로 CLI 에게 `select current_user` 를 시켜 응답 코드와 본문을 찍는다 |

## 4. 위젯 통과 경로 (컨타 회신)

운영 BO2 CS 상세의 AI 질문 칸은 브라우저가 `https://aimbase.banpoom.co.kr` 로 직접 붙는다. bp-wes 서버가 부르는 창구는 모두 망 `aimbase-apps` 안에서 `http://aimbase-api:8080` 으로 가므로(bp-infra `a801e19`) 공개 주소에 열 필요가 없다.

| 순번 | 구분 | 경로 | 쓰는 것 |
|---|---|---|---|
| 1 | 통과 | `/widget/` | 위젯 파일(진입 파일, ESM, 청크). GET |
| 2 | 통과 | `/api/v1/chat/` | 대화 `completions`(응답 스트림), 멈춤 `{세션}/abort`, 첨부 `attachments` 올리기와 `attachments/{id}` 지우기, 누르고 말하기 `stt`, 산출물 `artifacts` 와 `artifacts/download`. 이 아래 창구는 이 일곱 개뿐이고 관리 창구가 없다 |
| 3 | 막음 | `/api/v1/sessions/issue-widget-token` | 위젯 토큰 발급. bp-wes 서버가 망 안에서 소비앱 키로 부르고, 브라우저는 bp-wes 의 `/api/v1/wes/aimbase/widget-token` 으로 받는다 |
| 4 | 막음 | `/api/v1/platform/`, `/api/v1/admin/`, `/api/v1/auth/` | 관리 API, 관리자 로그인 |
| 5 | 막음 | `/api/v1/agents/`, `/mcp/` | 실행기 등록, 서버 도구 |
| 6 | 막음 | 그 밖의 `/api/`, `/actuator/`, `/swagger-ui/`, `/v3/api-docs` | 연결, 워크플로우, 문서 검색, 상태 점검, API 문서 |

위젯이 쓸 수 있지만 열지 않는 경로가 둘이다. `/api/v1/workflows/runs/{id}/subscribe` 는 위젯의 워크플로우 모드 전용이고 BO2 는 대화 모드만 쓴다. `/api/v1/knowledge-sources/{id}/chunks/{id}` 는 문서 검색 근거 보기이고 운영 chaws 에는 문서 검색이 없다.

nginx 설정(api 는 서버의 `127.0.0.1:8280` 에만 열려 있다):

```nginx
location /widget/      { proxy_pass http://127.0.0.1:8280; }
location /api/v1/chat/ {
    proxy_pass http://127.0.0.1:8280;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    gzip off;
    proxy_read_timeout 330s;
    client_max_body_size 100m;
}
location / { return 404; }
```

1. 대화 응답이 스트림이라 버퍼링, 캐시, 압축을 끈다. 대화 대기는 300초(`SseEmitter`)라 읽기 제한을 그보다 길게 둔다.
2. 업로드 상한은 api 와 같은 100MB 다.
3. 교차 출처 머리글은 api 가 붙인다. nginx 에서 더하면 두 번 붙어 브라우저가 막는다.
4. nginx 가 컨테이너면 망 `aimbase-apps` 에 합류해 `http://aimbase-api:8080` 으로 보낸다.
5. 허용 주소 `https://admin.banpoom.co.kr` 는 설치 스크립트 `widget` 단계가 넣는다. api 는 이 값을 5분까지 묵혀 읽는다.
6. BO2 의 위젯 주소는 bp-wes 응답값이고, bp-wes 의 `AIMBASE_WIDGET_BASE_URL=https://aimbase.banpoom.co.kr`, `AIMBASE_WIDGET_ORIGIN=https://admin.banpoom.co.kr` 는 bp-infra `757f2e0` 에 준비돼 있다.

## 5. 설계서

규모 소라 T1, T2, T3 는 고치지 않는다. 운용 가이드 § 2-10 에 세우는 순서, 비밀값 파일, 위젯 공개 주소를 적었다.

## 6. 확인

### 6.1 로컬 (11-09 05:16 부터 05:23)

맥 로컬 도커에 운영 구성 복사본(`docker-compose.chaws.yml`, 프로젝트 `cr173chaws`)을 기동해 설치 스크립트와 배포 사전 점검을 돌렸다. 운영 서버와 운영 DB 에는 붙지 않았다. `bp-postgres` 는 가짜 컨테이너를 망 `bp-infra_default` 에 두었다. 이미지는 11-07 로컬 이미지 사본이다(이 건은 Java 변경이 없다).

| 순번 | 확인 | 결과 |
|---|---|---|
| 1 | `admin` wms, 그다음 `admin` wes-cs | CLI 허용 테넌트 `bp_wms`, 그다음 `bp_wms,bp_wes`. 새 DB 에서 wes-cs 만 돌리면 `bp_wes` |
| 2 | `tenant` wes-cs | 테넌트 `bp_wes` 생성(201), 키가 `agent-wes-cs.env`, `secrets/bp-wes-api-key` 에 들어감 |
| 3 | 배포 사전 점검 | 폴더, 망, 키 파일 점검 통과. DB 비밀번호 파일이 없으면 경고 |
| 4 | 실행기 | 건강 상태 정상, `Agent registered successfully: name=cli-runner-bp-wes-cs`. 메모리 한도 1536MB, 망 default 와 `bp-infra_default`, 폴더 `-wes-cs` 둘, `PGUSER=wes_cs_ai` |
| 5 | `connections`, 로그인 없음 | 아무것도 만들지 않고 안내와 함께 멈춤 |
| 6 | `connections`, work01 만 로그인 | `-work01`, `-001`, 묶음 생성 |
| 7 | `connections`, work01 과 work02 | `-work02` 추가, 묶음 구성원 갱신(200) |
| 8 | 연결 설정 | 1절의 59 값과 같다. `-001` 의 계정 폴더만 work01 |
| 9 | 연결 이름 | `bp-wes CS CLI work01`, `bp-wes CS CLI work02`, `bp-wes CS 위젯 work01`, 묶음 `bp-wes CS CLI 계정 묶음` |
| 10 | `widget` wes-cs | 빈 값은 `https://admin.banpoom.co.kr`, `3201` 이 있으면 뒤에 덧붙임, 다시 돌려도 한 번만, `*` 는 그대로. wms 대상은 주소가 없어 멈춤 |
| 11 | 교차 출처 사전 요청 | `https://admin.banpoom.co.kr` 은 200 과 허용 머리글, `http://evil.example.com` 은 403 |
| 12 | `smoke` wes-cs | 실행기 DB 계정 `wes_cs_ai`. 대화가 묶음, 계정 work02 와 work01, 실행기, CLI 까지 갔고 CLI 가 `Not logged in` 으로 답했다(로컬에는 실제 로그인이 없다). 응답 코드 500 |
| 13 | wms 대상 되풀이 | `connections` wms 가 지금과 같게 `AIMBASE`, `get_current_time`, 묶음 `bp-wms CLI 계정 묶음` |
| 14 | 다시 돌리기 | 모든 단계가 같은 결과 |

정리: 시험 묶음 둘을 볼륨째 내렸고 가짜 `bp-postgres` 와 시험 이미지 이름을 지웠다. 시험 중 망을 빌려 쓴 로컬 `cr162local-api-1` 은 망 `aimbase-apps`(별칭 `api`, `aimbase-api`)에 다시 붙여 재기동했고 건강 상태 정상, `127.0.0.1:8280` 응답을 확인했다.

### 6.2 59

해당 없음. 바뀐 파일은 운영 chaws 구성과 그 배포, 설치 스크립트뿐이고 59 의 구성과 api 는 그대로다.

### 6.3 운영 chaws (11-09 06:56 부터)

반영: 컨타 승인(사용자 06:53 「운영에 올릴건 올리세요」). 7절 1번부터 6번, 5번 `wes_cs_ai` 도 이 창이 맡았다. 7번 Claude 로그인부터는 사람 차례다.

반영 전(읽기 전용, 06:56): 서비스 다섯(api, `aimbase-agent`, db, redis, `tts-sidecar`) 정상. 마지막 api 반영은 `0d6a394`. CLI 허용 테넌트 `bp_wms`, `widget.allowed-origins` 빈 값, 테넌트 `bp_wms` 하나. 운영 3PL DB 에 `wes_cs_ai` 없음(`claude_ro` 만 있음). 망 `aimbase-apps`, `bp-infra_default` 있음.

| 순번 | 단계 | 결과 |
|---|---|---|
| 1 | `chaws-init` | 처음 친 판은 실패했다. 기존 실행기 폴더가 999 소유라 서버 계정의 `mkdir -p claude-accounts/work01` 이 권한 거절로 멈췄고, 새 폴더는 서버 계정 소유로만 생겼다. 폴더를 root 컨테이너 안에서 만들게 고쳐(`cfee62c`, 운영 가지 `34c9e9c`) 다시 쳤다. 새 폴더 셋이 999 소유, 기존 폴더와 비밀값 파일, 서비스는 그대로 |
| 2 | `chaws-bootstrap` | 서버 `build/chaws-bootstrap.sh` 가 `991309b` 것과 같다(sha256 앞 16자리 대조) |
| 3 | `admin wes-cs` | CLI 허용 테넌트 `bp_wms,bp_wes` |
| 4 | `tenant wes-cs` | 테넌트 `bp_wes` 만들기 201. `agent-wes-cs.env` 에 키 셋, `secrets/bp-wes-api-key`. 둘 다 600 |
| 5 | `wes_cs_ai` | bp-wes `docs/자산/CR-WES-332_wes_cs_ai_계정.sql`(origin/dev)을 bp-postgres 소유자 계정으로 돌렸다. 비밀번호는 서버에서 만들어 `agent-wes-cs-db.env`(600)에만 두었고 화면과 맥에 남기지 않았다. 개인정보 칸 8개 확인이 모두 거부, 21개 표 519칸(설계 수와 같다), 접속 3개, 읽기 전용, 조회 30초 |
| 6 | `chaws-agent-wes-cs` | 06:59 부터 07:02. 실행기 `aimbase-agent-wes-cs` 건강 상태 정상, `Agent registered successfully: name=cli-runner-bp-wes-cs`. 메모리 한도 1536MB, 망 `aimbase_default` 와 `bp-infra_default`, 폴더 `claude-accounts-wes-cs`, `workspace-wes-cs`. 배포 기록 `aimbase-agent-wes-cs 991309b`. 실행기 안 `psql` 은 `wes_cs_ai` 로 붙어 허용 표는 읽고(`wms.warehouses` 2행), 고객 전화 칸은 권한 거부, 쓰기는 읽기 전용 거부 |

| 7 | Claude 로그인 | 사람이 work01, work02 로그인(07:13 알림). 실행기 안 두 폴더에 로그인 파일이 있음을 확인. work03 폴더는 없다 |
| 8 | `connections wes-cs` | 07:14. 로그인된 계정 `work01 work02`. 연결 `cli-runner-bp-wes-cs-work01`, `-work02`, 위젯 `-001`, 묶음 `cli-runner-bp-wes-cs-rr`(ROUND_ROBIN, work01 과 work02) 모두 201. 연결 설정은 1절의 59 값과 같다(`-001` 의 계정 폴더만 work01) |
| 9 | `smoke wes-cs` | 07:14 1차는 응답 200 이지만 CLI 가 「bash 도구를 쓸 수 없다」로 답했다. 시험 질문에 소문자 「bash 로」 라고 써서 모델이 CLI 도구 `Bash` 대신 없는 이름 `bash` 를 불렀다. 실행기 기동 명령과 CLI 판(2.1.197)은 59 와 같고, 59 의 CS 요청은 10일간 `Bash` 를 507번 정상으로 썼다. 질문을 「Bash 도구로」 로 고쳐(`102f711`, 운영 가지 `08f1abc`) 서버 스크립트를 바꾸고 07:17 2차: 응답 200, CLI 가 `Bash` 로 `psql` 을 돌려 `wes_cs_ai` 를 답했다. 1차는 work01, 2차는 work02 로 가 두 계정 로그인이 모두 산다 |

api, `aimbase-agent`, db, redis, `tts-sidecar` 는 다시 기동하지 않았다(반영 뒤 가동 시간 그대로). 10번(위젯 허용 주소, nginx, 인증서, DNS)은 하지 않았다.

관찰: 기본 안내문(요청에 시스템 안내가 없을 때)은 노출 도구를 Aimbase 이름 `bash` 로 적는다. NATIVE 모드 CLI 의 도구 이름은 `Bash` 라 2차에서도 모델이 도구 찾기를 다섯 번 한 뒤 `Bash` 를 썼다. bp-wes CS 요청은 자기 안내문을 실어 59 에서 바로 `Bash` 를 쓰므로 이 건의 범위 밖이다.

## 7. 운영 반영 순서 (승인 뒤, 컨타가 부른다)

운영 가지 `chaws-cr173`(`991309b`). 운영 반영본 `0d6a394`(`chaws-cr172`)에 CR-162 의 설치 스크립트 변경 `61a1ac1` 과 이 건을 얹었다. `0d6a394` 와의 차이는 `deploy.sh`, `docker-compose.chaws.yml`, `scripts/chaws-bootstrap.sh` 와 CR-162 가 고친 운용 가이드 몇 줄뿐이고 api 소스는 같다. 세 파일은 origin/dev `1a9c948` 과 같다.

CR-162 `61a1ac1`(운영 가지에서는 `3de6f47`)을 함께 싣는 까닭: 이 커밋은 10-27 부터 origin/dev 에 있지만 운영 반영본 줄(`chaws-cr164`, `chaws-cr172`)에는 없다. 설치 스크립트의 `connections` 단계가 실행기 안에서 로그인된 계정 폴더를 세어 연결과 묶음 구성원을 만드는 것이 이 커밋이다. 이 건의 스크립트는 그 위에서 고쳤고 로컬 시험(6.1 의 5 부터 7)도 그 판으로 했다. 빼면 계정 목록이 `work01 work02 work03` 고정으로 돌아가 CS 실행기에 없는 work03 연결이 묶음에 들어가고, 차례가 오면 로그인 없음으로 실패한다. 빼려면 이 건의 스크립트를 옛 판 위에서 다시 짜고 다시 시험해야 한다. 설치 스크립트는 손으로 돌릴 때만 쓰여 api 와 실행기에는 닿지 않는다. bp-wms 대상 `connections` 를 다시 돌리면 `aimbase-agent` 에 로그인된 계정만 구성원이 된다.

배포는 메인 폴더가 아니라 origin/dev 기준 작업 폴더(`_worktrees/aimbase-cr173`)에서 친다. 메인 폴더 로컬 dev 의 `deploy.sh` 에는 새 대상이 아직 없다.

| 순번 | 할 것 | 운영 쓰기 | 설계 7절 |
|---|---|---|---|
| 1 | `./deploy.sh chaws-init`(새 폴더 셋. 있는 파일은 그대로) | 폴더 | 5 |
| 2 | `CHAWS_COMMIT=991309b ./deploy.sh chaws-bootstrap` | 스크립트 파일 | 5 |
| 3 | `bootstrap admin wes-cs` | 설정 한 줄 | 5 |
| 4 | `bootstrap tenant wes-cs` | 테넌트, DB, 키 | 5 |
| 5 | 사람: `agent-wes-cs-db.env` 에 `PGPASSWORD`(wes_cs_ai) | 파일 | 5 |
| 6 | `CHAWS_COMMIT=991309b ./deploy.sh chaws-agent-wes-cs` | 실행기 하나 기동 | 5 |
| 7 | 사람: Claude 로그인 work01, work02 | 계정 폴더 | 5 |
| 8 | `bootstrap connections wes-cs` | 연결 셋, 묶음 | 5 |
| 9 | `bootstrap smoke wes-cs` | 대화 한 번 | 5 |
| 10 | `bootstrap widget wes-cs`, nginx, 인증서, DNS | 설정 한 줄 | 13 |

`bootstrap <단계> wes-cs` 는 `ssh chaws 'cd /home/ubuntu/aimbase && bash build/chaws-bootstrap.sh <단계> wes-cs'` 다. 어느 단계도 api 와 `aimbase-agent` 를 다시 기동하지 않는다.

되돌리기: 실행기는 `docker compose stop aimbase-agent-wes-cs` 후 `rm -f`. compose 는 `deploy-backup` 에 직전본이 있다. 설정 두 줄(`enabled-tenants`, `widget.allowed-origins`)은 원래 값으로 고친다. 테넌트와 연결은 남겨도 다른 테넌트에 닿지 않는다.

## 8. 사람 손 작업

1. Claude 로그인 두 번(work01, work02). `ssh -t chaws 'cd /home/ubuntu/aimbase && docker compose exec -e CLAUDE_CONFIG_DIR=/home/appuser/.claude-accounts/work01 aimbase-agent-wes-cs claude'`, work02 도 같다
2. 운영 3PL DB 에 `wes_cs_ai` 계정(bp-wes `docs/자산/CR-WES-332_wes_cs_ai_계정.sql`)을 만들고 그 비밀번호를 chaws `agent-wes-cs-db.env` 에 둔다
3. `secrets/bp-wes-api-key` 값을 bp-infra `env/bp-wes.env` 의 `AIMBASE_API_KEY` 로 옮긴다
4. `aimbase.banpoom.co.kr` 의 DNS, 인증서, nginx(4절)

## 9. 커밋

| 커밋 | 어디 | 내용 |
|---|---|---|
| `1a9c948` | origin/dev | 구성, 배포, 설치 스크립트, 운용 가이드 |
| `e8a1293` | origin/dev | 운용 가이드 위젯 공개 주소 절의 대화 창구 설명 |
| `749287a` | origin/dev | 이 문서, 원본, 대장 한 줄. 메인 폴더 로컬 dev 에도 같은 커밋(`77d25f1`)이 있다 |
| `3de6f47` | origin/chaws-cr173 | origin/dev 에 있는 CR-162 `61a1ac1` 을 운영 가지에 얹었다(까닭은 7절). CR-162 문서 충돌은 운영 가지 쪽을 두었다 |
| `cfee62c` | origin/dev | 운영 초기화가 실행기 폴더를 root 컨테이너 안에서 만든다(6.3 의 1) |
| `34c9e9c` | origin/chaws-cr173 | `cfee62c` 를 운영 가지에 얹었다. 맥에서 도는 배포 스크립트만 바뀌고 서버에 가는 구성과 설치 스크립트는 `991309b` 와 같다 |
| `102f711` | origin/dev | 설치 스크립트 시험 질문을 「Bash 도구로」 로(6.3 의 9) |
| `08f1abc` | origin/chaws-cr173 | `102f711` 을 운영 가지에 얹었다. 서버 `build/chaws-bootstrap.sh` 는 이 판이다 |
| `991309b` | origin/chaws-cr173 | `1a9c948` 을 얹었다. 운용 가이드 충돌은 운영 가지 쪽을 두었다(문서는 origin/dev 가 정본) |

## 10. 남은 것

1. 7절 10번 `bootstrap widget wes-cs`, nginx, 인증서, DNS 는 설계 7절 13번 차례에 컨타가 사용자 승인을 받아 부른다
2. 8절 3, 4(키를 bp-wes 로 옮기기, DNS와 인증서와 nginx). 8절 1 로그인과 2 `wes_cs_ai` 는 6.3 에서 끝났다
3. bp-infra 의 bp-wes 연결 켜기(`a801e19`)와 위젯 주소(`757f2e0`)는 bp-infra 몫이다

## 11. 경과

| 시각 | 한 것 |
|---|---|
| 2026-11-09 05:05 | 브리핑 받음, 착수 |
| 2026-11-09 05:09 | 59 `bp_wes` 연결과 묶음 설정 읽기 전용 조회 |
| 2026-11-09 05:16 | 로컬 운영 구성 복사본 시험 1회차 |
| 2026-11-09 05:23 | 연결 이름 수정 후 2회차, 정리와 `cr162local-api` 재기동 |
| 2026-11-09 05:28 | origin/dev `1a9c948`, 운영 가지 `chaws-cr173`(`991309b`) |
| 2026-11-09 05:33 | CR 문서, 원본, 대장, 위젯 통과 경로 회신 |
| 2026-11-09 05:40 | 컨타 지시로 운영 가지의 CR-162 커밋 까닭을 7절에 적고, 문서 커밋을 origin/dev 에 올림 |
| 2026-11-09 06:53 | 사용자 「운영에 올릴건 올리세요」, 컨타 승인(7절 1번부터 6번) |
| 2026-11-09 06:57 | 운영 `chaws-init` 실패, 폴더 만드는 곳을 고쳐(`cfee62c`) 다시 침 |
| 2026-11-09 06:58 | 운영 설치 스크립트, `admin`, `tenant` wes-cs, `wes_cs_ai` |
| 2026-11-09 07:02 | 운영 실행기 `aimbase-agent-wes-cs` 반영과 확인(6.3) |
| 2026-11-09 07:13 | 사람이 work01, work02 로그인, 컨타 승인(7절 8번, 9번) |
| 2026-11-09 07:14 | 운영 연결 셋과 묶음, 시험 1차(질문 문구 탓에 도구 못 씀) |
| 2026-11-09 07:17 | 시험 질문 고침(`102f711`), 시험 2차 통과 |
