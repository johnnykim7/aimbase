# Aimbase 백엔드 요청 처리 방식

---

## 1. 요약

Aimbase 플랫폼은 **Java 21 Virtual Threads** 방식을 채택하여, 전통적 멀티스레드의 직관적 코드 스타일과 논블로킹 이벤트 루프의 고성능을 동시에 달성하였다. Python+LangGraph 기반 구현 시에는 **asyncio 이벤트 루프 + 멀티프로세스** 조합이 표준 패턴이다.

---

## 2. 요청 처리 모델 비교

| 구분            | 전통 스레드 (Tomcat)         | 논블로킹 이벤트 루프 (nginx/asyncio) | Virtual Threads (Aimbase) |
| --------------- | ---------------------------- | ------------------------------------ | ------------------------- |
| 요청당 리소스   | OS 스레드 1개 (~1MB)         | 없음 (이벤트 큐)                     | 가상 스레드 1개 (~1KB)    |
| 동시 처리 수    | ~200개 (스레드 풀 제한)      | ~10,000+                             | ~100,000+                 |
| 코드 스타일     | 동기 (직관적)                | 비동기 콜백/await                    | 동기 (직관적)             |
| LLM API 대기 시 | OS 스레드 블로킹 (자원 낭비) | 비동기 대기 (효율적)                 | 자동 언마운트 (효율적)    |
| 적합 언어       | Java (기존), Go              | Python, Node.js                      | Java 21+                  |

---

## 3. Aimbase 솔루션 — Java 21 Virtual Threads

### 3.1 핵심 설정

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true # 모든 HTTP 요청을 Virtual Thread에서 처리
```

- Spring Boot 3.4가 Tomcat 요청마다 **경량 가상 스레드를 자동 생성**
- OS 스레드와 달리 메모리 ~1KB, 생성 비용 거의 없음
- I/O 대기 시 JVM이 자동으로 캐리어 스레드를 반환 → **실질적 논블로킹 효과**

### 3.2 처리 유형별 동시성 모델

| 처리 유형            | 방식                                | 비고                              |
| -------------------- | ----------------------------------- | --------------------------------- |
| 일반 API 요청        | Virtual Thread 자동 할당            | Spring Boot 기본 동작             |
| LLM 스트리밍 (SSE)   | `Thread.ofVirtual().start()`        | 요청별 전용 가상 스레드           |
| LLM API 호출         | `CompletableFuture.supplyAsync()`   | 비동기 논블로킹                   |
| 병렬 액션 실행       | `newVirtualThreadPerTaskExecutor()` | 타겟별 가상 스레드, 30초 타임아웃 |
| 워크플로우 병렬 스텝 | `newVirtualThreadPerTaskExecutor()` | DAG 위상정렬 후 병렬 실행         |
| RAG 문서 수집        | `CompletableFuture.supplyAsync()`   | 스레드풀(4~20) 비동기             |
| MCP 도구 호출        | 동기 블로킹 (HTTP/SSE)              | 30초 타임아웃                     |

### 3.3 요청 흐름도

```
[클라이언트 HTTP 요청]
        │
        ▼
  TenantResolver (Virtual Thread에서 실행)
  ├─ X-Tenant-Id 헤더로 테넌트 식별
  └─ ThreadLocal에 tenant_id 설정
        │
        ▼
  ChatController
  ├─ stream=false → OrchestratorEngine.chat()
  │   ├─ 세션 로드 (Redis)
  │   ├─ ModelRouter → LLM 어댑터 선택
  │   ├─ LLMAdapter.chat() → CompletableFuture (비동기)
  │   ├─ ToolCallHandler (최대 5회 루프)
  │   └─ 응답 반환
  │
  └─ stream=true → SseEmitter 즉시 반환
      └─ Thread.ofVirtual().start(→
          OrchestratorEngine.chatStream()
          └─ LLM 스트리밍 → SSE 청크 전송
        ←)
```

### 3.4 전통 스레드 대비 장점

- **처리량:** OS 스레드 200개 → Virtual Thread 10만개+ 동시 처리 가능
- **코드 단순성:** 콜백/Promise 없이 동기 코드 그대로 사용
- **LLM 특화:** LLM API 응답 대기(수초~수십초)에도 자원 낭비 없음
- **테넌트 격리:** ThreadLocal 기반 TenantContext가 Virtual Thread에서도 안전 동작

---

## 4. Python + LangGraph 구현 시 처리 방식

### 4.1 방식 A — asyncio 이벤트 루프 (Aimbase Python 사이드카 방식)

```
FastAPI/Uvicorn ─── asyncio 이벤트 루프 (단일 스레드)
                    ├── await llm_call()     ← I/O 대기 시 다른 요청 처리
                    ├── await db_query()
                    └── await tool_call()
