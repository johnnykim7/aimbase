# CR-204 대화 일괄 삭제 API 와 완전 삭제(LexFlow R3)

> 설계 버전: 1.0 | 최종 수정: 2026-11-27 | 관련 CR: CR-204, CR-046, CR-061, CR-175, CR-178, CR-182, CR-184, CR-194

- 원본 요구사항: [origins/원본_요구사항_CR204_대화_일괄_삭제_API_와_완전_삭제_20261127.md](origins/원본_요구사항_CR204_대화_일괄_삭제_API_와_완전_삭제_20261127.md)
- 출처: LexFlow 검토 회신 2026-11-25 의 I2, R3, Q4, Q9, 운영 3차. 실행맵 `docs/기록/실행맵_LexFlow_채팅창_요청_7건.md`
- 기능 ID: PRD-650, PRD-651
- 규칙: BIZ-292 ~ BIZ-296
- 규모: 중(소비앱 서버가 부르는 새 API 와 주기 삭제 추가. 테이블 변경 없음, 화면 없음)
- 범위 결정: 1.4 의 세 가지는 2026-11-27 컨타(lexflow-7434)에 승인요청. 승인 전에는 ① 안으로 설계한다

## 1. 범위

### 1.1 이번 CR 에 넣는 것

| 순번 | 기능 | 기능 ID | 바뀌는 곳 |
|---|---|---|---|
| 1 | 일괄 완전 삭제 API. 소비앱 서버가 연동키로 부른다. 사용자별(`user_ref`), 사건 대화별(`share_key`), 묶음(`user_ref`, `share_key` 접두어). 대화와 그 대화의 모든 기록과 파일을 DB 와 저장소에서 삭제한다(삭제 표시가 아니라 물리 삭제). 삭제하지 않고 건수만 보는 미리 보기 | PRD-650 | 서버 |
| 2 | 삭제 표시된 대화, 되감긴 질문과 답, 삭제 표시된 그림은 보관 기간이 지나면 자동으로 완전 삭제한다 | PRD-651 | 서버 |
| 3 | 보관 기간 정리와 소비앱 안내(API 가이드 40절, 운용 가이드 설정 키) | 없음 | 문서 |
| 4 | 실행기에 남는 CLI 대화 기록 사본의 보관 일수 설정(코드 아닌 운영 설정, 5절) | 없음 | 운영 |

### 1.2 넣지 않는 것

| 순번 | 무엇 | 이유 |
|---|---|---|
| 1 | Q9 특정 질문과 답변 하나만 삭제(가운데 턴) | 삭제하는 사람이 다르다. 이 CR 은 소비앱 서버가 연동키로 부르는 것이고, Q9 는 대화창을 쓰는 사람이 누르는 것이라 위젯 화면이 같이 바뀐다. 컨타는 이 CR 을 화면 없는 건으로 배정했다. 지금은 되감기(그 질문부터 뒤를 삭제)가 있고 이 CR 로 되감긴 것도 보관 기간 뒤 완전 삭제되므로, 가운데 턴 삭제는 별도 CR 후보로 넘긴다(7절) |
| 2 | 테넌트 관리 화면의 일괄 삭제 단추 | 지금 쓰는 곳이 없다. API 로만 제공한다 |
| 3 | 삭제 작업표(비동기 작업)와 진행 조회 | 한 번에 상한(기본 200 대화)만큼 삭제하고 남은 수를 돌려주는 반복 호출로 대신한다(BIZ-294). 작업표 없이도 끝까지 간다 |
| 4 | 사용량, 감사 로그(`usage_logs`, `audit_logs`) 삭제 | 대화 내용이 없고(토큰 수, 비용, 행위 이름) 과금과 감사의 근거다. T3-2 「대화 삭제」 절의 의도적 보존을 유지한다 |
| 5 | 토큰과 테이블에 사무소 구분 칸을 새로 두기 | 접두어로 한다(BIZ-294). LexFlow Q4 답변 초안의 형식(user_ref 는 `(사무소 ID):(사용자 ID)`, share_key 는 `lexflow:case:(사무소 ID):...`)과 맞고, aimbase 는 키의 뜻을 해석하지 않는다는 BIZ-277 을 지킨다 |
| 6 | 삭제 표시만 된 옛 대화를 반영 즉시 삭제 | PRD-651 주기가 처음 돌 때 보관 기간이 지난 것부터 상한만큼씩 삭제한다 |
| 7 | 실행기 CLI 프로세스에 남은 대화 기록을 삭제 요청 때 바로 삭제 | aimbase 는 CLI 세션 번호를 보관하지 않는다. 워커는 쉬면 풀이 닫고, 디스크 사본은 CLI 의 보관 일수 설정으로 삭제한다(5절) |

