# Aimbase — 개발 지시서

> **Version**: 3.0  
> **Date**: 2025-02-22  
> **대상**: AI 개발자 (Claude Code)  
> **참고 문서**: UNIVERSAL_LLM_PLATFORM_ARCHITECTURE.md (설계서), LLMPlatformAdmin.jsx (UI 프로토타입)

---

## 1. 프로젝트 개요

다양한 LLM(Claude, OpenAI, Ollama 로컬 모델 등)을 통합하고, LLM의 판단 결과를 실제 행동(DB 저장, 알림 발송 등)으로 자동 연결하는 오케스트레이션 플랫폼.

비개발자가 Admin UI에서 설정만으로 새로운 AI 서비스를 구성할 수 있어야 한다. 코딩 없이 프롬프트, 워크플로우, 정책, 스키마, RAG 소스를 관리한다.

---

## 2. 확정 기술 스택

### Backend

- **Java 21** (Virtual Threads 활용)
- **Spring Boot 3.x** (Spring MVC + Virtual Threads, WebFlux 아님)
- **Gradle** (Kotlin DSL)
- **Spring Data JPA** + QueryDSL (동적 쿼리)
- **Spring Security** (JWT 기반)
- **Spring AI 2.0+** (LLM 추상화, MCP 연동, Embedding, VectorStore)

### LLM / AI

- **anthropic-java** (공식 SDK v2.x) — Claude 호출
- **openai-java** (공식 SDK) — OpenAI 호출
- **Spring AI ChatModel** — 통합 추상화 (Ollama 포함)
- **MCP Java SDK** (공식, io.modelcontextprotocol.sdk) + mcp-spring-webmvc
- 로컬 LLM은 Ollama/vLLM이 OpenAI 호환 REST API를 제공하므로, OpenAI Adapter로 endpoint URL만 바꿔서 호출

### Database & Cache

- **PostgreSQL 16** — 메타데이터, 설정, 감사 로그
- **pgvector** 확장 — RAG 임베딩 벡터 저장
- **Redis 7** — 세션, 캐시, 이벤트 스트림

### Frontend

- **React 19 + TypeScript + Vite**
- **Tailwind CSS 4**
- **React Flow** — 워크플로우 DAG 에디터
- **TanStack Query** — API 상태 관리
- API 클라이언트는 **OpenAPI Generator**로 Spring 서버 스펙에서 자동 생성

### Infrastructure

- **Docker + Docker Compose** (로컬 개발, MVP 배포)
- **Micrometer + Prometheus + Grafana** (모니터링)
- **SpringDoc OpenAPI** (API 문서 자동 생성)

---

## 3. 우리가 만드는 것 vs 가져다 쓰는 것

### 가져다 쓰는 것 (라이브러리가 해결)

| 영역 | 라이브러리 |
|------|-----------|
| LLM 호출 추상화 | Spring AI ChatModel |
| Claude API | anthropic-java |
| OpenAI API | openai-java |
| MCP 클라이언트 | MCP Java SDK + Spring 통합 |
| 프롬프트 템플릿 | Spring AI PromptTemplate |
| 벡터DB 연동 | Spring AI VectorStore (pgvector) |
| 임베딩 호출 | Spring AI EmbeddingModel |
| 문서 파싱 | Apache Tika, PDFBox, POI |

### 우리가 만드는 것 (핵심 차별점)

| 모듈 | 설명 |
|------|------|
| **Action Layer** | Write(데이터 저장) + Notify(이벤트 발행) 종단 액션 엔진. 모든 LLM 결과가 이 두 가지로 귀결됨 |
| **Policy Engine** | 액션 실행 전 규칙 평가 — allow/deny/require_approval/transform/rate_limit/log |
| **Workflow Engine** | DAG 기반 다단계 실행 — LLM 호출, RAG 검색, Tool 호출, 조건 분기, 병렬 실행, 사람 승인을 조합 |
| **RAG Pipeline** | 지식 수집(Ingestion) + 검색 주입(Retrieval). Admin UI에서 설정만으로 소스 추가, 전략 변경 |
| **Schema Registry** | 데이터 구조 정의, JSON Schema 검증, Adapter별 자동 변환 |
| **Orchestrator** | Smart Routing(비용/성능 기반 모델 선택), LLM 체이닝, 컨텍스트 윈도우 관리 |
| **Config-driven 동작** | 모든 비즈니스 로직이 DB 설정으로 동작. YAML은 시드/import/export용 |
| **Admin UI** | 비개발자가 연결, 정책, 워크플로우, Knowledge, 프롬프트, 모니터링을 관리하는 화면 |

