# Aimbase 제안 설명회 Q&A (Detail)

> 기술 심화 버전 — 아키텍처/구현 상세 포함

---

## Part 1. 예상 Q&A

### Q1. 멀티 에이전트 환경에서 오케스트레이션을 어떻게 구현했나?

3가지 레벨의 오케스트레이션을 제공합니다.

| 레벨 | 메커니즘 | 설명 |
|------|---------|------|
| **단일 요청** | OrchestratorEngine | 세션→정책평가→RAG주입→LLM호출→도구루프→가드레일→응답 |
| **멀티스텝** | WorkflowEngine (DAG) | Kahn 알고리즘 위상정렬, 6가지 스텝타입(LLM/Tool/Condition/Parallel/Approval/Action) |
| **멀티 LLM** | ModelRouter + Adapter | Anthropic/OpenAI/Ollama 동시지원, 라우팅 전략(round-robin/cost/latency) 자동 전환 |

- 워크플로우 내에서 **PARALLEL 스텝**으로 여러 LLM을 동시 호출 가능
- **CONDITION 스텝**으로 LLM 응답에 따라 분기 처리
- **HUMAN_INPUT 스텝**으로 사람 승인 게이트 삽입 (자동화 + 휴먼인더루프 혼합)

---

### Q2. 오케스트레이션 시 시간 최적화는 어떻게 하나?

4가지 핵심 전략을 적용합니다.

**1) Virtual Threads (Java 21)**
- OS 스레드 1개당 수천 개 가상 스레드 구동
- LLM 호출(I/O 바운드)에 최적화 → 스레드풀 고갈 없이 동시 요청 수천 건 처리
- `CompletableFuture.supplyAsync()` + `newVirtualThreadPerTaskExecutor()`

**2) DAG 병렬 실행**
- 의존관계 없는 스텝은 자동 병렬 실행 (ParallelStepExecutor)
- 예: 3개 LLM을 동시 호출 → 결과 합산 → 가장 느린 LLM 시간만 소요

**3) SSE 스트리밍**
- 전체 응답 대기 없이 토큰 단위 실시간 전송
- 사용자 체감 대기시간 대폭 단축 (First Token Time 최소화)

**4) 컨텍스트 윈도우 트리밍**
- 불필요한 과거 메시지 자동 제거 → LLM 입력 토큰 최소화
- 시스템 메시지 보존 + 최신 대화 우선 → 응답 시간 단축

---

### Q3. 리소스(비용) 최적화는 어떻게 하나?

**1) 토큰 비용 실시간 추적**
- 모든 LLM 호출마다 `input_tokens`, `output_tokens`, `cost_usd` 기록
- 모델별 단가 매트릭스 내장 (Claude Opus $15/M, Sonnet $3/M, GPT-4o $5/M 등)
- 테넌트별 월간 토큰 쿼터 설정 → 초과 시 자동 차단

**2) 모델 라우팅으로 비용 분배**
- 간단한 질문 → 저비용 모델 (Haiku, GPT-4o-mini)
- 복잡한 추론 → 고성능 모델 (Opus, GPT-4o)
- `cost-optimized` 전략 시 자동 배분

**3) Rate Limiting**
- 정책 엔진에서 슬라이딩 윈도우 방식 (인메모리, 마이크로초 단위)
- 테넌트/세션/의도별 세밀한 빈도 제한

**4) RAG로 입력 토큰 절감**
- 전체 문서 대신 관련 청크만 주입 (top-k=5)
- 하이브리드 검색(BM25+벡터) + 리랭킹으로 정확도↑, 토큰↓

---

### Q4. 멀티테넌시에서 리소스 격리를 어떻게 보장하나?

**Database-per-Tenant** 아키텍처

- 각 테넌트는 **물리적으로 분리된 PostgreSQL DB** 보유
- `TenantRoutingDataSource`가 ThreadLocal 기반으로 동적 라우팅
- Redis 세션도 `tenant:{id}:` 프리픽스로 격리
- 쿼터 서비스로 테넌트별 토큰/연결/워크플로우 수 제한
- Virtual Thread의 ThreadLocal이 테넌트 컨텍스트 안전하게 전파

---