### 1.3 규칙

| 규칙 | 내용 |
|---|---|
| BIZ-292 | 완전 삭제 범위. 대화 하나를 완전 삭제하면 대화 행과 그 대화에 속한 모든 기록을 DB 에서 삭제하고 파일은 저장소에서 삭제한다. 메시지(삭제 표시된 것 포함), 첨부와 파일, 만든 그림과 파일, 옆 창 문서와 판과 파일, 공유 링크와 사본, 응답 평가, 대화 요약, 대화 기억, 도구 결과 보관, 승인 요청, 추적 기록(traces), 도구 실행 기록, Redis 대화 기록이다. 사용량과 감사 로그는 남긴다(내용 없음). 파일을 삭제하지 못한 대화는 DB 행을 남겨 두고 실패로 보고한다(파일만 남는 고아를 만들지 않는다) |
| BIZ-293 | 사용자별 삭제. `user_ref` 가 주인인 대화를 모두 완전 삭제하고, 그 사람의 자료(선호와 지시문, 자동 기억, 프로젝트와 자료 파일, 응답 평가)를 삭제한다. 함께 보는 대화에 남은 그 사람의 질문은 대화와 함께 두되 묻는 사람 이름을 삭제한다(user_ref 는 남는다. 소비앱의 식별자이고 사람 이름이 아니다) |
| BIZ-294 | 묶음 삭제와 반복 호출. 접두어는 글자 그대로 앞부분 일치이고 aimbase 는 뜻을 해석하지 않는다(BIZ-277). 조건이 하나도 없는 요청은 거절한다. 한 번 호출에 상한(`chat.purge.batch-limit`, 기본 200 대화)만큼 삭제하고 남은 대화 수를 돌려주며, 호출하는 쪽이 남은 수가 0 이 될 때까지 반복한다. 대화는 한 건씩 트랜잭션이라 중간에 끊겨도 삭제한 대화는 온전히 삭제해지고 남은 대화는 온전히 남는다 |
| BIZ-295 | 보관 기간. 대화는 삭제 전까지 보관한다. 삭제 표시된 대화, 되감긴 질문과 답, 삭제 표시된 그림과 문서 판은 삭제 시각부터 `chat.purge.retention-days`(기본 30일)가 지나면 자동으로 완전 삭제한다. 첨부 24시간(BIZ-101), 산출물 24시간, 음성 생성기 작업 7일은 지금 그대로다 |
| BIZ-296 | 호출 권한과 기록. 일괄 완전 삭제는 테넌트 API 키(연동키)나 관리자 로그인으로만 부른다. 위젯 토큰은 거절한다(BIZ-170). 호출마다 조건과 건수를 감사 로그에 남긴다(BIZ-020). 미리 보기는 기록하지 않는다 |

### 1.4 결정 (2026-11-27 사용자 승인)

세 건 모두 ① 로 정했다(2026-11-27 23:30 사용자 승인, 컨타 lexflow-7434 전달).

| 순번 | 정할 것 | 선택지 |
|---|---|---|
| 1 | Q9 특정 질문과 답변 하나만 삭제 | ① 이 CR 에서 빼고 별도 CR 후보(위젯 화면 포함)로 넘긴다. 답변에는 되감기와 보관 기간 뒤 완전 삭제로 안내한다 (권장) ② 이 CR 에 넣는다(턴 삭제 API 와 위젯 단추) |
| 2 | 삭제 표시된 것의 완전 삭제 | ① 삭제 뒤 30일(설정값)이 지나면 자동 완전 삭제한다 (권장) ② 지금처럼 표시만 두고 완전 삭제는 API 를 부를 때만 한다 |
| 3 | 사무소 묶음 기준 | ① `user_ref`, `share_key` 접두어. LexFlow 가 사무소 ID 를 앞에 붙인다(답변 초안 Q4 형식) (권장) ② 토큰 발급에 사무소 칸을 더하고 대화 테이블에 새 열을 둔다 |

## 2. 지금 상태 (설계 근거, origin/dev b83dcb32)

