# CR-159: bp-office 추출의 사진과 스캔 PDF 그림 읽기

- 발번: 2026-10-26
- 원본 요구사항: `docs/origins/원본_요구사항_CR159_bp_office_사진과_스캔PDF_그림_읽기_20261026.md`
- 요청: bp-office Sprint 4 실행맵(`bp-office/docs/기록/실행맵_Sprint4_7_9.md`) 9번 행. 사용자 10/26 07:37 「사진과 글 없는 스캔 PDF 는 AI 실행 기반의 그림 읽기로 추출한다. 그림 읽기는 aimbase CR, 그 둘만 파일로 보내는 경로는 bp-office CR-015」. CR 나눔 컨타 20:36 ①
- 앞 건: CR-158(bp_office 테넌트, 실행기 `cli-runner-bp-office`, CLI 연결 `cli-runner-bp-office-001`)
- 짝 워커: bp-office CR-015(bp-office-41170). 파일 전달 모양은 8절 질의 1 에서 ① 로 정했다
- 규모: 소. 워크플로우 LLM 단계의 설정 칸 하나, 실행 API 의 선택 인자 하나, CLI 연결의 그림 지원 표시. 새 API, 화면, 테이블이 없다
- 상태: 진행중

---

## 1. 문제

1. 워크플로우 LLM 단계는 시스템 글과 프롬프트 글 한 덩이만 모델에 보낸다(`LlmCallStepExecutor.baseMessages`, `callLlmStreaming`). 사진이나 스캔 PDF 를 단계에 줄 칸이 없다
2. 모델까지 가는 길은 이미 있다. CLI 연결은 실행기에 그림, 문서 블록을 그대로 실어 보내고(`ClaudeCliRunnerClient.toRawMessages`, CR-098, 실행기 `RunnerService.toUnified`), API 연결은 Anthropic 그림, 문서 블록으로 바꾼다(`AnthropicAdapter`, CR-061). 채팅 첨부가 이 길로 운영에서 돈다
3. 실행 API(`POST /api/v1/workflows/{id}/run`)는 세션 번호를 받지 않아 실행의 세션이 늘 `workflow-run-{uuid}` 이다. 첨부는 올린 세션에만 묶여 있어(`AttachmentService.loadOwned`) 바깥 앱이 올린 첨부를 워크플로우가 읽을 수 없다. LARGE_INPUT 단계의 `source_file` 첨부 번호도 같은 이유로 바깥 앱이 쓸 수 없다
4. CLI 연결은 그림 지원 표시가 기본값 「없음」이다(`LLMAdapter.capabilities` 기본 NONE, `ClaudeCliAdapter` 미재정의). PDF 판정기(`PdfVisionResolver`)가 이 표시를 보면 스캔 PDF 를 글 뽑기로 돌려 빈 글이 된다

## 2. 바꾸는 것

### 2.1 LLM 단계 `attachments`

| 항목 | 내용 |
|---|---|
| 칸 | LLM_CALL config `attachments`. `{{input.files}}` 처럼 입력이나 앞 단계 값 하나를 가리키는 참조, 또는 목록을 바로 적는다(`StepContext.resolveObject`) |
| 값 | 첨부 번호 글의 목록. 목록 항목이 `{attachment_id}` 모양이어도 받는다. 비었거나 칸이 없으면 지금과 같다 |
| 읽기 | 실행 세션이 올린 첨부만 읽는다(`AttachmentService.loadOwned(번호, 실행 세션)`). 없거나 기한이 지났으면 단계 실패 |
| 사진 | 그림 블록. 긴 변이 설정값(기본 1568px, 모델이 스스로 줄이는 크기)보다 크면 줄여서 보낸다. 투명이 있으면 PNG, 없으면 JPEG. 줄일 수 없는 형식(webp)은 그대로 보낸다 |
| PDF | 채팅 첨부와 같은 판정기(`PdfVisionResolver`)를 쓴다. 3MB 이하이고 연결이 PDF 를 받으면 문서 블록 통째, 아니면 쪽 그림(앞 20쪽), 연결이 그림도 못 받으면 뽑은 글 |
| 연결이 그림을 못 받을 때 | 사진이 있으면 단계 실패(조용히 빼지 않는다) |
| 보내는 자리 | 프롬프트 글과 같은 사용자 메시지에 글 앞으로 붙인다(Anthropic 권장 순서: 그림, 문서 다음에 묻는 글). 1차 호출, 잘림 뒤 다시 부르기, 구조화 결과 다시 요청, 자동분할의 쪽 호출이 모두 같은 메시지를 쓴다 |
| 실행 기록 | LLM 요청 기록에 첨부마다 파일 이름, 형식, 크기 한 줄을 붙인다. 파일 내용(base64)은 남기지 않는다 |
| 설정 | `aimbase.workflow.attachment.image-max-edge`(기본 1568, 0 이하면 줄이지 않음) |

