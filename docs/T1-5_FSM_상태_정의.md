# T1-5. FSM 상태 정의서

> 설계 버전: 1.5 | 최종 수정: 2026-11-13 | 관련 CR: CR-001, CR-164, CR-166, CR-167, CR-174, CR-185

> **프로젝트**: Aimbase
> **작성일**: 2026-03-10 (역설계)

---

## 전체 요약

| 엔티티 | 상태 코드 목록 |
|--------|---------------|
| Connection | `connected`, `disconnected`, `error` |
| MCPServer | `disconnected`, `connected` |
| KnowledgeSource | `idle`, `syncing`, `completed`, `error` |
| WorkflowRun | `running`, `pending_approval`, `completed`, `failed` |
| PendingApproval | `pending`, `approved`, `rejected`, `timeout`, `cancelled` |
| DevWorkItem | `IN_PROGRESS`, `COMPLETED`, `FAILED`, `CANCELLED` |
| Tenant | `provisioning`, `active`, `suspended`, `deleted` |
| User | `active`, `inactive` |
| TtsJob | `PENDING`, `RUNNING`, `COMPLETED`, `FAILED` |
| 생성기 모델(저장하지 않음) | `downloading`, `failed`, `unloaded`, `loading`, `ready`, `backoff` |

---

## Connection (연결)

```mermaid
stateDiagram-v2
    [*] --> disconnected : 연결 생성
    disconnected --> connected : 헬스체크 성공
    connected --> disconnected : 연결 끊김
    connected --> error : 헬스체크 실패
    error --> connected : 헬스체크 재성공
    error --> disconnected : 수동 리셋
    connected --> [*] : 삭제
    disconnected --> [*] : 삭제
    error --> [*] : 삭제
```

### disconnected | 연결 해제
- **설명**: 연결이 생성되었으나 아직 활성화되지 않은 상태
- **진입 조건**: 최초 생성 / 연결 끊김 / 에러 후 리셋
- **허용 다음 상태**: `connected`
- **관련 기능 ID**: PRD-004, PRD-008

### connected | 연결됨
- **설명**: 외부 서비스에 정상 연결된 상태
- **진입 조건**: 헬스체크 성공
- **허용 다음 상태**: `disconnected`, `error`
- **관련 기능 ID**: PRD-008

### error | 에러
- **설명**: 헬스체크 실패 또는 연결 오류 발생 상태
- **진입 조건**: 헬스체크 실패
- **허용 다음 상태**: `connected`, `disconnected`
- **관련 기능 ID**: PRD-008

---

## MCPServer (MCP 서버)

```mermaid
stateDiagram-v2
    [*] --> disconnected : 서버 등록
    disconnected --> connected : 연결 성공 / 도구 탐색
    connected --> disconnected : 연결 해제
    disconnected --> [*] : 삭제
    connected --> [*] : 삭제
```

### disconnected | 미연결
- **설명**: MCP 서버가 등록되었으나 연결되지 않은 상태
- **진입 조건**: 최초 등록 / 연결 해제 요청
- **허용 다음 상태**: `connected`
- **관련 기능 ID**: PRD-010, PRD-014

### connected | 연결됨
- **설명**: MCP 서버에 연결되어 도구 사용이 가능한 상태
- **진입 조건**: 도구 탐색 성공 / autoStart 자동 연결
- **허용 다음 상태**: `disconnected`
- **관련 기능 ID**: PRD-013
- **비고**: autoStart=true인 서버는 앱 시작 시 자동으로 connected 전환

---

## KnowledgeSource (지식소스)

```mermaid
stateDiagram-v2
    [*] --> idle : 지식소스 생성
    idle --> syncing : 동기화 요청
    syncing --> completed : 인제스션 성공
    syncing --> error : 인제스션 실패
    completed --> syncing : 재동기화 요청
    error --> syncing : 재동기화 요청
    idle --> [*] : 삭제
    completed --> [*] : 삭제
    error --> [*] : 삭제
```

