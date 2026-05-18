# 원본 요구사항 — SessionStore 정합성 정공 정리 (CR-083)

**일자**: 2026-04-30
**대화 맥락**: 사용자 지시 "SessionStore append race 부터 잡고, 그 다음 세션 관리 UX 정책 논의"
**결정**: 핫픽스(옵션 A)가 아니라 **정공(옵션 B)** 으로 진행 — 사용자 명시 "정본으로 가시죠"

---

## 1. 발단 — 운영 로그

```
SessionStore: Session {sid} DB has 6 messages but memory has 4 — skipping append
```

증상:
- 위젯 두 번째 turn 메시지가 LLM에 전달 안 되는 듯한 보고
- DB 메시지 카운트가 메모리(Redis) 보다 큰 비대칭 발생

→ 단순 race 수정으로 봤는데, SessionStore 코드 전수 점검 결과 **race 외에 6개의 추가 정합성 이슈** 발견.

---

## 2. SessionStore 전수 점검 결과 (7개 이슈)

### 2.1 appendNewMessages count race (운영 로그의 직접 원인)

[SessionStore.java:213-232](backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L213-L232) — `existing = countBySessionId()` 후 `existing < messages.size()` 검사 + INSERT. 같은 sessionId 의 비동기 VT 가 동시에 진입하면 다른 VT 가 먼저 INSERT 한 결과를 못 보고 stale snapshot 으로 INSERT → 카운트 불일치.

### 2.2 appendMessage RMW race (LLM 누락의 진짜 원인 후보)

[SessionStore.java:96-100](backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L96-L100):
```java
List<UnifiedMessage> messages = getMessages(sessionId);  // Redis read
messages.add(message);
saveMessages(sessionId, messages);                        // Redis write
```

