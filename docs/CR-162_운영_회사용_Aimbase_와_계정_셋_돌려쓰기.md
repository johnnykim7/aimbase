# CR-162: 운영 회사용 Aimbase 와 계정 셋 돌려쓰기

- 발번: 2026-10-27
- 원본 요구사항: `docs/origins/원본_요구사항_CR162_운영_Aimbase_계정_셋_20261027.md`
- 요청: 3PLcore 컨타(3plcore-23636). 차수 편성 추천에 AI 의견을 붙이기 위해 운영 서버 chaws 에 회사용 Aimbase 를 세우고, Claude CLI 계정 work01, work02, work03 을 돌아가며 쓴다
- 결정 (사용자 2026-10-27):
  - 08:22 계정은 work01, work02, work03 을 ROUND_ROBIN 으로 쓴다
  - 09:49 Aimbase 는 운영 서버 chaws 에 같이 올린다
  - 09:57 서버는 m6i.2xlarge(CPU 8, 메모리 32GB)로 시작하고 모자라면 늘린다
  - 11:58 설계 3절 ① 승인(컨타 위임). 운영 서버 작업은 4절 1번에서 5번, 7번과 8번을 묶어 사용자 승인을 받는다
  - 23:33 work03 은 없는 계정으로 보고 완료 조건에서 뺀다. 계정은 work04, work05 처럼 늘 수 있다는 전제로, 로그인과 명령 한 번으로 묶음에 들어가게 한다
- 규모: 중. 앱 소스 변경은 없다. 운영 설치 구성(DB, 메모리 한도, 망, 배포 대상)을 새로 정한다. 새 API, 화면, 테이블 없음
- 함께 도는 건: bp-wms CR-WMS-798(차수 편성 추천 AI 의견 운영 연결, 설계 주인 bp-wms), aimbase CR-161(워크플로우 단계의 연결 묶음, 다른 컨타 팀)
- 상태: 설계 확정, 구현 중

---

## 1. 목적

1. chaws 에 회사용 Aimbase(api, 실행기, DB, Redis)를 설치한다. 같은 서버의 운영 3PL 컨테이너 21개에 영향을 주지 않는다
2. CLI 계정 셋을 연결 셋으로 두고 ROUND_ROBIN 묶음으로 묶는다. 한 계정이 한도에 걸리면 다음 계정이 받는다
3. bp-wms 차수 편성 추천 워크플로우 `wms-wave-plan-opinion` 을 운영에 등록하고, bp-wms 가 부를 테넌트, 열쇠, 주소를 계약으로 넘긴다

## 2. 조사 결과

2026-10-27 11:50 KST chaws 읽기 조회와 소스 기준이다. 서버 변경 기록은 컨타 실측(`3PLcore/scratchpad/설계전_운영Aimbase_20261027/01_조사결과.md`)이다.

### 2.1 운영 서버 chaws

| 순번 | 항목 | 값 | 근거 |
|---|---|---|---|
| 1 | 인스턴스 | i-06b2de5f96026e077, m6i.2xlarge(CPU 8, 메모리 32GB). 10:28 r6i.xlarge 에서 바꿈 | 컨타 실측 |
| 2 | 주소 | 3.39.94.112, 탄력적 IP | 컨타 실측 |
| 3 | 메모리 | 전체 30GB, 사용 12GB, 여유(available) 17GB | `free -g` |
| 4 | 디스크 | 루트 29GB 중 16GB 남음. 도커 자료 디스크 99GB 중 60GB 남음(10:20 50GB 에서 100GB 로 늘림) | `df -h` |
| 5 | 운영 컨테이너 | 21개. 앱은 하나마다 메모리 한도가 있고(768MB 에서 6GB), 기반(bp-postgres, bp-kafka, bp-redis 등)은 한도 없음 | `docker stats` |
| 6 | 운영 DB | bp-postgres, `pgvector/pgvector:pg16`, PostgreSQL 16.15, vector 확장 있음. DB 는 `bp_fulfillment` 하나. 호스트 포트 없음 | `docker inspect`, `pg_database` |
| 7 | 운영 Redis | bp-redis, `redis:7.2`, 호스트 `0.0.0.0:6379` 로 열림 | `docker ps` |
| 8 | 망 | bp-wms 와 기반 컨테이너는 `bp-infra_default` 하나 | `docker inspect bp-wms` |
| 9 | 3PL 자동 배포 | main 합치기 때 chaws 의 GitHub 실행기가 서비스 하나만 `docker compose build <서비스>`, `up -d --no-deps <서비스>` 하고 `docker image prune -f`(이름 없는 이미지만)를 한다. 작업 폴더는 `/home/ubuntu/projects/bp-fulfillment-infra/bp-infra` | bp-wms `.github/workflows/deploy-aws.yml:59-63`, `:88` |
| 10 | Aimbase | 없음 | `docker ps` |