### idle | 대기
- **설명**: 지식소스가 생성되었으나 아직 동기화되지 않은 상태
- **진입 조건**: 최초 생성
- **허용 다음 상태**: `syncing`
- **관련 기능 ID**: PRD-048

### syncing | 동기화 중
- **설명**: 문서 파싱, 청킹, 임베딩 생성이 진행 중인 상태
- **진입 조건**: 동기화 요청 (POST /{id}/sync)
- **허용 다음 상태**: `completed`, `error`
- **관련 기능 ID**: PRD-052
- **비고**: 동기화 중 재요청 불가

### completed | 완료
- **설명**: 인제스션이 정상 완료된 상태. 벡터 검색 가능.
- **진입 조건**: 인제스션 파이프라인 성공 완료
- **허용 다음 상태**: `syncing` (재동기화)
- **관련 기능 ID**: PRD-053

### error | 에러
- **설명**: 인제스션 실패 상태
- **진입 조건**: 파싱/청킹/임베딩 과정 중 오류
- **허용 다음 상태**: `syncing` (재시도)
- **관련 기능 ID**: PRD-052

---

## WorkflowRun (워크플로우 실행)

```mermaid
stateDiagram-v2
    [*] --> running : 워크플로우 실행
    running --> pending_approval : HUMAN_INPUT 스텝 도달
    running --> completed : 모든 스텝 완료
    running --> failed : 스텝 실행 실패 (재시도 소진)
    pending_approval --> running : 승인 (approved=true)
    pending_approval --> failed : 거부 (approved=false)
    pending_approval --> failed : 승인 시한 초과 (감시기, CR-174)
```

### running | 실행 중
- **설명**: 워크플로우 스텝이 순차/병렬로 실행 중인 상태
- **진입 조건**: 워크플로우 실행 요청 / 승인 후 재개
- **허용 다음 상태**: `pending_approval`, `completed`, `failed`
- **관련 기능 ID**: PRD-043

### pending_approval | 승인 대기
- **설명**: HUMAN_INPUT 스텝에서 승인을 기다리는 상태
- **진입 조건**: HUMAN_INPUT 타입 스텝 도달
- **허용 다음 상태**: `running` (승인), `failed` (거부, 승인 시한 초과)
- **관련 기능 ID**: PRD-046, PRD-364
- **비고**: PendingApprovalEntity 자동 생성. 단계 `timeoutMs` 가 있으면 `timeout_at` 이 적히고 감시기가 지난 것을 `failed`(사유 `approval_timeout`)로 닫는다 [CR-174]

### completed | 완료
- **설명**: 모든 스텝이 성공적으로 완료된 상태
- **진입 조건**: 마지막 스텝 실행 성공
- **허용 다음 상태**: 없음 (종결 상태)
- **관련 기능 ID**: PRD-044

### failed | 실패
- **설명**: 스텝 실행 실패, 승인 거부, 승인 시한 초과로 종료된 상태
- **진입 조건**: 재시도 횟수 초과 / 승인 거부 / 승인 시한 초과(`error.reason = approval_timeout`, CR-174)
- **허용 다음 상태**: 없음 (종결 상태)
- **관련 기능 ID**: PRD-044

---

## DevWorkItem (개발 작업) [CR-164]

상태는 작업을 돌린 쪽이 보고하는 값이다. 엔진은 순서를 강제하지 않는다. 같은 상태를 다시 보고하는 것은 전이가 아니다(BIZ-129).

```mermaid
stateDiagram-v2
    [*] --> IN_PROGRESS : 실행 시작 또는 API
    [*] --> COMPLETED : API
    [*] --> FAILED : API
    IN_PROGRESS --> COMPLETED : 실행 completed 또는 API
    IN_PROGRESS --> FAILED : 실행 failed 또는 API
    IN_PROGRESS --> CANCELLED : 실행 cancelled 또는 API
    COMPLETED --> IN_PROGRESS : 다음 단계 시작, 재실행
    FAILED --> IN_PROGRESS : 재실행
    CANCELLED --> IN_PROGRESS : 재실행
```