### 2.2 실행 API 의 `session_id`

`POST /api/v1/workflows/{id}/run?session_id=...` 선택 인자. 주면 그 값이 실행의 세션이 되어 그 세션으로 올린 첨부를 단계가 읽는다. 없으면 지금처럼 `workflow-run-{uuid}` 다.

### 2.3 CLI 연결의 그림 지원 표시

`ClaudeCliAdapter.capabilities()` 를 그림과 PDF 지원으로 둔다(CR-098, CR-099 에서 CLI 가 두 블록을 받는 것을 실측했다). 이 표시를 보는 곳은 채팅의 모델 이름 경로(`ChatController.resolveAdapter`, 스프링 빈 목록 `LLMAdapterRegistry`)뿐이고 CLI 연결은 그 목록에 없어 채팅 동작은 바뀌지 않는다. 이 CR 의 LLM 단계가 처음 보는 곳이다.

### 2.4 다른 테넌트

`attachments` 칸을 쓰지 않고 `session_id` 를 주지 않으면 실행, 단계, 채팅 모두 지금과 같다. 다른 테넌트 실행기와 api 의 동작 변경은 없다.

## 3. 바꾸지 않는 것

1. 재실행(`POST /runs/{runId}/rerun`)은 지금처럼 새 세션으로 돈다. 첨부를 쓴 실행을 재실행하면 첨부를 못 읽어 실패한다. bp-office 는 다시 추출할 때 새 실행을 요청하므로 이 CR 에서 고치지 않는다
2. 첨부 올리기 API, 한도(사진 10MB, PDF 32MB, 세션당 10개, 24시간), 받는 형식(jpg, png, gif, webp, pdf)
3. 글 없는 PDF 인지 가리는 일은 bp-office 가 한다(CR-015 보정 22). aimbase 는 받은 PDF 를 그대로 읽는다

## 4. 바뀌는 파일

| 파일 | 내용 |
|---|---|
| `workflow/step/StepAttachmentResolver.java`(새) | 첨부 참조를 그림, 문서 블록으로 바꾼다. 사진 줄이기, PDF 판정 |
| `workflow/step/LlmCallStepExecutor.java` | `attachments` 를 읽어 사용자 메시지에 블록을 붙이고 요청 기록에 한 줄 |
| `api/WorkflowController.java` | 실행 API `session_id` 인자 |
| `llm/adapter/ClaudeCliAdapter.java` | `capabilities()` |
| `application.yml` | `aimbase.workflow.attachment.image-max-edge` |
| `application-local.yml` | 로컬 저장 폴더(`storage.local.base-path`). 맥에는 쓸 수 있는 `/data` 가 없어 로컬에서 첨부 올리기가 저장에서 실패하던 것 |
| API 가이드 § 4-4, 운용 가이드 LLM_CALL 표 | `session_id`, `attachments` |

## 5. 시험

단위시험 18건을 더했다(TC-CR159-001~013 `StepAttachmentResolverTest`, 020~023 `LlmCallStepExecutorAttachmentTest`, 030 `ClaudeCliAdapterCapabilityTest`). 워크플로우 단계, CLI 어댑터, 워크플로우 API, 첨부 시험 묶음 240건이 모두 통과했다.

로컬 실제 호출은 6절, 개발기 실제 호출은 7절에서 bp-office CR-015 창과 한다.

## 6. 로컬 실제 호출 (10/26 23:03)

맥 로컬 서버(8181)와 로컬 실행기(17147, Java 21, 프로젝트 밖 빈 폴더)로 CLI 연결(anthropic-cli, NATIVE, claude-sonnet-4-6)을 끝까지 돌렸다.

1. 로컬 tenant_dev 는 마이그레이션 기록 밖에서 만든 표가 있어 V58 에서 더 올릴 수 없었다(V37 `hook_definitions` 이미 있음, 적용 0). 시험 전용 테넌트 `cr159_local` 을 플랫폼 API 로 만들어(V73 까지 적용) 거기서 했다
2. 시험 파일: 세로로 찍은 휴대폰 사진 흉내(저장 픽셀 4032x3024, EXIF 방향 6, 2.5MB, 한글 회의 메모 6줄)와 글 없는 그림 한 장짜리 스캔 견적서 PDF(133KB, 한글 7줄)
3. 첨부 두 개를 세션 `cr159-local-230327` 로 올리고 `POST /workflows/wf-cr159-local/run?session_id=...` 에 `files` 로 실행. 실행의 세션이 그 값으로 잡혔다
4. 요청 기록: `[첨부] whiteboard_portrait.jpg image/jpeg 2590302B, 그림(줄임 image/jpeg)`, `[첨부] scan_quote.pdf application/pdf 133000B, PDF 통째`
5. 결과(15초, 완료): 두 파일의 글 13줄을 한 글자도 틀리지 않고 옮겼고, 사진의 글은 「똑바로」 서 있었다고 답했다(방향을 바로 세워 보냈다)
6. 3MB 초과 PDF 의 쪽 그림 경로는 로컬에 rag 사이드카가 없어 개발기에서 본다