| 순번 | 무엇 | 근거 |
|---|---|---|
| 1 | 관리용 대화 삭제는 소유자만, 삭제 표시다. 답이 돌고 있으면 멈추고, 대화와 메시지에 `deleted_at`, 만든 그림에 삭제 표시(파일은 남김), 공유 링크 끊기, Redis 기록 비우기까지 한다 | `api/ConversationController.java:83-104`, `session/ChatHistoryService.java:199-212` |
| 2 | 위젯의 대화 삭제도 같은 서비스다 | `session/ChatHistoryService.java:190-192` |
| 3 | `user_ref` 나 `share_key` 로 여러 대화를 삭제하는 길은 없다. 대화 저장소는 번호 하나, 함께 보기 키 하나로만 찾는다 | `repository/ConversationSessionRepository.java:20-66` |
| 4 | 되감기는 그 턴부터 뒤 메시지에 삭제 표시(`delete_reason` REWIND)를 하고 Redis 기록을 남은 행으로 다시 만든다. 가운데 메시지 하나만 삭제하는 길은 없다 | `session/SessionStore.java:184-230`, `session/ConversationRewindService.java:56-70` |
| 5 | 삭제 표시된 행을 물리 삭제하는 주기는 없다. 순번 고유 색인은 살아 있는 행에만 걸려 있어 되감긴 뒤 같은 순번을 다시 쓸 수 있다 | `db/migration/tenant/V51__cr046_conversation_soft_delete.sql`, `V77__cr175_chat_turn_feedback.sql:15-18` |
| 6 | 첨부는 올린 뒤 24시간(`widget.attachment.ttl-seconds`)이 지나면 5분마다 도는 GC 가 저장소 파일과 행을 삭제한다. 대화를 삭제해도 첨부는 그대로 두고 GC 가 삭제한다 | `attachment/AttachmentService.java:41,159`, `attachment/AttachmentGcScheduler.java:42-78` |
| 7 | 만든 그림은 삭제 표시만 하고 파일은 남긴다 | `session/ChatHistoryService.java:206`, `repository/ChatGeneratedImageRepository.java:22-34` |
| 8 | 옆 창 문서(`chat_documents`, `chat_document_versions`)의 파일(`storage_path`, `source_storage_path`)은 대화를 삭제할 때 손대지 않는다 | `db/migration/tenant/V84__cr181_chat_documents.sql`, `session/ChatHistoryService.java:199-212`(문서 처리 없음) |
| 9 | 공유 링크를 끊으면 사본 파일을 삭제하고 행은 남긴다 | `share/ChatShareService.java:170-205` |
| 10 | 대화를 삭제할 때 손대지 않는 기록: 응답 평가 `message_feedback`(session_id, user_ref), 요약 `session_briefs`, 대화 기억 `conversation_memories`(session_id, 자동 기억은 user_id = user_ref), 도구 결과 `tool_result_storage`(24시간), 승인 `pending_approvals`(session_id, tool_input), 추적 `traces`(messages_in, response 에 대화 내용), 도구 실행 `tool_execution_log`(input_full, output_full). `usage_logs`, `audit_logs` 는 내용이 없다 | `V77`, `V45`, `V25.3`, `V82`, `V52`, `V88`, `V21`, `V34`, `V11`, `V13` 마이그레이션, T3-2 「대화 삭제 (Soft Delete)」 |
| 11 | 사람 단위 자료: 선호와 지시문 `widget_user_preferences`(user_ref), 프로젝트 `chat_projects`(owner_ref)와 자료 파일 `chat_project_files`(storage_path, 프로젝트 삭제하면 같이), 음성 생성기 작업 `tts_jobs`(created_by 는 로그인 사용자, 7일 뒤 삭제). 위젯 읽어 주기 음성은 저장하지 않고 바로 흘려준다 | `V82`, `V81`, `V75`, `speech/TtsJobService.java:69,125`, `api/TtsJobController.java:49-52`, `speech/WidgetTtsService.java`(저장 없음) |
| 12 | 연동키는 `X-API-Key` 또는 `Authorization: ApiKey` 로 오고 역할 admin, id `system-(키 id)` 가 된다. `/api/v1/conversations` 는 위젯 토큰을 거절한다. 메서드 권한 검사(`@PreAuthorize`)를 쓸 수 있다 | `auth/ApiKeyAuthenticationFilter.java:90-124`, `api/ConversationController.java:44-49`, `config/SecurityConfig.java:34` |
| 13 | 함께 보기 키는 128자, 영문, 숫자, `:`, `/`, `-`, `_`, `.`, `[`, `]` 만(BIZ-277). user_ref 는 200자까지 온다 | `session/ChatKeys.java:11-20`, `V77:29`, `V82:6,15` |
| 14 | 묻는 사람은 질문 행의 `display.asker` 에 `user_ref`, `user_name` 으로 남는다(BIZ-278) | `session/SharedConversationService.java:95-101`, `api/ChatController.java:678-691` |
| 15 | Redis 대화 기록 키는 `tenant:(테넌트):session:messages:(대화 번호)` 하나다 | `session/SessionStore.java:49,234-241` |
| 16 | 실행기 워커는 살아 있는 동안 CLI 세션을 이어 쓰고(`--resume`), CLI 는 계정 폴더(`CLAUDE_CONFIG_DIR`) 아래에 대화 기록 사본을 둔다. aimbase 는 CLI 세션 번호를 보관하지 않는다 | `cli-runner/.../ClaudeCliWorker.java:72-74,375,612` |
| 17 | 산출물(`out/`)은 24시간(`aimbase.artifact.ttl-hours`) 뒤 GC 가 삭제한다. 설정값은 `PlatformSettingsService.getInt/getLong` 으로 읽고 DB 설정이 없으면 코드 기본값이다 | `artifact/ArtifactGcScheduler.java:43-50`, `config/PlatformSettingsService.java:68-82` |