### 2.2 Aimbase 가 기동에 쓰는 것

| 순번 | 항목 | 동작 | 근거 |
|---|---|---|---|
| 1 | DB | master 와 테넌트 DB 를 PostgreSQL 16 + pgvector 에 둔다. 테넌트를 만들 때 DB 를 새로 만든다 | `application.yml:26-28`, `:125-128` |
| 2 | Redis | 세션, 캐시, 호출 제한 | `application.yml:54-55` |
| 3 | 문서 검색(rag-pipeline) | `RAG_MCP_ENABLED` 기본 false. 끄면 기동에 필요 없다 | `application.yml:82-83` |
| 4 | 스킬 실행 컨테이너 | 주소나 열쇠가 비면 스킬 실행 도구만 거절한다 | `SkillSandboxClient.java:47` |
| 5 | 첫 관리자 | 운영 프로파일에는 관리자를 넣는 기동 처리가 없다(로컬 전용 `LocalDevInitializer`, `@Profile("local")`). master `tenant_admins` 에 한 줄을 넣어야 플랫폼 API(`/api/v1/platform/**`, SUPER_ADMIN)를 쓸 수 있다 | `LocalDevInitializer.java:30`, `V1__init_master_schema.sql:19`, `SecurityConfig.java:90` |
| 6 | CLI 계정 폴더 | CLI 연결의 `config.config_dir` 로 계정 폴더를 정한다. 연결 셋이 같은 실행기에서 폴더만 달리 쓸 수 있다 | `ConnectionAdapterFactory.java:363` |
| 7 | CLI 허용 테넌트 | master `global_config` 의 `llm.anthropic-cli.enabled-tenants` 에 든 테넌트만 CLI 연결을 쓴다(BIZ-099) | `ConnectionAdapterFactory.java` CR-050 주석 |
| 8 | JVM 메모리 | api 와 실행기 모두 `-XX:MaxRAMPercentage=75.0`. 컨테이너 한도의 75% 를 힙으로 쓴다 | 두 Dockerfile `ENTRYPOINT` |

### 2.3 59 Aimbase 메모리 (컨타 실측 10/27 09:51)

본체 1.7GB, 문서 검색 4.0GB, 실행기 하나 0.6GB 에서 0.9GB, skill-sandbox 17MB.

### 2.4 워크플로우 단계와 묶음

워크플로우 단계는 `connection_id` 하나만 읽는다(`LlmCallStepExecutor.java:117`). 묶음을 받게 하는 것은 CR-161 이다. CR-161 은 10/27 11:47 범위 ①(모든 경로가 묶음을 받는다)이 정해졌고 4절 설계는 아직 없다. CR-161 은 다른 컨타 팀이라 이 창에서 직접 묻지 못한다. 그 문서를 보며 맞춘다.

## 3. 설계 결정

이 창이 정한 안이다. 컨타에 `[승인요청]` 으로 올린다.

### 3.1 DB 와 Redis 는 운영 것과 나눈다

1. Aimbase 묶음 안에 DB(`pgvector/pgvector:pg16`)와 Redis(`redis:7-alpine`) 컨테이너를 따로 둔다. 둘 다 호스트 포트를 열지 않는다(권장)
2. 운영 bp-postgres 에 Aimbase 계정과 DB 를 더하고 bp-redis 를 같이 쓴다(잃는 것: 운영 DB 에 DB 를 만들 수 있는 계정이 생기고 테넌트마다 연결 풀이 붙어 연결 수, 백업, 재기동이 운영 서비스와 섞인다. Redis 는 키가 섞인다)