```

- nginx와 유사한 **단일 스레드 논블로킹 이벤트 루프**
- I/O 작업을 `await`로 비동기 처리, 대기 중 다른 요청 수행
- Aimbase의 RAG/Safety/Evaluation MCP 서버가 이 방식 채택
- **장점:** 메모리 효율, I/O 바운드에 최적
- **단점:** CPU 집약 작업 시 GIL 병목

### 4.2 방식 B — LangGraph 동기 실행 + 멀티프로세스

```python
# LangGraph 기본 동기 실행
graph = StateGraph(AgentState)
graph.add_node("agent", call_model)
graph.add_node("tools", tool_node)
app = graph.compile()
result = app.invoke({"messages": [HumanMessage("hello")]})
```

```bash
# Gunicorn 멀티프로세스로 동시 처리
gunicorn -w 4 -k uvicorn.workers.UvicornWorker app:app
```

- 각 요청이 **동기적으로 순차 실행**
- 동시 처리는 **Gunicorn 워커(멀티프로세스)**로 수평 확장
- **장점:** 단순함, 디버깅 용이
- **단점:** 프로세스당 메모리 소비 큼 (수백MB)

### 4.3 방식 C — LangGraph async (권장)

```python
# LangGraph 비동기 실행
graph = StateGraph(AgentState)
graph.add_node("agent", acall_model)   # async 함수
app = graph.compile()
result = await app.ainvoke({"messages": [HumanMessage("hello")]})

# 스트리밍
async for event in app.astream_events(input, version="v2"):
    yield event
```

- LangGraph의 `ainvoke()` / `astream()` + FastAPI asyncio 이벤트 루프
- **논블로킹 이벤트 루프 + LangGraph 그래프 실행**을 결합
- 프로덕션에서는 멀티프로세스 + 논블로킹 혼합 사용

### 4.4 Python 방식 비교

| 구분           | asyncio 단독        | 동기+멀티프로세스 | async LangGraph (권장) |
| -------------- | ------------------- | ----------------- | ---------------------- |
| 동시 요청 처리 | 이벤트 루프 내 병렬 | 워커 수만큼 병렬  | 이벤트 루프 + 워커     |
| 메모리 효율    | 높음                | 낮음              | 중간                   |
| 코드 복잡도    | 중간 (async/await)  | 낮음 (동기)       | 중간                   |
| LLM 대기 처리  | await로 효율적      | 프로세스 블로킹   | await로 효율적         |
| 스케일링       | 단일 프로세스 한계  | 프로세스 추가     | 프로세스 + 비동기      |

---

## 5. 결론

| 항목               | Aimbase (Java)                       | Python + LangGraph                 |
| ------------------ | ------------------------------------ | ---------------------------------- |
| **동시성 모델**    | Virtual Threads (요청별 경량 스레드) | asyncio 이벤트 루프 + 멀티프로세스 |
| **코드 스타일**    | 동기 코드 그대로 (콜백 없음)         | async/await 필요                   |
| **동시 처리 규모** | 10만+ 가상 스레드                    | 이벤트 루프 1만+ × 워커 수         |
| **LLM 대기 효율**  | 자동 최적화 (JVM 관리)               | 명시적 await 필요                  |
| **스케일 아웃**    | 인스턴스 추가                        | 워커/인스턴스 추가                 |

**Aimbase 플랫폼의 선택 근거:**

Aimbase는 **LangGraph를 사용하지 않으며**, Java 21 기반으로 오케스트레이션 엔진을 자체 구현하였다. 그 이유는 다음과 같다:

1. **자체 오케스트레이션 엔진:** `OrchestratorEngine`, `ToolCallHandler`, `WorkflowEngine` 등을 Java로 직접 구현하여 LLM 호출, 도구 호출 루프(최대 5회), DAG 워크플로우(Kahn 알고리즘)를 자체 제어
2. **Virtual Threads 활용:** Java 21 Virtual Threads로 동기 코드 스타일을 유지하면서 수십만 동시 요청을 처리할 수 있어, LLM API 호출처럼 I/O 대기가 긴 AI Agent 워크로드에 최적
3. **Python은 보조 역할:** Python 사이드카는 RAG Pipeline, Safety, Evaluation 등 ML 특화 기능만 MCP 서버로 제공하며, 에이전트 오케스트레이션에는 관여하지 않음

LangGraph(Python)는 빠른 프로토타이핑에 유리하나, 엔터프라이즈 멀티테넌트 환경에서는 GIL 제약, 멀티프로세스 메모리 오버헤드, 그리고 Java 생태계(Spring Boot, JPA, Virtual Threads)와의 통합 측면에서 Java 자체 구현이 더 적합하다고 판단하였다.