## 3. 서버

### 3.1 일괄 완전 삭제 API [PRD-650, BIZ-292 ~ BIZ-294, BIZ-296]

`POST /api/v1/conversations/purge`. 테넌트 API 키 또는 관리자 로그인(`ROLE_ADMIN`, `ROLE_SUPER_ADMIN`). 위젯 토큰은 403 `widget_forbidden`(기존).

요청 본문. 네 조건 중 하나 이상. 둘 이상 주면 어느 하나에라도 맞는 대화를 모두 삭제한다(합집합).

| 칸 | 타입 | 필수 | 뜻 |
|---|---|---|---|
| user_ref | string | 선택 | 이 값이 주인(`user_id`)인 대화 전부와 이 사람의 자료(BIZ-293) |
| share_key | string | 선택 | 이 함께 보기 키의 대화(삭제 표시된 것 포함) |
| user_ref_prefix | string | 선택 | 이 글자로 시작하는 모든 `user_ref` 의 대화와 자료 |
| share_key_prefix | string | 선택 | 이 글자로 시작하는 모든 함께 보기 키의 대화 |
| dry_run | boolean | 선택, 기본 false | true 면 삭제하지 않고 맞는 대화 수와 사람 수만 돌려준다 |
| limit | int | 선택 | 이번 호출에서 삭제할 대화 상한. 비우면 `chat.purge.batch-limit`(200). 그 값을 넘으면 그 값으로 줄인다 |

응답 200.

```json
{
  "dry_run": false,
  "completed": true,
  "sessions": {"matched": 12, "deleted": 12, "remaining": 0, "failed": 0, "failed_session_ids": []},
  "rows": {"messages": 340, "attachments": 5, "generated_images": 2, "documents": 1, "document_versions": 3,
           "shares": 1, "feedback": 3, "memories": 4, "briefs": 2, "approvals": 0, "tool_results": 0,
           "traces": 50, "tool_logs": 10},
  "files": {"deleted": 11, "failed": 0},
  "users": {"matched": 3, "preferences": 3, "memories": 7, "projects": 1, "project_files": 2,
            "feedback": 4, "asker_names_cleared": 12}
}
```

- `sessions.matched` 는 조건에 맞는 대화 전부(상한 무관), `deleted` 는 이번 호출에서 삭제 수, `remaining` 은 아직 남은 수(`matched - deleted - failed`). `failed` 는 파일을 삭제하지 못해 행을 남긴 대화 수이고 번호는 20개까지 보인다.
- `users` 는 `user_ref` 나 `user_ref_prefix` 를 준 요청에서, 남은 대화가 0 일 때 한 번 처리하고 채운다. 그 전 호출에서는 비어 있다(`null`).
- `completed` 는 `remaining` 이 0 이고 `failed` 가 0 이고 사람 자료 처리까지 끝났을 때 true 다. false 면 같은 본문으로 다시 부른다. 파일 삭제가 계속 실패하면 `failed_session_ids` 를 운영에 알린다.
- `dry_run` 이 true 면 `sessions.matched`, `users.matched` 만 채우고 나머지는 비운다. 감사 기록도 남기지 않는다.

| 상태 | 코드 | 경우 |
|---|---|---|
| 400 | `purge_invalid:criteria` | 네 조건이 모두 비어 있음 |
| 400 | `purge_invalid:share_key` | `share_key` 가 BIZ-277 글자 규칙에 어긋남 |
| 400 | `purge_invalid:share_key_prefix` | 접두어가 비어 있거나 128자를 넘거나 BIZ-277 글자 규칙에 어긋남 |
| 400 | `purge_invalid:user_ref` | 비어 있거나 200자를 넘음 (`user_ref_prefix` 도 같은 코드) |
| 400 | `purge_invalid:limit` | 1 보다 작음 |
| 403 | `widget_forbidden` | 위젯 토큰 |
| 403 | (Spring Security) | 관리자가 아닌 로그인 |