---

## 4. 프로젝트 구조

멀티 모듈 Gradle 프로젝트.

### platform-core (Spring Boot 백엔드)

패키지 루트: `com.platform`

| 패키지 | 역할 | 주요 클래스/인터페이스 |
|--------|------|----------------------|
| `llm.model` | LLM 통일 메시지 타입 | UnifiedMessage, LLMRequest, LLMResponse, ContentBlock, ToolCall, UnifiedToolDef, TokenUsage |
| `llm.adapter` | 모델별 어댑터 | LLMAdapter(인터페이스), AnthropicAdapter, OpenAIAdapter, OllamaAdapter |
| `llm.router` | Smart Routing | ModelRouter — 비용/성능/intent 기반 모델 자동 선택 |
| `llm` | 레지스트리 | LLMAdapterRegistry |
| `mcp` | MCP 통합 | MCPManager(서버 라이프사이클), ToolRegistry(디스커버리/캐싱), MCPBridge(MCP↔LLM Tool 변환) |
| `rag.model` | RAG 타입 | KnowledgeSource, Chunk, EmbeddedChunk, RetrievalResult, IngestionResult, RetrievedChunk |
| `rag.ingest` | 수집 파이프라인 | IngestionPipeline, IngestionScheduler |
| `rag.ingest.source` | 소스 커넥터 | SourceConnector(인터페이스), FileSourceConnector, DatabaseSourceConnector, WebCrawlSourceConnector, S3SourceConnector, MCPResourceConnector |
| `rag.ingest.parser` | 문서 파서 | DocumentParser(인터페이스), PDFParser, DocxParser, HTMLParser, CSVParser |
| `rag.ingest.chunker` | 청킹 | Chunker(인터페이스), FixedSizeChunker, RecursiveChunker, SemanticChunker |
| `rag.retrieve` | 검색 파이프라인 | Retriever(인터페이스), QueryProcessor, VectorSearcher, HybridSearcher, Reranker, ContextInjector |
| `rag` | 레지스트리 | KnowledgeSourceRegistry |
| `action.model` | Action 타입 | ActionRequest, ActionResult, ActionTarget, WriteResult, NotifyResult |
| `action` | 실행 | ActionRouter(intent→adapter), ActionExecutor(병렬/순차), AdapterRegistry |
| `action.write` | Write Adapter | WriteAdapter(인터페이스), PostgreSQLAdapter, MongoDBAdapter, FileAdapter, S3Adapter, RedisAdapter |
| `action.notify` | Notify Adapter | NotifyAdapter(인터페이스), WebSocketAdapter, SlackAdapter, WebhookAdapter, EmailAdapter, KakaoTalkAdapter |
| `policy.model` | Policy 타입 | Policy, PolicyRule, PolicyResult, PolicyMatch |
| `policy` | 정책 엔진 | PolicyEngine, ConditionEvaluator(SpEL 기반), ApprovalService, RateLimiter, PIIMasker, AuditLogger |
| `schema` | 스키마 | SchemaRegistry, SchemaValidator(JSON Schema), SchemaTransformer(adapter별 변환) |
| `workflow.model` | Workflow 타입 | Workflow, WorkflowStep, WorkflowRun, WorkflowTrigger, ErrorHandling |
| `workflow` | 워크플로우 엔진 | WorkflowEngine(DAG 실행), StepExecutor, WorkflowParser(YAML→DAG) |
| `orchestrator` | 오케스트레이션 | OrchestratorEngine(전체 흐름), ChainManager(LLM 체이닝), ContextManager(세션/토큰 관리) |
| `event` | 이벤트 버스 | EventBus, EventStore |
| `session` | 세션 | SessionStore(Redis), ContextWindowManager(토큰 관리, 히스토리 압축) |
| `api` | REST Controllers | ChatController, ModelController, ToolController, ActionController, WorkflowController, SchemaController, PolicyController, ConnectionController, MCPController, KnowledgeController, AdminController |
| `domain` | JPA Entities | 각 테이블에 대응하는 Entity 클래스들 |
| `repository` | Spring Data JPA | 각 Entity에 대응하는 Repository 인터페이스들 |
| `config` | 설정 | SecurityConfig, RedisConfig, WebSocketConfig, SchedulerConfig 등 |