### IN_PROGRESS | 진행
- **진입 조건**: `_dev_work_item` 을 실은 실행 시작, API 보고
- **허용 다음 상태**: 모두
- **관련 기능 ID**: PRD-350, PRD-351

### COMPLETED | 완료
- **진입 조건**: 실행 completed, API 보고
- **허용 다음 상태**: 모두(다음 단계를 시작하면 IN_PROGRESS)
- **관련 기능 ID**: PRD-350, PRD-351

### FAILED | 실패
- **진입 조건**: 실행 failed(단계 실패, 승인 거절, 예기치 못한 오류), API 보고
- **허용 다음 상태**: 모두
- **관련 기능 ID**: PRD-350, PRD-351

### CANCELLED | 중지
- **진입 조건**: 실행 cancelled, API 보고
- **허용 다음 상태**: 모두
- **관련 기능 ID**: PRD-350, PRD-351

- **금지 전이**: 없음. 단, 단계, 상태, 증빙 주소가 모두 같은 보고는 변경으로 남기지 않는다

---

## PendingApproval (승인 대기)

```mermaid
stateDiagram-v2
    [*] --> pending : 승인 요청 생성
    pending --> approved : 승인
    pending --> rejected : 거부
    pending --> timeout : 시한 초과 (감시기, CR-174)
    pending --> cancelled : 응답 중지, 스트림 끊김 (대화 승인, CR-185)
```

### pending | 대기
- **설명**: 승인을 기다리는 상태
- **진입 조건**: HUMAN_INPUT 스텝 도달 또는 REQUIRE_APPROVAL 정책 트리거. 대화 턴에서 도구 실행 전 승인을 묻기 시작(`approval_channel = chat`, CR-185)
- **허용 다음 상태**: `approved`, `rejected`, `timeout`, `cancelled`(대화 승인만)
- **관련 기능 ID**: PRD-063, PRD-364, PRD-560

### approved | 승인됨
- **설명**: 관리자, 워크플로우 승인자, 대화 턴을 연 사용자가 승인한 상태
- **진입 조건**: 승인 처리 (POST /approve). 대화 승인은 POST /chat/approvals/{approvalId} 도 (CR-185)
- **허용 다음 상태**: 없음 (종결 상태)
- **관련 기능 ID**: PRD-064, PRD-562

### rejected | 거부됨
- **설명**: 관리자, 워크플로우 승인자, 대화 턴을 연 사용자가 거절한 상태
- **진입 조건**: 거부 처리 (POST /reject). 대화 승인은 POST /chat/approvals/{approvalId} 도 (CR-185)
- **허용 다음 상태**: 없음 (종결 상태). 대화 승인은 AI 에게 「사용자가 실행을 거절했습니다」 도구 오류 결과
- **관련 기능 ID**: PRD-065, PRD-562

### timeout | 시한 초과 [CR-174]
- **설명**: `timeout_at` 이 지나도록 승인도 거부도 없어 감시기가 닫은 상태
- **진입 조건**: 승인 시한 감시기(PRD-364). 대화 승인(`chat`)은 기다리던 관문이 마감에 닫고, 서버가 다시 떠 관문이 사라진 줄은 감시기가 닫는다(CR-185)
- **허용 다음 상태**: 없음 (종결 상태). 워크플로우는 그 run 이 `failed`, 대화 승인은 AI 에게 「승인 시간이 지나 실행하지 않았습니다」 도구 오류 결과
- **관련 기능 ID**: PRD-364

### cancelled | 중지됨 [CR-185]
- **설명**: 대화 승인을 기다리는 중에 응답 중지(abort)나 스트림 끊김으로 닫힌 상태
- **진입 조건**: 관문이 응답 중지 신호나 스트림 끊김을 받음. `approval_channel = chat` 인 줄만
- **허용 다음 상태**: 없음 (종결 상태). 도구를 실행하지 않고 그 턴을 끝낸다
- **관련 기능 ID**: PRD-560

