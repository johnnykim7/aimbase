# CR-173: CS 운영 이전 전용 AI 실행기와 위젯 경로

- 발번: 2026-11-09
- 원본 요구사항: `docs/origins/원본_요구사항_CR173_CS_운영_이전_실행기와_위젯_경로_20261109.md`
- 요청: 컨타 3plcore-22478 브리핑(11-09 05:05). bp-wes CR-WES-368 CS 수집과 AI 초안 운영 이전의 aimbase 몫
- 근거: bp-wes origin/dev `docs/협업/요청_CR-WES-368_운영_이전.md` aimbase 절, `docs/CR/CR-WES-368_CS_수집과_AI_초안_운영_이전_설계.md` 3절 3, 4, 5, 10번과 7절. 사용자 승인 2026-11-09 04:15 `/전승`(구현 준비 시작, 운영 반영은 건마다 승인)
- 앞 건: CR-162(운영 회사용 Aimbase 와 설치 스크립트), CR-WMS-907(실행기 읽기 전용 계정과 망), CR-172(위젯 파일 교차 출처)
- 규모: 소. 운영 구성 파일, 배포 스크립트, 설치 스크립트만 바꾼다. 새 창구, 화면, 테이블, 마이그레이션, Java 변경 없음
- 상태: 완료(2026-11-09 17:03 컨타 승인, 사용자 일괄 승인. 운영 chaws 실행기, 위젯 공개 주소 `ai.banpoom.co.kr`, 12절 OpenAI 401 수정, 13절 실행기 메모리 수정의 운영 반영과 확인 끝. 운영 화면 AI 질문 칸 대화 확인은 사용자가 따로 한다. 이후 운영 api 반영은 가지 `chaws-cr173-oneshot` 위에서 한다)

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