### admin-ui (React 프론트엔드)

| 경로 | 역할 |
|------|------|
| `src/pages/Dashboard.tsx` | 실시간 통계, 액션 로그, 모델 사용량, 승인 대기 |
| `src/pages/Connections.tsx` | DB, 메시징, LLM, 실시간 연결 등록/관리/테스트 |
| `src/pages/MCPServers.tsx` | MCP 서버 등록, 상태, Tool 목록 |
| `src/pages/Knowledge.tsx` | RAG 소스 관리, 동기화 실행, 검색 테스트 |
| `src/pages/Schemas.tsx` | 데이터 스키마 정의/편집/검증 |
| `src/pages/Policies.tsx` | 정책 규칙 빌더, 시뮬레이션 |
| `src/pages/Prompts.tsx` | 프롬프트 편집, 변수 삽입, A/B 테스트 |
| `src/pages/Workflows.tsx` | React Flow 기반 비주얼 DAG 편집기 |
| `src/pages/Auth.tsx` | 역할/권한/사용자 관리 |
| `src/pages/Monitoring.tsx` | 비용 추적, 모델 성능, 에러율 |

UI 디자인은 LLMPlatformAdmin.jsx 프로토타입을 참고할 것. 다크 테마, 산업적/유틸리터리안 스타일.

---

## 5. 데이터 모델

아래 테이블을 JPA Entity + Flyway 마이그레이션으로 구현한다. pgvector 확장을 사용한다.

### connections

외부 시스템 연결 정보.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| name | varchar(200), not null | 표시 이름 |
| adapter | varchar(50), not null | postgresql, slack, anthropic 등 |
| type | varchar(20), not null | write, notify, llm |
| config | JSONB, not null | 연결 설정 (비밀번호는 암호화) |
| status | varchar(20), default 'disconnected' | connected, disconnected, error |
| health_config | JSONB, nullable | 헬스체크 설정 |
| created_at, updated_at | timestamptz | |

### mcp_servers

등록된 MCP 서버.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| name | varchar(200), not null | |
| transport | varchar(20), not null | stdio, sse, streamable-http |
| config | JSONB, not null | url, command, env 등 |
| auto_start | boolean, default true | |
| status | varchar(20) | |
| tools_cache | JSONB, nullable | 디스커버리된 Tool 목록 캐시 |
| created_at, updated_at | timestamptz | |

### schemas

데이터 구조 정의. id+version 복합키로 버전 관리.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK(복합), varchar(100) | |
| version | PK(복합), integer, default 1 | |
| domain | varchar(50), nullable | |
| description | text | |
| json_schema | JSONB, not null | JSON Schema 정의 |
| transforms | JSONB, nullable | Adapter별 변환 규칙 |
| validators | JSONB, nullable | 커스텀 검증 규칙 |
| created_at | timestamptz | |

### policies

액션 실행 규칙.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| name | varchar(200), not null | |
| domain | varchar(50), nullable | |
| priority | integer, not null, default 0 | 높을수록 먼저 평가 |
| is_active | boolean, default true | |
| match_rules | JSONB, not null | 어떤 intent/adapter/connection에 적용할지 |
| rules | JSONB, not null | 규칙 배열 (type, condition, config) |
| created_at, updated_at | timestamptz | |

### prompts

프롬프트 템플릿. id+version 복합키.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK(복합), varchar(100) | |
| version | PK(복합), integer, default 1 | |
| domain | varchar(50), nullable | |
| type | varchar(30), not null | system, notification_template 등 |
| template | text, not null | 프롬프트 본문 |
| variables | JSONB, nullable | 사용 가능한 변수 목록 |
| is_active | boolean, default false | |
| ab_test | JSONB, nullable | A/B 테스트 설정 (트래픽 비율 등) |
| created_at | timestamptz | |