### Q5. 정책 엔진으로 뭘 할 수 있나? (거버넌스)

6가지 규칙 타입으로 LLM 사용을 세밀하게 통제합니다.

| 규칙 | 예시 시나리오 |
|------|-------------|
| **DENY** | "고객 개인정보 직접 출력 금지" |
| **REQUIRE_APPROVAL** | "대량 메일 발송 전 관리자 승인" |
| **RATE_LIMIT** | "분당 100건 초과 차단" |
| **TRANSFORM** | "주민번호/전화번호 자동 마스킹 후 전달" |
| **LOG** | "모든 LLM 호출 감사 기록" |

- 우선순위 기반 평가 → 첫 DENY/APPROVAL에서 단락(short-circuit)
- SpEL 조건식으로 동적 매칭 (의도, 어댑터, 사용자 역할 등)

---

### Q6. PII(개인정보) 처리는 어떻게 하나?

Microsoft Presidio 기반 + 한국어 커스텀 인식기

- 주민등록번호, 전화번호, 이메일, 계좌번호, 주소 자동 탐지
- 정책 엔진의 TRANSFORM 규칙과 연동 → LLM에 전달 전 자동 마스킹
- 출력 가드레일로 LLM 응답에서도 PII 재검출

---

### Q7. 여러 LLM을 어떻게 전환/관리하나?

Adapter 패턴 + Connection 모델

```
ConnectionAdapterFactory
 ├─ AnthropicAdapter (Claude Opus/Sonnet/Haiku)
 ├─ OpenAIAdapter (GPT-4o/4o-mini/4-turbo)
 └─ OllamaAdapter (로컬 모델)
```

- **Connection** = LLM 연결 설정 (API키, 모델, 엔드포인트)을 DB에 저장
- 런타임에 Connection 추가/변경 → 재시작 없이 즉시 반영
- 어댑터 캐시(`ConcurrentHashMap`)로 클라이언트 재생성 방지
- API 키 변경 시 캐시 evict → 자동 갱신

---

### Q8. 워크플로우에서 에러/실패 처리는?

- 스텝별 **재시도 설정** (지수 백오프)
- **에러 핸들러** 설정으로 실패 시 대체 스텝 실행 가능
- CONDITION 스텝으로 에러 분기 처리
- 워크플로우 전체 상태: PENDING → RUNNING → COMPLETED/FAILED
- 스텝별 상태 + 결과 영속화 → 실패 지점부터 재개 가능

---

### Q9. 모니터링/관측성은?

Prometheus + Micrometer 기반

| 메트릭 | 태그 |
|--------|------|
| `platform.llm.calls.total` | provider, model, status |
| `platform.llm.latency.seconds` | provider, model |
| `platform.llm.tokens.input/output.total` | provider, model |
| `platform.workflow.executions.total` | status |
| `platform.tool.executions.total` | tool, status |
| `platform.policy.violations.total` | type |

- `/actuator/prometheus` 엔드포인트 → Grafana 대시보드 연동 가능
- 감사 로그로 모든 LLM 호출/도구 사용/정책 판정 이력 추적

---

### Q10. 확장성 한계와 향후 계획은?

| 현재 | 한계 | 대응 방향 |
|------|------|----------|
| 모놀리식 Spring Boot | 단일 인스턴스 스케일업 | Virtual Threads로 동시성 확보, 필요시 마이크로서비스 분리 |
| pgvector | 수천만 벡터 시 성능 | HNSW 인덱스 + 파티셔닝, 필요시 전용 벡터DB 전환 |
| 인메모리 Rate Limiter | 멀티 인스턴스 시 불일치 | Redis 기반 분산 Rate Limiter로 전환 예정 |
| Python 사이드카 (MCP) | 네트워크 호출 지연 | SSE 연결 풀링 + 그레이스풀 폴백 (Java 구현) |

---

## Part 2. 주요 용어집

### 오케스트레이션 핵심

