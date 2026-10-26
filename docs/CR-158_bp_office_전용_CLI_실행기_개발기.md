# CR-158: bp-office 전용 CLI 실행기 개발기

- 발번: 2026-10-26
- 원본 요구사항: `docs/origins/원본_요구사항_CR158_bp_office_전용_CLI_실행기_20261026.md`
- 요청: bp-office Sprint 4 실행맵(`bp-office/docs/기록/실행맵_Sprint4_7_9.md`) 9번 행. 사용자 10/26 05:25 「aimbase 레포 워커가 CR 로 진행」, 05:26 「지금 한도가 없어서 개발은 해놓되 테스트는 오늘 오후에」
- 결정: 컨타 [승인] 10/26 20:36(사용자 위임 10/25 22:39)
  - 범위 ①: 실행기와 bp_office 음성 변환 연결(둘 다 개발기 설정)은 이 CR. 사진과 글 없는 스캔 PDF 의 그림 읽기는 소스 수정이라 새 CR 로 나눈다
  - 계정 ①: 실행기는 기존 테넌트 실행기들처럼 입찰 실행기의 Claude 계정 폴더(`./claude-accounts`)를 같이 쓴다
- 규모: 소. 실행기 설정 한 벌과 개발기 설정. 새 API, 화면, 테이블, 소스 코드 변경이 없다
- 상태: 진행(개발기 반영은 컨타 신호 뒤)

---

## 1. 문제

1. bp-office CR-015 의 결정 추출 워크플로우는 Claude CLI 연결(NATIVE)로 돈다. 실행기 등록은 테넌트 DB 단위라 bp_office 에 붙은 실행기가 없으면 그 연결을 받을 곳이 없다. 다른 테넌트 실행기는 빌려 쓸 수 없다
2. bp-office CR-014 의 녹취 변환이 「활성 stt_local 커넥션이 없다」로 실패한다. 개발기 bp_office 테넌트에 음성 변환 연결이 없다

## 2. 개발기

| 항목 | 값 |
|---|---|
| 서버 | 59.8.160.12 `/home/therecommerce/aimbase`. 이 레포에서는 「옛 서버」라 부르고, 레포의 `docker-compose.prod.yml` 을 서버 `docker-compose.yml` 로 둔다 |
| bp-office 가 부르는 주소 | `http://59.8.160.12:8280` |
| 건드리지 않는 곳 | 우진비드 AWS(`/opt/wjbid/aimbase`), 다른 테넌트 실행기 6개와 api |

## 3. 이름

| 항목 | 값 |
|---|---|
| 테넌트 | `bp_office` (CR-015 가 만듦) |
| 실행기 서비스 | `aimbase-agent-bp-office` |
| 실행기 이름(`AGENT_NAME`, CLI 연결 `config.agent_name`) | `cli-runner-bp-office` |
| 실행기 키 이름(서버 `.env`) | `BP_OFFICE_SERVER_MCP_API_KEY` |
| 실행기 키 master `api_keys` 행 | `bp-office 실행기 MCP 키 (CR-158)`, 테넌트 `bp_office`, domain_app `bp_office`(테넌트 행과 같게), id `e8b1f8ef` |
| 실행기 키 사본 | `~/.claude-secrets/bp-office-aimbase-runner.env` (같은 이름의 한 줄) |
| 컨테이너 안 포트 | 실행기 8302, MCP 8303 |
| CLI 연결 | `cli-runner-bp-office-001` (`adapter=anthropic-cli`, `tool_mode=NATIVE`) |
| 음성 변환 연결 | `stt-local-mac-001` (`type=stt_local`, bp_wes 의 같은 이름 연결 설정을 옮김) |
| Claude 계정 | 입찰 실행기와 같은 `./claude-accounts` |

## 4. 레포 변경

| 파일 | 내용 |
|---|---|
| `docker-compose.prod.yml` | `aimbase-agent-bp-office` 서비스. lexflow 실행기와 같은 틀에 이름, 테넌트, 포트, 키 이름만 다르다. 키 기본값 없음(값이 없으면 compose 가 멈춘다, CR-155) |
| `.env.prod.template` | `BP_OFFICE_SERVER_MCP_API_KEY=` (이름만) |
| `deploy.sh` | `AGENT_SERVICES` 에 `aimbase-agent-bp-office`. 머리 주석의 「러너 전체 4개」를 그 목록 참조로 고침 |
| 운용 가이드 v3.14.0 § 2-9 | 실행기 키 표에 `BP_OFFICE_SERVER_MCP_API_KEY`, 테넌트 실행기를 새로 붙이는 순서 |