### routing_config

LLM 모델 선택 전략.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| strategy | varchar(30), not null | fixed, cost_optimized, quality_first, latency_first |
| rules | JSONB, not null | intent→model 매핑, 조건 등 |
| fallback_chain | JSONB, nullable | 폴백 순서 |
| is_active | boolean, default true | |
| created_at, updated_at | timestamptz | |

### workflows

DAG 기반 워크플로우 정의.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| name | varchar(200), not null | |
| domain | varchar(50), nullable | |
| trigger_config | JSONB, not null | intent_match, cron, event, manual |
| steps | JSONB, not null | DAG 스텝 배열 (id, type, config, dependsOn, onSuccess, onFailure) |
| error_handling | JSONB, nullable | retry, timeout, escalation |
| is_active | boolean, default true | |
| created_at, updated_at | timestamptz | |

### workflow_runs

워크플로우 실행 이력.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, UUID | |
| workflow_id | FK → workflows | |
| session_id | varchar(100) | |
| status | varchar(20), not null | running, completed, failed, pending_approval |
| current_step | varchar(100) | |
| step_results | JSONB | 각 스텝 실행 결과 |
| input_data | JSONB | |
| error | JSONB | |
| started_at, completed_at | timestamptz | |

### action_logs

모든 액션 실행 기록.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, UUID | |
| workflow_run_id | FK → workflow_runs, nullable | |
| session_id | varchar(100) | |
| intent | varchar(100), not null | |
| type | varchar(20), not null | write, notify |
| adapter | varchar(50), not null | |
| destination | varchar(200) | |
| payload | JSONB | |
| policy_result | JSONB | 정책 평가 결과 |
| status | varchar(20), not null | success, failed, pending_approval, rejected |
| result | JSONB | |
| error | JSONB | |
| executed_at | timestamptz | |

### pending_approvals

승인 대기 항목.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, UUID | |
| action_log_id | FK → action_logs | |
| policy_id | FK → policies | |
| approval_channel | varchar(100) | slack 채널 등 |
| approvers | JSONB | 승인자 목록 |
| status | varchar(20), default 'pending' | pending, approved, rejected, timeout |
| approved_by | varchar(100) | |
| reason | text | |
| requested_at, resolved_at, timeout_at | timestamptz | |

### audit_logs

감사 로그. created_at DESC 인덱스, action 인덱스.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, UUID | |
| user_id | varchar(100) | |
| session_id | varchar(100) | |
| action | varchar(50), not null | llm_call, tool_call, write, notify, policy_trigger 등 |
| target | varchar(200) | |
| detail | JSONB | |
| ip_address | varchar(45) | |
| created_at | timestamptz | |

### roles

RBAC 역할.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(50) | |
| name | varchar(100), not null | |
| inherits | varchar[] | 상속받는 역할 ID 배열 |
| permissions | JSONB, not null | 리소스별 권한 정의 |

### users

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| email | varchar(200), unique, not null | |
| name | varchar(100) | |
| role_id | FK → roles | |
| api_key_hash | varchar(200) | |
| is_active | boolean, default true | |
| created_at | timestamptz | |

### usage_logs

LLM 사용량 추적. created_at DESC 인덱스.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, UUID | |
| user_id | varchar(100) | |
| session_id | varchar(100) | |
| model | varchar(100), not null | |
| input_tokens, output_tokens | integer, default 0 | |
| cost_usd | numeric(10,6), default 0 | |
| latency_ms | integer | |
| created_at | timestamptz | |

### knowledge_sources

RAG 지식 소스 정의.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| name | varchar(200), not null | |
| type | varchar(30), not null | file, database, web, s3, api, mcp_resource |
| config | JSONB, not null | 소스별 설정 (경로, 쿼리, URL 등) |
| chunking_config | JSONB, not null | 청킹 전략(fixed/recursive/semantic), 크기, 겹침 |
| embedding_config | JSONB, not null | 임베딩 모델, 차원, 배치 크기 |
| sync_config | JSONB | cron 스케줄, incremental/full 모드 |
| status | varchar(20), default 'idle' | idle, syncing, error |
| document_count | integer, default 0 | |
| chunk_count | integer, default 0 | |
| last_synced_at | timestamptz | |
| created_at, updated_at | timestamptz | |