| 용어 | 설명 |
|------|------|
| **DAG (Directed Acyclic Graph)** | 방향성 비순환 그래프. 워크플로우의 스텝과 의존관계를 표현하는 자료구조. 순환(루프)이 없어 실행 순서가 명확하게 결정됨 |
| **위상 정렬 (Topological Sort)** | DAG에서 스텝 실행 순서를 결정하는 알고리즘. Aimbase는 Kahn's Algorithm을 사용하여 의존성이 충족된 스텝부터 순서대로 실행 |
| **Kahn's Algorithm** | 진입 차수(in-degree)가 0인 노드부터 큐에 넣고, 실행 후 후속 노드의 진입 차수를 감소시키는 방식의 위상 정렬 알고리즘. O(V+E) 시간 복잡도 |

### LLM & 어댑터

| 용어 | 설명 |
|------|------|
| **LLM (Large Language Model)** | 대규모 언어 모델. Anthropic Claude, OpenAI GPT, Ollama(로컬) 등 |
| **어댑터 패턴 (Adapter Pattern)** | 프로바이더마다 다른 API를 통일된 인터페이스(`LLMAdapter`)로 추상화하는 설계 패턴 |
| **모델 라우팅 (Model Routing)** | 요청을 어떤 LLM으로 보낼지 결정. round-robin / cost-optimized / latency 3가지 전략 |
| **Tool Use Loop** | LLM→도구호출→결과→LLM 재호출 반복. 무한 루프 방지 최대 5회 제한 (BIZ-001) |
| **Tool Choice** | LLM 도구 사용 제어. `auto`(자율), `none`(미사용), `required`(강제), `{toolName}`(특정 도구) |
| **Structured Output** | LLM 응답을 JSON Schema에 맞춰 구조화된 데이터로 반환 |

### RAG (검색 증강 생성)

| 용어 | 설명 |
|------|------|
| **RAG** | 외부 문서에서 관련 정보를 검색 → LLM 프롬프트에 주입하여 응답 정확도 향상 |
| **임베딩 (Embedding)** | 텍스트를 고차원 벡터로 변환. KoSimCSE(768차원) 또는 OpenAI(1536차원) 사용 |
| **시맨틱 청킹** | 문서를 의미 단위로 분할. 임베딩 유사도로 문맥 전환 지점을 감지 |
| **하이브리드 검색** | BM25(키워드) + 벡터 검색(의미 유사도)을 RRF로 결합 |
| **BM25** | 키워드 빈도 기반 전통적 검색 알고리즘. 정확한 키워드 매칭에 강점 |
| **RRF (Reciprocal Rank Fusion)** | 여러 검색 결과의 순위를 통합. `score = 1/(k + rank)` |
| **HNSW** | pgvector의 근사 최근접 이웃 탐색 인덱스. 그래프 기반 고속 유사도 검색 |
| **pgvector** | PostgreSQL 벡터 검색 확장. 별도 벡터DB 없이 벡터 연산 수행 |
| **리랭킹 / Cross-Encoder** | 초기 검색 결과를 쿼리-문서 쌍 모델로 재평가하여 정확도 향상 |
| **HyDE** | 가상 답변을 LLM으로 생성 → 임베딩하여 검색. 질문-문서 의미 갭 축소 |
| **Multi-Query** | 원본 질문을 3~5가지 변형으로 변환 → 병렬 검색 후 결과 통합 |

### MCP (Model Context Protocol)

| 용어 | 설명 |
|------|------|
| **MCP** | Anthropic 주도의 LLM 도구 통합 표준 프로토콜 |
| **MCP Server** | MCP 프로토콜 도구 서버. Aimbase는 RAG/Safety/Evaluation/Agent 4개 운영 |
| **FastMCP** | Python용 MCP 서버 프레임워크. `@mcp.tool()` 데코레이터로 도구 등록 |
| **SSE (Server-Sent Events)** | 서버→클라이언트 단방향 실시간 스트리밍. LLM 응답 및 MCP 통신에 사용 |
| **Tool Registry** | 빌트인 + MCP 도구를 통합 관리하는 중앙 저장소 |

### 정책 엔진 & 거버넌스