CLI 사용 테넌트 목록(`global_config` 의 `llm.anthropic-cli.enabled-tenants`)은 레포가 아니라 개발기 master DB 값이라 5절에서 바꾼다. 이 목록은 CLI 연결 시험과 연결을 고르지 않은 채팅이 본다(`ConnectionAdapterFactory.isCliEnabledForCurrentTenant`).

## 5. 개발기 반영 순서

컨타 신호 전에는 서버에 쓰지 않는다. 원격 재기동과 DB 쓰기 직전에 컨타에 `[승인요청]` 을 올리고 `[승인]` 을 받는다. 키 값은 화면, 기록, 메시지에 찍지 않는다.

### 5.0 먼저 읽기만 한다

1. 서버 `docker-compose.yml` 이 이 CR 직전 레포의 `docker-compose.prod.yml` 과 같은지 비교한다. 다르면 멈추고 컨타에 알린다(서버에서 손으로 고친 것을 덮지 않는다)
2. 서버 `backend/aimbase-agent/build/libs/` 의 실행기 jar 가 CR-156 배포 뒤 것인지 시각을 본다. 새 서비스 이미지는 서버에 있는 jar 로 만든다. 맥의 jar, Dockerfile 은 보내지 않는다
3. master `global_config` 의 `llm.anthropic-cli.enabled-tenants` 값
4. master `api_keys` 에서 다른 실행기 전용 키 행(lexflow CR-155 행)의 `scope`, `domain_app` 모양
5. lexflow 테넌트 CLI 연결의 `config` 키 목록과 다른 테넌트 `stt_local` 연결의 `config` 키 목록(값은 찍지 않는다)
6. 맥 전사 사이드카(8291)가 떠 있는지

### 5.1 서버 환경 파일

1. `.env` 와 `docker-compose.yml` 을 `deploy-backup/` 에 날짜 붙여 복사한다
2. 실행기 키 발급: 플랫폼 관리자로 `POST /api/v1/platform/api-keys` 에 `{"name": "bp-office-runner (CR-158)", "domainApp": "bp-office", "tenantId": "bp_office", "scope": <5.0-4 와 같은 모양>}`. 응답의 `apiKey` 는 서버 안에서 받아 바로 `.env` 에 `BP_OFFICE_SERVER_MCP_API_KEY=` 로 붙인다. 같은 값을 `~/.claude-secrets/bp-office-aimbase-runner.env` 에 둔다. bp-office 앱 키(`OFFICE_AIMBASE_API_KEY`)와 같이 쓰지 않는다(운용 가이드 § 2-9)
3. 서버 `.env` 에 들어간 키 이름만 확인한다: `grep -o '^BP_OFFICE_SERVER_MCP_API_KEY=' .env`

### 5.2 CLI 사용 테넌트 목록

1. 5.0-3 의 값이 `*` 이면 그대로 둔다
2. 아니면 `PUT /api/v1/platform/settings` 로 `llm.anthropic-cli.enabled-tenants` 끝에 `,bp_office` 를 더한다. 이 API 는 설정 캐시도 비운다

### 5.3 실행기 기동

1. 레포 `docker-compose.prod.yml` 을 서버 `docker-compose.yml` 로 보낸다
2. `docker compose config --services` 에 `aimbase-agent-bp-office` 가 나오는지 본다
3. 그 서비스만 만든다: `docker compose build aimbase-agent-bp-office` 뒤 `docker compose up -d --no-deps aimbase-agent-bp-office`. `./deploy.sh agents` 는 쓰지 않는다(다른 실행기 6개와 api 까지 다시 만든다)

### 5.4 연결 두 개

bp_office 테넌트 관리자(`~/.claude-secrets/bp-office-aimbase.env` 의 관리자 계정)로 `POST /api/v1/connections` 를 부른다.

1. CLI 연결 `cli-runner-bp-office-001`: `adapter=anthropic-cli`, `type=LLM`, `config` 는 lexflow CLI 연결과 같은 키에 `agent_name=cli-runner-bp-office`, `tool_mode=NATIVE`, `runner_api_key` 는 서버 `.env` 의 `RUNNER_API_KEY` 값(서버 안에서 읽어 넣는다)
2. 음성 변환 연결: `type=stt_local`, `config` 의 `base_url`, `api_key`, `timeout_seconds` 는 다른 테넌트 `stt_local` 연결 값을 서버 안에서 옮긴다