이미지는 T2-1 인프라 기술 스택에 있는 것 그대로다. DB 자료는 서버 폴더(루트 디스크, 16GB 남음)가 아니라 도커 볼륨 `aimbase_pgdata` 로 두어 도커 자료 디스크(60GB 남음)에 쌓이게 한다.

### 3.2 메모리 한도

| 순번 | 컨테이너 | 한도 | 까닭 |
|---|---|---|---|
| 1 | api | 2GB | 59 실측 1.7GB. 힙 1.5GB |
| 2 | 실행기 | 1.5GB | 59 실측 0.6GB 에서 0.9GB. 동시 CLI 를 3개로 줄인다(`AIMBASE_RUNNER_MAX_WORKERS=3`) |
| 3 | DB | 1GB | 테넌트 하나, 자료 적음 |
| 4 | Redis | 256MB | `--maxmemory 192mb`, 세션은 24시간 TTL 이라 TTL 있는 키부터 비운다(`volatile-lru`) |

합계 4.75GB. 지금 여유 17GB 에서 12GB 가 남는다. 한도는 compose 에 두고, 모자라면 compose 를 고쳐 다시 올린다.

### 3.3 문서 검색과 스킬 실행 컨테이너는 올리지 않는다

차수 편성 추천에 쓰지 않는다. `RAG_MCP_ENABLED=false`, `SKILL_SANDBOX_URL` 과 열쇠는 비운다. 59 기준 4GB 를 아낀다. 쓰게 되면 그때 컨타에 묻고 올린다.

### 3.4 서버 밖으로 열지 않는다

1. api 는 `127.0.0.1:8280` 에만 묶는다. 관리 API 는 맥에서 SSH 터널(`ssh -L 8280:127.0.0.1:8280 chaws`)로 부른다. 관리 화면은 올리지 않는다(우진비드 AWS 와 같다). 보안 그룹은 바꾸지 않는다
2. bp-wms 와는 전용 망 `aimbase-apps` 로 잇는다. api 가 그 망에 별칭 `aimbase-api` 로 붙고, bp-wms 가 그 망에 합류한다(권장)
3. bp-wms 가 Aimbase 기본 망(`aimbase_default`)에 합류한다(잃는 것: bp-wms 가 Aimbase DB 와 Redis 에도 닿는다)
4. api 가 운영 망 `bp-infra_default` 에 합류한다(잃는 것: Aimbase 가 운영 DB, Kafka 와 같은 망에 들어간다)

`aimbase-apps` 는 배포 스크립트가 `docker network create` 로 한 번 만들고, 양쪽 compose 가 `external: true` 로 받는다. 어느 쪽 compose 를 내려도 망이 지워지지 않는다. 실행기, DB, Redis 는 이 망에 붙지 않는다.

### 3.5 설치와 갱신

- compose 정본은 저장소 `docker-compose.chaws.yml`. 서버에서 고치지 않는다
- 서버 폴더 `/home/ubuntu/aimbase`. 3PL 레포 폴더(`/home/ubuntu/projects`)와 GitHub 실행기 폴더 밖이다. compose 이름은 `aimbase`
- `./deploy.sh chaws-be`(DB, Redis, api), `./deploy.sh chaws-agent`(실행기), `./deploy.sh chaws`(이 순서로 둘). 우진비드 AWS 대상(CR-151)과 같은 방식이다. 올릴 커밋을 `CHAWS_COMMIT` 으로 받아 그 커밋만 받은 별도 worktree 에서 jar 를 빌드하고, jar 와 Dockerfile 을 서버 `build/` 로 보내 서버에서 이미지를 만들고(맥 작업 폴더에는 다른 창의 덜 된 변경이 섞여 있다. 서버 `deploy-backup/deployed.log` 에 서비스별 커밋을 남긴다), 바꾸기 전 이미지는 `pre-<시각>` 태그로 남겨 되돌리는 명령을 출력한다. 기동 판정은 컨테이너 healthy 다
- 3PL 자동 배포와 섞이지 않는다. 그쪽은 bp-infra compose 의 서비스 이름을 집어 `--no-deps` 로만 올리고, `docker image prune -f` 는 이름 없는 이미지만 지운다. `pre-<시각>` 은 이름이 있어 남는다
- 비밀값은 서버 `api.env`, `agent.env`, `db.env` 와 `secrets/` 아래 파일에만 둔다(권한 600). 서버에서 `openssl rand` 로 만들고 화면과 기록에 찍지 않는다