### embeddings

pgvector 확장 사용. HNSW 인덱스.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, UUID | |
| source_id | FK → knowledge_sources (CASCADE DELETE) | |
| document_id | varchar(200) | 원본 문서 식별자 |
| chunk_index | integer, not null | 문서 내 순서 |
| content | text, not null | 청크 원문 |
| embedding | vector(1536) | 임베딩 벡터 (차원은 모델에 따라 동적) |
| metadata | JSONB | 파일명, 페이지, 카테고리 등 |
| created_at | timestamptz | |

source_id 인덱스, embedding HNSW cosine 인덱스 필요.

### ingestion_logs

수집 이력.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, UUID | |
| source_id | FK → knowledge_sources | |
| mode | varchar(20), not null | full, incremental |
| status | varchar(20), not null | running, completed, failed |
| documents_processed | integer, default 0 | |
| chunks_created | integer, default 0 | |
| errors | JSONB | |
| started_at, completed_at | timestamptz | |

### retrieval_config

RAG 검색 전략 설정.

| 필드 | 타입 | 설명 |
|------|------|------|
| id | PK, varchar(100) | |
| name | varchar(200), not null | |
| top_k | integer, default 5 | |
| similarity_threshold | numeric(3,2), default 0.7 | |
| max_context_tokens | integer, default 4000 | |
| search_type | varchar(20), default 'hybrid' | similarity, keyword, hybrid |
| source_filters | JSONB | intent별 소스 필터링 |
| query_processing | JSONB | 쿼리 확장, HyDE, 리랭킹 설정 |
| context_template | text | 검색 결과를 프롬프트에 삽입할 때 사용할 템플릿 |
| is_active | boolean, default true | |
| created_at, updated_at | timestamptz | |

---

## 6. 핵심 인터페이스 명세

코드를 작성하지 않고, 각 인터페이스가 가져야 할 메서드와 역할을 기술한다. 구현은 AI 개발자가 한다.

### LLMAdapter

LLM 모델별 어댑터. provider 식별자와 지원 모델 목록을 가진다. chat(동기), chatStream(스트리밍) 메서드를 제공한다. UnifiedToolDef를 각 모델의 네이티브 Tool 포맷으로 변환하는 메서드, 네이티브 응답에서 ToolCall을 추출하는 메서드가 있다. 모든 LLM 호출 결과는 LLMResponse로 통일되며, 여기에 토큰 사용량과 비용이 포함된다.

### WriteAdapter / NotifyAdapter

외부 시스템과의 연결을 관리한다. connect/disconnect/healthCheck가 공통이다. WriteAdapter는 write/read/update/delete, NotifyAdapter는 publish 메서드를 가진다. 각 어댑터는 자신의 id를 가지며 AdapterRegistry에 등록된다. healthCheck는 ok 여부와 latency를 반환한다.

### PolicyEngine

ActionRequest가 들어오면 해당 request에 매칭되는 모든 Policy를 priority 순으로 평가한다. 각 PolicyRule의 condition은 SpEL 표현식으로, ConditionEvaluator가 payload 데이터를 대상으로 평가한다. 결과는 allow/deny/require_approval 중 하나이며, transform 규칙이 있으면 payload를 변환한다. deny 시 사유를 반환하고, require_approval 시 ApprovalService를 통해 승인 플로우를 시작한다.

### WorkflowEngine

Workflow 정의(DAG)를 받아 실행한다. 각 스텝의 type은 LLM_CALL, TOOL_CALL, ACTION, CONDITION, PARALLEL, HUMAN_INPUT 중 하나다. dependsOn으로 의존 관계를 표현하고, 의존성이 없는 스텝은 병렬 실행 가능하다. CONDITION 스텝은 결과에 따라 onSuccess/onFailure로 분기한다. PARALLEL 스텝은 하위 스텝을 동시에 실행하고 전부 완료되길 기다린다. HUMAN_INPUT은 승인/입력을 대기한다. 각 스텝 실행 결과는 WorkflowRun의 stepResults에 누적 저장된다.

### IngestionPipeline