| 용어 | 설명 |
|------|------|
| **정책 엔진** | LLM 호출/액션 전 규칙 평가 → 허용/거부/변환 결정 |
| **DENY / REQUIRE_APPROVAL / RATE_LIMIT / TRANSFORM / LOG** | 정책 규칙 타입 |
| **슬라이딩 윈도우** | 시간 창 내 요청 횟수 추적으로 빈도 제한 |
| **PII** | 개인식별정보 — 주민등록번호, 전화번호, 이메일, 계좌번호 등 |
| **Presidio** | Microsoft 오픈소스 PII 탐지/마스킹 엔진 (한국어 확장 포함) |
| **가드레일 (Guardrails)** | LLM 출력 안전성 검증. 주제 이탈, 유해 콘텐츠, PII 노출 탐지 |
| **RBAC** | 역할 기반 접근 제어. 8개 리소스 x 2개 권한 = 16개 권한 조합 |
| **감사 로그** | 모든 주요 이벤트(LLM 호출, 도구 사용, 정책 판정) 추적 로그 |

### 멀티테넌시 & 인프라

| 용어 | 설명 |
|------|------|
| **Database-per-Tenant** | 테넌트마다 독립된 PostgreSQL DB 할당. 물리적 데이터 격리 |
| **TenantContext** | 현재 요청의 테넌트 ID를 ThreadLocal에 바인딩. Virtual Thread 안전 |
| **Dynamic DataSource Routing** | 테넌트 ID에 따라 해당 DB로 자동 라우팅. 재시작 없이 신규 추가 |
| **쿼터 (Quota)** | 테넌트별 리소스 한도. 월간 토큰, 연결 수, 지식소스 수 등 |
| **Virtual Threads** | Java 21 경량 스레드. OS 스레드 1개로 수천 개 구동. I/O 바운드 최적 |
| **Python 사이드카** | AI 특화 라이브러리(RAGAS, Presidio, LangGraph) 활용을 위한 별도 Python 서비스 |

### 평가 & 에이전트

| 용어 | 설명 |
|------|------|
| **RAGAS** | RAG 품질 평가 — Faithfulness, Answer Relevancy, Context Precision/Recall |
| **DeepEval** | LLM 출력 평가 — 환각(Hallucination), 유해성(Toxicity), 편향성(Bias) |
| **LangGraph** | 상태 기반 에이전트 추론 프레임워크 (노드+엣지 방식) |
| **ReAct** | Reasoning + Acting 순환 패턴. 추론→행동→관찰 반복 |
| **Plan and Execute** | 전체 계획 수립 후 단계적 실행. 복잡한 작업에 적합 |

### 워크플로우 스텝 유형

| 스텝 타입 | 설명 | 활용 예시 |
|----------|------|----------|
| **LLM_CALL** | 언어 모델 호출 | 텍스트 생성, 분류, 요약 |
| **TOOL_CALL** | 외부 도구 실행 | DB 조회, API 호출 |
| **CONDITION** | 조건 분기 | 이전 스텝 결과에 따라 다른 경로 |
| **PARALLEL** | 병렬 실행 | 여러 LLM 동시 호출 후 비교 |
| **HUMAN_INPUT** | 승인 게이트 | 고위험 작업 전 관리자 승인 대기 |
| **ACTION** | 외부 시스템 실행 | DB 쓰기, Slack 알림, WebSocket 발송 |

---

## 약어 모음

| 약어 | 풀네임 | 한국어 |
|------|--------|--------|
| DAG | Directed Acyclic Graph | 방향성 비순환 그래프 |
| LLM | Large Language Model | 대규모 언어 모델 |
| RAG | Retrieval-Augmented Generation | 검색 증강 생성 |
| MCP | Model Context Protocol | 모델 컨텍스트 프로토콜 |
| SSE | Server-Sent Events | 서버 전송 이벤트 |
| PII | Personally Identifiable Information | 개인식별정보 |
| RRF | Reciprocal Rank Fusion | 상호 순위 융합 |
| HNSW | Hierarchical Navigable Small World | 계층적 탐색 가능 소형 세계 그래프 |
| HyDE | Hypothetical Document Embedding | 가상 문서 임베딩 |
| RBAC | Role-Based Access Control | 역할 기반 접근 제어 |
| FSM | Finite State Machine | 유한 상태 머신 |
| RAGAS | RAG Assessment | RAG 품질 평가 |
| SpEL | Spring Expression Language | Spring 표현식 언어 |