### 3.6 테넌트, 실행기, 연결

| 순번 | 항목 | 값 | 비고 |
|---|---|---|---|
| 1 | 테넌트 | `bp_wms` | 59 는 bp_wes 테넌트를 빌려 쓴다. 운영은 앱마다 테넌트를 두는 59 의 다른 앱 방식을 따른다 |
| 2 | 실행기 | `cli-runner-bp-wms` 하나 | bp_wms 테넌트 실행기 전용 키로 스스로 등록한다(CR-156, CR-158) |
| 3 | 계정 폴더 | 서버 `claude-accounts/<계정>`. 지금 `work01`, `work02` | api 와 실행기가 부모 폴더 `claude-accounts` 를 통째로 붙여, 계정을 늘려도 다시 만들지 않는다 |
| 4 | 연결 | 로그인된 계정마다 `cli-runner-bp-wms-<계정>` | `adapter=anthropic-cli`, `config.agent_name=cli-runner-bp-wms`, `config.config_dir` 은 각 계정 폴더 |
| 5 | 묶음 | `cli-runner-bp-wms-rr`, ROUND_ROBIN, 로그인된 계정의 연결 전부 | CR-015. 설치 스크립트가 실행기 안에서 로그인 파일이 있는 계정 폴더를 세어 구성원을 맞춘다 |
| 6 | CLI 허용 | master `llm.anthropic-cli.enabled-tenants` 에 `bp_wms` | BIZ-099. 사내 테넌트 |

### 3.7 CR-161 과의 순서

1. CR-161 이 운영에 들어오기 전: 워크플로우 단계 `opinion` 의 `config.connection_id` 에 `cli-runner-bp-wms-work01` 을 넣는다. 그동안은 work01 하나만 쓴다
2. CR-161 이 단계에서 묶음을 받게 되면: chaws api 를 그 판으로 다시 올리고(`./deploy.sh chaws-be`), 단계 `opinion` 의 `config.connection_id` 를 빼고 `config.connection_group_id` 에 묶음 `cli-runner-bp-wms-rr` 을 넣는다(CR-161 운용 가이드 시나리오 P). `chaws-bootstrap.sh workflow` 가 그 자리를 채우게 같이 고친다. 부르는 쪽(bp-wms)은 바꿀 것이 없다

### 3.8 계정 로그인

사용자가 SSH 창에서 실행기 컨테이너의 CLI 로 계정마다 한 번 로그인한다. CLI 가 주는 주소를 맥 브라우저로 열어 그 계정으로 승인하고 받은 코드를 붙여 넣는다. 서버에 화면이 필요 없다. 막히면 `vnc-headless` 로 간다. 로그인 값은 서버 계정 폴더에만 남는다.

## 4. 운영 반영 순서

서버를 바꾸는 단계마다 직전에 컨타에 `[승인요청]` 한다. 운영 3PL 컨테이너는 건드리지 않는다. 단계마다 칠 명령은 운용 가이드 § 2-10 「처음 세우기」 의 같은 번호다.