KnowledgeSource를 입력받아 수집→파싱→청킹→임베딩→벡터DB 저장 전체 과정을 실행한다. incremental 모드는 마지막 동기화 이후 변경된 문서만 처리한다. IngestionScheduler가 cron 스케줄에 따라 자동 실행한다. 각 단계는 독립적이므로 소스 타입별 SourceConnector, 파일 타입별 DocumentParser, 전략별 Chunker를 조합할 수 있다.

### Retriever

사용자 질문과 RetrievalConfig를 받아 관련 문서 청크를 반환한다. QueryProcessor가 쿼리를 확장하거나 HyDE(LLM으로 가상 답변 생성 후 검색)를 수행한다. VectorSearcher가 pgvector에서 유사도 검색하고, HybridSearcher는 벡터+키워드를 결합한다. Reranker가 Cross-encoder로 재정렬하고, ContextInjector가 최종 결과를 프롬프트 템플릿에 삽입한다. 결과에는 각 청크의 유사도 점수와 출처 정보가 포함된다.

### OrchestratorEngine

전체 요청 흐름을 관리하는 최상위 컴포넌트. 요청이 들어오면:
1. Intent 분류 (RAG가 필요한지, Workflow가 있는지)
2. RAG 필요 시 Retriever 호출 → 검색 결과를 컨텍스트에 주입
3. ModelRouter로 적절한 LLM 선택
4. LLM 호출
5. LLM이 Tool Call을 반환하면 → MCP Tool 실행 → 결과로 LLM 재호출 (루프)
6. LLM이 Action을 요청하면 → PolicyEngine 평가 → ActionExecutor 실행
7. 결과 반환 + 이벤트 발행

---

## 7. API 엔드포인트

### 핵심 API

**POST /api/v1/chat/completions** — 가장 중요한 API. OpenAI 호환 형태.
- 입력: model(또는 "auto"), messages, stream 여부, actions_enabled 여부
- 출력: LLM 응답 + 실행된 액션 목록 + 토큰 사용량/비용
- 스트리밍: SSE로 응답

### 리소스 CRUD (모든 리소스 동일 패턴)

| 리소스 | 기본 경로 | 추가 엔드포인트 |
|--------|----------|----------------|
| connections | /api/v1/connections | POST /{id}/test (연결 테스트) |
| mcp-servers | /api/v1/mcp-servers | POST /{id}/discover (Tool 디스커버리) |
| schemas | /api/v1/schemas | POST /{id}/validate (데이터 검증 테스트) |
| policies | /api/v1/policies | POST /simulate (정책 시뮬레이션) |
| prompts | /api/v1/prompts | POST /{id}/test (프롬프트 테스트) |
| routing | /api/v1/routing | |
| workflows | /api/v1/workflows | POST /{id}/run (수동 실행), GET /{id}/runs (실행 이력) |
| knowledge-sources | /api/v1/knowledge-sources | POST /{id}/sync (동기화 실행), POST /search (검색 테스트) |
| retrieval-config | /api/v1/retrieval-config | |
| roles | /api/v1/roles | |
| users | /api/v1/users | |

각 리소스는 GET(목록, pagination+filter), POST(생성), GET/{id}(상세), PUT/{id}(수정), DELETE/{id}(삭제)를 제공한다.

### 모니터링 / 관리

- GET /api/v1/admin/dashboard — 대시보드 통계
- GET /api/v1/admin/action-logs — 액션 실행 로그
- GET /api/v1/admin/audit-logs — 감사 로그
- GET /api/v1/admin/usage — 사용량/비용 통계
- GET /api/v1/admin/approvals — 승인 대기 목록
- POST /api/v1/admin/approvals/{id}/approve — 승인
- POST /api/v1/admin/approvals/{id}/reject — 거부

---

## 8. 핵심 동작 흐름

### 흐름 1: 기본 채팅 (RAG + Action)