### 금지 전이 [CR-185]
- 종결 상태(`approved`, `rejected`, `timeout`, `cancelled`)에서 다른 상태로 가지 않는다. 결정 API 와 관리자 승인 API 는 `pending` 일 때만 바꾸는 한 번의 갱신으로 정하고, 아니면 409 `approval_closed`(BIZ-245)
- 워크플로우 승인(`workflow`)은 `cancelled` 로 가지 않는다. 대화 승인(`chat`)은 워크플로우 run 을 바꾸지 않는다

---

## Tenant (테넌트)

```mermaid
stateDiagram-v2
    [*] --> provisioning : 테넌트 생성 요청
    provisioning --> active : 프로비저닝 완료
    active --> suspended : 일시정지
    suspended --> active : 재활성화
    active --> deleted : 삭제
    suspended --> deleted : 삭제
```

### provisioning | 프로비저닝 중
- **설명**: 테넌트 DB 생성, 마이그레이션, 초기화가 진행 중인 상태
- **진입 조건**: 테넌트 생성 요청
- **허용 다음 상태**: `active`
- **관련 기능 ID**: PRD-080

### active | 활성
- **설명**: 테넌트가 정상 운영 중인 상태. API 접근 가능.
- **진입 조건**: 프로비저닝 완료 / 재활성화
- **허용 다음 상태**: `suspended`, `deleted`
- **관련 기능 ID**: PRD-084

### suspended | 정지
- **설명**: 테넌트가 일시 정지되어 API 접근이 차단된 상태
- **진입 조건**: 관리자의 일시정지 요청
- **허용 다음 상태**: `active`, `deleted`
- **관련 기능 ID**: PRD-083

### deleted | 삭제
- **설명**: 테넌트가 완전 삭제된 상태. DB 드롭 완료.
- **진입 조건**: 삭제 요청
- **허용 다음 상태**: 없음 (종결 상태)
- **관련 기능 ID**: PRD-085
- **비고**: 복구 불가

---

## User (사용자)

```mermaid
stateDiagram-v2
    [*] --> active : 사용자 생성
    active --> inactive : 비활성화
    inactive --> active : 재활성화
```

### active | 활성
- **설명**: 사용자가 정상적으로 시스템을 사용할 수 있는 상태
- **진입 조건**: 사용자 생성 / 재활성화
- **허용 다음 상태**: `inactive`
- **관련 기능 ID**: PRD-067

### inactive | 비활성
- **설명**: 사용자가 비활성화되어 로그인 불가한 상태
- **진입 조건**: DELETE /users/{id} (소프트 삭제)
- **허용 다음 상태**: `active`
- **관련 기능 ID**: PRD-070

---

## TtsJob (음성 생성 작업) [CR-166]

```mermaid
stateDiagram-v2
    [*] --> PENDING : 작업 맡기기
    PENDING --> RUNNING : 작업 스레드가 집음
    RUNNING --> COMPLETED : 소리 만들기 성공
    RUNNING --> FAILED : 생성기 실패, 연결 실패, 시간 초과
    RUNNING --> PENDING : api 재기동으로 차례에서 빠진 것을 다시 넣음
    COMPLETED --> [*]
    FAILED --> [*]
```

### PENDING | 대기
- **설명**: 맡겨졌고 차례를 기다린다
- **진입 조건**: POST /speech/tts-jobs, 또는 차례에서 빠진 RUNNING 작업을 조회나 같은 글 다시 맡기기로 다시 넣을 때
- **허용 다음 상태**: `RUNNING`
- **관련 기능 ID**: PRD-354

### RUNNING | 만드는 중
- **설명**: 생성기가 이 작업의 소리를 만들고 있다. 서버 전체에서 하나뿐이다(BIZ-133)
- **진입 조건**: 작업 스레드가 차례에서 꺼냄
- **허용 다음 상태**: `COMPLETED`, `FAILED`, `PENDING`(api 재기동 뒤 다시 넣을 때만)
- **관련 기능 ID**: PRD-354

### COMPLETED | 끝남
- **설명**: 소리가 저장됐다
- **진입 조건**: 생성기가 MP3 를 돌려줌
- **허용 다음 상태**: 없음 (종결 상태). 7일 뒤 행을 지운다(BIZ-135)
- **관련 기능 ID**: PRD-354