| 순번 | 단계 | 서버 변경 |
|---|---|---|
| 1 | 서버 폴더, 비밀값 파일, 망 `aimbase-apps` 만들기 | 있음 |
| 2 | `./deploy.sh chaws-be` 로 DB, Redis, api 기동 | 있음 |
| 3 | 첫 관리자 한 줄 넣기(비밀번호는 `secrets/admin-password` 에만), CLI 허용 테넌트 설정 | Aimbase DB 쓰기 |
| 4 | 테넌트 `bp_wms` 만들기, 실행기 키와 bp-wms 키 발급(값은 `secrets/` 파일로) | Aimbase DB 쓰기 |
| 5 | `./deploy.sh chaws-agent` 로 실행기 기동, 등록 확인 | 있음 |
| 6 | 계정 로그인(사용자) | 계정 폴더 |
| 7 | 로그인된 계정마다 연결, 묶음 만들기 | Aimbase DB 쓰기 |
| 8 | 워크플로우 `wms-wave-plan-opinion` 등록(단계 연결 work01), `aimbase-apps` 망 안에서 시험 실행 | Aimbase DB 쓰기 |
| 9 | bp-wms 창에 운영 값 회신 | 없음 |

## 5. bp-wms 계약 값

계약 문서는 bp-wms `docs/협업/계약_CR-WMS-798_운영_Aimbase_차수_편성_추천_의견.md`(bp-wms dev 532447177)다. 이 창이 줄 값은 다음과 같다.

| 순번 | 항목 | 운영 값 |
|---|---|---|
| 1 | bp-wms 가 부를 주소 | `http://aimbase-api:8080` |
| 2 | bp-wms 가 합류할 망 | `aimbase-apps`(external) |
| 3 | 운영 테넌트 | `bp_wms` |
| 4 | 열쇠 | chaws `/home/ubuntu/aimbase/secrets/bp-wms-api-key`(값은 파일에만) |
| 5 | 단계에 넣은 연결 | `cli-runner-bp-wms-work01`. CR-161 뒤 묶음 `cli-runner-bp-wms-rr` |
| 6 | 운영 등록 완료 시각 | 10/27 22:32(KST). 8절 |
| 7 | 등록할 워크플로우 정의 | bp-wms dev 1c9fe88e8 의 `docs/자산/aimbase/wave-plan-opinion-workflow.json`(이미 있으면 PUT 으로 고친다). 구역을 「구역 N」 으로 부르게 지시문을 고친 판으로, f71bc0531 판과는 지시문 두 칸만 다르다. 맥 연습 설치에 이 판을 PUT(200)으로 넣어 DB 의 단계가 정의와 같음을 봤다(단계 연결만 더함) |

## 6. 영향 설계서

| 순번 | 설계서 | 판정 |
|---|---|---|
| 1 | T1-1, T1-3 | 영향 없음. 기능과 규칙이 바뀌지 않는다. BIZ-099(CLI 허용 테넌트)를 그대로 따른다 |
| 2 | T2-1 | 영향 없음. 새 라이브러리, 새 이미지 없음(pgvector:pg16, redis:7-alpine 은 인프라 기술 스택에 있다) |
| 3 | T3 | 영향 없음. 앱 동작, 테이블, API 변경 없음 |
| 4 | 운용 가이드 | § 2-10 운영 chaws 서버 순서를 더한다(설치, 갱신, 되돌리기, 계정 로그인, 메모리 한도) |
| 5 | 저장소 파일 | `docker-compose.chaws.yml`(신규), `deploy.sh`(chaws 대상), `scripts/chaws-bootstrap.sh`(신규, 4절 3, 4, 7, 8번을 서버에서 돌린다) |

## 7. 검증

- 로컬: `docker compose -f docker-compose.chaws.yml config` 통과, `bash -n deploy.sh`. 같은 compose 를 맥 도커로 띄워 api 가 문서 검색, 스킬 실행 컨테이너 없이 healthy 가 되는지 본다
- 운영: 4절 순서대로. 실행기 등록, 연결 셋 시험 호출, `aimbase-apps` 망 안에서 워크플로우 실행이 `completed` 이고 `choice`, `opinion` 이 나오는지 본다. 운영 3PL 컨테이너의 상태와 메모리가 반영 전과 같은지 본다

### 7.1 로컬 리허설 (10/27 맥 도커, compose 이름 `cr162local`)

운영 compose 를 그대로 띄우고 이미지 이름만 로컬 것으로 바꿔 4절 1번부터 8번까지 `chaws-bootstrap.sh` 로 돌렸다.