OrchestratorEngine 이 `request.messages().forEach(appendMessage)` 를 빠르게 연속 호출 ([OrchestratorEngine.java:417](backend/platform-core/src/main/java/com/platform/orchestrator/OrchestratorEngine.java#L417), [638](backend/platform-core/src/main/java/com/platform/orchestrator/OrchestratorEngine.java#L638)). 동시 stream 요청에서 sessionId 같으면 lost-update 발생 → **메시지 자체가 Redis 에서 사라짐** → 다음 turn 의 LLM history 에 빠짐.

### 2.3 Tool/Image 블록 lossy 저장 — 가장 심각

[SessionStore.java:144](backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L144), [234-239](backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L234-L239):
```java
List.of(new ContentBlock.Text(msg.getContent()))   // 로드: Text 만 복원
extractText(msg)                                    // 저장: Text 만 concat
```

ContentBlock 종류:
- Text ✓ (저장됨)
- ToolUse ✗ (영구 손실)
- ToolResult ✗ (영구 손실)
- Image ✗ (영구 손실)
- Thinking ✗ (영구 손실)

`messageType` 컬럼이 있는데도 모두 `TYPE_TEXT` 고정 ([SessionStore.java:225-229](backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L225-L229)).

→ **Redis TTL 만료 후 세션 복원 시 도구 호출 흔적 전부 사라짐** → LLM 이 빈 도구 결과 보고 환각. CR-061 첨부 이미지·CR-054 HttpRequestTool 결과 등 모든 멀티모달 컨텍스트 손실.

### 2.4 Redis/DB 진실 불일치

- Redis: UnifiedMessage JSON 직렬화 (모든 블록 보존)
- DB: 텍스트만 lossy 저장
- 24h TTL 만료 → loadFromDb 로 복원 시 **본질적으로 다른 세션이 됨**

### 2.5 VT 무제한 생성

[SessionStore.java:82-93](backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L82-L93) — `Thread.ofVirtual().start(...)` 매 saveMessages 마다 신규 VT. 부하 시 sessionId 하나당 수십 개 동시 INSERT 시도 → DB 커넥션 풀 고갈 + 락 경합 증폭.

### 2.6 loadFromDb 캐시 덮어쓰기 race

[SessionStore.java:147-154](backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L147-L154) — TTL 만료 후 `getMessages` → DB 로드 → Redis SET. 그 사이에 다른 thread 가 `appendMessage` 로 user#1 을 SET 했다면 DB 로드 결과(0개)가 그 SET 을 덮어버림 → 방금 추가한 메시지 분실.

### 2.7 createdAt 정렬 비결정성

[ConversationMessageRepository.java:19](backend/platform-core/src/main/java/com/platform/repository/ConversationMessageRepository.java#L19) — `ORDER BY createdAt ASC`. `OffsetDateTime.now()` 가 같은 ms 면 user/assistant 순서 뒤섞임.

---

## 3. 정공 설계 (옵션 B)

### 3.1 스키마 변경 (Flyway V61)

```sql
ALTER TABLE conversation_messages
    ADD COLUMN seq INTEGER,
    ADD COLUMN content_json JSONB;

-- 기존 row backfill: createdAt 순서대로 seq 부여
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) - 1 AS rn
    FROM conversation_messages
)
UPDATE conversation_messages cm
SET seq = ranked.rn,
    content_json = jsonb_build_array(jsonb_build_object('type', 'text', 'text', cm.content))
FROM ranked
WHERE cm.id = ranked.id;

ALTER TABLE conversation_messages
    ALTER COLUMN seq SET NOT NULL,
    ALTER COLUMN content_json SET NOT NULL,
    ADD CONSTRAINT uq_conv_msg_session_seq UNIQUE (session_id, seq);

CREATE INDEX idx_conv_msg_session_seq ON conversation_messages (session_id, seq);
```

`content` 컬럼은 호환성 위해 유지하되 `extractText(content_json)` 를 항상 동기화 (검색 / 호환).

### 3.2 SessionStore 재작성 핵심

**(a) per-session 직렬화** — `ConcurrentHashMap<String, Semaphore>` 로 sessionId 별 Semaphore(1). 단일 가상스레드 Executor 1개로 전환.

**(b) 멀티블록 보존** — `content_json` JSONB 로 ContentBlock 리스트 통째 직렬화. loadFromDb 도 JSONB 역직렬화. `messageType` 도 ToolUse/ToolResult 등 정확히 셋팅.

**(c) seq 기반 idempotent INSERT** — `INSERT ... ON CONFLICT (session_id, seq) DO NOTHING`. 카운트 비교 폐기.

**(d) Redis 갱신은 lock 안에서만** — `loadFromDb` 캐시 SET 도 lock 보호. lost update 차단.

**(e) 정렬은 seq 우선** — Repository 쿼리 `ORDER BY seq ASC` (createdAt fallback 제거).

### 3.3 OrchestratorEngine 영향

호출 측 변경 없음 — `appendMessage(sessionId, message)` 시그니처 동일. 내부적으로만 lock + JSONB.

### 3.4 운영 데이터 backfill

- 기존 `content` (텍스트) → `content_json = [{type:text, text:content}]` 로 변환
- ToolUse/ToolResult 등은 어차피 이미 손실됨 — 복원 불가 (수용)
- seq 는 createdAt 순으로 ROW_NUMBER 부여

### 3.5 추정

**5MD** — 마이그레이션 1MD + SessionStore 재작성 2MD + 테스트 1MD + 회귀/배포 1MD

---

## 4. 결정 사항 (사용자 명시)

| 항목 | 결정 |
|---|---|
| 핫픽스 vs 정공 | **정공 (옵션 B)** |
| CR 번호 | **CR-083** (CR-082 다음) |
| Flyway 버전 | **V61** (V60 다음) |
| 코드 수정·빌드·재기동 | 사용자 승인 절차 따름 (전역 CLAUDE.md 규칙) |

---

## 5. 후속 (이 CR 이후)

- 위젯 ChatRecipe 통합 (project_widget_chat_recipe_idea.md 보관 중) — 이 CR 완료 후 별도 발번
- 세션 관리 UX 정책 논의 — 위 ChatRecipe 안에 흡수됨