### 5.5 확인

| 순번 | 무엇 | 기대 |
|---|---|---|
| 1 | `docker compose ps aimbase-agent-bp-office` | healthy |
| 2 | 실행기 기록 | `Aimbase registration failed` 줄 없음, 등록 성공 |
| 3 | bp_office 테넌트 DB `agent_registry` | `cli-runner-bp-office` 행, 실행기 가능 표시, 최근 상태 알림 |
| 4 | api 와 다른 실행기 6개의 시작 시각 | 5.3 전과 같다(다시 만들어지지 않음) |
| 5 | `POST /api/v1/connections/cli-runner-bp-office-001/test` | 정상 |
| 6 | 그 CLI 연결로 짧은 채팅 1회(`POST /api/v1/chat/completions`, `connection_id` 지정) | 응답이 오고 실행기 기록에 그 요청이 있다 |
| 7 | 짧은 오디오로 `POST /api/v1/transcribe-jobs` 뒤 조회 | `COMPLETED` |

6, 7 은 Claude 한도와 맥 사이드카에 기대므로 오후 신호 뒤에 한다(사용자 05:26).

### 5.6 알림

- 실행기와 CLI 연결: 컨타, bp-office CR-015, CR-017 창
- 음성 변환 연결: 반영 즉시 bp-office CR-014 창(컨타 20:36)

### 5.7 되돌리기

1. `docker compose stop aimbase-agent-bp-office` 뒤 `docker compose rm -f aimbase-agent-bp-office`
2. `deploy-backup/` 의 `.env`, `docker-compose.yml` 을 되돌린다
3. 발급한 키 행을 끈다(`is_active=false`), 만든 연결 두 개를 지운다, `enabled-tenants` 를 5.0-3 값으로 되돌린다

## 6. 이 CR 밖

- 사진과 글 없는 스캔 PDF 의 그림 읽기(워크플로우 도구 단계가 그림 블록을 넘기게): 새 aimbase CR(컨타 20:36 범위 ①)
- 추출 워크플로우 등록과 실제 추출 호출: bp-office CR-015

## 7. 진행

| 날짜 | 내용 |
|---|---|
| 2026-10-26 | 착수 20:32. 범위, 계정 결정(컨타 20:36). 레포 변경(4절)과 개발기 반영 순서(5절), dev 4bca194 |
| 2026-10-26 20:41 | 컨타 [승인] 개발기 반영 신호(사용자 위임 10/25 22:39) |
| 2026-10-26 20:42 | 5.0 읽기: 서버 compose 가 이 CR 직전 레포와 같음. 서버 실행기 jar 10/25 23:59(CR-156 배포, 다른 실행기 기동 23:59:33 과 같음). `enabled-tenants` 는 `*` 라 5.2 는 하지 않음. 실행기 키 행은 scope 없음, domain_app 은 테넌트 값. lexflow CLI 연결 설정 키는 agent_name, config_dir, model, runner_api_key, tool_mode 이고 runner_api_key 는 서버 `RUNNER_API_KEY` 와 같음. 음성 변환 연결은 bp_wes 에만 있음 |
| 2026-10-26 20:44 | 5.1, 5.4: 환경 파일과 compose 백업(`deploy-backup/*.20261026-204439`). 실행기 키 행 e8b1f8ef 를 서버에서 만들어 넣음(값은 찍지 않음, 사본 `~/.claude-secrets/bp-office-aimbase-runner.env`). 연결 두 개는 서버 안에서 lexflow, bp_wes 연결 설정을 옮겨 SQL 로 넣음(연결 생성 API 도 상태 disconnected 로 저장만 한다) |
| 2026-10-26 20:45 | 5.3: compose 보냄, `aimbase-agent-bp-office` 만 build, `up -d --no-deps`. 5초 안에 healthy, 등록 성공(agent_registry ACTIVE, 실행기 가능). api 와 다른 실행기 6개 시작 시각 그대로 |
| 2026-10-26 20:46 | 5.5-5 연결 시험 ok. 5.5-6 채팅 1회 200, 4.3초, 답 「확인」, 실행기 기록에 NATIVE 워커 기동 |
| 2026-10-26 20:47 | 5.5-7 대기: 맥 전사 사이드카가 17:47 에 내려간 뒤 launchd 재기동이 종료코드 78 로 계속 실패. 컨타에 결재 요청 |