| 순번 | 본 것 | 결과 |
|---|---|---|
| 1 | 새 master DB 로 api 기동, 문서 검색과 스킬 실행 컨테이너 없음 | healthy. 메모리 api 795에서 862MiB, DB 53에서 76MiB, Redis 5에서 9MiB |
| 2 | admin, tenant | 관리자 로그인, 테넌트 생성(`aimbase_bp_wms` DB), 실행기 키와 bp-wms 키 발급 |
| 3 | 실행기 | `Agent registered successfully: name=cli-runner-bp-wms`, 메모리 325에서 420MiB |
| 4 | connections | 연결 셋과 묶음 201. 다시 돌리면 넷 다 「있음」 으로 건너뜀 |
| 5 | workflow | 처음 201, 다시 돌리면 200(고침). DB 의 단계 `opinion` 연결이 `cli-runner-bp-wms-work01` |
| 6 | smoke | 망 `aimbase-apps` 와 bp-wms 키로 실행이 만들어지고 실행기 CLI 까지 갔다. 계정 로그인이 없어 `Not logged in` 으로 `failed`(로컬에선 예상한 결과) |

- 리허설 jar 는 맥 작업 폴더에서 12:03 에 빌드한 것이다. 12:08 부터 CR-161 창이 작업 폴더 소스를 고치기 시작해, 운영에는 정해진 커밋의 worktree 에서 빌드하게 `deploy.sh` 를 바꿨다(컨타 지시). 그런데 dev 끝 42bbfba 는 worktree 에서 컴파일되지 않는다. 59 에 나가 있는 앞 CR 변경이 커밋되지 않았기 때문이다(새 파일 4개와 고친 파일 여럿, 그중 7개는 CR-161 이 고치는 파일과 섞임). 정리 커밋은 65a7203(앞 CR 미커밋분 19개, 59 의 Dockerfile 둘과 `application.yml` 이 sha256 으로 같음)이고, 마지막으로 필요했던 `ChatRequest` 는 503da60(CR-128)에 들어갔다
- 다시 돈 리허설(10/27 19:30): dev 503da60 을 받은 worktree 에서 빌드한 jar 로 DB 를 비우고 위 1번부터 6번을 처음부터 돌렸다. 결과는 위 표와 같다. 실행기 등록, 연결 셋과 묶음 201, 워크플로우 201 에 DB 단계가 정의와 같고, smoke 는 실행기 CLI 까지 가서 `Not logged in` 으로 `failed`. 두 번 다 1초 안에 돌아와 본 것 1 의 600초 멈춤은 다시 나오지 않았다. DB 기록에 재시작 줄이 없었다. 메모리 api 849MiB, DB 87MiB, Redis 12MiB, 실행기 588MiB. 운영에는 이 커밋(503da60)을 올린다
- 본 것 1: smoke 첫 시도는 34초 만에 `Not logged in` 으로 실패했고, 두 번째 시도는 새 CLI 를 띄운 뒤 실행기 한 턴 제한(600초)까지 답이 없었다. 로그인 없음 때문인지, 같은 때 로컬 장비가 느려진 탓인지(본 것 2) 가르지 못했다. 운영에서는 계정 로그인 뒤 smoke 를 돌리므로 다시 나오면 그때 본다
- 본 것 2: 리허설 중 로컬 DB 가 두 번 스스로 재시작했다(`server process exited with exit code 2`, 컨테이너 재시작과 메모리 초과 종료는 없음). 그때 맥 도커 메모리 8GB 를 컨테이너 25개가 7GB 가까이 쓰고 있었다. 원인은 확인 못 했다. 운영 반영 뒤 DB 기록에서 같은 줄이 있는지 본다

## 8. 운영 반영

운영 chaws 에 10/27 19:33 부터 22:34 까지 4절 순서대로 반영했다. 서버 `deploy-backup/deployed.log` 의 마지막 판은 api `503da60`, 실행기 `6889aed` 다.