### 3.2 대화 하나의 삭제 순서 [BIZ-292]

대화 한 건이 한 트랜잭션이다. 파일을 먼저 삭제하고 DB 를 삭제한다. 반대로 하면 행이 사라진 파일(고아)이 남아 찾을 수 없다.

| 순서 | 자리 | 무엇 | 방법 |
|---|---|---|---|
| 1 | 응답 | 답이 돌고 있으면 멈춤 | `CancellationRegistry.cancel(sessionId)` (기존) |
| 2 | 저장소 | 첨부 파일 `chat_attachments.storage_path`, 그림 `chat_generated_images.storage_path`, 문서 판 `chat_document_versions.storage_path`, `source_storage_path`, 공유 사본 폴더(`chat_shares.snapshot_path` 의 부모) | `StorageService.delete`. false 가 돌아오면 `exists` 로 다시 본다. 아직 있으면 이 대화는 실패로 두고 3 이하를 하지 않는다 |
| 3 | DB | `conversation_messages`, `chat_attachments`, `chat_generated_images`, `chat_document_versions`(문서 id 로), `chat_documents`, `chat_shares`, `message_feedback`, `session_briefs`, `conversation_memories`(session_id), `tool_result_storage`, `pending_approvals`, `traces`, `tool_execution_log` | `session_id` 로 물리 삭제(삭제 표시 여부 무관) |
| 4 | DB | `conversation_sessions` 행 | 물리 삭제 |
| 5 | Redis | 대화 기록 | `SessionStore.clearSession` (기존) |

실행기 쪽 CLI 워커는 따로 부르지 않는다. 멈춘 답은 1 에서 끊기고, 쉬는 워커는 풀이 닫는다(1.2 의 7).

### 3.3 조건에 맞는 대화 찾기 [BIZ-294]

- `user_ref`: `conversation_sessions.user_id = :ref`
- `share_key`: `conversation_sessions.share_key = :key`
- `user_ref_prefix`: `user_id LIKE :p ESCAPE '\'`. 접두어의 `\`, `%`, `_` 는 이스케이프해서 글자 그대로 맞춘다
- `share_key_prefix`: `share_key LIKE :p ESCAPE '\'`
- 삭제 표시(`deleted_at`) 여부를 가리지 않는다. 생성 순으로 상한만큼 집는다.

### 3.4 사람 자료 삭제 [BIZ-293]

`user_ref` 또는 `user_ref_prefix` 조건이 있고 남은 대화가 0 일 때 한 번 한다. 접두어는 3.3 과 같은 LIKE 다.

| 순서 | 자리 | 방법 |
|---|---|---|
| 1 | `chat_projects`(owner_ref) | 프로젝트마다 자료 파일을 저장소에서 삭제하고 행을 삭제한다(`chat_project_files` 는 FK ON DELETE CASCADE) |
| 2 | `widget_user_preferences`(user_ref) | 물리 삭제 |
| 3 | `conversation_memories`(user_id) | 물리 삭제(자동 기억 USER_PROFILE 포함, 모든 종류) |
| 4 | `message_feedback`(user_ref) | 물리 삭제(남의 대화나 함께 보는 대화에 남긴 평가) |
| 5 | `conversation_messages.display.asker` | `user_ref` 가 맞는 질문 행의 `asker.user_name` 키를 뗀다(`display #- '{asker,user_name}'`). 행과 `user_ref` 는 남는다 |

`users.matched` 는 대화 주인, 선호, 프로젝트에서 모은 서로 다른 `user_ref` 수다.

### 3.5 삭제한 것의 자동 완전 삭제 [PRD-651, BIZ-295]

`ConversationPurgeScheduler`. 1시간마다, 처음은 기동 10분 뒤, 캐시된 테넌트마다(`AttachmentGcScheduler` 와 같은 순회). 기준 시각은 지금에서 `chat.purge.retention-days` 를 뺀 시각이다.

| 순서 | 대상 | 방법 |
|---|---|---|
| 1 | `conversation_sessions.deleted_at < 기준` 인 대화 | 3.2 와 같은 완전 삭제. 한 회차에 `chat.purge.batch-limit` 만큼 |
| 2 | 살아 있는 대화의 `conversation_messages.deleted_at < 기준` 인 행(되감긴 질문과 답) | 행 물리 삭제. 그 행이 속한 턴의 `chat_generated_images.deleted_at < 기준` 그림은 파일과 행을, `chat_document_versions.deleted_at < 기준` 판은 파일과 행을 삭제한다 |