## 7. 개발기 반영과 실제 호출

### 7.1 배포용 사본으로 반영한 이유

1. dev 커밋(0c43065)만으로는 api 가 컴파일되지 않는다. 7월부터 커밋된 `OrchestratorEngine` 이 커밋 안 된 `ChatRunRecorder` 에 기대고 있다(이 CR 과 무관한 기존 문제). 깨끗한 작업 폴더(git worktree)에서 컴파일 오류 11건
2. 개발기 api 실행본(10/26 05:15, CR-157 배포)은 맥 작업 폴더를 그대로 빌드한 것이라 커밋 안 된 남의 변경(CR-128, 129, 130, 132 등)이 이미 들어 있다. 실행본 안의 클래스와 설정을 읽어 대조했다(`ChatRunRecorder`, `ArtifactController`, CLI 어댑터 `exposedTools`, 워크플로우 `run_type`, `vision.frame-max-edge` 있음)
3. 작업 폴더에는 05:15 뒤에 진행 중인 CR-160(음성 변환 문구 거르기) 이 더해져 있다. 실행본에는 없다(`SegmentSuspectReason` 없음, master 마이그레이션 V70 까지)
4. 그래서 작업 폴더를 scratchpad 사본으로 떠서 CR-160 미완성분(음성 변환 소스 2개와 시험 2개를 dev 커밋 상태로 되돌리고, 새 파일 3개와 master V71 은 빼고)만 걸러 낸 뒤, 그 사본에서 `./deploy.sh be` 를 쳤다. 개발기 = 05:15 실행본 + CR-159. 사본에서 컴파일과 관련 시험 270건 통과(실패 0)
5. 컨타 승인 10/26 23:10 ①(사용자 위임)

### 7.2 반영 (10/26 23:11)

| 확인 | 결과 |
|---|---|
| 보낸 compose, Dockerfile | 개발기 것과 같음(보내기 전 대조) |
| api | 23:11:26 다시 만들어짐, healthy, 11초 만에 기동 |
| 실행기 7개 | 시작 시각 그대로(다시 만들어지지 않음) |
| 실린 기능 | `StepAttachmentResolver` 있음, CLI 어댑터 그림 지원 표시 있음, 05:15 실행본의 남의 변경 그대로, CR-160 없음 |
| rag 사이드카 | healthy, api 가 연결해 도구 42개 등록(3MB 초과 PDF 쪽 그림 경로) |

### 7.3 실제 호출

bp-office CR-015 창이 사진 한 장, 스캔 PDF 한 개로 추출을 요청하고 결과 항목과 근거를 본다(컨타 경유).

## 8. 질의

| 번호 | 누구 | 물을 것 | 기본안 |
|---|---|---|---|
| 1 | bp-office CR-015(컨타 경유) | 파일 전달 모양 | ① 첨부로 올리고 실행에 `session_id` 와 첨부 번호 목록을 준다(권장) ② 실행 본문에 base64 로 바로 싣는다(파일이 실행 기록에 영구히 남는다) |

회신(10/26 22:40, CR-015): ①. `session_id` 는 `bp-office-work-{workId}`, 원문 하나에 파일 하나. HEIC 와 20쪽 초과 PDF 는 bp-office 가 걸러 알린다. 3MB 초과 스캔 PDF 가 흔할 것이라 개발기에서 쪽 그림 경로(rag 사이드카)를 실제로 본다. 추출 워크플로우 정의(`text` 필수 해제, 첨부도 원문이라는 프롬프트 한 줄)는 CR-015 가 고친다.

## 9. 진행

| 단계 | 상태 |
|---|---|
| 소스 확인 | 끝 |
| 질의 1 | 끝(①) |
| 구현, 단위시험 | 끝(새 시험 18건, 묶음 240건 통과) |
| 로컬 실제 호출 | 끝(사진, 스캔 PDF 모두 정확히 읽음) |
| 개발기 반영 | 끝(10/26 23:11, 배포용 사본, 7.1) |
| 실제 호출 | 대기 |