| 순번 | 결과 |
|---|---|
| 1 | 19:33 서버 폴더, db.env, api.env, agent.env, 망 `aimbase-apps`. 계정 폴더와 작업 폴더는 실행 계정 999 소유 |
| 2 | db, redis, api(503da60) healthy. api 는 127.0.0.1:8280 만 연다 |
| 3 | 첫 관리자 로그인, CLI 허용 테넌트 `bp_wms` |
| 4 | 테넌트 `bp_wms`(DB `aimbase_bp_wms`) 201. 실행기 전용 키는 agent.env, bp-wms 키는 `secrets/bp-wms-api-key` 에만 |
| 5 | 19:39 실행기(503da60) healthy, `Agent registered successfully: name=cli-runner-bp-wms` |
| 6 | 22:25 사용자가 work01, work02 로그인(구독 max). work03 은 뒤에 한다 |
| 실행기 다시 올림 | 22:30 실행기를 `6889aed` 로 다시 올렸다. 실행기 소스는 CR-163 의 8da5cfb 와 같다(그 사이는 단위시험 1줄, 문서, 설치 스크립트뿐). healthy, 다시 등록됨, work01, work02 로그인 그대로. 되돌리기 이미지 `aimbase-aimbase-agent:pre-20261027-223007` |
| 7 | `ACCOUNTS="work01 work02"` 로 연결 `cli-runner-bp-wms-work01`, `-work02` 201, 묶음 `cli-runner-bp-wms-rr`(ROUND_ROBIN, 구성원 둘) 201. DB 에서 연결마다 계정 폴더와 실행기 이름을 봤다 |
| 8 | 워크플로우 `wms-wave-plan-opinion`(bp-wms 1c9fe88e8 판) 201, DB 단계가 정의와 같고 단계 연결은 work01. smoke 실행 `fc9038de` 는 22:32:21 에 시작해 22:34:03 에 `completed`, `choice` B 와 `opinion` 이 나왔다. 102초 가운데 모델 답 생성이 거의 전부다(출력 6904 토큰, 결과 125자). 실행기 CLI 계정은 work01 |

- 운영 3PL 컨테이너 24개(돌고 있는 21개, 원래 멈춰 있던 3개)의 시작 시각, 재시작 횟수, 상태가 시작 전과 단계마다 같았다. bp-postgres, bp-redis, 보안 그룹은 건드리지 않았다
- 실행기 대상 배포는 설치 스크립트를 서버에 올리지 않는다(api 대상만 올린다). 그래서 6889aed worktree 의 `scripts/chaws-bootstrap.sh` 한 파일을 배포 스크립트와 같은 scp 로 서버 `build/` 에 올렸다(sha256 이 맥 것과 같다)
- Aimbase DB 기록에 재시작 줄이 없다(첫 기동의 초기화 안내문뿐). 7.1 본 것 2 는 운영에서 나오지 않았고, 본 것 1 의 600초 멈춤도 나오지 않았다. api 기록에 ERROR 줄 없음
- 메모리 api 676MiB, 실행기 464MiB, DB 74MiB, Redis 15MiB. 서버 남은 메모리 약 14GB
- 계정 늘리기(사용자 23:33 방향): work03 은 없는 계정으로 보고 완료 조건에서 뺐다. 설치 스크립트가 계정 목록을 고정값 대신 실행기 안의 로그인 파일이 있는 계정 폴더에서 센다. 맥 연습 설치에서 실행기를 다시 띄우지 않고 `work04` 폴더를 만들어 연결 201, 묶음 구성원 셋 200 을 봤고, 묶음으로 채팅을 세 번 부르니 실행기가 CLI 를 work01, work02, work04 로 시작점을 돌려 띄웠다. 세기는 세 경우를 봤다. 목록을 주면 그 목록(둘), 목록 없이 돌리면 로그인 파일이 있는 work01, work02, work04 만(로그인 없는 work03 은 빠짐), 로그인된 계정이 없으면 멈추고 묶음을 건드리지 않는다
- 남은 일: 새 설치 스크립트를 운영 서버 `build/` 에 올린다(승인 뒤). CR-161 이 들어오면 단계 연결을 묶음 `cli-runner-bp-wms-rr` 로 바꾼다