실패한 대화는 다음 회차에 다시 본다. 회차 결과(테넌트, 삭제 수, 실패 수)는 로그에 남긴다.

### 3.6 감사 기록 [BIZ-296]

호출마다 `AuditLogger.log("conversation_purge", 조건 요약, 호출자 id, null, {criteria, sessions, users, files})`. `dry_run` 은 남기지 않는다.

### 3.7 코드 자리

| 무엇 | 자리 |
|---|---|
| 조건 값 검증, 응답 조립 | `api/ConversationController.java` 에 `POST /purge`(`@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")`) |
| 대화 찾기, 한 건 완전 삭제, 사람 자료 삭제, 결과 집계 | `session/ConversationPurgeService.java`(신규), `session/ConversationPurgeRequest.java`(신규, 조건과 상한), `session/ConversationPurgeResult.java`(신규) |
| 주기 삭제 | `session/ConversationPurgeScheduler.java`(신규) |
| 저장소 조회와 삭제 추가 | `ConversationSessionRepository`(조건별 찾기, 삭제 표시 지난 것 찾기), `ConversationMessageRepository`(삭제 표시 지난 행, asker 이름 떼기), `ChatGeneratedImageRepository`, `ChatDocumentRepository`, `ChatDocumentVersionRepository`, `ChatShareRepository`, `MessageFeedbackRepository`, `ConversationMemoryRepository`, `ToolResultStorageRepository`, `PendingApprovalRepository`, `TraceRepository`, `ToolExecutionLogRepository`, `WidgetUserPreferenceRepository`, `ChatProjectRepository` 에 `session_id` 또는 `user_ref` 기준 삭제와 LIKE 조회 |
| 설정 키 | `chat.purge.retention-days`(30), `chat.purge.batch-limit`(200). `PlatformSettingsService.getInt` 로 읽는다 |

## 4. 데이터

테이블과 열은 바뀌지 않는다. 마이그레이션 없음. 설정 키 둘은 DB 설정이 없으면 코드 기본값이다(2절 17).

## 5. 운영 작업: 실행기의 CLI 대화 기록 사본

Claude CLI 는 계정 폴더 `projects/` 아래에 세션 대화 기록을 둔다(2절 16). 이 사본은 aimbase DB 밖에 있고 aimbase 는 세션 번호를 모르므로 API 로 삭제하지 못한다. CLI 설정 `cleanupPeriodDays` 로 보관 일수를 정한다(기본 30일, 기록의 마지막 활동 뒤 그 일수가 지나면 CLI 가 기동 때 삭제한다).

- 대상: 59 의 `cli-runner-lexflow-chat` 실행기가 쓰는 계정 폴더의 `settings.json`
- 값: 7일로 둔다(음성 생성기 작업 7일과 같은 기준). 적용 전에 그 서버의 현재 값과 폴더 위치를 확인하고, 운영 서버 설정 변경이라 사용자 승인 뒤 한다
- 이 값은 보관 기간 안내(6절)에 함께 적는다
- 확인(2026-11-28, 59 읽기만): LexFlow 연결 `cli-runner-lexflow-chat`, `cli-runner-lexflow-001` 의 계정 폴더는 `local-agent-1` 이다. 같은 폴더를 axopm, 입찰, bp-office, bp-wes, workmap 연결도 쓴다. 폴더에 `settings.json` 이 없어 지금은 CLI 기본 30일이고, 대화 기록 폴더 309개 중 가장 오래된 것이 10-29, 7일이 넘은 것이 267개다
- 오래된 기록을 삭제해도 대화 이어 가기는 깨지지 않는다. 실행기는 새 워커를 기록 없이 띄우고(`ClaudeCliWorkerPool.java:466`), 이전 세션을 이어 쓰는 것은 살아 있는 워커에서 갈라지는 병렬 갈래뿐이다(`ClaudeCliWorkerPool.java:576`)
- 적용(2026-11-28 14:58, 사용자 14:56 승인, 컨타 전달): 59 `/home/therecommerce/aimbase/claude-accounts/local-agent-1/settings.json` 을 새로 만들어 `{"cleanupPeriodDays": 7}` 을 넣었다(파일이 없었다, 주인 uid 999). LexFlow, bp-office, axopm 실행기에서 같은 값이 보인다. CLI 가 다음에 뜰 때 7일 지난 기록을 삭제한다