```
사용자: "운동화 ORD-123 환불해주세요"

1. OrchestratorEngine 수신
2. RAG Retriever → "환불 정책" 관련 문서 검색 → 컨텍스트 주입
3. ModelRouter → intent "refund" → Claude Sonnet 선택
4. LLM 호출 (환불 정책 컨텍스트 + 사용자 질문)
5. LLM → Tool Call: query_order(order_id: "ORD-123")
6. MCP Bridge → order-service MCP 서버 호출 → 주문 정보 반환
7. LLM 재호출 (주문 정보 포함)
8. LLM → Action 요청: refund 처리 (amount: 89000)
9. PolicyEngine 평가 → amount < 200000 → allow (자동 승인)
10. ActionExecutor 병렬 실행:
    - Write: PostgreSQL에 환불 레코드 저장
    - Notify: Slack #cs-log 채널에 알림
    - Notify: WebSocket으로 Admin 대시보드 갱신
    - Notify: KakaoTalk으로 고객에게 완료 안내
11. 응답 반환: "ORD-123 환불 처리 완료되었습니다. 89,000원이 2~3일 내 환불됩니다."
```

### 흐름 2: 고액 환불 (승인 필요)

```
1~8. 위와 동일
9. PolicyEngine 평가 → amount >= 200000 → require_approval
10. ApprovalService → Slack #cs-managers에 승인 요청 발송
11. 응답 반환: "환불 요청을 접수했습니다. 팀장 승인 후 처리됩니다."
12. [비동기] 팀장이 Admin UI 또는 Slack에서 승인
13. ActionExecutor 실행 (위 10번과 동일)
14. Notify: 고객에게 승인 완료 안내
```

### 흐름 3: RAG 지식 수집

```
1. Admin UI에서 Knowledge Source 추가 (예: 제품 매뉴얼 PDF 폴더)
2. IngestionPipeline 실행:
   a. FileSourceConnector → /data/manuals/ 에서 PDF 파일들 수집
   b. PDFParser → 텍스트 추출
   c. RecursiveChunker → 1000토큰 단위로 분할 (200토큰 겹침)
   d. EmbeddingModel → OpenAI text-embedding-3-small로 벡터화
   e. VectorStore → pgvector에 저장
3. Write: ingestion_logs에 결과 기록
4. Notify: WebSocket으로 Admin 대시보드에 동기화 완료 알림
5. IngestionScheduler가 sync_config의 cron 스케줄에 따라 주기적 재실행
```

---

## 9. 설정 기반 동작 원칙

모든 비즈니스 로직은 DB에 저장된 설정으로 동작한다. 코드 변경 없이 Admin UI에서:

| 변경 사항 | 설정 위치 | 효과 |
|----------|----------|------|
| 새 LLM 모델 추가 | connections + routing_config | 즉시 사용 가능 |
| 환불 한도 변경 (20만→50만) | policies의 rule condition | 즉시 적용 |
| 프롬프트 개선 | prompts 새 버전 | A/B 테스트 또는 즉시 전환 |
| 새 알림 채널 추가 | connections + 해당 workflow | 워크플로우에서 사용 |
| RAG 소스 추가 | knowledge_sources | 동기화 후 즉시 검색에 반영 |
| 워크플로우 변경 | workflows의 steps | 즉시 반영 |
| 역할/권한 변경 | roles + users | 즉시 반영 |

초기 데이터는 YAML 시드 파일로 제공하되, 이후 관리는 전부 Admin UI → DB.

---

## 10. 개발 Phase

### Phase 1 — Core Foundation (4~6주)

**목표**: 하나의 모델로 채팅 → DB에 저장 → WebSocket으로 알림, 이 단일 흐름이 동작.

- 프로젝트 세팅: 멀티 모듈 Gradle, Docker Compose (PostgreSQL, Redis, Ollama), Flyway 마이그레이션
- LLM Adapter 3종: Anthropic, OpenAI, Ollama
- Action Adapter: PostgreSQL Write + WebSocket Notify
- Chat API (POST /api/v1/chat/completions) + Session Store (Redis)
- 기본 Policy (인증, 감사 로깅)
- Schema Validator (JSON Schema 검증)
- Admin UI: 대시보드 + 연결관리 화면

**완료 기준**: Admin UI에서 PostgreSQL 연결 등록 → API로 채팅 → Claude 응답 + DB 저장 + WebSocket 이벤트 → 대시보드에서 로그 확인

### Phase 2 — MCP & Tools (3~4주)

**목표**: MCP 서버의 Tool을 어떤 LLM에서든 호출 가능.

