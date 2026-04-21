# 원본 요구사항 — SessionStore.persistToDb 중복키 에러 해소

- **대상 CR**: CR-052
- **발견일**: 2026-04-16
- **발견 경위**: CR-045 E2E 검증 중 BE 로그에서 확인
- **영향**: 기능 동작에는 영향 없음(세션 메시지는 Redis 캐시로 정상 동작). 그러나 매 메시지 저장마다 `duplicate key` 에러 스택이 로그에 남음 — 노이즈 + DB 부하.

## 1. 재현 증상

SSE 스트리밍 요청 처리 중:

```
ERROR ... o.h.engine.jdbc.spi.SqlExceptionHelper
  Batch entry 0 insert into conversation_sessions
    (app_id, ..., session_id, ..., id)
    values ((NULL), ..., ('phase4b2-1776263964'), ..., ('a4f9c196-...'::uuid))
  was aborted:
  ERROR: duplicate key value violates unique constraint "conversation_sessions_session_id_key"
  Detail: Key (session_id)=(phase4b2-1776263964) already exists.

WARN  ... c.p.session.SessionStore : Failed to persist session ... to DB
```

## 2. 원인 분석 (1차 가설)

`SessionStore.saveMessages(sessionId, messages)` → `persistToDb(sessionId, messages)` 흐름:

```java
// SessionStore.persistToDb (현재 구조)
transactionTemplate.executeWithoutResult(status -> {
    ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId)
            .orElseGet(() -> {
                ConversationSessionEntity newSession = new ConversationSessionEntity();
                newSession.setSessionId(sessionId);
                // title 세팅
                return newSession;
            });
    session.setMessageCount(messages.size());
    sessionRepository.save(session);  // ← UPDATE 또는 INSERT
    messageRepository.deleteBySessionId(sessionId);
    for (UnifiedMessage msg : messages) {
        messageRepository.save(...);
    }
});
```

문제 후보:
1. **동시성**: `appendMessage` 호출이 연속으로 발생하면 (예: user 메시지 저장 + assistant 응답 저장) 두 persist가 **병렬 가상 스레드**에서 실행됨. 첫 건이 `findBySessionId` 빈 결과 → 새 엔티티 생성 → save, 두 번째 건도 거의 동시에 빈 결과 확인 → 둘 다 INSERT → UNIQUE 제약 충돌.
2. **생성자 ID 자동 생성**: `ConversationSessionEntity.id`가 UUID 자동 생성이지만 `session_id` 컬럼에 별도 UNIQUE 제약 존재. save()가 managed 상태 판단을 실패해 merge 대신 persist를 호출하면 같은 증상.

Redis는 정상 — Redis 저장만 성공하고 DB 영속화가 실패해서 `WARN`으로 잡혀 표면적 기능엔 영향 없음.

## 3. 해결 방향

### 3.1 단기 — 낙관적 upsert
```java
// 의사 코드
try {
    var session = sessionRepository.findBySessionId(sessionId).orElse(null);
    if (session == null) {
        session = new ConversationSessionEntity();
        session.setSessionId(sessionId);
    }
    session.setMessageCount(messages.size());
    sessionRepository.save(session);
    // messages ...
} catch (DataIntegrityViolationException e) {
    // 다른 스레드가 먼저 INSERT 성공한 케이스 → 재조회 후 update
    var session = sessionRepository.findBySessionId(sessionId).orElseThrow();
    session.setMessageCount(messages.size());
    sessionRepository.save(session);
}
```

### 3.2 중기 — 직렬화 키
`SessionStore.saveMessages` 진입 시 sessionId 기준 `synchronized` 또는 Redisson 분산락. 가상 스레드 환경에서는 pinning 주의 → `ReentrantLock` 권장. 단 lock 스코프가 Redis persist + DB persist를 모두 감싸야 의미 있음.

### 3.3 장기 — persist 분리
매 `appendMessage`마다 전체 세션을 재저장하는 현재 구조(`deleteBySessionId` + `for msg: save`) 자체가 과도함. 신규 메시지만 append하는 구조로 전환:
- 세션은 최초 한 번만 upsert
- 메시지는 순수 insert (id가 자동 생성이라 충돌 없음)

## 4. 스코프 추정

- 단기 수정: SessionStore 1 파일, ~20 LOC + 통합 테스트
- 중기 수정: +ReentrantLock 도입
- 장기 수정: DB 스키마 + persist 전략 재설계 (영향 큼)

**본 CR은 단기 수정 + 중기 직렬화 범위**. 장기는 성능 이슈 제기되면 별도 CR.

## 5. 관련 코드 / 참조
- [SessionStore.java:79-99](../../backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L79-L99)
- [SessionStore.java:159-189](../../backend/platform-core/src/main/java/com/platform/session/SessionStore.java#L159-L189)
- `ConversationSessionEntity.session_id` UNIQUE 제약 (`conversation_sessions_session_id_key`)

## 6. 우선순위
**Medium-Low** — 기능 무영향이지만 매 메시지마다 ERROR 스택 로그 + DB 배치 롤백 부하. FlowGuard 벤치마크 실험처럼 다회차 대화를 돌리면 로그량이 빠르게 커짐. 벤치마크 전에 단기 수정만이라도 적용 권장.