## 6. 보관 기간 (LexFlow 답변과 API 가이드에 적는 값)

| 무엇 | 보관 | 어디서 정하나 |
|---|---|---|
| 대화와 질문, 답 | 삭제 전까지. 사람이 삭제하거나 되감으면 삭제 표시가 되고 30일 뒤 완전 삭제. 삭제 API 는 바로 완전 삭제 | `chat.purge.retention-days` |
| 첨부 파일 | 올린 뒤 24시간 | `widget.attachment.ttl-seconds` (BIZ-101) |
| 만든 그림, 옆 창 문서 파일 | 대화와 같다 | 위와 같다 |
| 공유 링크 사본 | 링크 기한까지. 끊으면 바로 | `chat.share.*` (CR-182) |
| 실행기 산출물(`out/`) | 24시간 | `aimbase.artifact.ttl-hours` |
| 위젯 읽어 주기 음성 | 저장하지 않는다 | 없음 |
| 음성 생성기 작업(`tts_jobs`, LexFlow 미사용) | 7일 | `aimbase.tts.retention-days` |
| 실행기 CLI 대화 기록 사본 | 마지막 활동 뒤 7일 | CLI `cleanupPeriodDays` |
| 사용량, 감사 로그 | 보존(대화 내용 없음) | 없음 |

## 7. Q9 판단: 특정 질문과 답변만 삭제

- 지금: 없다. 되감기(질문 수정, 다시 만들기)가 그 질문부터 뒤를 삭제 표시한다. 함께 보는 대화에서는 마지막 질문을 한 사람만 되감는다(BIZ-280).
- 이 CR 뒤: 되감긴 것은 30일 뒤 완전 삭제된다(BIZ-295). 실수로 넣은 개인정보가 마지막 질문이면 되감기로 바로 보이지 않게 하고 30일 뒤 사라진다.
- 남는 것: 가운데 질문 하나만 삭제. 위젯에 질문별 삭제 단추가 필요하고, 메시지 하나를 삭제 뒤 Redis 기록 재구성, 그 턴의 그림과 문서 판 처리, 함께 보는 대화의 권한(누가 남의 질문을 삭제하나) 결정이 든다. 별도 CR 후보로 넘긴다(`docs/CR/BACKLOG.md` 없음, 컨타에 `[알림]`).
- 답변 초안 Q9 줄은 「되감기로 그 질문부터 삭제할 수 있고 삭제한 것은 30일 뒤 완전 삭제됩니다. 가운데 질문 하나만 삭제는 별도 개발 항목입니다」 로 고친다.

## 8. 레포 간 알림 (lexflow 안내서와 답변 문서에 반영할 것)

완료 뒤 컨타(lexflow-7434)에 `[알림]` 한 줄과 이 문서 경로를 보낸다. 반영할 것:

- API: `POST /api/v1/conversations/purge`, 본문 칸 6개, 반복 호출 규칙, 응답 칸. 규격은 API 가이드 40절
- 보관 기간: 6절 표
- Q4: 접두어 형식에 동의. 사무소 삭제는 `user_ref_prefix` 와 `share_key_prefix` 를 한 요청에 싣는다(예 `"user_ref_prefix": "(사무소 ID):", "share_key_prefix": "lexflow:case:(사무소 ID):"`). 접두어는 구분자까지 넣어야 `12` 가 `123` 을 삭제하지 않는다
- Q9: 7절

## 9. 시험

### 9.1 단위시험 (T3-5)

| TC ID | 시나리오 | 관련 |
|---|---|---|
| TC-CHT-162 | 조건 검증. 조건 없음, share_key 글자 규칙 위반, 빈 접두어, 200자 넘는 user_ref, limit 0 | PRD-650, BIZ-294 |
| TC-CHT-163 | 대화 한 건 완전 삭제 순서. 파일 먼저 삭제하고 13개 표와 대화 행을 삭제하고 Redis 를 비운다. 파일이 남아 있으면 행을 남기고 실패로 센다 | PRD-650, BIZ-292 |
| TC-CHT-164 | 조건별 찾기와 반복 호출. user_ref, share_key, 두 접두어 합집합, 삭제 표시된 대화 포함, 상한과 remaining, dry_run | PRD-650, BIZ-294 |
| TC-CHT-165 | 사람 자료. 남은 대화 0 일 때 프로젝트 파일과 행, 선호, 기억, 평가를 삭제하고 함께 보는 대화의 묻는 사람 이름을 뗀다. 남은 대화가 있으면 하지 않는다 | PRD-650, BIZ-293 |
| TC-CHT-166 | 주기 삭제. 기준 시각 지난 삭제한 대화는 완전 삭제, 살아 있는 대화의 되감긴 행과 그 그림, 문서 판 파일 삭제, 기준 안의 것은 그대로 | PRD-651, BIZ-295 |