- MCP Client (Java SDK 활용, stdio + HTTP transport)
- MCP Tool ↔ LLM Tool 포맷 변환 (Bridge)
- Tool 실행 → 결과 → LLM 재호출 루프
- Admin UI: MCP 서버 관리 화면

### Phase 3 — RAG & Knowledge (3~4주)

**목표**: "우리 데이터"를 기반으로 답변하는 RAG 파이프라인.

- Knowledge Source 관리 (파일, DB, 웹, S3, MCP Resource)
- Ingestion Pipeline: 파싱 → 청킹 → 임베딩 → pgvector 저장
- Retrieval Pipeline: 유사도 검색 → 하이브리드 → 리랭킹 → 컨텍스트 주입
- Ingestion 스케줄러 (Spring @Scheduled + cron)
- Admin UI: Knowledge Source 관리 + 검색 테스트

### Phase 4 — Workflow & Orchestration (4~5주)

**목표**: 다단계 LLM + RAG + Tool + Action 워크플로우.

- DAG Runner (순차, 병렬, 분기, 에러 핸들링)
- Workflow YAML 파서
- Smart Router (비용/성능/intent 기반 모델 자동 선택)
- Context Manager (토큰 윈도우 관리, 히스토리 압축)
- RAG Step을 워크플로우 스텝으로 사용 가능
- Admin UI: React Flow 기반 비주얼 DAG 편집기

### Phase 5 — Policy & Safety (3~4주)

**목표**: 안전하고 통제 가능한 프로덕션 시스템.

- SpEL 기반 조건식 평가
- Approval Flow (승인 요청 → Slack/Admin UI에서 승인/거부 → 액션 실행)
- Rate Limiter (어댑터/사용자별)
- PII 마스킹 (전화번호, 이메일, 주소 자동 마스킹 후 전달)
- Knowledge Source 접근 제어
- Admin UI: 정책 규칙 빌더 + 시뮬레이션 (금액 입력 → 어떤 정책 적용되는지 즉시 표시)

### Phase 6 — Extensions & Production (4~6주)

- Extension 인터페이스 정의 + 로더
- Adapter SDK (커스텀 어댑터 개발 가이드)
- 샘플 Extension 1~2개 (e-commerce CS 등)
- 모니터링 (Micrometer + Prometheus + Grafana)
- API 문서 (SpringDoc OpenAPI)

---

## 11. 마일스톤

| 시점 | 체크포인트 |
|------|-----------|
| Week 1 | 프로젝트 세팅 완료, Docker Compose로 전체 인프라 기동 |
| Week 2 | 인터페이스(LLMAdapter, WriteAdapter, NotifyAdapter, PolicyEngine) 확정 |
| Week 4 | Phase 1 중간 데모 — LLM 호출 + DB Write + WebSocket Notify |
| Week 6 | Phase 1 완료 — 전체 채팅 흐름 + Admin 대시보드 |
| Week 10 | Phase 2+3 완료 — MCP Tool + RAG 검색 |
| Week 14 | Phase 4 완료 — 워크플로우 DAG 실행 |
| Week 18 | Phase 5 완료 — Policy + 승인 플로우 |
| Week 24 | Phase 6 완료 — v1.0 릴리즈 후보 |

---

## 12. 제약 사항 및 참고

- Java 21의 Virtual Threads를 적극 활용한다. @Async 대신 Virtual Threads 기반 executor를 사용한다.
- JSONB 필드가 많으므로 Hibernate에서 JSONB 매핑을 적절히 처리한다 (vladmihalcea hibernate-types 또는 직접 AttributeConverter).
- 비밀번호/API 키 등 민감한 config 필드는 저장 시 암호화한다.
- 모든 API 응답은 일관된 형태 (success, data, error, pagination)로 통일한다.
- 모든 설정 변경은 audit_logs에 기록한다.
- pgvector의 embedding 차원은 모델에 따라 다르므로 (1536, 768 등), knowledge_sources의 embedding_config에 명시된 차원을 사용한다.
- Admin UI 디자인은 LLMPlatformAdmin.jsx 프로토타입을 참고한다. 다크 테마, 산업적 스타일, JetBrains Mono(코드), DM Sans(UI).