### FAILED | 실패
- **설명**: 만들지 못했다. error 에 까닭
- **진입 조건**: 생성기 422(검사 3번 실패), 생성기에 닿지 못함, 시간 초과
- **허용 다음 상태**: 없음 (종결 상태). 같은 글을 다시 맡기면 새 작업이 된다(BIZ-134)
- **관련 기능 ID**: PRD-354

---

## 생성기 모델 (저장하지 않음) [CR-167]

생성기 프로세스 안의 상태다. DB 에 남지 않고 `GET /health` 로만 보인다. 생성기가 다시 뜨면 처음부터다.

```mermaid
stateDiagram-v2
    [*] --> downloading : 창구 기동
    downloading --> unloaded : 모델 파일 준비 끝
    downloading --> failed : 받기 실패, 파일이 볼륨에 없음
    unloaded --> loading : 문장이 옴
    loading --> ready : 싣기와 견본 특징 끝
    loading --> backoff : 모델 프로세스가 죽음
    ready --> unloaded : 일이 없고 쉬는 시간 지남
    ready --> backoff : 만드는 중 모델 프로세스가 죽음
    backoff --> unloaded : 쉬는 시간 지남
```

금지 전이: 만드는 중의 `ready` 에서 `unloaded` 로 가지 않는다. `backoff` 에서 바로 `loading` 으로 가지 않는다. `failed` 는 다시 기동해야 벗어난다.

### downloading | 모델 파일 받는 중
- **설명**: 창구만 떠 있고 볼륨에 모델 파일을 받거나 있는지 본다. `/health` 503
- **진입 조건**: 생성기 기동
- **허용 다음 상태**: `unloaded`, `failed`
- **관련 기능 ID**: PRD-361

### failed | 모델 파일 없음
- **설명**: 받기에 실패했고 볼륨에도 파일이 없다. `/health` 503
- **진입 조건**: 받기 실패
- **허용 다음 상태**: 없음. 생성기를 다시 기동한다
- **관련 기능 ID**: PRD-361

### unloaded | 내려감
- **설명**: 모델 프로세스가 없고 메모리를 쓰지 않는다. `/health` 200
- **진입 조건**: 파일 준비 끝, 쉬는 시간이 지나 내림, `backoff` 가 끝남
- **허용 다음 상태**: `loading`
- **관련 기능 ID**: PRD-361

### loading | 싣는 중
- **설명**: 모델 프로세스가 모델과 견본 특징을 싣는다. 이때 온 문장은 잠금 앞에서 기다린다. `/health` 200
- **진입 조건**: `unloaded` 에서 문장이 옴
- **허용 다음 상태**: `ready`, `backoff`
- **관련 기능 ID**: PRD-361

### ready | 실림
- **설명**: 문장을 차례로 만든다. 마지막 문장이 끝난 때부터 쉬는 시간을 잰다. `/health` 200
- **진입 조건**: 싣기 끝
- **허용 다음 상태**: `unloaded`, `backoff`
- **관련 기능 ID**: PRD-361

### backoff | 실패 뒤 쉬는 중
- **설명**: 모델 프로세스가 죽어 `TTS_LOAD_RETRY_SECONDS` 동안 다시 싣지 않는다. `/health`, `/synthesize` 503
- **진입 조건**: 싣다가 또는 만들다가 모델 프로세스가 죽음
- **허용 다음 상태**: `unloaded`
- **관련 기능 ID**: PRD-361

---

## 작성 규칙

1. **화이트리스트 원칙**: 명시되지 않은 상태 전환은 불허한다
2. **진입 조건 필수**: 모든 상태는 어떤 조건에서 진입하는지 명시한다
3. **종결 상태 표시**: 더 이상 전환이 없는 상태는 "없음 (종결 상태)"로 표시한다
4. **관련 기능 ID 매핑**: 각 상태는 관련 기능(PRD-xxx)과 연결한다
5. **Mermaid 다이어그램**: 모든 엔티티에 시각적 상태도를 포함한다
6. **비고**: 특이사항이 있는 경우 비고란에 기재한다