운영 BO2 CS 상세의 AI 질문 칸은 브라우저가 `https://ai.banpoom.co.kr` 로 직접 붙는다. `aimbase.banpoom.co.kr` 은 개발기(59) 관리 화면이 쓰고 있어 운영은 새 주소로 열었다(11-09 08:08 정정). bp-wes 서버가 부르는 창구는 모두 망 `aimbase-apps` 안에서 `http://aimbase-api:8080` 으로 가므로(bp-infra `a801e19`) 공개 주소에 열 필요가 없다.

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
6. BO2 의 위젯 주소는 bp-wes 응답값이고, bp-wes 의 `AIMBASE_WIDGET_BASE_URL=https://ai.banpoom.co.kr`, `AIMBASE_WIDGET_ORIGIN=https://admin.banpoom.co.kr` 는 bp-infra main `72025a8`(PR #182)에 있다. 처음 준비한 `757f2e0` 은 옛 주소이고 AI 켜기 `a801e19` 뒤에 있어 main 에 싣지 않았다.

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
| 10 | 위젯 열기 | 08:11 부터 08:14. 컨타 승인(사용자 08:08 「바로 적용」). Route 53 `banpoom.co.kr` 영역에 `ai` A `3.39.94.112`(TTL 300). 서버 nginx 에 `sites-available/ai.banpoom.co.kr.conf`(4절 두 경로만 통과, 나머지 404, http 는 https 로 넘김)와 Let's Encrypt 인증서(webroot, 2027-02-06 까지, 기존 certbot 타이머가 갱신). nginx 는 다시 읽기만 했다. `bootstrap widget wes-cs` 로 허용 주소 `https://admin.banpoom.co.kr`. bp-infra 위젯 주소는 main `72025a8`(PR #182, 합침 `8e2d320`), AI 켜기 `a801e19` 는 싣지 않았다 |

7절 1번부터 10번 중 이 창은 api, `aimbase-agent`, db, redis, `tts-sidecar` 를 다시 기동하지 않았다. 08:18 에 본 api 와 `tts-sidecar` 기동 시각은 07:35 부터 07:36 인데 이 창이 친 것이 아니다(다른 반영).

10번 밖(맥)에서 확인(08:12):

| 순번 | 확인 | 결과 |
|---|---|---|
| 1 | 위젯 파일 `/widget/v1/aimbase-chat.umd.global.js`, `.esm.js` | 200. 출처 `admin` 으로 부르면 허용 머리글이 붙는다 |
| 2 | 관리 API, 관리자 로그인, 토큰 발급, 실행기 등록, 서버 도구, 상태 점검, 연결, 워크플로우, `/` | 모두 404 |
| 3 | 대화 창구 토큰 없이 | 403(api 가 거부. nginx 는 통과) |
| 4 | 대화 창구 사전 확인 | 출처 `https://admin.banpoom.co.kr` 은 200 과 허용 머리글, 다른 출처는 403 |
| 5 | 인증서와 http | 인증서 이름 `ai.banpoom.co.kr`. http 는 301 로 https |

응답 스트림이 끊기지 않고 오는지는 실제 대화로만 볼 수 있어 bp-wes AI 켜기(설계 7절 9번) 뒤 운영 CS 상세에서 본다.

스트림 확인(10:33, AI 켜기 10:23 뒤): 서버 안에서 위젯 토큰을 받아 공개 주소로 대화 두 번을 보냈다(도착 시각만 기록, 토큰과 키는 화면에 남기지 않음).

| 순번 | 대화 | 결과 |
|---|---|---|
| 1 | 짧은 답(1부터 5) | 4.25초에 200 과 `text/event-stream`, 허용 머리글 `https://admin.banpoom.co.kr`. 답 조각 1개, 끝 신호 |
| 2 | 긴 답(970자) | 답 조각 1개가 60.55초, 끝 신호가 78.10초에 왔다. nginx 가 끝까지 모아 두지 않고 받는 대로 넘긴다 |

1. CLI 실행기 경로는 답을 한 조각으로 보낸다. 브라우저에는 답이 글자 단위가 아니라 한 번에 뜬다. 이 건이 바꾼 것이 아니다.
2. 10:23 이후 nginx 기록의 대화 요청은 10:29 서버 안 스크립트(`Python-urllib`) 1건과 위 두 건뿐이고, 브라우저가 위젯 파일을 받은 기록은 아직 없다. 운영 CS 상세 화면의 대화는 사람이 열어 볼 때 확인된다.

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
4. `ai.banpoom.co.kr` 의 DNS, 인증서, nginx(4절). 6.3 의 10 에서 이 창이 했다

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
| `72025a8` | bp-infra main(PR #182, 합침 `8e2d320`) | 운영 bp-wes 위젯 주소 두 줄(`ai.banpoom.co.kr`). AI 켜기는 꺼진 채 |
| `8a1fa049` | bp-wes origin/dev | CR-WES-368 설계서 결정 4와 사람 손 3번의 주소를 `ai.banpoom.co.kr` 로 |
| `be8dc23` | origin/dev | OpenAI 키가 비면 임베딩을 만들지 않고 응답 캐시 의미 비교를 건너뛴다(12절) |
| `4c1975c` | origin/chaws-cr173-embed | `be8dc23` 을 운영 api 판 `0e8fdae`(CR-171, 로컬 가지 `chaws-cr171`) 위에 얹었다 |
| `2b6c990` | origin/dev | 세션 없는 요청은 답한 뒤 CLI 실행기 프로세스를 닫는다(13절) |
| `e5c75e2` | origin/chaws-cr173-oneshot | `2b6c990` 의 코드와 시험만 `4c1975c` 위에 얹었다. 지금 운영 api 판이다. 다음 운영 api 반영은 이 가지 위에서 한다 |
| `0df152b` | origin/dev | 실행기 전체 Claude 프로세스 상한과 줄서기, 59 compose 상한, `deploy.sh agent-wes-cs`(14절) |
| `4b6a4b0` | origin/chaws-cr173-oneshot | 14절의 실행기 상한(CR-161 계정 폴더 닫기 없이), 묶음 요청 닫기, 운영 compose 상한, 시험 |

## 10. 남은 것

1. 8절 3(키를 bp-wes 로 옮기기). 8절 1, 2, 4 는 6.3 에서 끝났다
2. bp-infra 의 bp-wes AI 켜기(`a801e19`)는 설계 7절 9번 차례에 합친다. 그 가지 `cr-wes-368-cs-prod` 의 `757f2e0` 은 옛 주소라 main `72025a8` 이 대신하므로, 합칠 때 `a801e19` 만 담는다
3. 운영 CS 상세 화면에서 사람이 위젯 대화를 여는 확인(공개 주소 스트림은 6.3 에서 확인)
4. 13절 실행기 메모리 수정의 운영 api 반영. 2026-11-09 16:19 끝
5. 14절 운영 반영(CS 실행기, api)과 반영 뒤 14건 동시 확인

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
| 2026-11-09 08:08 | 사용자 「바로 적용」, 컨타 지시(7절 10번, 주소는 `ai.banpoom.co.kr`) |
| 2026-11-09 08:11 | Route 53 `ai` 등록, 서버 nginx 와 인증서, 허용 주소 |
| 2026-11-09 08:12 | 밖에서 확인(6.3 의 10) |
| 2026-11-09 08:14 | bp-infra 위젯 주소 main 합침(PR #182), bp-wes 설계서 주소 정정(`8a1fa049`) |
| 2026-11-09 10:31 | 컨타 알림으로 OpenAI 401 경고 원인 조사(12절) |
| 2026-11-09 10:33 | 공개 주소 스트림 확인 두 번(6.3) |
| 2026-11-09 10:36 | 컨타 승인(위임) 12절 방법 1, 운영 api 는 13시 |
| 2026-11-09 10:40 | 단위시험 7건 통과, 로컬 키 없이 기동 확인 |
| 2026-11-09 10:41 | 59 api 반영(메인 폴더 사본), origin/dev `be8dc23`, 운영 가지 `chaws-cr173-embed`(`4c1975c`) |
| 2026-11-09 13:04 | 컨타 승인 12절 운영 api 반영. 13:04:09 부터 13:05:26 까지 `CHAWS_COMMIT=4c1975c ./deploy.sh chaws-be`, api healthy |
| 2026-11-09 13:06 | 운영 세션 없는 대화 1회 뒤 `Retry error` 0건, 401 0건 |
| 2026-11-09 15:50 | 컨타 지시로 운영 CS 실행기 15:45 재시작 원인 조사(13절) |
| 2026-11-09 15:55 | 컨타 승인(위임) 13절 방법 1, 운영 반영 시점은 사용자 결정 |
| 2026-11-09 16:02 | 로컬 확인. 세션 없는 요청 두 건 모두 답한 뒤 실행기 프로세스 0 |
| 2026-11-09 16:07 | 단위시험 222건 통과 |
| 2026-11-09 16:11 | 59 api 반영(메인 폴더 사본에 이 수정과 12절 파일), origin/dev `2b6c990` |
| 2026-11-09 16:14 | 59 세션 없는 요청 1건 뒤 실행기 프로세스가 답한 그 초에 닫힘 |
| 2026-11-09 16:18 | 운영 api 재생성 현장 영향 판단. 분류대 음성은 저장분을 bp-wms 가 직접 주고 새 문장 생성만 늦어진다(오늘 17건). 차수 편성 추천 의견은 매시 50분에만 돌고 실패해도 편성은 그대로. 받아 적기와 재배치 AI 는 오늘 0건. 멈춤 없음으로 보고 바로 진행, 컨타 승인 |
| 2026-11-09 16:19 | 운영 api 반영 `e5c75e2`(가지 `chaws-cr173-oneshot`). 세션 없는 요청 뒤 프로세스 닫힘, 401 0건 |
| 2026-11-09 16:20 | 운영 실행기의 반영 전 요청분 2개가 유휴 회수로 닫혀 claude 0, 메모리 544MiB/1.5GiB |
| 2026-11-09 17:03 | 컨타 승인 완료 처리 |

## 12. 후속 2026-11-09 운영 OpenAI 401 경고

컨타 알림(10:31): 운영 CS AI 를 켠 뒤(10:23) aimbase 요청마다 OpenAI 키 없음 401 경고가 남는다. 본 흐름은 성공.

**원인**: 응답 캐시가 비슷한 질문 찾기(의미 비교)를 하려고 질문 글을 OpenAI 임베딩으로 보낸다(`ResponseCacheService.java:73` 조회, `:116` 저장). 운영 chaws api 에는 OpenAI 키가 없는데도 임베딩 부품이 만들어진다. 부품 생성 조건이 `@ConditionalOnProperty(name = "spring.ai.openai.api-key")` 이고(`EmbeddingConfig.java:17`), 이 조건은 값이 빈 문자열이어도 참이다(`application.yml:63` 의 `${OPENAI_API_KEY:}`). 그래서 「키가 없으면 임베딩 끔」 이라는 설계 의도(`EmbeddingService.java:14`, 기동 안내 문구)가 운영에서 작동하지 않는다. 기동 기록에 임베딩 끔 안내가 0건이다.

**범위**(10:15 부터 10:35 api 기록):

| 순번 | 항목 | 결과 |
|---|---|---|
| 1 | 경고 수 | 8건. 모두 `bp_wes` 의 대화 요청 4건(10:24 부터 10:27). 요청마다 조회 1, 저장 1 |
| 2 | 어떤 요청 | 세션 번호 없는 한 번짜리 요청(bp-wes CS 초안과 분류). 위젯 대화(10:29, 10:33)는 응답 캐시를 거치지 않아 경고가 없다 |
| 3 | 늦어짐 | 요청 앞에서 OpenAI 를 한 번 다녀온다. 0.1초 안팎, 첫 요청은 0.8초 |
| 4 | 밖으로 나가는 것 | 401 은 OpenAI 서버가 받은 뒤 거절한 것이라, 질문 글(CS 문의 내용이 든 프롬프트)이 키 없이 api.openai.com 까지 간다 |

**고치는 방법**

1. (권장) aimbase 코드: 키가 비어 있으면 임베딩 부품을 만들지 않게 조건을 고치고, 응답 캐시는 임베딩을 못 쓰면 의미 비교를 건너뛴다. 같은 질문 그대로 찾기(정확히 같은 질문)는 그대로 돈다. 바뀌는 파일 둘과 단위시험, 운영 api 반영(다시 기동) 1회
2. 운영에 OpenAI 키를 넣는다(잃는 것: CS 문의 글이 OpenAI 임베딩으로 나간다)
3. 경고 기록만 끈다(잃는 것: 글이 계속 OpenAI 로 가고 늦어짐도 남는다. 증상 숨기기)

관찰(이 건 밖, 확인 안 함): 응답 캐시는 모델과 마지막 질문 글만으로 같은 요청을 가린다(`OrchestratorEngine.java:325`, 시스템 안내는 `null`). bp-wes 가 같은 프롬프트를 60분 안에 다시 보내면 CLI 를 부르지 않고 앞 답을 돌려준다. CS 「다시 만들기」 같은 흐름이 같은 프롬프트를 보내는지는 bp-wes 쪽에서 봐야 한다.

**조치**(컨타 승인 10:36, 방법 1. 사용자 위임)

| 순번 | 바뀐 것 | 내용 |
|---|---|---|
| 1 | `EmbeddingConfig.java` | 생성 조건을 「키 값에 글자가 있을 때」(`OpenAiKeyPresent`)로 바꿨다. 빈 값, 공백, 설정 없음이면 임베딩을 만들지 않는다 |
| 2 | `ResponseCacheService.java` | 임베딩을 쓸 수 없으면 조회의 의미 비교와 저장 뒤 임베딩 만들기를 건너뛴다. 같은 질문 그대로 찾기는 그대로다 |
| 3 | `EmbeddingConfigTest`, `ResponseCacheServiceTest` | 4건과 3건. 빈 값이면 만들지 않음, 키가 있으면 만듦, 임베딩이 없으면 조회와 저장이 임베딩을 부르지 않음, 있으면 부름 |

확인

| 순번 | 어디 | 결과 |
|---|---|---|
| 1 | 단위시험(origin/dev 기준, 운영 가지 기준 각각) | 7건 통과 |
| 2 | 로컬 기동(8181, 작업 사본 Redis 16173, OpenAI 키 없음) | 10:40 기동 기록에 「EmbeddingModel not available」 안내가 나오고 정상 기동. 운영은 같은 조건에서 이 안내가 0건이었다 |
| 3 | 59(10:41, 메인 폴더 사본에 이 건 파일 넷만 더해 `./deploy.sh be`) | 건강 상태 200. 59 는 OpenAI 키가 있어 임베딩이 그대로 켜져 있고 동작이 바뀌지 않는다. 사본과 메인 폴더의 backend 차이는 이 건 파일 넷, 59 compose 는 사본과 같다 |
| 4 | 운영(13:04:09 부터 13:05:26, `CHAWS_COMMIT=4c1975c ./deploy.sh chaws-be`) | 운영 가지 `chaws-cr173-embed` 는 운영 api 판 `0e8fdae`(CR-171, 07:35 반영) 위에 이 건만 얹었다. api 만 다시 만들고 db, redis, 실행기 둘, tts 는 그대로. 반영 기록 `20261109-130409 api 4c1975c`, api healthy. 되돌리기 이미지 `aimbase-api:pre-20261109-130409` |
| 5 | 운영 기동 기록 | 「EmbeddingModel not available」 안내 1건(13:05:16) |
| 6 | 운영 세션 없는 대화 1회(13:05:49, 서버 안에서 bp-wes 키로 `cli-runner-bp-wes-cs-rr` 에 `stream:false`) | 200, 7.7초. 응답 캐시에 그 질문이 13:05:56 에 저장됐고 의미 비교용 값은 비어 있다. 즉 캐시 경로를 탔고 임베딩은 건너뛰었다. 13:06:33 까지 `Retry error` 0건, `NonTransientAiException` 0건 |

시험으로 남은 캐시 한 줄(질문 「연결 확인입니다」)은 만료 시각이 지나면 조회에 걸리지 않는다. 앞으로 CR-171 등 다른 건이 운영 api 를 다시 만들 때는 `chaws-cr173-embed` 위에서 지어야 이 수정이 빠지지 않는다.

## 13. 후속 2026-11-09 운영 CS 실행기 메모리 한도 재시작

컨타 지시(15:50): 운영 CS 실행기(`aimbase-agent-wes-cs`)가 15:45 에 재시작했다. 운영 수집기 첫 기동으로 밀린 문의 31건을 판별하던 중이었고, 판별 14건과 초안 3건이 실패했다.

**원인**: 세션 없이 온 요청도 실행기에서 Claude 프로세스를 하나 띄우고, 답한 뒤에 닫지 않는다. 프로세스는 실행기의 유휴 회수(15분, `RunnerProperties.reaperIdleThresholdSeconds`)까지 남는다. bp-wes 의 판별과 초안은 모두 세션 없는 요청이라, 문의가 몰리면 요청 수만큼 쌓인다. 실행기 메모리 한도는 1.5GiB 다. `AIMBASE_RUNNER_MAX_WORKERS`(2)는 한 요청 안의 작업자 상한이라 실행기 전체 수는 막지 못한다.

확인(운영 chaws, 읽기 전용):

| 순번 | 본 것 | 결과 |
|---|---|---|
| 1 | 커널 기록 15:45:24 | 실행기 컨테이너가 메모리 한도(1572864kB)에 닿아 java 가 강제 종료됐다. 그 순간 claude 프로세스 10개(대부분 각 170MB 안팎)와 java(270MB 안팎). Docker 의 메모리 부족 표시는 false 인데, 첫 프로세스인 docker-init 은 살아 있었기 때문이다. 종료 코드 0, 재시작 1회 |
| 2 | 실행기 기록 15:42:51 부터 15:45:24 | 프로세스 생성 10번, 닫힘 0번(`shutdown complete` 0건, 유휴 회수 0건) |
| 3 | 동시 실행 | 같은 초에 생긴 것은 많아야 2 |
| 4 | api 기록 15:45:24 부터 | 처리 중이던 요청 5건이 끊겼고(EOF), 두 연결(work01, work02)의 차단기가 15:50:25 까지 열려 그사이 요청이 모두 실패했다. 실패 17건 = 판별 14 + 초안 3 |
| 5 | 계정 한도 | work01 주간 88%(`allowed_warning`, 11-10 05:00 초기화), work02 허용. 거절 0건이라 원인이 아니다 |
| 6 | 15:52 | claude 3개(각 250MB 에서 300MB), 1.07GiB/1.5GiB. 몇 건 더 몰리면 다시 넘는다 |

**조치**(컨타 승인 15:55, 방법 1): api 가 세션 없는 요청에 답한 뒤 실행기에 그 세션의 프로세스를 닫으라고 보낸다. 신호는 워크플로우 AGENT_CALL 이 정상 완료 뒤 쓰는 것(CR-114 `cleanupSession`)을 그대로 쓴다. 일반 응답은 구조화 출력 재시도까지 끝난 뒤, 스트림 응답은 끝나는 자리에서 닫는다. 실패한 요청도 닫는다. 캐시로 답한 요청은 프로세스를 띄우지 않아 닫을 것이 없다. 세션이 있는 요청(위젯, 관리자 화면 대화)은 다음 턴에 이어 쓰도록 그대로 남긴다. 세션 없는 요청이 돌려받은 세션 번호로 이어 묻는 경우는 새 프로세스가 앞 대화를 처음부터 받아 이어 간다(느려질 뿐 답은 같다).

| 순번 | 파일 | 바뀌는 것 |
|---|---|---|
| 1 | `OrchestratorEngine.java` | 일반, 스트림 두 경로에서 세션 없는 요청이면 답한 뒤 `cleanupSession` |
| 2 | `OrchestratorOneShotWorkerTest.java` | 6건. 세션 없는 일반, 스트림 요청은 닫는다. 세션 있는 일반, 스트림 요청은 남긴다. 실패해도 닫는다. 캐시 응답은 부르지 않는다 |

확인:

| 순번 | 어디 | 결과 |
|---|---|---|
| 1 | 단위시험 | 새 6건 통과. orchestrator, llm, agent, session 묶음 36개 222건 통과 |
| 2 | 로컬(api 8181, 실행기 17147, 시험 테넌트 `cr159_local`, 연결 `cli-cr159-local`) | 세션 없는 일반 요청(16:02:43)과 스트림 요청(16:02:50) 모두 답한 그 초에 실행기 기록 `shutdown complete, closed 1 workers`, 실행기 아래 claude 수가 1 에서 0. 세션 있는 요청(16:02:57)은 1 이 남았다. 응답 4.4초, 3.3초, 2.9초 |
| 3 | 59(16:10:37 부터 16:11:17, 메인 폴더 사본에 이 수정과 12절 파일을 더해 `./deploy.sh be`) | 건강 상태 200. CS 실행기 연결 `cli-runner-bp-wes-cs-work01` 로 세션 없는 일반 요청 1건(16:14:05, 응답 5초). 답한 그 초에 실행기 기록 `shutdown complete, closed 1 workers`. 실행기 아래 남은 claude 3개는 반영 전 api 가 16:05, 16:06 에 띄운 것이고 유휴 회수 대상이다. CR-174 가 15:58 메인 폴더로 59 api 를 반영하면서 12절 수정이 빠졌던 것도 이번 반영으로 되살렸다 |
| 4 | 운영(16:18:24 부터 16:19:37, `CHAWS_COMMIT=e5c75e2 ./deploy.sh chaws-be`) | 운영 가지 `chaws-cr173-oneshot` 은 `chaws-cr173-embed`(`4c1975c`) 위에 이 수정과 시험만 얹었다(시험 26건 통과). api 만 다시 만들고 db, redis, 실행기 둘, tts 는 그대로. 반영 기록 `20261109-161824 api e5c75e2`, api healthy, 기동 15초. 세션 없는 일반 요청 1건(16:19:48, 연결 `cli-runner-bp-wes-cs-work01`) 뒤 그 초에 `shutdown complete, closed 1 workers`. 실행기 아래 남은 claude 2개는 반영 전 api 가 16:01 에 띄운 것이고 유휴 회수 대상, 실행기 메모리 902MiB/1.5GiB, api 401 0건 |

로컬 확인에 쓴 것: 로컬 master 의 시험 관리자 `cr173-local@platform.local`(비밀번호는 작업 창 임시 폴더에만 있다). `cr159_local` 시험 키 둘은 확인 뒤 회수했다.

## 14. 후속 2026-11-09 운영 CS 실행기 메모리 한도 종료 재발

컨타 지시(22:45, 브리핑 3PLcore `docs/40_브리핑/CS실행기동시실행_AIMBASE.md`): 13절 반영(16:19) 뒤에도 운영 CS 실행기가 22:15, 22:25 에 메모리 한도로 다시 종료됐다. bp-wes 는 동시 2 로 부르는데 Claude 가 8개 넘게 떠 있었다.

**원인**: 13절 수정은 연결 하나로 온 요청만 닫았다. bp-wes 의 판별과 초안은 연결 묶음 `cli-runner-bp-wes-cs-rr` 로 온다. 운영 가지 `chaws-cr173-oneshot` 은 CR-161 이전 바탕이라 묶음 경로가 실제로 부른 연결을 엔진에 돌려주지 않고, 엔진은 닫을 연결을 몰라 닫기를 건너뛰었다. 프로세스는 유휴 회수(15분)까지 남아 쌓였다. 13절 운영 확인(16:19:48)은 연결 하나로 보낸 요청이라 이 경로를 밟지 않았다. 한 요청이 여러 프로세스를 띄운 것도, 실제 동시 요청이 8건이었던 것도 아니다. 실행기에는 실행기 전체 프로세스 상한이 없어(`AIMBASE_RUNNER_MAX_WORKERS` 는 요청 하나 안의 상한) 닫기가 한 경로에서만 빠져도 한도를 넘는다. dev 계열(59)은 CR-161 이 묶음 경로도 `chatSingle` 로 돌려 `2b6c990` 이 닫는다.

확인(운영 chaws, 읽기 전용):

| 순번 | 본 것 | 결과 |
|---|---|---|
| 1 | CS 실행기 기록 16:19 부터 22:46 | 프로세스 생성 35번, 요청 뒤 닫힘 1번(13절 확인 요청), 유휴 회수 17번 |
| 2 | 재시작 | 실행기 기동 기록 22:15:24, 22:25:08. 컨테이너 재시작 누적 3 |
| 3 | 커널 기록 22:25 | 한도에 닿을 때 claude 9개와 java |
| 4 | 동시 실행 | 같은 초에 생긴 것은 많아야 2. 요청 하나에 프로세스 하나 |
| 5 | api 기록 | 판별과 초안은 묶음으로 오고 세션이 없다. 묶음 요청 뒤 실행기 닫기 신호가 없다 |

**조치**: 메모리 한도(1.5GiB)는 그대로 둔다.

| 순번 | 파일 | 바뀌는 것 |
|---|---|---|
| 1 | `ConnectionGroupSelector.java`(운영 가지) | 묶음 실행이 실제로 부른 구성원 연결을 알려 준다. 앞 구성원이 실패하고 다음으로 넘어가면 둘 다 알린다 |
| 2 | `OrchestratorEngine.java`(운영 가지) | 일반 경로가 부른 연결을 모두 모아 세션 없는 요청이면 답한 뒤 모두 닫는다 |
| 3 | `ClaudeCliWorkerPool.java` | 실행기 전체 Claude 프로세스 상한. 넘는 요청은 온 순서대로 줄을 서고, 기다리는 동안 요청이 쥐고 있지 않은 유휴 메인 워커를 오래 쉰 것부터 닫아 자리를 낸다. 대기 상한을 넘으면 실패로 돌려준다. 자리는 프로세스가 닫힌 뒤에 돌려받는다. 줄은 run 잠금 밖에서 선다(잠금을 쥔 채 기다리면 자리 내기와 서로 막힌다). dev 가지는 CR-161 의 계정 폴더 바뀜 닫기도 같은 자리에서 한다 |
| 4 | `ClaudeCliWorker.java` | 요청이 쥔 표시, 닫힘 알림, 첫 턴 표시를 워커에 둔다 |
| 5 | `RunnerService.java` | 턴이 끝나면 쥔 표시를 푼다. 첫 턴 여부를 run 이 아니라 워커에서 받는다. 유휴 회수나 자리 내기로 새 워커가 뜨면 그 워커에 대화 전체를 다시 넣는다 |
| 6 | `RunnerProperties.java`, `RunnerAutoConfiguration.java` | `AIMBASE_RUNNER_MAX_PROCESSES`(기본 0, 끔), `AIMBASE_RUNNER_PROCESS_QUEUE_TIMEOUT_SECONDS`(기본 300, api 가 실행기를 기다리는 시간과 같다) |
| 7 | `docker-compose.chaws.yml`, `docker-compose.prod.yml` | CS 실행기만 상한 2. claude 하나 170MB 에서 300MB, java 270MB 에서 310MB 라 둘이면 900MB 안팎 |
| 8 | `deploy.sh`(dev) | `agent-wes-cs`: 59 CS 실행기만 다시 올린다(`--no-deps`, api 와 다른 실행기는 그대로) |
| 9 | 시험 | 풀 7건(줄서기, 대기 초과, 유휴 자리 내기와 새 워커, 같은 run 재사용, 죽은 워커 자리 반환, 14건 몰림 상한, dev 는 계정 폴더 바뀜 1건 더), RunnerService 2건(쥔 표시 해제, 새 워커 첫 턴), 단발 워커 2건(묶음 요청은 부른 구성원 모두 닫는다, 세션 있는 묶음 요청은 남긴다) |

확인:

| 순번 | 어디 | 결과 |
|---|---|---|
| 1 | 단위시험(운영 가지) | 풀 17, RunnerService 6, 단발 워커 8 통과. 넓은 묶음 37개 295건 통과 |
| 2 | 단위시험(dev 가지) | 풀 18, RunnerService 6 통과 |
| 3 | 로컬(실행기 17147, 상한 2, 한 턴 4초짜리 가짜 CLI) | 서로 다른 run 14건 동시. 모두 200, 프로세스 최대 2, 4초마다 두 건씩 끝나 마지막 30초. 기동 기록 `process limit 2 per runner`, 자리 내기 12번 |
| 4 | 59 반영 전(23:08) | CS 묶음으로 세션 없는 짧은 요청 14건 동시. 모두 200(5초에서 7초). CS 실행기에 claude 14개가 함께 떠 메모리 최대 2.66GiB(운영 한도의 1.7배). 답한 뒤에는 모두 닫혔다(59 api 는 묶음도 닫는다) |
| 5 | 59 반영(23:09, 메인 폴더 사본에 이 수정을 얹고 compose 는 59 의 지금 파일에 상한 한 줄만 더해 `./deploy.sh agent-wes-cs`) | CS 실행기만 다시 만들었다. api 와 다른 실행기는 그대로. 기동 기록 `CR-173: process limit 2 per runner, queue timeout PT5M`. 등록은 두 번 실패한 뒤 2분째 성공 |
| 6 | 59 반영 뒤(23:12, 23:15) | 같은 14건 동시. 모두 200, 4초에서 32초(두 건씩 줄서기). 메모리 최대 870MiB. 실행기가 띄운 claude 는 최대 2. 0.2초 표본 한 번에 3개가 잡혔는데 셋째는 막 뜬 claude 가 1초 안 되게 낳은 자기 자식이었다(부모가 claude, 상태 R). 대기 초과 0 |

시험 문장이 같으면 응답 캐시가 답해 프로세스가 뜨지 않는다(23:11 의 한 번). 요청마다 문장을 달리했다.

**운영 반영**(컨타와 사용자 승인 뒤): 운영 가지 `chaws-cr173-oneshot` 의 `4b6a4b0` 으로 CS 실행기를 먼저, api 를 다음에 올린다. 실행기만 올라가도 묶음 요청이 닫히지 않는 동안 자리 내기가 상한을 지킨다.

1. `CHAWS_COMMIT=4b6a4b0 ./deploy.sh chaws-agent-wes-cs`
2. `CHAWS_COMMIT=4b6a4b0 ./deploy.sh chaws-be`(api 만 다시 만든다)
3. 같은 14건 동시 요청으로 프로세스 수와 메모리를 본다