### 9.2 통합 시험항목

영역 29 「대화 일괄 완전 삭제와 보관 기간」 `tools/testbook/items/items_29.py`, IT-29-001 ~ IT-29-009. 근거는 이 문서 1.3, 3절이다.

## 10. 구현 순서

1. 설계 반영과 설계분 커밋(T1-1, T1-3, T1-7, T3-2, T3-5, 시험항목 영역 29, CR 대장, 이 문서)
2. 저장소 조회와 삭제 메서드, `ConversationPurgeService`, 컨트롤러, 단위시험
3. `ConversationPurgeScheduler`, 단위시험
4. API 가이드 40절, 운용 가이드 설정 키, 변경 이력
5. 로컬 확인: 로컬 api 와 로컬 DB 에서 대화를 만들고 첨부, 그림, 평가를 남긴 뒤 API 로 삭제해 표와 파일이 비는 것, 접두어 반복 호출, dry_run, 위젯 토큰 거절을 본다
6. 59 배포(`./deploy.sh be`, 사용자 승인), 5절 운영 설정(사용자 승인), 컨타에 `[알림]`

## 11. 확인 기록

- 2026-11-27 설계 작성. 결정 대기 1.4 를 컨타에 승인요청.
- 2026-11-27 설계 반영: T1-1 v7.25(PRD-650, 651), T1-3 v5.27(BIZ-292 ~ 296), T1-7 v3.35, T1-10 v1.12, T3-2 v7.31, T3-5 v4.29(TC-CHT-162 ~ 166), 시험항목 영역 29(IT-29-001 ~ 009), API 가이드 v3.47.0 40절, 운용 가이드 v3.47.0. T2, T3-1 은 바뀌지 않는다(테이블 변경 없음).
- 2026-11-27 23:30 범위 3건을 ① 로 확정(사용자 승인, 컨타 전달). 설계와 구현이 이미 ① 기준이라 바꾼 것은 없다.
- 2026-11-28 구현: 일괄 완전 삭제 API, 보관 기간 뒤 자동 완전 삭제 주기, 저장소 삭제 질의. 단위시험 21건 통과.
- 2026-11-28 로컬 확인(작업 사본 api 8251, 로컬 DB 테넌트 `cr196_local`, 전용 Redis 16204, 저장소는 임시 폴더). 시험항목 IT-29-001 ~ 009 를 서버 호출과 DB 조회로 봤다. 조건 오류 4가지는 400, 위젯 토큰은 403 `widget_forbidden`, 뷰어 키는 403. dry_run 은 건수만 돌려주고 감사 기록을 남기지 않는다. 사무소 A 를 두 접두어와 limit 1 로 부르면 두 번에 나눠 대화 2개를 삭제하고, 남은 것이 없어진 호출에서 사용자 자료(선호 2, 기억 1, 프로젝트 1, 평가 1, 묻는 사람 이름 1)를 정리한다. 사무소 B 대화는 남고 그 안의 A 사람 이름만 빠진다. 함께 보기 키 하나, 사용자 하나 단위도 그 대화만 삭제한다. 시험 파일 13개(첨부, 그림, 문서 판, 공유 사본, 프로젝트 자료)가 저장소에서 모두 사라졌다. 감사 기록은 삭제 호출 5번에 5건이다. 자동 주기는 기동 10분 뒤 30일 지난 삭제 표시 대화 1, 되감긴 질문 1, 삭제 표시 그림 1 을 삭제하고 5일 된 것은 남겼다.
- 로컬의 마이그레이션이 덜 된 옛 테넌트 DB 에서는 자동 주기가 칸 없음 오류를 남기고 그 테넌트만 건너뛴다. 로컬 환경 문제이고 59 는 기동 때 테넌트 마이그레이션이 돈다.
- 2026-11-28 origin/dev c807052d(CR-206) 위로 옮겼다. 같은 판 번호를 잡은 설계서는 다음 판(T1-1 v7.25, T1-3 v5.27, T1-7 v3.35, T1-10 v1.12, T3-2 v7.31, T3-5 v4.29, 가이드 v3.47.0)으로, API 가이드 절은 40절로 옮겼다. 옮긴 코드로 관련 단위시험 45건 통과, 로컬 재기동 뒤 조건 오류, 뷰어 거절, 삭제 표시 대화를 포함한 접두어 삭제를 다시 봤다.
