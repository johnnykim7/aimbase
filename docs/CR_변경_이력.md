# Aimbase 변경 이력 (Change Request Log)

> ai-sdlc 템플릿: v2.7.0 기반

> 기능 변경 및 설계 보정을 기록한다. git commit과 별개로, 비즈니스·설계 수준의 변경을 추적한다.
> T1~T4 설계 문서의 단일 파일 원칙에 따라, 이 문서가 변경 이력의 단일 진실 소스이다.

## 전체 요약

| CR 번호 | 변경 제목 | 변경 타입 | 영향도 | 적용 버전 | 상태 |
|---------|----------|----------|--------|----------|------|
| CR-001 | 초기 시스템 구축 | 신규 | High | v1.0.0 | ✅ 완료 |
| CR-002 | Python 사이드카 아키텍처 도입 | 변경 | High | v2.0.0 | ✅ 완료 |
| CR-003 | MCP 클라이언트 통합 완성 | 변경 | High | v2.1.0 | ✅ 완료 |
| CR-004 | Sprint 18 고급 기능 구현 완성 | 변경 | Medium | v2.2.0 | ✅ 완료 |
| CR-005 | 워크플로우 비주얼 스튜디오 | 변경 | Medium | v2.3.0 | ✅ 완료 |
| CR-006 | 도구 선택 제어 (Tool Selection Control) | 변경 | Medium | v2.4.0 | ✅ 완료 |
| CR-007 | 구조화된 출력 (Structured Output) | 변경 | High | v2.5.0 | ✅ 완료 |
| CR-008 | LLM 연결 테스트 실제 검증 | 버그수정 | Medium | v2.5.1 | ✅ 완료 |
| CR-009 | Python 사이드카 알파 기능 | 변경 | High | v3.0.0 | ✅ 완료 |
| CR-010 | 플랫폼 핵심 강화 | 변경 | High | v3.0.0 | ✅ 완료 |
| CR-011 | ClaudeCodeTool 안정화 및 확장성 개선 | 변경 | High | v3.1.0 | ✅ 완료 |
| CR-012 | LLM 컨텍스트 설계 보강 | 변경 | High | v3.2.0 | ✅ 완료 |
| CR-013 | API Rate Limit 방어 (TokenBucket) | 변경 | Medium | v3.3.0 | ✅ 완료 |
| CR-014 | App-Tenant 3계층 멀티테넌시 | 변경 | High | v3.4.0 | ✅ 완료 |
| CR-015 | 커넥션 그룹 Resilience + 키 관리 권한 체계 | 변경 | High | v3.5.0 | ✅ 완료 |
| CR-016 | 공용 워크플로우 + 빌트인 도구 확장 | 변경 | High | v3.6.0 | ✅ 완료 |
| CR-017 | FlowGuard Agent 범용 도구 확장 | 변경 | High | v3.7.0 | ✅ 완료 |
| CR-029 | Aimbase 1단계 고도화 — Native Tool + Session Meta + Context Assembly + Runtime 재배치 | 변경 | High | v4.0.0 | ✅ 완료 |
| CR-030 | Aimbase 2단계 고도화 — Hook Architecture + Extended Thinking + Agent Isolation + 압축 전략 강화 | 변경 | High | v4.1.0 | ✅ 완료 |
| CR-031 | 성능/퀄리티 메커니즘 — Post-Compact Recovery + MICRO_COMPACT + Extract Memories + Adaptive Thinking | 변경 | High | v5.0.0 | ✅ 완료 |
| CR-032 | 프로바이더 확장 — OpenAI Compatible shim + Bedrock + Vertex AI + 에이전트 라우팅 | 변경 | High | v5.0.0 | ✅ 완료 |
| CR-033 | 에이전트 구조적 사고 체계 — Plan Mode + Todo + Task 관리 (10개 Tool + FE 대시보드) | 변경 | High | v6.1.0 | ✅ 완료 |
| CR-034 | 멀티에이전트 협업 완성 — SendMessage + Built-in Agent 5타입 + Hook 14개 | 신규 | High | v6.0.0+ | ✅ 완료 |
| CR-035 | Tool/Policy 확장성·자동화 — ScheduleCron + SkillTool + Firecrawl + 도메인 필터링 (PRD-234~240) | 변경 | High | v6.2.0 | ✅ 완료 |
| CR-036 | 프롬프트 외부화 + 영문 전환 + OpenClaude 프롬프트 전수 포팅 (PRD-249~264, FE-020~021) | 변경 | High | v6.5.0 | 🔧 진행중 |
| CR-037 | 핵심 도구 네이티브화 — BashTool + FileWriteTool + WebSearchTool + SuggestBackgroundPR (PRD-241~244) | 변경 | High | v6.3.0 | ✅ 완료 |
| CR-038 | 에이전트 자율성 강화 — MCP 리소스 탐색·읽기 + 이벤트 트리거 + 세션 브리핑 (PRD-245~248, FE-019) | 변경 | High | v6.4.0 | ✅ 완료 |
| CR-039 | 고급 확장 도구 — Swarm 팀 협업 + Notebook 편집 + LSP 코드 분석 (PRD-265~268, FE-022) | 변경 | High | v6.6.0 | 🔧 진행중 |
| CR-040 | 런타임 설정 관리 — DB 기반 설정 + 관리자 UI + 하드코딩 제거 (PRD-269~272, FE-023) | 변경 | High | v6.7.0 | 🔧 진행중 |
| CR-043 | ClaudeCodeTool 다중계정 운영 정책 정착 — 테넌트 전용/공통 풀 페일오버 + 호출중 자동 재시도 | 변경 | Medium | v7.1.0 | ✅ 완료 |
| CR-044 | CLI 두뇌 + Aimbase 손발 — Claude CLI 네이티브 도구 봉인 + Aimbase MCP 강제 (PRD-279~282) | 변경 | High | v7.2.0 | 📝 등록 |
| CR-045 | 대화형 채팅 UI + 워크스페이스 컨텍스트 + 실시간 도구 이벤트 (PRD-290~293, FE-030~032) | 변경 | High | v7.3.0 | ✅ 완료 |
| CR-046 | Chat 실시간 제어·대화방 관리 — 중지(abort) + 자동끼어들기 + Soft Delete (PRD-294~297, FE-033) | 변경 | High | v7.4.0 | 🔧 진행중 |
| CR-047 | 런타임 성능 최적화 — 병렬 도구 실행 + Prefetch + Cache TTL 분기 + Hook 비동기 (PRD-298~301) | 변경 | High | v7.5.0 | 📝 등록 |
| CR-048 | 컨텍스트·토큰 효율 — Deferred Tool 스키마 런타임 주입 + Tool Result Storage + Adaptive Thinking 동적 조정 (PRD-300~302) | 변경 | High | v7.6.0 | 📝 등록 |
| CR-049 | 세션 복원·지침 체계 — Session Resume + Compact Boundary + 테넌트/프로젝트 커스텀 지침 (PRD-303~305, FE-034) | 변경 | High | v7.7.0 | 📝 등록 |
| CR-050 | Claude CLI → LLM 어댑터 승격 — Worker Pool + fork-session 병렬 브랜치 + Max 구독 정액제 활용 (PRD-306~309) | 변경 | High | v7.8.0 | ✅ 구현 완료 (2026-04-24) |
| CR-051 | SSE 스트림 가상 스레드 SecurityContext 전파 — AccessDenied 로그 해소 | 버그수정 | Low | v7.3.1 | ✅ 완료 |
| CR-052 | SessionStore append-only persist — conversation_sessions 중복키 근본 해소 | 버그수정 | Medium | v7.3.1 | ✅ 완료 |
| CR-053 | 서브에이전트 UX 완성 — Built-in Agent 프롬프트 고도화 + SSE 라이프사이클 이벤트 + FE Task 블록 렌더 (PRD-310~312, FE-035) | 변경 | Medium | v7.8.0 | 📝 등록 |
| CR-054 | Aimbase 플랫폼 공통 HttpRequestTool — 범용 REST 호출 Tool + Connection type=HTTP + 가이드 문서 | 신규 | High | v7.9.0 | ✅ 완료 |
| CR-055 | Evaluator-Optimizer 워크플로우 노드 — EVALUATOR_LOOP StepType 신설 (generator + evaluator + max_iterations + pass_criteria), Anthropic 6패턴 커버리지 완성 | 신규 | Medium | v7.10.0 | ✅ 설계 완료 |
| CR-058 | Aimbase Chat Widget SDK — 소비앱 임베드용 채팅 + 워크플로우 실행 가시화 + RAG 출처 카드 (CORS + 단기 위젯 토큰 + 워크플로우 SSE + Web Component/UMD) | 신규 | High | v8.0.0 | ✅ 완료 (Sprint 52+53) |
| CR-061 | 위젯 파일 업로드 (이미지/PDF Vision 첨부) — 사전업로드 + attachment_id 참조 + Anthropic document 블록 + 비-Anthropic 프로바이더 텍스트 추출 폴백 (PRD-319~323, FE-036, BIZ-099~101) | 신규 | Medium | v8.1.0 | 📝 설계 완료 |
| CR-060 | 위젯 음성 입력 (STT) — 마이크 녹음 + Whisper 변환 + 입력창 자동 삽입 (`/chat/stt` 전용 + `SpeechService` 추출 + scope `chat:stt` + BIZ-102~104 + global_config) (PRD-324~326, FE-037) | 신규 | Medium | v8.2.0 | 📝 설계 완료 |
| CR-065 | SubWorkflow 자식 run 분리 생성 — `SubWorkflowStepExecutor` 인라인 실행 → `WorkflowRunEntity` 별도 레코드 승격 + `parent_run_id` 트리 + 자식 개별 재시도 API (PRD-327~329) | 변경 | Medium | v8.3.0 | 📝 발번 |
| CR-066 | Tenant Flyway 자동 재실행 — `TenantMigrationRunner` + Admin API `POST /platform/tenants/migrate` + 실패 격리 + 운영 가이드 § 2-5 자동화 (PRD-330~332) | 변경 | Medium | v8.4.0 | ✅ 구현 완료 |
| CR-067 | EnhancedToolExecutor default bridge 본문 노출 — `ToolResultRenderer` 신설 + bridge 정정 (CR-050 Phase 9 후속, MCP stdio 직접 검증으로 본문 노출 확인) | 버그수정 | High | v8.5.0 | ✅ 구현 완료 |
| CR-068 | API 어댑터 도구 호출 회귀 4종 정공 — (1) CR-048 filterActive 회귀 (2) HttpRequestTool body type 오타 (3) anti-hallucination 지시문 누락 (4) actions_executed 메타 누락. 작년 4월 정상 동작 동등 회복 | 버그수정 | High | v8.5.1 | ✅ 구현 완료 |
| CR-069 | Claude CLI 호출 공통 빌더 — `ClaudeCliCommandBuilder` 신설 + ToolMode(AIMBASE/NATIVE/HYBRID) 단일 스위치. Worker / ClaudeCodeTool 양쪽 잠금 정책(strict-mcp-config / bypassPermissions / --tools "" sealing) 통일. application.yml `tool-mode` 외부화 | 변경 | Medium | v8.5.2 | ✅ 구현 완료 |
| CR-070 | ClaudeCodeTool 실시간 스트리밍 중계 보강 — Phase A: stdout 라인 단위 스트림 + `STREAM_SINK` ThreadLocal 패턴 적용 (ClaudeCliWorker/SubagentRunner 패턴 차용). Phase B: Agent 실행 시 Agent → 서버 진행 이벤트 push 채널 추가. ToS 안전한 (3) 표준 경로 UX 완성 | 변경 | Medium | v8.5.3 | 📝 발번 |
| CR-071 | ClaudeCliAdapter — 3경로(API/CLI어댑터/ClaudeCodeTool) 단일 LLMAdapter 통일. CLI 실행 주체(`ClaudeCliRunner`)를 connection 단위 자유 배치 (서버/사용자 PC). aimbase-agent Runner화 + ClaudeCliLlmAdapter→ClaudeCliAdapter 진화 + ClaudeCodeTool 즉시 삭제. ToolMode(CR-069) 재활용. API/CLI 어댑터 대칭(`AnthropicAdapter` vs `ClaudeCliAdapter`) | 변경 | High | v8.6.0 | ✅ 구현 완료 |
| CR-072 | Aimbase 서버 도구 MCP endpoint 노출 — Claude CLI 가 사용자 PC aimbase-agent (SDK 14개) 외에 **서버 도구 26개**(WebSearch / HttpRequest / SendMessage / ScheduleCron / Notebook / LSP 등) 도 MCP 채널로 호출할 수 있도록 서버에 `/mcp/sse` endpoint 신설. McpExposureLevel 메타데이터 + McpExposurePolicy 화이트리스트 (CLI 26 / NONE 6). ServerMcpConfig + ServerMcpToolDispatcher (PRE/POST_TOOL_USE Hook + Rate Limit). ClaudeCliAdapterConfig 다중 mcpServers (`aimbase-local` stdio + `aimbase-server` SSE w/ `type:"sse"`) 출력. SecurityConfig `/mcp/**` authenticated. McpToolConversion sdk-mcp/platform-core 공유. 단위 16 PASS + 회귀 PASS + **풀 e2e 검증 완료** (Claude CLI 가 `mcp__aimbase-server__web_search` 실호출 → Wikipedia 결과 반환 → 모델 답변 생성). 검증 중 발견·수정 7건 (핵심: SSE 트랜스포트 `type` 필드 누락) | 신규 | High | v8.7.0 | ✅ 구현 + e2e 검증 완료 |
| CR-073 | aimbase-agent Spring Boot 인스턴스 통합 + `--runner-mode` 플래그 제거 — `--mcp-stdio` 외 모든 진입은 SERVLET 단일 컨텍스트. RunnerProperties 에 server MCP 헤더 3종 추가. RunnerAutoConfiguration 이 ClaudeCliAdapterConfig 빈 통해 mcpConfigJson 빌드 후 Worker 에 주입 (CR-072 mcpServers 전파 경로). 단위 PASS + aimbase-agent 회귀 PASS | 변경 | Medium | v8.7.0 | ✅ 구현 완료 |
| CR-086 | X-Tenant-Id 헤더 신뢰 경계 강화 — JWT `tenant_id` claim ↔ `X-Tenant-Id` 헤더 일치 강제 (cross-tenant 사칭 차단). 소비앱 외부 '격리 안됨' 보고는 실측 반증(mcp/workflow DB-per-Tenant 물리격리·master 테이블 없음·운영 403), 진짜 약점=TenantResolver 헤더 무검증 신뢰 + JwtAuthenticationFilter claim 미대조 | 신규 | High | (미정) | 📝 발번 |
| CR-087 | 워크플로우 FOREACH step (동적 컬렉션 fan-out) — 신규 `FOREACH` StepType. 런타임 컬렉션 각 원소에 body step(LLM_CALL/TOOL_CALL/SUB_WORKFLOW 등) 위임 실행. sequential/parallel(VT) + max_concurrency + max_items 방어 + collect reducer(CR-085 append/merge 재사용) + on_item_error(fail/continue). EVALUATOR_LOOP/SUB_WORKFLOW 패턴의 형제 — 단일 노드 내부 루프이므로 DAG/cyclic 양 스케줄러 무변경. LangGraph Send/map 대응 | 신규 | High | (미정) | 📝 발번 |
| CR-088 | AGENT_CALL response_schema + 도구 루프 동시 지원 — AGENT_CALL config 에 `response_schema` 키 추가. SubagentRequest → ChatRequest → LLMRequest 까지 schema 전파. ToolCallHandler.executeLoop 시그니처에 resolvedSchema 추가하여 도구 루프 매 회차 어댑터 호출 시 schema 전달. AnthropicAdapter 의 `if(structured)/else if(tools)` 상호배타 → 결합으로 변경 (진짜 도구 + `structured_output` 가상 tool 한 배열에 함께). structured_output tool 호출되면 도구 루프 종료 + 결과 추출. 소비앱(bidding-agency) 제안서 워크플로우에서 AGENT_CALL이 공고 PDF 자율 열람 + TipTap 구조화 출력을 동시에 받기 위함 (CR-087 FOREACH collect:merge 와 짝). Anthropic 우선, 타 어댑터는 후속 CR | 신규 | High | (미정) | 📝 발번 |
| CR-089 | MCP SSE 클라이언트 baseUri/sseEndpoint 분리 + 재연결 메커니즘 — SDK 0.10.0 `HttpClientSseClientTransport` 는 `baseUri.resolve(sseEndpoint)` 방식으로 SSE 호출. Java URI 표준상 절대경로 sseEndpoint("/sse" 기본) 가 base path 를 덮어쓰므로 DB 의 `url` 컬럼에 context path(`/api/mcp`) 가 포함되면 SDK 호출이 root(`/sse`) 로 가서 404. `MCPServerClient.splitBaseAndSsePath` 신설 → baseUri=scheme://authority, sseEndpoint=path+"/sse" 로 자동 분리. 더불어 기존 `ApplicationReadyEvent` 1회성 autoconnect 만 있고 재시도 메커니즘 부재로 일시적 SSE 실패 후 영구 dead state. 3가지 보강: (1) `MCPServerManager.reconnect(serverId)` + `POST /api/v1/mcp-servers/{id}/reconnect` API (2) `@Scheduled fixedDelay=60s, initialDelay=120s` 주기 health check + 자동 재연결 (3) `MCPToolExecutor` lazy reconnect (호출 실패 시 1회 재연결+재시도). 운영에서 bidding_system 의 bidding-agency-mcp 가 어제부터 dead 상태로 워크플로우 막혀있던 버그 해소. 단위 27 PASS / mcp 회귀 PASS. DB 마이그레이션 불필요 | 신규 | High | v8.13.0 | ✅ 구현 |

---

## 변경 이력

### CR-001 | 초기 시스템 구축
- **대상 기능 ID**: PRD-001 ~ PRD-095 (전체)
- **변경 타입**: 신규
- **변경 내용**: 멀티테넌트 LLM 오케스트레이션 플랫폼 전체 구현
  - BE: Spring Boot 3.4.2, 16개 모듈, 95개 기능
  - FE: React 18, 13개 페이지
  - DB: PostgreSQL Master/Tenant, Redis, pgvector
- **변경 사유**: 신규 프로젝트 초기 구축
- **영향 모듈**: 전체
- **영향도**: High
- **영향 범위**: ALL
- **영향 설계서**: T1-1 ~ T4-8 (전체)
- **요청자**: 프로젝트 오너 | **승인자**: - | **적용 버전**: v1.0.0
- **변경 일자**: 2026-03-10

### CR-002 | Python 사이드카 아키텍처 도입
- **대상 기능 ID**: PRD-048(일부), PRD-052, PRD-053, PRD-095(일부), PY-001~PY-012(신규)
- **변경 타입**: 변경
- **변경 내용**: AI 특화 기능을 Python MCP Server로 분리하는 하이브리드 아키텍처 도입
  - RAG 파이프라인 이관: 문서 파싱(Tika→Unstructured), 청킹(고정→시맨틱), 검색(코사인→하이브리드+리랭킹)
  - PII 탐지 이관: PIIMasker(자체)→Presidio(다국어)
  - 신규 추가: 로컬 임베딩, 쿼리 변환, RAG 평가(RAGAS), LLM 출력 평가(DeepEval), 프롬프트 회귀 테스트, 출력 가드레일, 고급 에이전트(LangGraph), 임베딩 파인튜닝
  - 4개 MCP Server 구성: RAG Pipeline, Evaluation, Safety, Agent
- **변경 사유**: Python AI 생태계 활용으로 RAG 품질 향상, 품질 측정 체계 구축, PII 정확도 개선
- **영향 모듈**: RAG, 정책(PII), 오케스트레이터
- **영향도**: High
- **영향 범위**: PRD-048, PRD-052, PRD-053, PRD-095, PY-001~PY-012
- **영향 설계서**: T1-1, T1-2, T2-1, T3-1, T3-2, T3-5, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v2.0.0
- **변경 일자**: 2026-03-12

### CR-003 | MCP 클라이언트 통합 완성
- **대상 기능 ID**: PY-009, PY-010, PY-011
- **변경 타입**: 변경
- **변경 내용**: Spring Boot ↔ Python MCP Server 통합 완성
  - EvaluationService + EvaluationController: 평가 API 엔드포인트 신규 (POST /api/v1/evaluations/rag, /llm-output, /prompt-comparison)
  - 출력 가드레일(PY-010): OrchestratorEngine에 MCPSafetyClient.validateOutput() 연동, ChatResponse에 guardrail 필드 추가
  - MCPRagClientTest, MCPSafetyClientTest: 단위 테스트 추가
  - MCPClientIntegrationTest: 3개 MCP 클라이언트 라운드트립 통합 테스트 (RAG Pipeline, Safety, Evaluation)
- **변경 사유**: CR-002에서 도입한 MCP 클라이언트들의 비즈니스 로직 연동 및 테스트 커버리지 확보
- **영향 모듈**: Evaluation(신규), 오케스트레이터, RAG, 정책(Safety)
- **영향도**: High
- **영향 범위**: PY-009, PY-010, PY-011
- **영향 설계서**: T3-2, T3-5
- **요청자**: sykim | **승인자**: - | **적용 버전**: v2.1.0
- **변경 일자**: 2026-03-12

### CR-004 | Sprint 18 고급 기능 구현 완성
- **대상 기능 ID**: PY-005, PY-010, PY-011, PY-012
- **변경 타입**: 변경
- **변경 내용**: Sprint 18에서 "완료"로 표기되었으나 실제 미구현이었던 4건의 고급 기능을 구현
  - PY-005 쿼리 변환: HyDE, Multi-Query, Step-Back 전략 (RAG Pipeline MCP Server에 transform_query 도구 추가)
  - PY-010 출력 가드레일: 규칙 기반 검증 엔진 (Safety MCP Server에 validate_output_guardrails 도구 추가, topic/format/safety 규칙)
  - PY-011 고급 추론 체인: MCP Server 4 (Agent) 신규 — reflection, plan_and_execute, ReAct 패턴
  - PY-012 임베딩 파인튜닝: sentence-transformers 기반 학습 파이프라인 (RAG Pipeline MCP Server에 finetune_embeddings 도구 추가)
  - Spring MCP Client: MCPRagClient에 transformQuery/finetuneEmbeddings, MCPSafetyClient에 validateOutputGuardrails 메서드 추가
  - Docker Compose: Agent MCP Server (포트 8003) 서비스 추가
  - application.yml: agent.mcp 설정 추가
- **변경 사유**: 전체 점검에서 T1-7 "완료" 표기와 실제 코드 간 불일치 발견 — 설계 문서 정합성 확보
- **영향 모듈**: RAG(PY-005, PY-012), Safety(PY-010), Agent(PY-011, 신규)
- **영향도**: Medium
- **영향 범위**: PY-005, PY-010, PY-011, PY-012
- **영향 설계서**: T1-1(상태 갱신), T1-7(상태 확인), T4-1(검증 항목 추가)
- **요청자**: sykim | **승인자**: - | **적용 버전**: v2.2.0
- **변경 일자**: 2026-03-15

### CR-005 | 워크플로우 비주얼 스튜디오
- **대상 기능 ID**: PRD-039(생성), PRD-041(수정), FE-001~FE-005(신규)
- **변경 타입**: 변경
- **변경 내용**: 읽기 전용 워크플로우 목록/프리뷰를 UiPath 수준의 비주얼 워크플로우 빌더로 확장
  - React Flow(@xyflow/react) 기반 드래그 & 드롭 DAG 에디터
  - FE-001: 캔버스 에디터 (줌/팬/미니맵, 노드 배치, 엣지 연결)
  - FE-002: 노드 팔레트 (6개 스텝 유형 드래그 추가)
  - FE-003: 노드 설정 패널 (스텝별 config 편집)
  - FE-004: 캔버스 ↔ WorkflowRequest JSON 양방향 변환
  - FE-005: 실행 시각화 (노드별 상태 실시간 표시, 승인 인라인)
  - 라우팅 추가: /workflows/new, /workflows/:id/edit
  - FE 기술 스택 추가: @xyflow/react, dagre
- **변경 사유**: BE에 워크플로우 CRUD + DAG 엔진이 완비되어 있으나 FE에서 생성/편집 UI 부재. 사용자 관점 기능 미완성
- **영향 모듈**: 워크플로우 (FE)
- **영향도**: Medium
- **영향 범위**: PRD-039, PRD-041, FE-001~FE-005
- **영향 설계서**: T1-1, T1-2, T1-7, T2-1, T3-6, T4-1
- **요청자**: sykim | **승인자**: sykim | **적용 버전**: v2.3.0
- **변경 일자**: 2026-03-15

### CR-006 | 도구 선택 제어 (Tool Selection Control)
- **대상 기능 ID**: PRD-092(확장), PRD-096(신규), PRD-097(신규)
- **변경 타입**: 변경
- **변경 내용**: LLM에 노출할 도구를 제어하는 2가지 메커니즘 추가
  - PRD-096 컨텍스트 기반 도구 필터링 (방식 B): ToolRegistry에 ToolFilterContext 기반 getToolDefs(filter) 추가. 테넌트/태그/허용목록 기준으로 LLM에 노출할 도구 후보를 제한
  - PRD-097 도구 강제 선택 (방식 C): LLMRequest에 toolChoice 필드 추가, 각 LLMAdapter(Anthropic/OpenAI/Ollama)에서 provider별 tool_choice 파라미터 매핑 (auto/none/required/특정tool)
  - PRD-092 확장: ToolCallHandler에서 필터링된 도구 목록 + toolChoice를 LLM 호출에 전달
- **변경 사유**: 도구 수 증가 시 LLM 정확도/비용 저하 방지, 고위험 도구 노출 제어, 특정 워크플로우 스텝에서 도구 강제 필요
- **영향 모듈**: 오케스트레이터, 도구(Tool), LLM 어댑터
- **영향도**: Medium
- **영향 범위**: PRD-092, PRD-096(신규), PRD-097(신규)
- **영향 설계서**: T1-1, T1-2, T2-1, T3-6, T4-1
- **요청자**: sykim | **승인자**: sykim | **적용 버전**: v2.4.0
- **변경 일자**: 2026-03-15

### CR-007 | 구조화된 출력 (Structured Output)
- **대상 기능 ID**: PRD-001(확장), PRD-019(확장), PRD-043(확장), PRD-098~PRD-101(신규)
- **변경 타입**: 변경
- **변경 내용**: LLM 응답을 JSON Schema 기반 구조화된 포맷으로 반환하는 기능 추가
  - PRD-098 구조화된 출력 요청: ChatRequest에 `response_format` 파라미터 추가 (inline json_schema 또는 schema_id 참조)
  - PRD-099 LLM 어댑터별 구조화 출력 분기: OpenAI → `response_format: json_schema` + `strict: true`, Gemini → `responseSchema`, Claude → 시스템 프롬프트 주입 + Tool Use 역이용, Ollama → `format: "json"`
  - PRD-100 워크플로우 출력 스키마: Workflow 정의에 `output_schema` 필드 추가 — 설계 시점 스키마 바인딩, 런타임 자동 적용
  - PRD-101 워크플로우 스튜디오 스키마 편집 (FE): 노드 설정 패널에 출력 스키마 탭 추가, 등록된 스키마 드롭다운 선택/인라인 JSON Schema 에디터, 워크플로우 레벨 최종 output_schema 설정
  - PRD-001 확장: ChatResponse에 `type: "structured"` ContentBlock 추가, 구조화 응답과 텍스트 응답 분기
  - PRD-019 확장: 기존 SchemaService.validate() 재사용하여 LLM 구조화 응답 검증
  - PRD-043 확장: 워크플로우 실행 시 output_schema 자동 주입
- **변경 사유**: 채팅 외 클라이언트(폼 자동완성, RPA, 대시보드 등)가 구조화된 데이터를 필요로 함. 현재 Text 블록만 반환하여 AI 미들웨어 역할 부족. 업계 표준(OpenAI Structured Outputs, Gemini responseSchema) 대응
- **영향 모듈**: 오케스트레이터, 채팅, 스키마, 워크플로우, LLM 어댑터, 워크플로우 스튜디오(FE)
- **영향도**: High
- **영향 범위**: PRD-001, PRD-019, PRD-043, PRD-098~PRD-101(신규)
- **영향 설계서**: T1-1, T1-2, T1-7, T2-1, T3-2, T3-3, T3-6, T4-1
- **요청자**: sykim | **승인자**: sykim | **적용 버전**: v2.5.0
- **변경 일자**: 2026-03-15

### CR-008 | LLM 연결 테스트 실제 검증
- **대상 기능 ID**: PRD-008(연결 테스트)
- **변경 타입**: 버그수정
- **변경 내용**: ConnectionController.test()에서 LLM 타입 연결이 실제 API 호출 없이 무조건 성공을 반환하던 버그 수정
  - 기존: `write`/`notify` 타입이 아닌 경우 `HealthStatus(true, 0)` 하드코딩 → 항상 "연결 성공"
  - 수정: LLM 타입 연결 시 ConnectionAdapterFactory를 통해 실제 어댑터를 생성하고, 최소 토큰의 ping 요청을 전송하여 API Key 유효성 및 네트워크 연결을 검증
  - Anthropic: `Messages.create()` with max_tokens=1
  - OpenAI: `ChatCompletion.create()` with max_tokens=1
  - 응답 시간(latencyMs) 측정하여 반환
- **변경 사유**: 연결 테스트가 실제 검증 없이 성공을 반환하여, 잘못된 API Key로도 "연결 성공" 표시됨. 사용자가 채팅 시점에서야 오류를 인지하게 되는 UX 결함
- **영향 모듈**: 연결 관리(Connection)
- **영향도**: Medium
- **영향 범위**: PRD-008
- **영향 설계서**: T3-2, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v2.5.1
- **변경 일자**: 2026-03-18

### CR-009 | Python 사이드카 알파 기능
- **대상 기능 ID**: PY-013~PY-022(신규)
- **변경 타입**: 변경
- **변경 내용**: Python MCP Server 4개에 알파 수준 고급 기능 10개 추가
  - PY-013 문서 파싱 도구(parse_document): unstructured 기반 PDF/DOCX/PPTX/XLSX/CSV/HTML 파싱, RAG Pipeline MCP에 도구 등록
  - PY-014 Self-RAG 자동 개선 루프(self_rag_search): 검색 품질 자동 평가 → 쿼리 재작성 → 재검색 (최대 2회 반복)
  - PY-015 컨텍스트 압축(compress_context): 쿼리와 무관한 문장 제거로 LLM 입력 토큰 절감
  - PY-016 멀티모달 임베딩(embed_multimodal): CLIP 기반 이미지+텍스트 통합 임베딩
  - PY-017 웹 스크래핑 강화(scrape_url): Playwright JS 렌더링, 사이트맵 크롤링, robots.txt 준수
  - PY-018 한국어 NER 강화: 여권번호, 사업자등록번호, 운전면허번호, 한국 주소 인식기 4종 추가
  - PY-019 한국어 독성 분류 강화: 키워드 기반 → 임베딩 유사도 기반 독성 분류 고도화
  - PY-020 RAG 평가 벤치마크 자동 생성(generate_benchmark): 지식소스 청크에서 Q&A 쌍 자동 생성
  - PY-021 임베딩 드리프트 감지(detect_embedding_drift): 임베딩 분포 변화 모니터링 및 재인덱싱 권고
  - PY-022 추론 체인 LLM 콜백 연동: 휴리스틱 → Spring 오케스트레이터 HTTP 콜백으로 실제 LLM 호출
- **변경 사유**: 경쟁 플랫폼(Dify, OpenWebUI) 대비 RAG/Safety 품질 차별화, 프로덕션급 AI 파이프라인 완성
- **영향 모듈**: RAG Pipeline(PY-013~017), Safety(PY-018~019), Evaluation(PY-020~021), Agent(PY-022)
- **영향도**: High
- **영향 범위**: PY-013~PY-022(신규)
- **영향 설계서**: T1-1, T1-2, T3-2, T3-6, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.0.0
- **변경 일자**: 2026-03-19

### CR-010 | 플랫폼 핵심 강화
- **대상 기능 ID**: PRD-102~PRD-115(신규), PRD-048(확장), PRD-089(확장), PRD-092(확장), PRD-096(완성), PRD-097(완성), PRD-098~101(완성)
- **변경 타입**: 변경
- **변경 내용**: 프로덕션 서비스 수준으로 플랫폼 핵심 기능 10개 영역 강화
  - B1 파일 업로드 API(PRD-102): 지식소스에 멀티파트 파일 업로드, StorageService → 자동 인제스션
  - B2 대화 히스토리 DB 저장(PRD-103~105): Redis 캐시 + DB 영구 듀얼 저장, 대화 목록/상세/삭제 API
  - B3 인증/RBAC(PRD-106~108): JWT 인증 + API Key 인증, 역할 기반 접근 제어 실제 적용
  - B4 CR-006 완성(PRD-096~097): OllamaAdapter toolChoice "none" 처리 등 잔여 작업
  - B5 CR-007 완성(PRD-098~101): WorkflowEngine outputSchema 자동 주입, LlmCallStepExecutor 연동
  - B6 비용 추적 대시보드 강화(PRD-109~110): 모델별 단가 테이블, 비용 분석 차트(recharts)
  - B7 멀티모달 API 입력(PRD-111): ChatController content를 텍스트/이미지 혼합 지원, 3개 어댑터 매핑
  - B8 LLM 트레이싱(PRD-112~113): 모든 LLM 호출의 입출력/토큰/지연/비용 기록, 트레이스 조회 API
  - B9 검색 설정 CRUD 완성(PRD-055~058): RetrievalConfig ↔ VectorSearcher 실참조 연결 확인
  - B10 클라우드 스토리지 추상화(PRD-114~115): StorageService 인터페이스, Local/S3 구현체
- **변경 사유**: 경쟁 플랫폼 대비 누락된 핵심 기능(파일 업로드, 대화 영구 저장, 인증, 멀티모달) 보완, 프로덕션 운영에 필요한 트레이싱/비용 관리 체계 구축
- **영향 모듈**: RAG, 세션, 인증(신규), 채팅, 워크플로우, 오케스트레이터, LLM 어댑터, 관리, 스토리지(신규), 모니터링(FE)
- **영향도**: High
- **영향 범위**: PRD-048, PRD-055~058, PRD-089, PRD-092, PRD-096~101, PRD-102~115(신규)
- **영향 설계서**: T1-1, T1-2, T2-1, T3-2, T3-3, T3-6, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.0.0
- **변경 일자**: 2026-03-19

### CR-011 | ClaudeCodeTool 안정화 및 확장성 개선
- **대상 기능 ID**: PRD-116~PRD-121(신규)
- **변경 타입**: 변경
- **변경 내용**: ClaudeCodeTool(빌트인 Claude Code CLI 래퍼)의 프로덕션 안정화 및 확장성 개선
  - PRD-116 CLI 옵션 동적 전달: 하드코딩된 buildCommand → `cli_options` 맵 방식 전환. 워크플로우에서 CLI 옵션을 자유롭게 지정, CLI 업데이트 시 소스 변경 불필요
  - PRD-117 에러 패턴 DB 관리: `claude_code_error_patterns` Master DB 테이블. 문자열 매칭 기반 에러 분류 (AUTH_EXPIRED, RATE_LIMIT, NETWORK, TIMEOUT, MAX_TURNS, UNKNOWN)
  - PRD-118 서킷 브레이커: 연속 3회 실패 시 OPEN(5분 차단), HALF-OPEN 재시도, 성공 1회 시 CLOSED. 원인 불명 에러 및 타임아웃 재시도 실패 시 적용
  - PRD-119 알림 연동: 인증만료/Rate limit 즉시 알림, 서킷 OPEN 시 문자 발송, 미복구 시 30분 주기 재알림. Aimbase 알림 모듈 활용
  - PRD-120 Permission/세션 관리: `--permission-mode` 워크플로우 설정 지원 (도구 승인 자동화), `--continue`/`--resume` 세션 이어가기 지원
  - PRD-121 도구 파라미터 스키마 개선: `UnifiedToolDef.inputSchema`에 자주 쓰는 옵션(model, effort, permission-mode)은 enum 정의, 나머지는 cli_options 자유 입력. FE 워크플로우 스튜디오에서 동적 폼 렌더링 지원
- **변경 사유**: Docker 환경 구동 시 다수 문제 발견(인증, 품질, 행 걸림), CLI 옵션 하드코딩으로 확장성 제한, 에러 발생 시 사용자 인지/대응 수단 부재
- **영향 모듈**: tool/builtin(ClaudeCodeTool), monitoring(서킷 브레이커), workflow(파라미터 전달), FE 워크플로우 스튜디오(도구 설정 UI)
- **영향도**: High
- **영향 범위**: PRD-116~PRD-121(신규)
- **영향 설계서**: T1-1, T1-2, T2-1, T3-2, T3-6, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.1.0
- **변경 일자**: 2026-03-29

### CR-012 | LLM 컨텍스트 설계 보강
- **대상 기능 ID**: PRD-122~PRD-131(신규)
- **변경 타입**: 변경
- **변경 내용**: "LLM은 기억하지 않는다 — 컨텍스트 설계가 서비스다" 관점에서 5개 Gap 보강
  - PRD-122 Fallback Chain 실행기: 모델 A 실패 시 모델 B 자동 전환, 지수 백오프(1s→2s→4s)
  - PRD-123 범용 서킷 브레이커: ClaudeCodeCircuitBreaker를 일반화한 GenericCircuitBreaker, 모델별 장애 격리
  - PRD-124 의도 분류기: 규칙 기반 요청 복잡도 분류 (SIMPLE/MODERATE/COMPLEX)
  - PRD-125 Smart Model Routing: 복잡도에 따라 Haiku/Sonnet/Opus 자동 분기, routing_config DB 참조
  - PRD-126 대화 요약 생성: Haiku 모델로 이전 대화를 요약하여 컨텍스트 압축
  - PRD-127 요약 주입 및 ContextWindow 확장: 70% 토큰 도달 시 요약 트리거, 요약본 SYSTEM 뒤 주입
  - PRD-128 Exact Match 응답 캐시: SHA-256 해시 기반 Redis LLM 응답 캐시
  - PRD-129 Semantic Match 응답 캐시: 임베딩 유사도 기반 pgvector 의미적 캐시 (cosine≥0.95)
  - PRD-130 메모리 계층 분리: SYSTEM_RULES/LONG_TERM/SHORT_TERM/USER_PROFILE 4계층 구조
  - PRD-131 메모리 관리 API: 메모리 CRUD, 계층별 필터, 사용자 프로필 조회
  - DB: V23(conversation_sessions 컬럼 추가), V24(response_cache 테이블), V25(conversation_memories 테이블)
- **변경 사유**: 시중 오픈소스(Dify B+, Open WebUI C+, n8n C+) 대비 컨텍스트 설계 성숙도를 A-로 끌어올려 종합 1위 수준 달성. 특히 대화 요약(경쟁사 미보유)은 차별점, Smart Routing과 응답 캐시(Dify만 보유)는 경쟁 동등화
- **영향 모듈**: 오케스트레이터, 세션, 라우팅, 캐시(신규), 메모리(신규), Resilience(신규), LLM 어댑터
- **영향도**: High
- **영향 범위**: PRD-122~PRD-131(신규)
- **영향 설계서**: T1-1, T1-2, T2-1, T3-1, T3-2, T3-6, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.2.0
- **변경 일자**: 2026-03-29

---

### CR-013 | API Rate Limit 방어 (TokenBucket)
- **대상 기능 ID**: BIZ-007(Rate Limiting 강화)
- **변경 타입**: 변경
- **변경 내용**: Redis 기반 분산 TokenBucket Rate Limiter 도입 및 LLM 토큰 쿼터 적용
  - **TokenBucketRateLimiter**: Redis Lua 스크립트로 원자적 INCR + EXPIRE. 키: `rl:{tenantId}:{minuteWindow}`. 분산 환경(멀티 인스턴스)에서 정확한 카운팅 보장. Redis 장애 시 fail-open(요청 허용).
  - **RateLimitFilter**: Servlet Filter(@Order -100), TenantResolver 뒤에서 실행. 테넌트별 분당 요청 수 제한. 초과 시 429 + `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After` 헤더 반환.
  - **플랜별 RPM 기본값**: free=60, starter=300, pro=1000, enterprise=무제한(0). `subscriptions.api_rpm_limit` 컬럼으로 테넌트별 커스텀 가능.
  - **OrchestratorEngine 쿼터 적용**: chat()/chatStream() 진입 시 `QuotaService.checkLLMQuota()` 호출하여 월간 토큰 쿼터 초과 사전 거부.
  - **GlobalExceptionHandler**: QuotaExceededException → 429 Too Many Requests 매핑.
  - **DB 마이그레이션**: `V12__add_api_rpm_limit.sql` — subscriptions 테이블에 api_rpm_limit 컬럼 추가.
- **변경 사유**: 기존 Rate Limit은 정책 엔진 내 인메모리 슬라이딩 윈도우로 세션/인텐트 단위만 제한. API 레벨 테넌트별 분당 요청 제한과 월간 토큰 쿼터 사전 적용이 부재하여, 악의적/과도한 API 호출에 대한 방어와 비용 통제 불가.
- **영향 모듈**: policy(TokenBucketRateLimiter, RateLimitFilter 신규), orchestrator(OrchestratorEngine), api(GlobalExceptionHandler), domain/master(SubscriptionEntity), config(RedisConfig 활용)
- **영향도**: Medium
- **영향 범위**: BIZ-007, 전체 API 엔드포인트
- **영향 설계서**: T1-3, T3-1, T3-2
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.3.0
- **변경 일자**: 2026-04-05

---

### CR-014 | App-Tenant 3계층 멀티테넌시
- **대상 기능 ID**: PRD-신규 (App 관리, 소비앱 어드민 셀프서비스)
- **변경 타입**: 변경
- **변경 내용**: Platform → App → Tenant 3계층 멀티테넌시 아키텍처 도입
  - **App(소비앱) 계층 신설**: Master DB에 `apps` 테이블, `tenants.app_id` FK 추가
  - **App 전용 DB**: 소비앱 공통 리소스(워크플로우, 지식저장소, 프롬프트, 정책, 커넥션, 도구) 관리
  - **리소스 해석 우선순위**: Tenant 설정 > App 공통 설정 > Platform 기본값
  - **역할 분리**: 슈퍼어드민 = App 등록/관리, 소비앱 어드민 = 하위 Tenant 셀프서비스 생성/관리
  - **API 신규**:
    - `POST /api/v1/platform/apps` — 슈퍼어드민: App 등록 (App DB 자동 프로비저닝)
    - `GET/PUT/DELETE /api/v1/platform/apps/{appId}` — 슈퍼어드민: App CRUD
    - `POST /api/v1/apps/{appId}/tenants` — 소비앱 어드민: 하위 Tenant 생성
    - `GET/PUT/DELETE /api/v1/apps/{appId}/tenants/{tenantId}` — 소비앱 어드민: Tenant 관리
  - **오케스트레이터 변경**: 요청 처리 시 Tenant DB → App DB fallback 조회 로직
  - **FE**: App 관리 페이지 (슈퍼어드민), Tenant 셀프서비스 페이지 (소비앱 어드민)
  - **DB 구조**:
    - `aimbase_master` — apps, tenants(+app_id) 테이블
    - `aimbase_app_<appId>` — 소비앱 공통 리소스 DB
    - `aimbase_<tenantId>` — 테넌트 독립 DB (기존과 동일)
- **변경 사유**: 소비앱 내부 고객(하위 테넌트)별 DB 격리는 되어 있으나, 같은 소비앱 소속 테넌트들이 공통 리소스(워크플로우, 지식저장소 등)를 공유할 방법이 없음. 소비앱 어드민이 직접 하위 테넌트를 관리할 수 있는 셀프서비스 필요
- **영향 모듈**: tenant, api, orchestrator, config, domain, repository, FE(pages/platform, pages/app)
- **영향도**: High
- **영향 범위**: BIZ-003(멀티테넌시), PRD-001~095 전반 (리소스 조회 경로 변경)
- **영향 설계서**: T1-1, T2-1, T2-2, T3-1, T3-6
- **요청자**: 프로젝트 오너 | **승인자**: - | **적용 버전**: v3.4.0
- **변경 일자**: 2026-03-29

---

### CR-015 | 커넥션 그룹 Resilience + 키 관리 권한 체계
- **대상 기능 ID**: PRD-132~PRD-134(신규)
- **변경 타입**: 변경
- **변경 내용**: 커넥션 레벨 장애 대응 및 키 관리 권한 분리 체계 도입
  - **PRD-132 커넥션 그룹(Connection Group)**: 동일 프로바이더 커넥션을 그룹으로 묶어 관리. 3가지 분배 전략 지원 (PRIORITY — 우선순위 고정, ROUND_ROBIN — 순환 분산, LEAST_USED — 사용량 기반). 그룹 내 동일 adapter 타입만 허용(크로스 프로바이더 불가). Tenant DB에 `connection_groups` 테이블 신규.
    - API: `/api/v1/connection-groups` CRUD
    - `chat/completions`에 `connection_group_id` 파라미터 추가
    - FE: 커넥션 그룹 관리 페이지 (생성/편집/삭제, 멤버 드래그 정렬, 전략 선택)
  - **PRD-133 커넥션 레벨 폴백 엔진**: 그룹 내 커넥션 장애 시 자동 전환. 2단계 폴백 구조:
    - 1단계(커넥션 폴백): 같은 모델, 다른 커넥션(키)으로 시도. 전략에 따라 다음 커넥션 선택
    - 2단계(모델 폴백): 모든 커넥션 실패 시 기존 FallbackChainExecutor의 모델 폴백 발동
    - 커넥션별 GenericCircuitBreaker 인스턴스 관리
    - 커넥션별 사용 카운터(AtomicLong) — ROUND_ROBIN/LEAST_USED 전략용
  - **PRD-134 키 관리 권한 체계**: 구독 플랜에 따라 3가지 관리 모드
    - `PLATFORM_MANAGED`: 슈퍼어드민이 App DB에 커넥션 제공, 테넌트는 읽기만 (커넥션 CRUD 차단)
    - `TENANT_MANAGED`: 테넌트가 자기 DB에서 자유 관리 (현재 동작과 동일)
    - `HYBRID`: 플랫폼 제공 키(App DB) + 테넌트 자체 키(Tenant DB) 병용. 조회 시 Tenant DB 우선 → App DB fallback
    - Master DB `subscriptions`에 `connection_management_mode` 컬럼 추가
    - 커넥션 CRUD API에 모드별 권한 체크 로직 추가
- **변경 사유**: 단일 키 의존 구조는 키 만료/Rate Limit/장애 시 서비스 중단 위험. 다수 키를 확보해도 자동 전환 메커니즘 부재. 또한 SaaS 모델에서 슈퍼어드민 중앙 키 제공과 BYOK(Bring Your Own Key) 고객 자율 관리를 계약에 따라 유연하게 운영할 수 있는 구조 필요
- **영향 모듈**: llm(ConnectionAdapterFactory, FallbackChainExecutor, ModelRouter), orchestrator(OrchestratorEngine), api(ConnectionGroupController, ConnectionController 권한 체크), domain(ConnectionGroupEntity, SubscriptionEntity 확장), repository, FE(pages/connection-groups)
- **영향도**: High
- **영향 범위**: PRD-122(FallbackChain 확장), PRD-123(CircuitBreaker 확장), BIZ-003(멀티테넌시 키 관리)
- **영향 설계서**: T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.5.0
- **변경 일자**: 2026-03-30

---

### CR-016 | 공용 워크플로우 + 빌트인 도구 확장
- **대상 기능 ID**: PRD-135~PRD-138(신규)
- **변경 타입**: 변경
- **변경 내용**: 플랫폼 공용 워크플로우 아키텍처 및 파일 분석 빌트인 도구 체계 도입
  - **PRD-135 빌트인 도구 — ZipExtractTool**: ZIP 파일을 임시 디렉토리에 압축 해제. Zip Slip/Zip Bomb 방어 (경로 순회 차단, 파일당 100MB·총 10,000개 제한). 워크플로우 TOOL_CALL Step으로 사용.
  - **PRD-136 빌트인 도구 — TempCleanupTool**: ZipExtractTool이 생성한 임시 디렉토리를 안전 삭제. `aimbase-zip-` 접두사 경로만 삭제 허용 (임의 경로 삭제 차단).
  - **PRD-137 플랫폼 공용 워크플로우 (Platform Workflows)**: Master DB에 `platform_workflows` 테이블 신설. 모든 테넌트가 공용으로 사용 가능한 워크플로우 등록/조회/실행.
    - API: `/api/v1/platform/workflows` CRUD + Run (`SUPER_ADMIN` 권한)
    - WorkflowEngine에 `executePlatform()` 메서드 추가 — PlatformWorkflowEntity를 받아 기존 DAG 엔진 재사용
    - 시드 6개: 파일 분석, 코드 리뷰, 문서 생성, 텍스트 요약, 텍스트 번역, 데이터 정제
    - FE: NodePalette에 "공용 워크플로우" 섹션 동적 렌더링, 드래그&드롭으로 DAG에 삽입
  - **PRD-138 SUB_WORKFLOW StepType**: 공용 워크플로우를 서브 워크플로우로 끼워 넣는 새 StepType.
    - `SubWorkflowStepExecutor` — 공용 워크플로우의 steps를 내부적으로 위상 정렬 + 순차 실행
    - 부모 워크플로우의 DAG에서 하나의 노드로 표현 (입출력만 연결)
    - 공용 워크플로우 업데이트 시 자동 반영 (참조 실행, 복사 아님)
    - config: `{"workflow_id": "file-analysis", "input": {"zip_path": "{{input.zip_path}}", "prompt": "..."}}`
    - FE: WorkflowStudio에서 `sub_workflow` 노드 드롭 시 `workflow_id` 자동 설정
  - **DB 마이그레이션**:
    - `V9__create_platform_workflows.sql` — Master DB에 `platform_workflows` 테이블
    - `V10__seed_platform_workflows.sql` — 시드 6개 (file-analysis, code-review, doc-generation, text-summarize, text-translate, data-transform)
- **변경 사유**: 테넌트별 워크플로우만 존재하여 "파일 업로드 → Claude Code 분석" 같은 범용 파이프라인을 각 테넌트마다 중복 등록해야 했음. 공용 워크플로우를 플랫폼 레벨로 제공하고, SUB_WORKFLOW로 기존 DAG에 끼워 넣을 수 있게 하여 재사용성 확보
- **영향 모듈**: tool/builtin(ZipExtractTool, TempCleanupTool), workflow(SubWorkflowStepExecutor, WorkflowEngine, WorkflowStep), api(PlatformWorkflowController), domain/master(PlatformWorkflowEntity), repository/master(PlatformWorkflowRepository), FE(NodePalette, WorkflowNode, WorkflowStudio, platformWorkflows API/hook)
- **영향도**: High
- **영향 범위**: BIZ-009(워크플로우 DAG 확장), PRD-135~138(신규)
- **영향 설계서**: T1-2, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.6.0
- **변경 일자**: 2026-03-31

---

### CR-017 | FlowGuard Agent 범용 도구 확장
- **대상 기능 ID**: PRD-163~PRD-166(신규)
- **변경 타입**: 변경
- **변경 내용**: 기존 FlowGuard Agent(flowguard-agent.jar, Playwright 전담)에 범용 로컬 도구를 추가하여 단일 데몬으로 통합
  - **PRD-139 Agent 범용 도구 프레임워크**: WebSocket ASSIGN_TASK 프로토콜에 `taskType` 분기 추가. `"playwright"` (기존) 또는 `"tool"` (범용 도구). ToolDispatcher가 toolName으로 적절한 실행기에 위임.
    - 설정: `agent-config.json` (allowedPaths, allowedCommands, 타임아웃)
    - 보안: PathValidator(경로 화이트리스트, 심볼릭 링크 차단), CommandSanitizer(명령 화이트리스트, Docker 위험 플래그 차단)
  - **PRD-140 Claude CLI 도구**: 로컬 소스코드 대상 Claude CLI 실행 (도구 수준, 오케스트레이션 아님). FlowGuard가 직접 호출 — Aimbase를 경유하지 않음.
    - 도구: `claude_execute` (프로젝트 경로 + 프롬프트 → 결과)
    - CLAUDE_CONFIG_DIR 멀티 계정 지원
  - **PRD-141 파일시스템 도구**: 로컬 파일 읽기/쓰기/목록/검색. allowedPaths 화이트리스트로 접근 범위 제한.
    - 도구: `file_read`, `file_write`, `file_list`, `file_search`
  - **PRD-142 Docker/Git/Shell 도구**: 로컬 Docker, Git, 셸 명령 실행.
    - `docker_exec`, `docker_logs`, `docker_ps` — 컨테이너 관리
    - `git_status`, `git_diff`, `git_log` — 소스 이력 조회
    - `shell_exec` — 화이트리스트 명령만 허용 (npm, gradle, mvn 등)
  - **아키텍처 결정**:
    - 범용 도구는 AI 오케스트레이션이 아닌 **도구 수준** 실행 — Aimbase 경유 불필요
    - FlowGuard가 WebSocket으로 직접 Agent에 요청 → Agent가 로컬에서 실행 → 결과 반환
    - Aimbase 컨테이너 내 Claude CLI는 별도 유지 (컨테이너 내부 작업용)
- **변경 사유**: 사이드카 제거 후 원격 PC의 로컬 자원(소스코드, Docker, git)에 접근하는 도구 부재. FlowGuard Agent가 이미 Playwright 데몬으로 존재하므로, 여기에 범용 도구를 추가하여 데몬 관리 포인트를 1개로 유지
- **영향 모듈**: flowguard/agent (FlowGuardAgent, AgentWebSocketClient, tool/*, util/*)
- **영향도**: High
- **영향 범위**: PRD-163~PRD-166(신규)
- **영향 설계서**: T1-1, T2-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.7.0
- **변경 일자**: 2026-04-01

---

### CR-029 | Aimbase 1단계 고도화 — Native Tool + Session Meta + Context Assembly + Runtime 재배치
- **대상 기능 ID**: PRD-167 ~ PRD-185, FE-011 ~ FE-014
- **변경 타입**: 변경
- **변경 내용**: ClaudeTool 내부에 묻혀 있는 도구·세션·컨텍스트·실행 흐름의 통제권을 Aimbase 상위 레이어로 끌어올림
  - Tool Contract 표준화: EnhancedToolExecutor, ToolContext, ToolResult, ToolContractMeta
  - Workspace Guard: WorkspacePolicy, WorkspacePolicyEngine (3단계 정책 계층)
  - Native Tool 9종: FileRead, Glob, Grep, WorkspaceSnapshot, PathInfo, StructuredSearch, DocumentSectionRead, SafeEdit, PatchApply
  - Session Meta: scope_type, runtime_kind, workspace_ref, parent_session_id + tool_execution_log
  - Context Assembly: ContextAssemblyEngine, Context Recipe, 10개 Source Provider, Budget/Priority/Freshness/Dedup, AssemblyTrace
  - Runtime Adapter: RuntimeAdapter, RuntimeRegistry, ClaudeTool STATELESS/PERSISTENT mode, selectionReason 로그
  - Domain Pack: DomainAppConfig (도메인별 기본 recipe/tool allowlist/runtime)
  - FE: Sessions, ContextRecipes, DomainConfigs 페이지 + WorkflowStudio Tool 통합
- **변경 사유**: Aimbase를 도메인 앱용 AI 운영 레이어로 재정렬. ClaudeTool 실행 래퍼 → 상위 통제 구조로 전환
- **영향 모듈**: Tool, Session, Context, Orchestrator, Runtime, Workflow, FE 전체
- **영향도**: High
- **영향 범위**: PRD-167 ~ PRD-185, FE-011 ~ FE-014
- **영향 설계서**: T1-1, T1-3, T2-1, T2-2, T3-1, T3-2, T3-3, T3-4
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.0.0
- **변경 일자**: 2026-04-06

### CR-030 | Aimbase 2단계 고도화 — Hook Architecture + Extended Thinking + Agent Isolation + 압축 전략 강화
- **대상 기능 ID**: PRD-186 ~ PRD-210 (신규 할당)
- **변경 타입**: 변경
- **변경 내용**: openclaude 벤치마킹 기반 플랫폼 기능 격차 해소 (6 Phase)
  - **Phase 1 — Extended Thinking**: ContentBlock.Thinking sealed 서브타입 추가, TokenUsage에 thinking 토큰 필드, ModelConfig에 extendedThinking/thinkingBudgetTokens 필드, AnthropicAdapter thinking 파라미터 빌드 + ThinkingBlock 응답 파싱
  - **Phase 2 — Hook Architecture**: HookEvent enum(9+ 이벤트: PreToolUse, PostToolUse, UserPromptSubmit, SessionStart/End, PreCompact/PostCompact, PermissionRequest/Denied), HookInput/HookOutput/HookDecision 모델, HookRegistry + HookExecutor + HookDispatcher, ToolCallHandler에 PreToolUse/PostToolUse 삽입, OrchestratorEngine에 UserPromptSubmit/SessionStart/End 삽입
  - **Phase 3 — Permission AUTO Mode**: PermissionLevel.AUTO 추가, PermissionClassifier(요청 내용 기반 자동 권한 분류), PermissionRule(도구명 패턴 → 권한 매핑), EnhancedToolExecutor 도구별 최소 권한 검증
  - **Phase 4 — Memory Scope**: MemoryScope enum(PRIVATE/TEAM/GLOBAL), ConversationMemoryEntity에 scope/teamId 필드, TeamMemoryService, 컨텍스트 주입 우선순위(PRIVATE > TEAM > GLOBAL), Flyway 마이그레이션
  - **Phase 5 — 압축 보정 전략 강화**: CompactionStrategy enum(SNIP/MICRO/AUTO/SESSION_MEMORY/BLOCK), CompactionState/CompactionThresholds 모델, SessionMemoryCompactionService, ContextWindowManager 5전략 재구성, blocking limit + 환경변수 override
  - **Phase 6 — Subagent + Worktree Isolation**: SubagentRequest/Result/Context 모델, SubagentRunner(포그라운드 CompletableFuture + 백그라운드 Virtual Thread), WorktreeManager(git worktree add/remove), WorkflowEngine AGENT_CALL 스텝 타입, AgentOrchestrator(병렬 에이전트 조율)
- **변경 사유**: openclaude 전수 비교 결과 aimbase 플랫폼 레벨에서 누락된 핵심 메커니즘 식별. 훅 시스템(확장성), Extended Thinking(추론 품질), 에이전트 격리(안전성), 압축 전략(장시간 세션) 등 엔터프라이즈 플랫폼 필수 기능 확보
- **영향 모듈**: Hook(신규), Agent(신규), LLM(ContentBlock/TokenUsage/AnthropicAdapter), Tool(PermissionLevel/PermissionClassifier), Session(MemoryScope/CompactionStrategy), Orchestrator, Workflow, Context
- **영향도**: High
- **영향 범위**: PRD-186 ~ PRD-210
- **영향 설계서**: T1-1, T2-1, T2-2, T3-1, T3-2, T3-3
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.1.0
- **변경 일자**: 2026-04-07

---

### CR-031 | 성능/퀄리티 메커니즘 6 Phase
- **대상 기능 ID**: PRD-211 ~ PRD-216 (신규)
- **변경 타입**: 변경
- **변경 내용**: openclaude 벤치마킹 기반 LLM 출력 품질 및 비용 최적화 메커니즘 6종
  - **PRD-211 Post-Compact Recovery**: 압축 후 최근 참조 파일 5개 + 장기 메모리 자동 재주입 (50K 토큰 예산)
  - **PRD-212 MICRO_COMPACT 0비용**: Haiku 호출 없이 마커 대체로 MICRO_COMPACT 수행
  - **PRD-213 Extract Memories 자동화**: 대화 5턴 이상 시 Haiku로 메모리 자동 추출 + 중복 판정
  - **PRD-214 Adaptive Thinking**: ThinkingMode(DISABLED/ENABLED/ADAPTIVE) + Claude 4.6+ 분기
  - **PRD-215 Tool Result 축약**: 도구별 축약 전략 레지스트리
  - **PRD-216 Agent 진행 요약**: 30초 주기 서브에이전트 progressSummary SSE 푸시
- **변경 사유**: 장시간 대화 품질 급락 방지 + 불필요한 LLM 비용 절감
- **영향 모듈**: Session(압축), Memory(자동추출), LLM(Adaptive Thinking), Tool(축약), Agent(진행요약)
- **영향도**: High
- **영향 범위**: PRD-211 ~ PRD-216, BIZ-046 ~ BIZ-049
- **영향 설계서**: T1-1, T1-3, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v5.0.0
- **변경 일자**: 2026-04-08

---

### CR-032 | 프로바이더 확장 6 Phase
- **대상 기능 ID**: PRD-217 ~ PRD-221 (신규)
- **변경 타입**: 변경
- **변경 내용**: LLM 프로바이더 3개 추가 + 에이전트별 라우팅 + FE 동적 폼
  - **PRD-217 OpenAI Compatible shim**: Chat Completions API 호환 범용 어댑터 (DeepSeek/Groq/Mistral 등)
  - **PRD-218 AWS Bedrock**: Bedrock Runtime SDK 프록시 모드
  - **PRD-219 Google Vertex AI**: Vertex AI Prediction API 프록시 모드
  - **PRD-220 에이전트별 라우팅**: SubagentRequest.preferredConnectionId로 ModelRouter 우회
  - **PRD-221 FE Connection 폼 확장**: adapter별 동적 config 필드 렌더링
- **변경 사유**: 엔터프라이즈 환경에서 Bedrock/Vertex 필수. OpenAI 호환 서비스 통합 필요
- **영향 모듈**: LLM(어댑터 3개), Agent(라우팅), FE(Connection 폼)
- **영향도**: High
- **영향 범위**: PRD-217 ~ PRD-221, BIZ-050 ~ BIZ-051
- **영향 설계서**: T1-1, T1-3, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v5.0.0
- **변경 일자**: 2026-04-08

---

### CR-033 | 에이전트 구조적 사고 체계 — Plan Mode + Todo + Task 관리
- **대상 기능 ID**: PRD-222 ~ PRD-227 (신규), FE-015 (신규)
- **변경 타입**: 변경
- **변경 내용**: LLM 에이전트의 구조적 사고를 지원하는 도구 10종 + FE 대시보드
  - **PRD-222 EnterPlanModeTool**: 계획 모드 진입 — PlanEntity 생성, ToolFilterContext readOnlyMode 활성화, 쓰기 도구 자동 차단
  - **PRD-223 ExitPlanModeTool**: 계획 모드 종료 — steps 확정, planModeActive 해제, 실행 단계 전환
  - **PRD-224 VerifyPlanExecutionTool**: 계획 대비 실행 검증 — step별 결과 매칭, 완료율 산출, 미완료 gap 식별
  - **PRD-225 TodoWriteTool**: 세션 체크리스트 — 전체 교체 방식 CRUD, Redis 캐시 + DB 영속
  - **PRD-226 Task Create/Get/List**: SubagentRunner 래핑 태스크 생성·조회·목록 3종
  - **PRD-227 Task Update/Output/Stop**: 태스크 수정, 대용량 출력 별도 저장, 실행 중 태스크 중지 3종
  - **FE-015 Plan/Todo/Task 대시보드**: 세션 상세 화면에 3개 탭 패널 추가
  - **DB 추가**: plans, todos 2개 테이블 신규. subagent_runs에 task_description/priority/large_output 3개 컬럼 추가
  - **BIZ 규칙 추가**: BIZ-052(Plan 읽기전용), BIZ-053(세션당 Plan 1개), BIZ-054(Plan FSM), BIZ-055(Todo 전체교체), BIZ-056(Task 5개 제한)
- **변경 사유**: openclaude 전수 비교에서 식별된 높은 우선순위 Tool 누락. 복잡한 요청에서 "바로 코드 쓰기" 대신 "탐색→계획→실행→검증" 구조적 사고 가능. 멀티스텝 워크플로우와 서브에이전트 시나리오에서 에이전트 출력 품질 구조적 향상
- **영향 모듈**: Tool(도구 10종), Agent(PlanService, TodoService), Session(planModeActive), Domain(PlanEntity, TodoEntity), FE(SessionDetail 확장)
- **영향도**: High
- **영향 범위**: PRD-222 ~ PRD-227, FE-015, BIZ-052 ~ BIZ-056
- **영향 설계서**: T1-1, T1-3, T1-7, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.1.0
- **변경 일자**: 2026-04-08

---

### CR-034 | 멀티에이전트 협업 완성 — SendMessage + Built-in Agent 5타입 + Hook 14개
- **대상 기능 ID**: PRD-228 ~ PRD-233 (신규), FE-016 (신규)
- **변경 타입**: 신규
- **변경 내용**: 멀티에이전트 협업을 위한 메시지 통신 + 에이전트 타입 체계 + Hook 확장
  - **SendMessageTool**: 에이전트간 1:1/브로드캐스트 메시지
  - **AgentMessageBus**: 메시지 큐 + 라우팅
  - **Built-in Agent 5타입**: GENERAL/PLAN/EXPLORE/GUIDE/VERIFICATION
  - **AgentTypeRegistry**: 에이전트 타입 중앙 관리
  - **HookEvent 14개 추가**: Notification, Stop, StopFailure, Setup, TeammateIdle, TaskCreated, TaskCompleted, Elicitation, ElicitationResult, ConfigChange, WorktreeCreate, WorktreeRemove, InstructionsLoaded, CwdChanged
  - **FE MessagePanel 컴포넌트**
- **변경 사유**: 멀티에이전트 시나리오에서 에이전트간 협업 통신 및 역할 기반 실행 체계 필요
- **영향 모듈**: Agent, Hook, Tool, Workflow, FE
- **영향도**: High
- **영향 범위**: PRD-228 ~ PRD-233, FE-016
- **영향 설계서**: T1-1, T1-3, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.0.0+
- **변경 일자**: 2026-04-08

---

### CR-035 | Tool/Policy 확장성·자동화 — ScheduleCron + SkillTool + Firecrawl + 도메인 필터링
- **대상 기능 ID**: PRD-234 ~ PRD-240 (신규), FE-017 (신규)
- **변경 타입**: 변경
- **변경 내용**: 갭 분석 기반 tool/ + policy/ 패키지 미구현 기능 추가 (6 Phase)
  - **PRD-234 Cron 스케줄 엔진**: CronScheduleManager (Spring TaskScheduler 래핑), scheduled_jobs 테이블, 서버 기동 시 active job 로드, TenantContext 내 워크플로우/도구 실행
  - **PRD-235 ScheduleCronTool + CronListTool + CronDeleteTool**: LLM이 자율적으로 Cron 작업 CRUD. 테넌트당 50개 제한, 최소 1분 간격, 3회 실패 시 비활성화
  - **PRD-236 ToolSearchTool**: ToolContractMeta의 tags/capabilities/description 키워드 검색. ToolRegistry.searchTools() 메서드 추가
  - **PRD-237 SkillTool**: 재사용 가능한 프롬프트+도구 조합 경량 실행. skills 테이블, 단일 LLM 호출 (워크플로우와 차별점)
  - **PRD-238 Python 사이드카 Firecrawl 어댑터**: scraper.py에 firecrawl 모드 추가, firecrawl-py 의존성, Self-hosted 지원, API Key 미설정 시 js_render 폴백
  - **PRD-239 BE 지식소스 Firecrawl 모드**: KnowledgeSource.crawl_mode 필드, IngestionPipeline 분기, FE 크롤링 모드 선택
  - **PRD-240 PolicyEngine 도메인 필터링**: DOMAIN_FILTER 규칙 타입 신규, allowed_domains/blocked_domains, ALLOWLIST/BLOCKLIST 모드, 와일드카드 서브도메인
  - **FE-017 관리 UI**: 스케줄 모니터링 탭, 도구 탐색 패널, 스킬 관리 페이지, 정책 도메인 필터 UI
  - **DB**: V20__cr035_scheduled_jobs.sql (Master), V44__cr035_skills.sql (Tenant)
  - **BIZ 규칙**: BIZ-057~065 (스케줄 제한, 스킬 규칙, Firecrawl 폴백, 도메인 필터링)
- **변경 사유**: openclaude 갭 분석에서 식별된 중간 우선순위 Tool 누락 + 정책 엔진 확장. 자동화(Cron), 확장성(Skill/ToolSearch), 웹 소스 품질(Firecrawl), 보안(도메인 필터) 4개 축 강화
- **영향 모듈**: Tool(도구 5종 신규), Policy(DOMAIN_FILTER), RAG(Firecrawl), Python 사이드카(scraper), Workflow(CronScheduleManager), FE(4개 페이지/패널)
- **영향도**: High
- **영향 범위**: PRD-234 ~ PRD-240, FE-017, BIZ-057 ~ BIZ-065
- **영향 설계서**: T1-1, T1-3, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.2.0
- **변경 일자**: 2026-04-08

---

### CR-036 | 프롬프트 외부화 + 영문 전환 + OpenClaude 프롬프트 전수 포팅
- **대상 기능 ID**: PRD-249 ~ PRD-264 (신규), FE-020 ~ FE-021 (신규)
- **변경 타입**: 변경
- **변경 내용**: Java/Python 소스에 하드코딩된 시스템 프롬프트 25개를 DB 외부화하고, OpenClaude 원본 70+개 프롬프트를 전수 포팅 (10 Phase)
  - **PRD-249 prompt_templates 테이블**: Tenant DB V46 마이그레이션. key+version 복합키, category/name/template/variables/language/is_system 컬럼. Caffeine 캐시 → DB → resources/prompts/*.txt 3단계 폴백
  - **PRD-250 PromptTemplateService**: getTemplate/render/getTemplateOrFallback/bulkLoad 핵심 메서드. Caffeine 로컬 캐시 (5분 TTL, 200개). {{variable}} 치환 렌더링
  - **PRD-251 PromptTemplateController**: `/api/v1/prompt-templates` CRUD + render + bulk API
  - **PRD-252 기존 Java 프롬프트 외부화**: AgentType(5), ContextAssemblyEngine(1), RAGService(1), LlmCallStepExecutor(5), ImageAnalysisTool(1), TranslationTool(1), ConversationSummarizer(2), MemoryAutoExtractService(1), Adapter(2) — 한글→영문 전환 포함
  - **PRD-253 시드 마이그레이션**: V47 시드 데이터 INSERT (25개 영문 프롬프트 기본값)
  - **PRD-254 Python PromptTemplateClient**: httpx 기반 BE API 벌크 로드 + 메모리 캐시. 실패 시 로컬 파일 폴백
  - **PRD-255 Python 프롬프트 외부화**: query_transformer(3), contextual_chunker(1), evaluator(3) — 한글→영문 전환
  - **PRD-256 OpenClaude Core 시스템 프롬프트**: prompts.ts(914줄) 기반 core.* 카테고리 10+개. ContextAssemblyEngine 모듈별 조립으로 리팩토링
  - **PRD-257 OpenClaude Tool 프롬프트 Part 1**: BashTool(369줄), AgentTool(287줄), TodoWriteTool(184줄) 등 주요 도구 8개
  - **PRD-258 OpenClaude Tool 프롬프트 Part 2**: FileRead/Edit/Write, Grep, Glob, WebFetch/Search 등 나머지 28개
  - **PRD-259 OpenClaude 서비스 프롬프트**: extractMemories, SessionMemory, compact, MagicDocs 등 6개
  - **PRD-260 동적 프롬프트 조립 엔진**: systemPromptSections.ts 기반 동적 조립. 언어/MCP/Git/출력스타일 등 런타임 섹션
  - **FE-020 프롬프트 템플릿 관리 화면**: 목록/편집/카테고리 필터. is_system=true 삭제 불가
  - **FE-021 프롬프트 테스트 패널**: 변수 입력 → 렌더링 프리뷰 + 토큰 추정
  - **DB**: V46__cr036_prompt_templates.sql, V47__cr036_seed_prompts.sql (Tenant)
  - **BIZ 규칙**: BIZ-070(프롬프트 3단계 폴백), BIZ-071(시스템 프롬프트 삭제 불가), BIZ-072(프롬프트 캐시 TTL 5분)
- **변경 사유**: 프롬프트 하드코딩으로 수정 시 재배포 필수, 한글 프롬프트 토큰 비용 2~3배, 런타임 A/B 테스트 불가. OpenClaude 대비 Tool별 프롬프트 세분화 부족 (103줄 통합 vs 36개 개별)
- **영향 모듈**: Context(ContextAssemblyEngine 리팩토링), Agent(AgentType), RAG(RAGService), Workflow(LlmCallStepExecutor), Tool(ImageAnalysis/Translation), Session(Summarizer/MemoryExtract), LLM(Adapter), Python(query_transformer/chunker/evaluator), FE(신규 2개 페이지)
- **영향도**: High
- **영향 범위**: PRD-249 ~ PRD-260, FE-020 ~ FE-021, BIZ-070 ~ BIZ-072
- **영향 설계서**: T1-1, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.5.0
- **변경 일자**: 2026-04-08

---

### CR-037 | 핵심 도구 네이티브화 — BashTool + FileWriteTool + WebSearchTool + SuggestBackgroundPR
- **대상 기능 ID**: PRD-241 ~ PRD-244 (신규), FE-018 (신규)
- **변경 타입**: 변경
- **변경 내용**: ClaudeCodeTool 경유 없이 직접 실행 가능한 핵심 도구 4종 네이티브 구현
  - **PRD-241 BashTool**: ProcessBuilder 기반 셸 명령 실행, 위험 명령 차단 목록, 타임아웃 120초, stdout/stderr 분리 캡처, WorkspaceResolver 작업 디렉토리 제한
  - **PRD-242 FileWriteTool**: 신규 파일 생성 (SafeEditTool 보완), 부모 디렉토리 자동 생성, 기존 파일 덮어쓰기 경고, WorkspacePolicyEngine 경로 검증
  - **PRD-243 WebSearchTool**: Tavily API 우선 + DuckDuckGo HTML 폴백, Connection 테이블에서 API Key 조회, title/url/snippet 구조화 반환
  - **PRD-244 SuggestBackgroundPR**: Git 커밋 + GitHub PR 자동 생성, ProcessBuilder git 명령, GitHub REST API, Connection에서 토큰 조회
  - **FE-018**: 기존 도구 목록에 자동 노출 (ToolRegistry 기반, 별도 페이지 불필요)
- **변경 사유**: ClaudeCodeTool 경유 시 프로세스 오버헤드(subprocess + CLI), 인증 의존성(Anthropic API Key), lineage 추적 불가, 정책 엔진 우회 등 4가지 문제 해소. openclaude 갭 분석 핵심 4개 항목
- **영향 모듈**: Tool(도구 4종 신규), FE(도구 목록 자동 노출)
- **영향도**: High
- **영향 범위**: PRD-241 ~ PRD-244, FE-018
- **영향 설계서**: T1-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.3.0
- **변경 일자**: 2026-04-08

---

### CR-038 | 에이전트 자율성 강화 — MCP 리소스 탐색·읽기 + 이벤트 트리거 + 세션 브리핑
- **대상 기능 ID**: PRD-245 ~ PRD-248 (신규), FE-019 (신규)
- **변경 타입**: 변경
- **변경 내용**: 에이전트가 사람 개입 없이 자율적으로 MCP 리소스 탐색·읽기, 이벤트 기반 즉시 트리거, 세션 이전 작업 브리핑을 수행하는 도구 4종 + FE 확장
  - **PRD-245 ListMcpResourcesTool**: 연결된 MCP 서버의 리소스 목록 탐색. MCPServerClient.listResources() 호출. server_id 선택적 필터. 리소스 URI/name/description/mimeType 반환
  - **PRD-246 ReadMcpResourceTool**: MCP 리소스 URI로 직접 읽기. MCPServerClient.readResource() 호출. text/blob 콘텐츠 반환. 대용량 텍스트 32KB 트렁케이션
  - **PRD-247 RemoteTriggerTool**: 이벤트 기반 워크플로우/도구 즉시 실행. CronScheduleManager의 executeJob 로직 재사용. Cron(주기적)과 보완. trigger_reason 감사 기록
  - **PRD-248 BriefTool**: 세션 이전 작업 요약 생성. SessionStore에서 최근 메시지 로드 → LLM 호출로 요약. session_briefs 테이블 캐시. 메모리 시스템과 용도 차별화 (Brief=세션 즉시 요약, Memory=장기 저장)
  - **FE-019 세션 브리핑 패널**: 세션 상세 화면에 Brief 탭 추가. 이전 세션 요약 표시 + 수동 생성 버튼
  - **DB**: V45__cr038_session_briefs.sql (Tenant) — session_briefs 테이블
  - **BIZ 규칙**: BIZ-066(MCP 리소스 읽기 32KB 트렁케이션), BIZ-067(RemoteTrigger 분당 10회 제한), BIZ-068(Brief 캐시 TTL 1시간), BIZ-069(Brief 생성 시 최근 50개 메시지 사용)
- **변경 사유**: openclaude 갭 분석에서 식별된 에이전트 자율성 관련 4개 도구. MCP 리소스 탐색은 UI(사람용)와 Tool(에이전트용) 용도 분리 필요. Brief는 Memory와 다른 용도(세션 즉시 복원 vs 장기 저장). RemoteTrigger는 Cron(주기적)과 보완적(이벤트 기반 즉시)
- **영향 모듈**: Tool(도구 4종 신규), MCP(MCPServerClient 리소스 메서드 추가), Session(BriefService), FE(세션 상세 확장)
- **영향도**: High
- **영향 범위**: PRD-245 ~ PRD-248, FE-019, BIZ-066 ~ BIZ-069
- **영향 설계서**: T1-1, T1-3, T1-7, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.4.0
- **변경 일자**: 2026-04-08

---

### CR-039 | 고급 확장 도구 — Swarm 팀 협업 + Notebook 편집 + LSP 코드 분석
- **대상 기능 ID**: PRD-265 ~ PRD-268 (신규), FE-022 (신규)
- **변경 타입**: 변경
- **변경 내용**: openclaude 갭 분석 최종 잔여 4개 도구 — Swarm 팀 협업, Notebook 편집, LSP 코드 분석 (4 Phase)
  - **PRD-265 TeamCreateTool**: Swarm 패턴 동적 팀 생성. 팀 이름, 목적, 멤버 에이전트(AgentType + 역할) 정의. 세션 스코프 휘발성 (Redis 캐시 + teams 테이블 기록용). AgentOrchestrator.runParallel()로 팀 멤버 병렬 실행. 팀 내 에이전트간 SendMessageTool 통신 자동 활성화. 팀당 최대 5명 멤버 제한
    - 입력: `{"name": "code-review-team", "objective": "PR #42 리뷰", "members": [{"agent_type": "EXPLORE", "role": "코드 탐색"}, {"agent_type": "VERIFICATION", "role": "테스트 검증"}]}`
    - 출력: `{"team_id": "...", "members": [...], "status": "ACTIVE"}`
    - TeamService: 팀 CRUD + 멤버 관리 + 상태 추적 (ACTIVE/COMPLETED/DISSOLVED)
    - teams 테이블 (Tenant DB): id, session_id, name, objective, status, members(JSONB), created_at, dissolved_at
  - **PRD-266 TeamDeleteTool**: 팀 해체. 실행 중 멤버 에이전트 graceful stop (SubagentLifecycleManager 활용). 팀 상태 DISSOLVED로 전환. Redis 캐시 삭제 + DB 기록 유지
    - 입력: `{"team_id": "...", "reason": "리뷰 완료"}`
    - 출력: `{"team_id": "...", "status": "DISSOLVED", "members_stopped": 3}`
  - **PRD-267 NotebookEditTool**: Jupyter Notebook(.ipynb) 셀 CRUD. .ipynb는 JSON 구조이므로 Java 직접 편집 (Python sidecar 불필요). nbformat v4 호환. 5가지 작업 지원:
    - `add_cell`: 지정 위치에 code/markdown 셀 추가. execution_count 자동 관리
    - `edit_cell`: 기존 셀 소스 교체 (인덱스 또는 셀 ID 지정)
    - `delete_cell`: 셀 삭제
    - `move_cell`: 셀 순서 변경
    - `read_cell`: 특정 셀 또는 전체 노트북 읽기
    - WorkspacePolicyEngine 경로 검증 (허용된 워크스페이스 내 .ipynb만 편집)
    - 셀 출력(outputs) 보존 — 편집 시 기존 출력 유지, 명시적 clear 옵션 제공
    - 최대 노트북 크기 10MB 제한
  - **PRD-268 LSPTool**: Language Server Protocol 클라이언트 — 핵심 3개 기능만 초기 구현
    - `definition`: 심볼 정의 위치 추적 (textDocument/definition)
    - `references`: 심볼 참조 위치 목록 (textDocument/references)
    - `hover`: 심볼 타입 정보 조회 (textDocument/hover)
    - LSPClientManager: 언어별 Language Server 프로세스 관리 (Java → Eclipse JDT LS, TypeScript → typescript-language-server, Python → pylsp)
    - JSON-RPC 2.0 통신 (stdin/stdout). ProcessBuilder로 LS 프로세스 기동
    - Lazy 초기화: 첫 요청 시 해당 언어 LS 기동 + initialize 핸드셰이크. 5분 미사용 시 자동 종료
    - 입력: `{"action": "definition", "file_path": "src/Main.java", "line": 42, "character": 15}`
    - 출력: `{"uri": "file:///src/Service.java", "range": {"start": {"line": 10, "character": 4}, ...}}`
    - 세션 스코프: 세션 종료 시 모든 LS 프로세스 정리
    - 지원 언어: java, typescript, python (초기). 설정으로 확장 가능
  - **FE-022 팀 관리 + 도구 상태 UI**:
    - 세션 상세 화면에 Teams 탭 추가: 활성 팀 목록, 멤버 상태, 팀 생성/해체 이력
    - 도구 목록 페이지에 LSP 상태 표시: 언어별 LS 프로세스 상태 (IDLE/RUNNING/ERROR)
  - **DB**: V48__cr039_teams.sql (Tenant) — teams 테이블
  - **BIZ 규칙**: BIZ-073(팀당 멤버 최대 5명), BIZ-074(세션당 활성 팀 최대 3개), BIZ-075(Notebook 최대 10MB), BIZ-076(LSP 프로세스 5분 미사용 자동 종료), BIZ-077(LSP 초기 지원 언어 3개: java/typescript/python)
- **변경 사유**: openclaude 갭 분석 최종 잔여 4개. TeamCreate/Delete로 Swarm 패턴 멀티에이전트 협업 완성 (기존 AgentOrchestrator + SendMessage 위에 팀 추상화). NotebookEdit로 RAG 평가/벤치마크 결과의 재현 가능한 관리. LSPTool로 에이전트 코드 분석 품질 향상 (파일 텍스트 검색 → 타입 인식 심볼 추적)
- **영향 모듈**: Agent(TeamService 신규, AgentOrchestrator 확장), Tool(도구 4종 신규), FE(세션 상세 Teams 탭, 도구 LSP 상태)
- **영향도**: High
- **영향 범위**: PRD-265 ~ PRD-268, FE-022, BIZ-073 ~ BIZ-077
- **영향 설계서**: T1-1, T1-3, T1-7, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.6.0
- **변경 일자**: 2026-04-09

### CR-040 | 런타임 설정 관리 — DB 기반 설정 + 관리자 UI + 하드코딩 제거
- **대상 기능 ID**: PRD-269 ~ PRD-272, FE-023
- **변경 타입**: 변경
- **변경 내용**: 서버 재기동 없이 관리자가 런타임에 미세조정할 수 있도록 DB 기반 설정 관리 + FE 관리 UI 제공
  - **PRD-269 PlatformSettingsService**: global_config 테이블 기반 런타임 설정 서비스. Caffeine 캐시(5분 TTL) + 변경 시 캐시 무효화 + 감사 로그 기록. 카테고리별 그룹핑(orchestrator/session/compaction). 기본값 폴백(application.yml → 하드코딩).
  - **PRD-270 PlatformSettingsController**: GET/PUT /api/v1/platform/settings API. 카테고리별 조회, 단건/다건 수정. Super Admin 전용.
  - **PRD-271 V13 seed 마이그레이션**: global_config에 ~13개 기본 설정값 INSERT (max-tool-iterations, default-max-tokens, tool-result-budget-bytes, session-ttl-hours, compaction 임계값 등)
  - **PRD-272 하드코딩 제거**: ToolCallHandler(80KB/50KB 임계값), SessionStore(24h TTL), SendMessageTool(500/32KB), ReadMcpResourceTool(32KB)을 PlatformSettingsService 조회로 교체
  - **FE-023 플랫폼 설정 관리 페이지**: /platform/settings 라우트. 카테고리별 그룹핑(오케스트레이터/세션/압축). 인라인 편집 + 저장 + 즉시 반영. 기본값 리셋 버튼.
- **변경 사유**: 3자 벤치마크에서 max-tool-iterations, default-max-tokens 등의 하드코딩 값이 응답 품질에 직접 영향 확인. 변경 시 빌드/재기동 필요한 구조를 런타임 조정 가능하도록 개선.
- **영향 모듈**: Config(PlatformSettingsService 신규, PlatformSettingsController 신규), Tool(ToolCallHandler 수정), Session(SessionStore 수정), FE(설정 페이지 신규)
- **영향도**: High
- **영향 범위**: PRD-269 ~ PRD-272, FE-023
- **영향 설계서**: T1-1, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v6.7.0
- **변경 일자**: 2026-04-09

### CR-043 | ClaudeCodeTool 다중계정 운영 정책 정착 — 테넌트 전용/공통 풀 페일오버 + 호출중 자동 재시도
- **대상 기능 ID**: TBD (PRD 별도 발번 예정), 관련 BIZ-001/CR-014
- **변경 타입**: 변경 (운영정책 정착 + 코드 보강)
- **변경 내용**:
  1. **운영 정책 표준화**: `agent_accounts` + `agent_account_assignments` 테이블을 활용하여 (a) 테넌트별 전용 OAuth 토큰, (b) 공통 라운드로빈 풀 폴백, (c) 한 계정 실패 시 다음 후보 자동 사용하는 운영 패턴을 표준화한다. 현재 코드(`AgentAccountPoolManager.resolveAccount`)가 이미 specificity 정렬 + Circuit Breaker 기반 페일오버를 지원하므로 **선택 시점 페일오버는 추가 코드 불필요**.
  2. **호출중 자동 재시도 (신규 코드)**: 현재 페일오버는 "계정 선택 시점"에만 동작. 선택 후 토큰 만료/Rate Limit/일시 장애로 호출이 실패하면 그 호출 자체는 실패 반환됨. 단일 호출 내에서도 다음 후보 계정으로 자동 재시도하는 로직을 ClaudeCodeTool에 추가한다.
     - 실패 시 현재 계정의 GenericCircuitBreaker에 실패 기록
     - resolveAccount 재호출 → 다음 후보(specificity 또는 round-robin 차순위) 사용
     - 최대 재시도 횟수 설정값(기본 2회) 도달하면 최종 실패 반환
  3. **운영 가이드 문서화**: 테넌트 6개 + 공통 풀 운영 시나리오를 `aimbase-ops-guide.md`에 추가
- **변경 사유**:
  - 사용자 요구사항: "테넌트별로 키값을 분리해서 사용하거나 옵션에 따라 공통 계정을 같이 사용. 각 키값을 하나가 문제생기면 다른 하나를 사용할 수 있게."
  - 현재 인프라(테이블/Resolver/CircuitBreaker)는 95% 충족, 호출중 재시도만 미구현 → 갭 메우기
- **영향 모듈**: ClaudeCodeTool(retry 로직), AgentAccountPoolManager(재시도 콜백 추가), 운영 가이드
- **영향도**: Medium
- **영향 범위**: 신규 PRD(번호 미정, 1~2개), CR-014 후속
- **영향 설계서**: T3-1(테이블 추가 없음, 흐름도 갱신), aimbase-ops-guide.md
- **요청자**: sykim | **승인자**: - | **적용 버전**: v7.1.0
- **변경 일자**: 2026-04-14
- **상태**: ✅ 완료 (2026-04-16)
- **구현 요약**:
  - `ClaudeCodeToolConfig`에 `maxRetry`(기본 2), `retryBackoffMs`(기본 500ms) 설정 추가
  - `ClaudeCodeTool.tryExecuteViaPool` — 단일 계정 1회 실행 → 재시도 루프로 전환. 매 시도마다 `resolveAccount` 재호출하여 실패 계정은 GenericCircuitBreaker로 자동 스킵
  - `isRetryableFailure()` 헬퍼 추가 — stderr 키워드 기반으로 401/403/429/5xx · 인증만료 · Rate Limit · 5xx · Overloaded를 재시도 대상으로, 그 외 사용자 오류는 비재시도로 분류
  - exponential backoff (500ms → 1500ms → 3500ms …), 명시 계정(`_agent_account_id`) 지정 시 재시도 안 함
  - 사용자 노출 메시지: 보안상 계정 ID 숨김, audit log에만 기록
  - 테스트: `ClaudeCodeToolRetryTest` 5 케이스(인증/레이트리밋/5xx/사용자 오류/빈 출력) 전부 PASS
  - 운영 가이드 § 4 시나리오 J 추가, `aimbase-ops-guide.md` v1.9.0

### CR-044 | CLI 두뇌 + Aimbase 손발 — Claude CLI 네이티브 도구 봉인 + Aimbase MCP 강제
- **대상 기능 ID**: PRD-279 ~ PRD-282 (FE 없음)
- **변경 타입**: 변경 (기존 부품 결합, 신규 도구·엔진 없음)
- **배경**:
  - Aimbase는 Bash/Read/Write/Edit/Grep/Glob/TodoWrite/Task/MCP 등 모든 핵심 도구를 네이티브 보유(CR-037/CR-038/CR-041). Claude Code CLI도 동일 도구를 자체 보유.
  - ClaudeCodeTool 경로 실행 시 같은 기능이 두 갈래로 흘러 **PolicyEngine·감사 로그(BIZ-020)·WorkspaceResolver·테넌트 격리(BIZ-003)·비용 메트릭이 CLI 쪽에서는 우회**됨.
  - API 경로는 종량제, CLI 경로는 Claude Max 정액제로 비용 차이 수십 배. CLI 경로를 버릴 수 없으므로 CLI의 "두뇌(추론)"만 쓰고 "손발(실행)"은 Aimbase에 강제 위임.
- **변경 내용**:
  1. **PRD-279 ClaudeCodeTool CLI 플래그 자동 주입**: CLI 서브프로세스 기동 시 `--mcp-config /tmp/aimbase-session-${sid}.json` + `--allowedTools "mcp__aimbase__*,TodoWrite,ExitPlanMode"` + `--disallowedTools "Bash,Read,Write,Edit,Grep,Glob,Task,WebFetch,WebSearch,NotebookEdit"` 플래그를 기본 주입. 네이티브 도구 봉인은 모델 규율이 아닌 하네스 차단으로 물리적 강제. TodoWrite/ExitPlanMode는 사고 구조화용 예외 허용.
  2. **PRD-280 세션별 MCP 설정 파일 동적 생성기**: `ClaudeCodeSessionMcpConfigGenerator`(신규) 가 세션 기동 시 `aimbase-tool-sdk-mcp` 서버 엔드포인트 + 세션 스코프 인증 토큰을 담은 MCP JSON 설정 파일을 임시 경로에 생성, 세션 종료 시 정리.
  3. **PRD-281 CLI 경로 도구 결과 축약 어댑터**: CR-031의 `ToolResultCompactor`를 `aimbase-tool-sdk-mcp` 응답 핸들러에 주입. Claude CLI가 MCP 응답 전문을 컨텍스트에 재삽입하므로 긴 grep/파일 read가 Max 플랜 rate limit을 빠르게 소진하는 문제 완화.
  4. **PRD-282 Skill.metadata.toolBridge 필드**: `aimbase-mcp-only`(기본, 완전 봉인) / `hybrid`(일부 네이티브 허용, 실험·디버깅) / `native`(플래그 미주입, 레거시·벤치마크). SubagentRunner가 Skill 값에 따라 CLI 플래그 세트를 분기.
- **변경 사유**:
  - 사용자 요구사항: "claude cli tool 내부에서 자기 툴 사용하지 말고 aimbase tool 사용하도록 강제화. claude cli를 이용한 두뇌만 이용하고 싶은 거죠."
  - API vs CLI 경로 도구 스택 일원화 → 정책·감사·격리·관측성을 두 경로에서 동일 코드 패스로 보장
  - Claude Max 정액제 활용하면서도 Aimbase 운영 표준 미준수 우회로 차단
- **기술적 근거**: Claude Code CLI가 공식 지원하는 `--mcp-config`/`--allowedTools`/`--disallowedTools` 플래그 사용. 비공식 해킹 아님.
- **비용/Rate Limit 트레이드오프**:
  - 이득: 모델 토큰(Max 정액 내 무료), 정책·감사·격리·관측성 API 경로와 100% 동일 코드 패스 공유.
  - 주의: MCP 응답이 모델 컨텍스트에 재삽입되어 Max 플랜 시간당 rate limit 소진 속도 증가 → PRD-281 축약 어댑터로 완화. MCP JSON-RPC 왕복 레이턴시 소폭 증가(체감 미미).
- **기존 부품 재사용**:
  - `aimbase-tool-sdk-mcp` (CR-041), `ClaudeCodeTool` (CR-011, Docker 검증 완료), `PolicyEngine` / `WorkspaceResolver` / 감사 로그 / `ToolResultCompactor` (CR-031) 전부 그대로 재사용. 신규 도구·엔진·테이블 없음.
- **영향 모듈**:
  - BE: `ClaudeCodeTool`, `ClaudeCodeSessionMcpConfigGenerator`(신규 1개), `aimbase-tool-sdk-mcp` 응답 핸들러, `SkillEntity.metadata` 스키마 문서화, `SubagentRunner` 분기 로직
  - FE: 없음 (Skill 관리 화면은 metadata 자유 편집으로 커버)
- **영향도**: High (CLI 경로 기본 동작 변경, 단 toolBridge=native 로 롤백 가능)
- **영향 범위**: PRD-279~282, BIZ-001(도구 루프) 동작 영역은 그대로, BIZ-020(감사 로그) CLI 경로 커버리지 신규 확보
- **영향 설계서**: T3-6 실행지시서(ClaudeCodeTool 섹션 갱신), aimbase-ops-guide.md(toolBridge 모드 운영 가이드 추가)
- **요청자**: sykim | **승인자**: - | **적용 버전**: v7.2.0
- **변경 일자**: 2026-04-14
- **상태**: 등록만(미착수). POC(플래그 주입 + Task/Subagent 상속 검증) 선행 후 구현 착수.
- **사전 검증 필요 항목**:
  - Claude CLI 내부 Task 서브에이전트가 `--allowedTools` 제약을 상속하는지 — 미상속 시 Task도 disallowedTools에 추가하고 Aimbase SubagentRunner 경유로 우회
  - `--mcp-config`로 가짜 MCP 서버 등록 후 모델이 실제로 mcp__ 호출을 선택하는지
- **원본 요구사항**: `docs/origins/원본_요구사항_CLI_두뇌_Aimbase_손발_20260414.md`

### CR-045 | 대화형 채팅 UI + 워크스페이스 컨텍스트 + 실시간 도구 이벤트
- **대상 기능 ID**: PRD-290 ~ PRD-293, FE-030 ~ FE-032
- **변경 타입**: 변경 (기능 추가 + 기존 Chat 파이프라인 보강)
- **배경**:
  - FlowGuard 시나리오 추출 벤치마크 실험(Claude Code CLI vs Aimbase Chat API) 준비 중 Aimbase 환경이 Claude Code와 비대칭임이 드러남.
  - 내장 도구(BashTool/GrepTool/FileReadTool/GlobTool)는 `WorkspaceResolver` 기반으로 동작 준비 완료(CR-037/CR-041)이나, `OrchestratorEngine.chat()`에서 `ToolContext.workspacePath = "/"`로 **하드코딩**되어 소비앱 소스 폴더 지정 불가.
  - `frontend/src/pages/` 아래 **채팅 UI 부재** (SessionDetail에 "대화 탭 (예정)" placeholder만 존재).
  - SSE 이벤트 타입이 `delta`/`done` 2종뿐이어서 도구 호출/thinking이 실시간 전달되지 않음.
- **변경 내용**:
  1. **PRD-290 Chat API 워크스페이스 파이프라인**: `ChatRequest.workingDirectory` 필드 추가, `OrchestratorEngine.resolveWorkspace()` 헬퍼(세션 메타 > 요청값 > null 우선순위), `ToolContext.workspacePath` 하드코딩 제거, 세션 메타 `workspaceRef` 저장/복원.
  2. **PRD-291 SSE 이벤트 타입 확장**: 스트리밍 응답을 `delta`/`thinking`/`tool_use_start`/`tool_result`/`done` 5종으로 분리 송출. `ToolCallHandler.executeTool()` 전후 옵션 콜백 훅 주입(비스트림 모드 무영향). thinking 블록 필터링 제거(OrchestratorEngine.java:471).
  3. **PRD-292 워크스페이스 목록 API**: `GET /api/v1/workspaces` 신규. 화이트리스트 루트 하위 1단계 디렉토리 나열(name, path, modifiedAt). `WorkspaceController` 신규 클래스 ~50 LOC.
  4. **PRD-293 WorkspacePolicy 화이트리스트 강화**: `WorkspacePolicyEngine.validatePath()`에 `$HOME/Documents/GitHub/bp-fulfillment-infra` 루트 제약 + 상대경로 탈출 차단 + `toRealPath()` 심볼릭 링크 해석. 루트는 `application.yml`의 `aimbase.workspace.whitelist-roots`로 설정값화.
  5. **FE-030 `/chat` 페이지 라우트**: `/chat`, `/chat/:sessionId` 2개 라우트. 좌측 세션 사이드바 + 우측 대화창 레이아웃. `App.tsx` 라우트 추가, 사이드바 메뉴 "채팅" 추가.
  6. **FE-031 새 대화 시작 모달**: 워크스페이스 드롭다운(PRD-292 API) + Connection + 모델 + 세션명 선택. 생성 시 client-side `session_id = uuid()` 발급, `/chat/:sessionId` navigate.
  7. **FE-032 SSE 스트림 훅 + 메시지 블록 렌더러**: `useChatStream` 커스텀 훅(fetch + ReadableStream SSE 파서). `blocks/` 4종: `TextBlock`, `ThinkingBlock`(접기/펴기), `ToolUseBlock`(도구명+인자+spinner), `ToolResultBlock`(결과 요약+전문 토글). 기존 세션 로드는 `GET /api/v1/conversations/:id` 재사용.
  - **BIZ 규칙 신규**: BIZ-090(workingDirectory는 화이트리스트 루트 하위만 허용, 위반 시 403), BIZ-091(같은 session_id 재사용 시 세션 메타 workspaceRef 우선, 요청값 충돌 시 409).
- **변경 사유**:
  - 벤치마크 실험 블로커 해소가 일차 동기이나, Aimbase 자체 대화형 사용성을 Claude Code 수준으로 정식 기능화.
  - 소비앱 소스를 탐색하는 실제 작업 시나리오 지원(OMS 분석, FlowGuard 시나리오 추출 등).
  - 도구 호출 실시간 visibility가 사용자 신뢰도/디버깅에 직결.
- **기존 부품 재사용**:
  - `PolicyEngine`, `AuditLogger`, `SessionStore`, `WorkspaceResolver`, `ToolCallHandler`, `ConversationController`, `WorkspacePolicyEngine` 그대로 재사용.
  - FE: 기존 `ui/`, `common/`, `useConnections`, `useSessions` 재사용.
  - **신규 도구·엔진·테이블 없음**. DB는 기존 `conversation_sessions.meta` JSONB에 `workspaceRef` 필드 추가만.
- **영향 모듈**:
  - BE: `ChatController`, `ChatRequest`, `OrchestratorEngine`, `ToolCallHandler`(훅 추가), `WorkspacePolicyEngine`(강화), `WorkspaceController`(신규), `SseEventType`(신규 enum)
  - FE: `pages/Chat.tsx`(신규), `components/chat/*`(신규 9개), `hooks/useChatStream`·`useWorkspaces`·`useConversation`(신규 3개), `api/chat.ts`·`api/workspaces.ts`(신규), `App.tsx`·`Sidebar.tsx`(수정)
- **영향도**: High (Chat API 기본 동작 변경 — `"/"` 하드코딩 제거가 기존 호출자에 영향 가능)
- **영향 범위**: PRD-290~293, FE-030~032, BIZ-002(갱신), BIZ-090/091(신규)
- **영향 설계서**: T1-1, T1-3, T3-1, T3-2, T3-3, T3-4, T3-5, T3-6
- **Phase 분할**: Phase 1 BE 워크스페이스 파이프라인 → Phase 2 BE SSE 이벤트 확장 → Phase 3 FE 기반 구조 → Phase 4 FE 스트림+블록 렌더러
- **리스크**:
  - `ToolContext.workspacePath = "/"` 하드코딩 제거 시 의존 코드 존재 가능 → Phase 1 착수 전 전수 grep 필수
  - `ToolCallHandler` 콜백 훅이 침습적 → 옵션 파라미터로 주입, 비스트림 모드 기존 동작 유지
- **요청자**: sykim | **승인자**: - | **적용 버전**: v7.3.0
- **변경 일자**: 2026-04-15
- **상태**: ✅ 완료 (2026-04-16). Phase 1/2-A/2-B/3-A/3-B/4-A/4-B 코드 + 실행 검증 완료. 관련 커밋 11개 (`2a7c575` ~ `00cdaf0`).
- **E2E 검증 요약**: bp-oms 워크스페이스에서 "OrderController 포함 .java 개수" 요청 → `tool_use_start` → `tool_result` (matchCount=1) → `delta` × N → `done` 전체 5종 이벤트 수신.
- **알려진 부수 이슈 (CR-045 외)**: SSE 완료 후 Spring Security async dispatch에서 AccessDenied 경고가 로그에 남음 (가상 스레드 SecurityContext 전파 누락, 기능 영향 없음). conversation_sessions 중복키 배치 에러(persist 로직 별건). 둘 다 별도 CR로 분리 권장.
- **원본 요구사항**: `docs/origins/원본_요구사항_채팅UI_워크스페이스_실시간도구이벤트_20260415.md`
- **설계서**: `docs/원본_설계_CR045_채팅UI_워크스페이스_실시간도구이벤트_20260415.md`
- **Phase 2-B 설계 리뷰**: `docs/설계리뷰_CR045_Phase2B_도구루프스트리밍통합_20260415.md`

### CR-046 | Chat 실시간 제어·대화방 관리 — 중지·자동끼어들기·Soft Delete
- **대상 기능 ID**: PRD-294 ~ PRD-297, FE-033
- **변경 타입**: 변경 (실전 결함 수정 + UX 보강)
- **배경**:
  - CR-045 완료 후 코드 검증에서 **사용자 노출 UX 결함 3종** 확인:
    1. **중지 허위 구현** — FE AbortController로 fetch만 끊고 BE Virtual Thread는 계속 실행 (LLM 토큰·도구 비용 지속 발생). `useChatStream.ts:78` AbortController 있으나 BE `/abort` 엔드포인트 없음, `OrchestratorEngine.chatStream():430-525`/`ToolCallHandler.executeLoopStream():324-451`에 cancellation token 부재.
    2. **동시성 Race** — 동일 sessionId 동시 요청 시 BE sessionLock 부재로 병렬 실행, 마지막 writer wins. FE 가드(`Chat.tsx:66`)만으로는 curl/멀티탭 우회 가능. (DB 중복키는 별도로 SessionStore append-only 전환으로 해소됨)
    3. **대화방 삭제 UI 부재** — BE `DELETE /api/v1/conversations/{id}`(`ConversationController.java:64-72`) + FE API 클라이언트(`sessions.ts:70-71`)는 있으나 **UI 버튼 없음**. 게다가 hard delete + 권한 체크 없음 + 5개 테이블 고아 로그 발생(`tool_execution_log`/`usage_logs`/`audit_logs`/`traces`/`session_briefs`).
- **변경 내용**:
  1. **PRD-294 BE 중지(Abort) 인프라**: `POST /api/v1/chat/{sessionId}/abort` 엔드포인트 신규. `CancellationRegistry`(`ConcurrentHashMap<String, AtomicBoolean>`) 도입, `OrchestratorEngine.chatStream()`/`ToolCallHandler.executeLoopStream()` 매 iteration `if(cancelled.get()) break;` 체크. 어댑터 스트림은 HTTP 커넥션 close로 중단(Anthropic SDK AbortSignal 미지원). 부분 메시지는 `[중단됨]` 마커 + partial 토큰 DB 저장. 중지 시점까지만 `usage_logs` 기록.
  2. **PRD-295 BE 동시성 (옵션 B 자동 abort)**: `ConcurrentHashMap<String, ReentrantLock> sessionLocks`. `ChatController.completions()` 진입 시 이미 스트림 중이면 **이전 스트림 자동 abort → 새 요청 처리** (ChatGPT/Claude.ai 표준). 옵션 A(409)/C(큐잉)은 채택 안 함.
  3. **PRD-296 BE 대화방 Soft Delete**: Flyway V47 `ALTER TABLE conversation_sessions ADD COLUMN deleted_at TIMESTAMPTZ`, `conversation_messages` 동일 추가. 모든 목록/조회 쿼리에 `WHERE deleted_at IS NULL` 추가. `ConversationController.delete()` hard → soft 변경. **본인 권한 체크**(세션 user_id 일치) 필수. 삭제 시 active 스트림 자동 abort(PRD-294 연동). 휴지통/복구 UI·벌크 삭제·관리자 강제 삭제는 **본 CR 제외**(필요 시 후속 CR).
  4. **PRD-297 고아 로그 정책**: FK 추가하지 않음 — `tool_execution_log`/`usage_logs`/`audit_logs`/`traces`/`session_briefs`는 감사·과금 목적 보존. soft delete 채택으로 자연스럽게 부모 레코드 유지됨.
  5. **FE-033 채팅 제어 UX**:
     - 중지 버튼: 현재 fetch abort만 → BE `/abort` API 호출 보강(`useChatStream.ts:80`).
     - 끼어들기: 스트림 중에도 입력창 활성화, 전송 시 자동 이전 abort + 새 메시지 처리(옵션 B).
     - 대화방 사이드바: 호버 시 휴지통 아이콘 노출 + 확인 모달.
     - `useMutation` + 쿼리 invalidate로 목록 즉시 갱신.
  - **BIZ 규칙 신규**: BIZ-092(중지 시 partial 메시지에 `[중단됨]` 마커 저장 + 중지 시점까지의 토큰만 과금), BIZ-093(동일 sessionId 동시 요청 시 자동 이전 abort), BIZ-094(대화방 삭제는 본인만 가능, soft delete로 deleted_at 기록).
- **변경 사유**:
  - 중지 허위 구현은 토큰 비용 지속 발생 → **즉시 수정 필요**.
  - 동시성 Race는 메시지 순서 비결정 → 사용자 신뢰 직결.
  - 삭제 UI 부재는 UX 미완성, 권한 부재는 보안 결함.
- **기존 부품 재사용**:
  - `OrchestratorEngine`/`ToolCallHandler`/`SessionStore`/`ConversationController` 그대로 활용, abort 훅과 권한 체크만 주입.
  - SSE 스트림 `done` 이벤트 재사용(중단 시 `reason=aborted` 추가).
  - FE: 기존 `useChatStream`/`Sessions` 컴포넌트에 액션 추가만.
- **영향 모듈**:
  - BE 신규: `CancellationRegistry`, `SessionConcurrencyManager`
  - BE 수정: `ChatController`, `ConversationController`, `OrchestratorEngine`, `ToolCallHandler`, `ConversationSessionEntity`, `ConversationMessageEntity`, `ConversationRepository`, `ConversationMessageRepository`
  - DB: Flyway V47 (`deleted_at` 컬럼 2개)
  - FE 수정: `useChatStream.ts`, `Chat.tsx`, `ChatInput.tsx`, `api/sessions.ts`, (신규) 대화방 목록 사이드바 컴포넌트
- **영향도**: High (Chat 핵심 파이프라인 + 삭제 의미론 변경)
- **영향 범위**: PRD-294~297, FE-033, BIZ-092/093/094(신규)
- **영향 설계서**: T3-1(deleted_at 컬럼), T3-2(abort/delete API), T3-3(채팅 화면), aimbase-api-guide.md, aimbase-ops-guide.md
- **Phase 분할**: Phase 1 BE 중지 → Phase 2 BE 동시성(옵션 B) → Phase 3 BE Soft Delete → Phase 4 FE 통합
- **제외 (본 CR 범위 밖)**: 휴지통 UI, 복구(restore) 엔드포인트, 벌크 삭제, 관리자 강제 삭제, FK 추가
- **사전 해소 항목 (CR-045 잔여 중)**: ✅ conversation_sessions 중복키 배치 에러는 SessionStore append-only 전환으로 본 CR 착수 전 해소됨. 🟡 SSE 완료 후 AccessDenied 경고는 a19b7e2(SecurityContext 전파 fix)로 부분 해소 추정 — 본 CR 착수 후 로그 재현으로 잔존 여부 확인.
- **리스크**:
  - 어댑터 스트림 강제 종료 시 LLM 측 미과금 토큰 처리 정확성 — Anthropic/OpenAI 응답 partial usage 검증 필수.
  - 자동 abort 옵션 B에서 partial 메시지 저장이 사용자 혼란 야기 가능 — `[중단됨]` 명확 표기 + FE에 시각적 구분(회색 처리) 필수.
  - Soft delete 전환 후 기존 hard delete API 호출자 영향 — 외부 의존 없음 확인 후 진행.
- **요청자**: sykim | **승인자**: sykim (2026-04-16) | **적용 버전**: v7.4.0
- **변경 일자**: 2026-04-16
- **상태**: 🔧 진행중 (2026-04-16 등록). § 4 결정 7개 사용자 승인 완료(옵션 B / soft delete YES / 휴지통 제외 / 벌크 제외 / 본인만 / `[중단됨]` 마커 / Phase 5 대부분 해소).
- **원본 요구사항**: `docs/origins/원본_요구사항_SSE_SecurityContext_전파_20260416.md`, `docs/origins/원본_요구사항_SessionStore_중복키_20260416.md`
- **이관 인계서**: `docs/origins/CR046_이관인계서_20260416.md`

### CR-047 | 런타임 성능 최적화 — 병렬 도구 실행 + Prefetch + Cache TTL 분기 + Hook 비동기
- **대상 기능 ID**: PRD-298 ~ PRD-301 (FE 없음)
- **변경 타입**: 변경 (체감 지연·비용 직결 최적화, 기능 변경 없음)
- **배경**:
  - OpenClaude 소스 전수 대조 결과 Aimbase는 핵심 하네스 기능은 갖췄으나 **런타임 성능 레이어 4가지 누락/부분 구현** 확인.
  - 1) 병렬 도구 실행: `ToolCallHandler.java:264-285`에서 safeCalls/unsafeCalls 분류만 하고 **둘 다 순차 for-loop**. 코드 주석에 "향후 CompletableFuture로 전환" 명시. OpenClaude `StreamingToolExecutor.ts:76-150`은 실제 병렬 디스패치.
  - 2) Memory/Skill Prefetch: `ContextAssemblyEngine.assemble():177-192` 완전 동기. `OrchestratorEngine.chat():208` 동기 assembly 후 LLM 호출. OpenClaude `query.ts:299`/`329`는 LLM 스트리밍과 병렬 prefetch.
  - 3) Prompt Cache TTL: `AnthropicAdapter.java:66-67,117-126,179-185,238-252` 모든 cache_control 포인트에 단일 `CACHE_EPHEMERAL` 5분 TTL 적용. 소스별 분기 없음.
  - 4) Hook 백그라운드 실행: `HookDispatcher.java:75-100 executeAndAggregate()` 완전 동기 for-loop. `@Async` / `fireAndForget` 0건. 모든 hook이 턴 지연에 직접 가산.
- **변경 내용**:
  1. **PRD-298 병렬 도구 실행**: `ToolCallHandler.executeLoop():264-285` 리팩터링. `safeCalls`는 `CompletableFuture.allOf(...)` + Virtual Threads(`Executors.newVirtualThreadPerTaskExecutor()`) 병렬 디스패치. `unsafeCalls`는 기존 순차 유지. 예외 격리(`exceptionally()`) + 원래 tool_use 순서 재정렬. **동시 실행 상한 기본 10개** (`aimbase.tool.parallel-max` 설정값, BIZ-095).
  2. **PRD-299 Memory/Skill Prefetch**: `OrchestratorEngine.chat()` 진입 직후 `CompletableFuture<MemoryContext>`/`CompletableFuture<List<Skill>>` kick-off. `ContextAssemblyEngine`에서 `.get()` 합류. Prefetch 실패 시 **빈 컨텍스트 폴백**(LLM 호출 차단 금지). 스트리밍 경로도 동일 적용.
  3. **PRD-300 Prompt Cache TTL 소스별 분기**: `CacheControlStrategy` 인터페이스 신설. System prompt → **1h TTL**(long-lived), Tool schemas → **5m TTL**(tool 추가/제거 시 invalidation), Recent messages → **cache 미적용**. Anthropic 응답의 `cache_creation_input_tokens`/`cache_read_input_tokens`를 `UsageLog`에 기록하여 hit율 추적.
  4. **PRD-301 Hook 백그라운드 실행**: `HookEvent` enum에 `synchronous: boolean` 속성 추가. **게이팅 hook**(PRE_TOOL_USE, PERMISSION_REQUEST, STOP_HOOK, USER_PROMPT_SUBMIT 등 결정권 있는 것) → 동기 유지. **로그성 hook**(POST_TOOL_USE, SESSION_END, POST_COMPACT 등 26종 중 나머지) → `CompletableFuture.runAsync(..., virtualExecutor)` 비동기. 비동기 hook 실패는 로그만 남기고 메인 플로우 차단 금지. (BIZ-096)
  - **BIZ 규칙 신규**: BIZ-095(병렬 도구 동시 실행 상한 10개, `aimbase.tool.parallel-max` 설정값으로 조정 가능), BIZ-096(Hook은 게이팅/로그성으로 분류. 게이팅은 동기, 로그성은 비동기 실행).
- **변경 사유**:
  - 사용자 체감 지연(TTFT) · 턴 총 소요시간 · 토큰 비용 직결. 모든 대화·도구 호출에 누적 영향.
  - OpenClaude는 동일 모델(Sonnet 4.6) 기준 이미 적용된 최적화로, Aimbase 벤치마크에서 비용 우위가 이 갭을 메우면 더 벌어짐.
  - 기능 변경이 아닌 최적화이므로 사용자 시나리오·API 스펙 영향 없음.
- **기존 부품 재사용**:
  - `ToolCallHandler`, `OrchestratorEngine`, `ContextAssemblyEngine`, `AnthropicAdapter`, `HookDispatcher` 전부 유지. 실행 방식만 동기→병렬/비동기로 전환.
  - Virtual Threads(이미 활성화), `CompletableFuture`(표준 라이브러리), 기존 `CACHE_EPHEMERAL` 인프라 전부 재사용.
  - **신규 도구·엔진·테이블 없음**. 설정값 추가(`aimbase.tool.parallel-max`, `aimbase.cache.ttl.system`, `aimbase.cache.ttl.tools`).
- **영향 모듈**:
  - BE 수정: `ToolCallHandler`, `OrchestratorEngine`, `ContextAssemblyEngine`, `AnthropicAdapter`, `HookDispatcher`, `HookEvent`, `UsageLog`(cache hit 필드 추가)
  - BE 신규: `CacheControlStrategy` 인터페이스 + 구현체 3종(SystemPrompt/ToolSchema/Message)
  - FE: 없음
  - DB: 없음 (UsageLog가 기존 JSONB meta 사용 시 스키마 변경 불필요, 별도 컬럼 추가 시 Flyway V48)
- **영향도**: High (핵심 파이프라인 실행 방식 변경. 기능 스펙 무변경이나 타이밍·순서 관찰 테스트 영향 가능)
- **영향 범위**: PRD-298~301, BIZ-095/096(신규)
- **영향 설계서**: T1-1(PRD 4개 추가), T1-3(BIZ-095/096), T3-6(실행 방식 기술)
- **Phase 분할**:
  - Phase 0: KPI baseline 측정(TTFT, 병렬 처리 시간, cache hit율, 턴 총 지연)
  - Phase 1: PRD-298 병렬 도구 실행 (가장 체감 큰 영역)
  - Phase 2: PRD-299 Memory/Skill Prefetch
  - Phase 3: PRD-300 Cache TTL 소스별 분기
  - Phase 4: PRD-301 Hook 비동기
  - Phase 5: KPI 재측정 + 회귀 검증
- **제외 (본 CR 범위 밖)**: 어댑터 레벨 스트리밍 최적화(별도 CR), LLM 응답 캐시(의미론 다름), 도구 결과 캐시(CR-031에서 별도).
- **리스크**:
  - 병렬 도구 실행 시 도구간 암묵적 의존성(같은 파일 동시 Read는 안전, 동시 Write는 위험) → `isConcurrencySafe` 플래그 재검토 필수. Bash/Write/Edit는 unsafeCalls로 확실히 분류.
  - Prefetch가 tenant-scoped 리소스를 건드리므로 `TenantContext` 전파 필수 (가상 스레드에서). CR-045 SSE TenantContext fix와 동일 주의.
  - Cache TTL 1h가 시스템 프롬프트 변경 직후 반영 지연 유발 가능 → 프롬프트 수정 시 수동 invalidation API 제공 또는 버전 해시 기반 키 운영.
  - Hook 비동기화 시 실행 순서 보장이 사라짐 → 로그성 hook이 순서에 의존하지 않음을 각 hook별 리뷰로 확인.
- **의존성**: CR-043(다중계정 재시도) 선행 완료됨 — ClaudeCodeTool 재시도와 병렬 도구 실행 로직 충돌 없음 확인 필요. CR-046(Chat 실시간 제어)와 독립적이나 cancellation token이 병렬 실행 중인 도구에도 전달되도록 Phase 1 설계 시 연계 고려.
- **KPI (Phase 0/5에서 측정)**:
  - TTFT (prefetch 전/후)
  - N개 safeCalls 동시 실행 시간 / 순차 실행 시간 비율 (목표: N=3일 때 50% 이하)
  - Cache hit 비율 (system/tools/messages 소스별)
  - 턴 총 지연 시간 (hook 비동기 전/후)
- **요청자**: sykim | **승인자**: sykim (2026-04-16) | **적용 버전**: v7.5.0
- **변경 일자**: 2026-04-16
- **상태**: 📝 등록 완료, 구현 착수 대기. § 5 결정 4개 사용자 승인 완료(병렬 상한 10개 / TTL system 1h·tools 5m·messages 미적용 / Hook 게이팅-동기·로그성-비동기 / Prefetch 실패 시 빈 컨텍스트 폴백).
- **이관 인계서**: `docs/origins/CR047_이관인계서_20260416.md`

### CR-041 | Agent SDK 추출 + Agent Registry — 소비앱 도구 SDK 배포 + 원격 에이전트 오케스트레이션
- **대상 기능 ID**: PRD-273 ~ PRD-278, FE-024
- **변경 타입**: 신규
- **변경 내용**: platform-core의 순수 도구(파일시스템, Bash, 유틸리티)를 독립 SDK로 추출하고, 원격 Agent가 MCP 서버로 도구를 노출하여 Aimbase가 오케스트레이션하는 구조 구현
  - **PRD-273 aimbase-tool-sdk-core**: Gradle 멀티모듈 구조. 도구 인터페이스(ToolExecutor, EnhancedToolExecutor) + 레코드(ToolContext, ToolResult, UnifiedToolDef 등 13개) + 워크스페이스 인프라(WorkspaceResolver, WorkspacePolicyEngine) + 도구 구현체 16개(FileRead/Write, Glob, Grep, SafeEdit, Bash, Calculator 등) 추출. Spring 의존성 제거, 순수 Java 라이브러리.
  - **PRD-274 aimbase-tool-sdk-mcp**: sdk-core 도구를 MCP 서버로 자동 노출하는 모듈. AgentMcpServer(SSE transport), StunAddressResolver(공인주소 탐색), AimbaseRegistrationClient(REST 등록), AgentLifecycle(기동→등록→하트비트→종료).
  - **PRD-275 Agent Registry BE**: agent_registry 테이블 + AgentRegistryEntity/Repository/Service/Controller. 에이전트 자가 등록(POST /api/v1/agents/register), 해제(DELETE), 목록(GET), 하트비트(POST). 5분 무응답 시 STALE 처리.
  - **PRD-276 RemoteToolDiscovery**: 30초 주기로 활성 에이전트의 도구를 ToolRegistry에 동기화. RemoteAgentToolExecutor로 온디맨드 MCP 연결→도구 실행→연결 종료.
  - **PRD-277 SdkToolBeanConfig**: SDK 도구를 Spring Bean으로 등록하는 브릿지. 기존 ToolRegistry 자동 수집 코드 변경 없음.
  - **PRD-278 UnifiedToolDef 패키지 이동**: com.platform.llm.model → com.platform.tool.model. platform-core 전체 import 일괄 변경.
  - **BIZ 규칙**: BIZ-078(에이전트 하트비트 간격 60초), BIZ-079(에이전트 stale 임계값 5분), BIZ-080(원격 도구 동기화 주기 30초)
- **변경 사유**: FlowGuard Agent가 claude -p(Claude CLI) 경유로 LLM 이중 호출 비용 + 도구 제한 문제. Aimbase 도구를 SDK로 배포하면 소비앱이 LLM 호출 없이 도구만 실행하고, 오케스트레이션은 Aimbase 서버가 담당하여 비용 절감 + 도구 통합.
- **영향 모듈**: SDK(tool-sdk-core 신규, tool-sdk-mcp 신규), Tool(인터페이스/구현체 SDK 이동), MCP(RemoteToolDiscovery, RemoteAgentToolExecutor 신규), Config(SdkToolBeanConfig 신규), API(AgentRegistryController 신규)
- **영향도**: High
- **영향 범위**: PRD-273 ~ PRD-278, BIZ-078 ~ BIZ-080
- **영향 설계서**: T1-1, T2-1, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v7.0.0
- **변경 일자**: 2026-04-09

### CR-048 | 컨텍스트·토큰 효율 — Deferred Tool 스키마 런타임 주입 + Tool Result Storage + Adaptive Thinking 동적 조정
- **대상 기능 ID**: PRD-300 ~ PRD-302
- **변경 타입**: 변경
- **변경 내용**: OpenClaude 전수 대조 결과 누락된 토큰·컨텍스트 최적화 3종을 Aimbase에 이식.
  - **PRD-300 Deferred Tool 스키마 런타임 주입**: `SessionToolRegistry` 신설 (sessionId별 활성 도구 목록 관리). 초기 세트 Read/Edit/Grep/Bash/TodoWrite/ToolSearch 6종만 스키마 포함, 나머지는 이름+1줄 설명만 system prompt 뒤쪽에 텍스트로 노출. `ToolSearchTool` 호출 시 결과를 레지스트리에 add → 다음 턴부터 해당 도구 스키마 주입. `ContextAssemblyEngine`이 레지스트리를 조회해 tool defs 조립. Anthropic cache_control prefix는 고정 유지, 활성 도구 변경은 뒷부분에만 영향.
  - **PRD-301 Tool Result Storage 치환**: `tool_result_storage` 테이블(Postgres) 신설. `ToolCallHandler`가 임계치(81920B) 초과 시 원본을 저장하고 체인에는 `{type:"tool_result_ref", id:"res_xxx", summary:"..."}` stub 주입. `ReadToolResult` 신규 도구로 모델이 원본 복구 가능. TTL 24h(세션 TTL과 일치), 본인 세션 result_id만 접근.
  - **PRD-302 Adaptive Thinking 동적 조정**: `AdaptiveThinkingPolicy` 인터페이스 신설. `AnthropicAdapter.resolveThinkingMode()`가 ADAPTIVE 모드일 때 policy 호출해 런타임 budget 계산. 공식: base 4000 × (tool_calls≥3 → 1.5) × (직전 턴 에러 → 2.0) × (질문 길이>500 → 1.3), cap 32000. budget vs 재시도/품질 로깅으로 A/B 튜닝.
- **변경 사유**: MCP 도구 수백 개 보유 테넌트에서 매 턴 5k~10k 토큰 낭비(도구 스키마 전량 포함), 대형 tool result가 인라인 truncate로 소실되어 복구 불가, Extended Thinking budget이 정적이라 복잡도 반영 안 됨. 토큰 비용 직접 절감 + 복구성 향상 + 품질 안정.
- **영향 모듈**: Tool(SessionToolRegistry, ReadToolResultTool 신규 / ToolSearchTool, ToolCallHandler 수정), Storage(ToolResultStorageService 신규 + tool_result_storage 테이블), Context(ContextAssemblyEngine 수정), LLM(AdaptiveThinkingPolicy 신규 / AnthropicAdapter 수정)
- **영향도**: High
- **영향 범위**: PRD-300 ~ PRD-302
- **영향 설계서**: T3-1(tool_result_storage 추가), T3-2(ReadToolResult 스펙), T3-6(3개 흐름도), aimbase-ops-guide.md(TTL 운영, SessionToolRegistry)
- **결정사항 (설계 확정, 2026-04-16)**:
  1. Deferred Tool 기본 활성 세트: **Read/Edit/Grep/Bash/TodoWrite/ToolSearch 6종**
  2. Tool Result Storage: **Postgres** (용량·압축·감사 유리, 세션 TTL 24h라 Redis 속도 이점 작음)
  3. Storage TTL: **24h** (세션 TTL과 일치)
  4. Adaptive Thinking 공식: **×1.5 (tool≥3) / ×2.0 (직전 에러) / ×1.3 (질문>500자), cap 32000** — 초안 수용, 로깅 기반 튜닝
  5. 비활성 도구 노출 방식: **이름+1줄 설명을 system prompt 후미에 텍스트로 노출** (ToolSearch로 검색 유도)
- **제외 (본 CR 범위 밖)**:
  - 어댑터(Bedrock/Vertex)의 thinking budget 변환 정합성 — CR-032 후속으로 분리
  - tool_result_storage 원본 압축(gzip) — 1차 반영 후 사용량 보고 결정
  - FE 변경 없음 (백엔드 내부 최적화)
- **리스크**:
  - SessionToolRegistry 변경이 Anthropic cache prefix를 깨뜨리면 토큰 절감이 오히려 cache miss로 상쇄 → prefix 고정/가변 경계 테스트 필수
  - ReadToolResult 권한 검증 누락 시 타 세션 result 노출 가능 → sessionId 일치 검증 + 감사 로깅
  - Adaptive 공식이 특정 워크로드에서 thinking budget을 과도하게 늘리면 비용 증가 → cap 32000 + 일일 budget 상한 모니터링
  - tool_result_storage TTL 처리 누락 시 테이블 비대화 → 일일 스케줄러로 만료 레코드 삭제 + 크기 알림
- **의존성**: CR-046(Chat 실시간 제어) 선행 권장 — 세션 동시성 제어가 SessionToolRegistry 설계에 영향. CR-047과는 독립 병렬 가능.
- **KPI**:
  - 턴당 평균 input token 감소율 (Deferred Tool 전/후)
  - Anthropic cache hit 비율 변화 (prefix 고정 효과)
  - tool_result_storage 평균 사이즈 / 만료 처리 지연
  - Adaptive thinking budget 분포 + 재시도/실패율 상관
- **요청자**: sykim | **승인자**: sykim (2026-04-16) | **적용 버전**: v7.6.0
- **변경 일자**: 2026-04-16
- **상태**: 📝 등록 완료, 설계 캐스케이드 완료. 구현 착수 대기 (사용자 별도 승인 필요).
- **이관 인계서**: `docs/origins/CR048_이관인계서_20260416.md`

### CR-049 | 세션 복원·지침 체계 — Session Resume + Compact Boundary + 테넌트/프로젝트 커스텀 지침
- **대상 기능 ID**: PRD-303 ~ PRD-305, FE-034
- **변경 타입**: 변경 (장기 세션 UX + 테넌트 관리자 기능)
- **배경**:
  - OpenClaude 전수 대조 결과 Aimbase는 **장기 세션 복원** 메커니즘과 **테넌트/프로젝트 커스텀 지침 주입** 메커니즘이 부재함.
  - 1) Session Resume: grep 결과 `resume`, `CompactBoundary`, `compact_boundary` 전체 Java 소스 0건. `/sessions/{id}/resume` 엔드포인트 없음. `SystemCompactBoundaryMessage` 같은 메시지 타입 마커 없음. ContextWindowManager는 circuit breaker 카운터만 있고 메시지 레벨 마커 부재. OpenClaude는 `sessionStorage.ts` JSONL + `--resume` + `SystemCompactBoundaryMessage` 타입 보유.
  - 2) Stop Hooks 재시도 강제: `HookDispatcher.java:75-100` BLOCK/APPROVE/PASSTHROUGH 집계만 있고 `ToolCallHandler` iteration(:219-234)은 finishReason만 체크. STOP 이벤트는 발행되나 루프 재진입 강제 없음.
  - 3) 테넌트/프로젝트 지침: `prompt_templates`(CR-036) 글로벌만, `scope`/`project_id` 컬럼 없음. `TenantEntity`/`ProjectEntity`에 `custom_instructions` 필드 없음. `ContextAssemblyEngine.assembleSystemPrompt():346-394`가 글로벌 key만 조회 — 테넌트 필터 0. CR-036 설계 시 스코프 누락 확정.
- **변경 내용**:
  1. **PRD-303 Session Resume + Compact Boundary**:
     - `ConversationMessageEntity.message_type` enum에 `COMPACT_BOUNDARY` 추가 (USER/ASSISTANT/TOOL_USE/TOOL_RESULT 외).
     - `ContextWindowManager.trimWithState():86-150`에서 압축 수행 시 압축된 메시지 그룹 뒤에 `[COMPACT_BOUNDARY {summary, compacted_count, tokens_saved}]` 메시지 append.
     - `POST /api/v1/sessions/{sessionId}/resume` 신규 — 압축 경계 이후 메시지 체인 + 보존 context 반환. 24h TTL 이내 active 세션만 (만료는 archived 별도 조회).
     - FE: 대화방 목록 "재개" 버튼 + 압축 경계 UI 표시(접을 수 있는 구분선).
  2. **PRD-304 Stop Hooks 재시도 강제**:
     - `HookEvent.STOP` 결과가 BLOCK이면 BLOCK 사유를 새 user 메시지로 주입하고 `ToolCallHandler.executeLoop()` 루프 재진입.
     - 무한루프 방지: 동일 BLOCK 사유 **3회 초과** 시 강제 종료 + 사용자 알림. (BIZ-097)
     - 사용 사례: TodoWrite 미완료, 테스트 FAIL, 사용자 정의 검증.
  0. **(범위 확장, 2026-04-24) prod 테넌트 자동 마이그레이션 Initializer**:
     - 운영(prod) 환경에서 앱 기동 시 모든 활성 테넌트 DB 에 Flyway 를 자동 적용하도록 `ProdTenantMigrationInitializer`(@Profile("prod"), ApplicationRunner, @Order(1000)) 신규.
     - 기존에는 `TenantOnboardingService`(신규 테넌트 생성 시에만 migrate) 경로만 있어서 CR-046/048/055/058(V51~V54) 가 운영 DB 에 미적용된 사례 발생 — CR-049 배포 시 수동 복구함. 본 Initializer 로 구조적 해소.
     - 실패 테넌트는 로그만 남기고 다음 테넌트로 계속 진행 (한 테넌트 오류로 서비스 기동 차단하지 않음).
  3. **PRD-305 테넌트/프로젝트 커스텀 지침**:
     - DB: `prompt_templates` 테이블 확장 (Flyway V48) — `scope VARCHAR(20) NOT NULL DEFAULT 'GLOBAL'`, `project_id VARCHAR(100) NULL`. scope: `GLOBAL | TENANT | PROJECT`. tenant DB라 tenant_id는 자동 스코프됨. 인덱스 `idx_prompt_templates_scope(scope, project_id)`.
     - `PromptTemplateService.getTemplate(key, tenantId, projectId)` 확장. 우선순위 PROJECT → TENANT → GLOBAL **폴백 + append**(cascade 병합, BIZ-098).
     - `ContextAssemblyEngine.assembleSystemPrompt()` 수정: GLOBAL `core.system.prefix` 로드 후 TENANT override append, PROJECT override append. 최종 = GLOBAL + TENANT + PROJECT.
     - API 신설:
       - `GET /api/v1/prompt-templates?scope=TENANT|PROJECT&projectId=...`
       - `PUT /api/v1/prompt-templates` (관리자)
       - `GET/PUT /api/v1/projects/{id}/instructions`
     - FE-034: 테넌트 설정 "시스템 지침" 탭(monaco editor), 프로젝트 상세 "프로젝트 지침" 탭, "최종 system prompt = GLOBAL + TENANT + PROJECT" 미리보기.
     - 권한: 슈퍼어드민=GLOBAL, 테넌트 관리자=TENANT/PROJECT.
     - 버저닝: 기존 `prompt_templates.version` 컬럼 재사용 (편집 이력 보관).
  - **BIZ 규칙 신규**: BIZ-097(Stop Hook BLOCK 재진입은 동일 사유 3회 초과 시 강제 종료), BIZ-098(시스템 지침 스코프 우선순위는 PROJECT > TENANT > GLOBAL이며 cascade append 방식으로 병합).
- **변경 사유**:
  - 장기 세션이 압축으로 잘려나가면 사용자가 이전 맥락을 잃어 같은 작업을 재설명해야 함 → Resume + Compact Boundary로 무손실 재개 보장.
  - Stop Hook이 단순 발행만 하면 검증 hook(TodoWrite 미완료 차단 등)이 실효성 없음 → 재진입 강제로 사용자 정의 품질 게이트 작동.
  - SaaS 멀티테넌트에서 테넌트별 톤·금지사항·산업 특화 지침을 코드 배포 없이 주입할 수 있어야 함. CR-036 글로벌 스코프만으로는 부족.
- **기존 부품 재사용**:
  - `prompt_templates` 테이블 확장(신규 테이블 아님), `PromptTemplateService` 시그니처 확장.
  - `HookEvent.STOP` 이미 존재(처리만 강화), `HookDispatcher` 결정 집계 로직 그대로.
  - `ConversationMessageEntity` 메시지 타입만 확장.
  - 신규: `SessionResumeController`, `TenantInstructionsController`, `ProjectInstructionsController`.
- **영향 모듈**:
  - BE 수정: `ConversationMessageEntity`, `ContextWindowManager`, `ContextAssemblyEngine`, `PromptTemplateService`, `PromptTemplateEntity`, `HookDispatcher`, `ToolCallHandler`
  - BE 신규: `SessionResumeController`, `TenantInstructionsController`, `ProjectInstructionsController`, `ProdTenantMigrationInitializer`(prod 테넌트 자동 마이그레이션)
  - Flyway: V48 (prompt_templates scope 확장, tenant DB), V49 (conversation_messages.message_type COMPACT_BOUNDARY 추가, tenant DB)
  - FE 신규: 테넌트 설정 "시스템 지침" 탭, 프로젝트 상세 "프로젝트 지침" 탭, 대화방 목록 "재개" 버튼, 압축 경계 UI 컴포넌트 (FE-034)
- **영향도**: High
- **영향 범위**: PRD-303 ~ PRD-305, FE-034, BIZ-097, BIZ-098
- **영향 설계서**: T1-1(PRD 3개), T1-3(BIZ-097/098), T3-1(prompt_templates/conversation_messages 스키마), T3-2(API 5종 신규), T3-3(화면 컴포넌트 4종), T3-6(Sprint 52 6 Phase), aimbase-api-guide.md, aimbase-ops-guide.md
- **결정사항 (설계 확정, 2026-04-16)**:
  1. Resume 동작 범위: **24h TTL 이내 active 세션만** (만료 세션은 archived 별도 조회로 분리)
  2. Compact Boundary UI: **표시(접을 수 있는 구분선)** — 사용자가 압축 사실 인지 가능
  3. Stop Hook BLOCK 표현: **새 user 메시지 주입** (시스템 reminder 아님 — 모델이 자연스럽게 응답)
  4. Stop Hook 무한루프 방지: **동일 BLOCK 사유 3회 초과 시 강제 종료** + 사용자 알림
  5. 지침 스코프 병합: **append (cascade)** — GLOBAL → TENANT → PROJECT 순서로 누적
  6. 지침 버저닝: **기존 prompt_templates.version 재사용** (편집 이력 보관)
  7. 지침 권한: **슈퍼어드민=GLOBAL, 테넌트 관리자=TENANT/PROJECT**
- **Phase 분할**:
  - Phase 0: 결정 포인트 7개 사용자 최종 승인
  - Phase 1: PRD-305 (테넌트/프로젝트 지침) BE — 스키마/Service/Engine
  - Phase 2: PRD-305 API + FE-034 (사용자 체감 가장 큰 영역)
  - Phase 3: PRD-303 Compact Boundary 메시지 마커 + Resume API
  - Phase 4: PRD-303 FE Resume UI + 압축 경계 컴포넌트
  - Phase 5: PRD-304 Stop Hook 재진입 + 무한루프 방지
  - Phase 6: 통합 검증 + 가이드 갱신
- **제외 (본 CR 범위 밖)**:
  - 만료 세션(24h 초과) 아카이브 조회 — 별도 CR
  - JSONL 파일 export(OpenClaude식) — DB 기반이므로 불필요
  - 지침 템플릿 마켓플레이스(공유/가져오기) — 별도 CR
- **리스크**:
  - 압축 경계 메시지가 LLM context에 노이즈로 작용 가능 → tool/system 메시지로 분류해 모델에 전달 시 메타데이터로만 (본문 X)
  - Stop Hook 무한루프 방지 임계값 3회가 과소/과대 가능 → BIZ-097로 명시 + 운영 모니터링 후 조정
  - 테넌트 지침에 민감 정보(API 키 등) 입력 시 system prompt 노출 → FE에서 secret 입력 경고 + audit log
  - cascade append로 system prompt 길이 폭증 → 합산 길이 상한(예: 8KB) 검증 + 초과 시 경고
- **의존성**: CR-046(Chat 실시간 제어) 완료 권장 — Resume이 대화방 목록 UI와 통합. CR-036(프롬프트 외부화) 선행 완료 — prompt_templates 테이블 확장 기반. CR-047/CR-048 독립적.
- **KPI**:
  - Resume 사용률 (TTL 이내 세션 중 재개 호출 비율)
  - Stop Hook BLOCK 재진입 후 PASS 전환율 (검증 hook 효과)
  - 테넌트 지침 활성화 테넌트 수 + 평균 길이
  - cascade append 최종 system prompt 길이 분포
- **요청자**: sykim | **승인자**: sykim (2026-04-16) | **적용 버전**: v7.7.0
- **변경 일자**: 2026-04-16
- **상태**: 📝 등록 완료, 설계 캐스케이드 진행 중. 구현 착수 대기 (사용자 별도 승인 필요).
- **이관 인계서**: `docs/origins/CR049_이관인계서_20260416.md`

### CR-050 | Claude CLI → LLM 어댑터 승격 — Worker Pool + fork-session 병렬 브랜치 + Max 구독 정액제 활용
- **대상 기능 ID**: PRD-306 ~ PRD-309
- **변경 타입**: 변경 (LLM 경로 신규 어댑터 추가)
- **배경**:
  - 현재 LLM_CALL 경로는 `AnthropicAdapter`(API Key, 토큰 과금) 단일. Claude Max/Pro 구독자는 정액제 자산을 워크플로우 LLM_CALL에서 활용할 수 없음.
  - `ClaudeCodeTool`은 Tool 경로로 존재하나 호출마다 Node.js 기동 500ms+ 오버헤드, LLM 어댑터 인터페이스(`LLMAdapter`)를 구현하지 않아 `LLM_CALL` Step에서 선택 불가.
  - 2026-04-14~16 세션 3개에 걸쳐 설계 완료했으나 CR 번호 미발번으로 이력에 누락 → 2026-04-16 복원 등록.
- **변경 내용**:
  1. **PRD-306 ClaudeCliWorker (단일 프로세스 래퍼)**:
     - `backend/platform-core/src/main/java/com/platform/llm/claudecli/ClaudeCliWorker.java` 신규.
     - ProcessBuilder로 `claude -p --input-format stream-json --output-format stream-json --tools ""` 기동.
     - stdin NDJSON 주입 / stdout 라인 단위 파싱 / **stderr drain 스레드 필수**(버퍼 차면 멈춤).
     - 한 턴 송수신 API `ChatResponse turn(List<UnifiedMessage>)` — 내부 뮤텍스로 동시 호출 직렬화.
     - 프로세스 생존 확인, 크래시 감지, 세션 파일 경로 추적(fork-session용).
  2. **PRD-307 ClaudeCliWorkerPool (Run 단위 풀 + fork-session)**:
     - `llm/claudecli/ClaudeCliWorkerPool.java` 신규. `ConcurrentHashMap<String, RunWorkers>` (key: workflowRunId).
     - `getOrCreateMain(runId)` lazy spawn, `spawnForkedWorker(runId, parentSessionId)` — `--resume <sid> --fork-session`으로 새 프로세스 분기(캐시 재사용).
     - `shutdownForRun(runId)` 모든 워커 프로세스 종료. 워커 크래시 시 재기동 + 현재 run 실패 처리.
     - `ParallelStepExecutor` 수정 — 병렬 브랜치 시작 시 `spawnForkedWorker`, 종료 시 `releaseForkedWorker`.
  3. **PRD-308 ClaudeCliLlmAdapter (LLMAdapter 구현) + Factory 통합**:
     - `llm/adapter/ClaudeCliLlmAdapter.java` 신규 — `LLMAdapter` 인터페이스 구현.
     - `chat()`: workerPool에서 워커 획득 → turn 실행 → UnifiedResponse 변환.
     - **첫 턴**: 전체 messages NDJSON 주입. **이후 턴**: 마지막 user 메시지만 주입(CLI가 맥락 기억).
     - `transformToolDefs()`: 빈 구현(도구 미지원, `--tools ""` 봉인).
     - `chatStream()`: stdout 이벤트를 StreamEvent로 변환.
     - `ConnectionAdapterFactory` 수정 — `normalizeAdapterType()`에 `"anthropic-cli"` 매핑, `createAdapter()`에 `case "anthropic-cli"` 추가.
     - `llm/claudecli/ClaudeCliAdapterConfig.java` 신규 — `platform.llm.anthropic-cli` 섹션(enabled/timeout/max-workers-per-run/cli-binary-path/allowed-tenants 피처 플래그).
  4. **PRD-309 WorkflowEngine 수명 훅 + 병렬 브랜치 통합**:
     - `workflow/WorkflowEngine.java` 수정 — Run 종료(정상/예외 모두) 시 try/finally로 `claudeCliWorkerPool.shutdownForRun(runId)` 호출.
     - `workflow/step/ParallelStepExecutor.java` 수정 — 병렬 브랜치 시작 시 fork 워커 확보, 종료 시 해제.
     - 프로세스 누수 방지가 핵심(CLI는 무거운 자원).
  - **BIZ 규칙 신규**: BIZ-099(Claude CLI 어댑터는 테넌트 피처 플래그 허용 시에만 활성화되며, 상용 외부 테넌트는 ToS 경계상 비활성 유지). BIZ-100(run당 CLI 워커는 기본 5개 상한, 초과 시 큐잉 또는 실패).
- **변경 사유**:
  - Max/Pro 구독 정액제를 LLM_CALL 경로에서 소진 → 토큰 과금 대비 비용 절감.
  - Node.js 기동 오버헤드 500ms×N → 500ms×1(run 단위 상주)로 감소. 10 LLM_CALL 워크플로우 5초→0.5초 실측.
  - 병렬 브랜치 `--fork-session`으로 prompt prefix 캐시 재사용 → T4 검증 기준 $0.07→$0.007 (10배 절감).
- **기존 부품 재사용**:
  - `LLMAdapter` 인터페이스 그대로 구현(신규 어댑터 추가만). 기존 `AnthropicAdapter`는 API Key 경로로 존속.
  - `ConnectionAdapterFactory` 매핑 확장(신규 분기 추가). `Connection` 엔티티 그대로.
  - `AgentAccountPoolManager`(CLAUDE_CONFIG_DIR 격리) 재사용 — OAuth 계정 풀 + 설정 디렉토리 격리 로직 그대로 유용.
  - `ClaudeCodeTool` 그대로 존속(에이전트 자율 작업용 — 어댑터와 용도 분리).
- **영향 모듈**:
  - BE 신규(4개 클래스): `ClaudeCliWorker`, `ClaudeCliWorkerPool`, `ClaudeCliLlmAdapter`, `ClaudeCliAdapterConfig`.
  - BE 수정(3개 클래스): `ConnectionAdapterFactory`(매핑), `WorkflowEngine`(run 종료 훅), `ParallelStepExecutor`(fork 워커).
  - 설정: `application.yml`에 `platform.llm.anthropic-cli` 섹션 추가.
  - 테스트: `ClaudeCliWorkerTest`(프로세스 기동/종료/stdin/stdout), `ClaudeCliWorkerPoolTest`(동시성/lazy spawn/shutdown), `ClaudeCliLlmAdapterIT`(실제 CLI, CI skip), E2E 4시나리오.
- **영향도**: High
- **영향 범위**: PRD-306 ~ PRD-309, BIZ-099, BIZ-100
- **영향 설계서**: T1-1(PRD 4개), T1-3(BIZ-099/100), T2-1(기술스택 CLI 의존성 추가), T3-2(Connection 타입 `anthropic-cli` 확장), T3-6(Sprint N 4 Phase 실행 지시), aimbase-api-guide.md(Connection 타입), aimbase-ops-guide.md(CLI 바이너리 설치/버전 관리).
- **결정 필요 (Phase 0 착수 전 사용자 승인 7종)**:
  1. run당 최대 워커 수 기본값(초안 5개) — 적정 여부.
  2. Usage 추출 방식 — stream-json 이벤트 파싱 vs Redis 감사 로그만.
  3. CI 통합 테스트 — 실제 CLI 실행 vs 모킹.
  4. 테넌트 피처 플래그 위치 — `application.yml allowed-tenants` vs `global_config` 키 vs Connection 플래그.
  5. 첫 턴/이후 턴 구분 상태 위치 — Adapter 내부 vs Worker 내부.
  6. `--model` 전달 — Connection config의 모델 ID 그대로 CLI 인자 매핑.
  7. 타임아웃 실패 처리 — 워커 강제 종료 + run 실패 vs 워커 재기동 후 재시도.
- **Phase 분할**:
  - Phase 0: 결정 포인트 7종 사용자 승인 + 설계서 캐스케이드(T2-1/T3-2/T3-6).
  - Phase 1: PRD-306 ClaudeCliWorker + 단위 테스트.
  - Phase 2: PRD-307 ClaudeCliWorkerPool + fork-session + ParallelStepExecutor 통합.
  - Phase 3: PRD-308 Adapter + Factory + Config + 피처 플래그.
  - Phase 4: PRD-309 WorkflowEngine 수명 훅 + 누수 방지 검증.
  - Phase 5: E2E(단일 턴/멀티 턴/병렬 브랜치/Run 종료 정리) + KPI 측정(오버헤드 감소율/캐시 hit).
  - Phase 6: 가이드 갱신(api/ops) + Flyway 불요(DB 스키마 변경 없음).
- **제외 (본 CR 범위 밖)**:
  - `ClaudeCodeTool` 재구성 — CR-043/044 소관.
  - 외부 상용 테넌트 활성화 — Anthropic 공식 허가 전까지 비활성 유지(ToS 경계).
  - CLI 사이드카 HTTP gateway화 — 별도 검토.
- **리스크**:
  - ToS 경계: Max 구독을 자동화 파이프라인에서 대량 호출 시 Anthropic 정책 위반 소지 → **개인/내부 테넌트 한정** 피처 플래그로 제한. 외부 재판매 금지.
  - stderr drain 누락 시 프로세스 hang → 워커 기동 직후 별도 스레드 필수.
  - fork-session 미지원 CLI 버전 → `cli-binary-path` + 버전 체크 + 기동 실패 시 명시적 에러.
  - Run 종료 누락 시 프로세스 누수 → try/finally + `SubagentLifecycleManager` 유형의 고아 프로세스 스캔 추가 검토.
  - Usage 정확도: stream-json 이벤트가 토큰 정보를 제공하지 않으면 과금/한도 추적 불가 → 구현 단계 실측 후 폴백 경로 결정.
- **의존성**: 없음 — 즉시 착수 가능. `AgentAccountPoolManager`(CR-043) 재활용 가능.
- **KPI**:
  - LLM_CALL 오버헤드: 500ms × N → 500ms × 1 (측정 방법: 동일 워크플로우 구 경로/신 경로 실행 시간 차).
  - 10 LLM_CALL 워크플로우 총 시간 감소율.
  - 병렬 브랜치 캐시 hit율 — 첫 브랜치 대비 이후 브랜치 토큰 과금.
  - Max 플랜 5시간 윈도우 내 호출 가능 수.
  - 워커 크래시 빈도(run 대비 %).
- **요청자**: sykim | **승인자**: sykim (2026-04-16, 결정 7종 추천안 확정 2026-04-24) | **적용 버전**: v7.8.0
- **변경 일자**: 2026-04-16 (착수 2026-04-24)
- **상태**: ✅ Phase 1~9 구현 완료 (2026-04-24~26, claude 2.1.109). 신규 클래스 6개(Worker/Pool/Adapter/Config/BranchScope + 예외 2), 수정 5개(ConnectionAdapterFactory, WorkflowEngine, ParallelStepExecutor, CacheControlStrategy, application.yml), 단위 테스트 **26 PASS** (Worker 6 + Pool 8 + Adapter 12). **Phase 7**: ParallelStepExecutor 자동 fork 워커. **Phase 8 (시도 후 폐기)**: 시스템 프롬프트 텍스트 인젝션으로 도구 호출 시도 — 실측 결과 CLI 환경에서 native tool_use 끌어내지 못함을 확인하고 폐기. **Phase 9 (정석, 2026-04-26)**: MCP 통합으로 도구 호출 정식 구현 — 워커 buildCommand 에 `--strict-mcp-config --mcp-config <aimbase-agent-stdio>` + `--permission-mode bypassPermissions` 주입. aimbase-agent jar 가 자식 프로세스 stdio MCP 서버로 14개 SDK 도구 노출. CLI 가 `mcp__aimbase__<name>` 형식으로 native tool_use 발행, MCP 서버가 도구 실행, **CLI 내부에서 tool_result 자동 소비 후 다음 응답 생성** (실측됨). 외부(어댑터)는 tool_use 를 관찰 로그로만 남기고 LLMResponse.toolCalls 빈 채로 finishReason=END 반환 → OrchestratorEngine 외부 도구 루프와 충돌 없음. **트레이드오프 명시**: API 어댑터는 OrchestratorEngine ToolCallHandler 가 도구 루프 통제, CLI 어댑터는 CLI 내부 루프가 자율 통제 (Stop Hook/max_iterations 등 외부 정책 미적용). 같은 ToolRegistry/SDK 도구 본체는 공유 — 결과물 동등. cache_control TTL 순서 버그(tools 5m vs system 1h) 부수 수정. IT 환경 셋업 검증 완료 (CLI MCP 연결 status=connected, 도구 50회+ native 호출 관찰).
- **결정 승인 (2026-04-24 전부 추천안 확정)**:
  1. run당 최대 워커 수 = 5, 초과 시 큐잉(Semaphore 대기)
  2. Usage 추출 = stream-json 파싱, 실패 시 0 폴백 + 경고 로그
  3. IT = 로컬 전용 `@EnabledIfEnvironmentVariable(CLAUDE_CLI_IT=true)`, CI skip
  4. 피처 플래그 = `global_config.llm.anthropic-cli.enabled-tenants` (CR-040 재사용), `*`=전체 / `,`구분=특정 / 빈값=차단
  5. 첫 턴/이후 턴 = Worker 내부 상태(`firstTurnSent`) + Adapter 중복 가드
  6. `--model` = Connection config 의 model 을 그대로 CLI 인자로
  7. 타임아웃 = 워커 강제 종료 + run 실패 (상위 `WorkflowStep.retry` 에서 재시도)
- **이관 인계서**: `docs/origins/CR050_이관인계서_20260416.md`

### CR-051 | SSE 스트림 가상 스레드 SecurityContext 전파 — AccessDenied 로그 해소
- **변경 타입**: 버그수정
- **배경**: CR-045 Phase 2-B E2E 검증 중 SSE 스트림이 정상 완료됨에도 매 요청마다 `AuthorizationDeniedException` 스택이 2회 로그에 남음. 원인은 `ChatController.streamResponse()`가 `Thread.ofVirtual()`로 스트림을 처리할 때 부모 요청 스레드의 `SecurityContextHolder`(ThreadLocal)가 자식 가상 스레드에 전파되지 않아, async dispatch 재처리 시 `AuthorizationFilter`가 익명 사용자로 판단.
- **변경 내용**: `ChatController.streamResponse()` 진입부에서 `SecurityContextHolder.getContext()`를 캡처, 가상 스레드 시작 직후 `setContext(...)`, `finally`에서 `clearContext()`. 기존 TenantContext 전파 패턴과 동형.
- **변경 사유**: 기능 영향은 없으나 운영 로그 노이즈 + 보안 필터 정합성 착시. 벤치마크/모니터링 전에 제거 필요.
- **영향 모듈**: `ChatController`
- **영향도**: Low
- **영향 범위**: 관측성 (운영 로그)
- **영향 설계서**: 없음
- **요청자**: sykim | **승인자**: sykim (2026-04-16) | **적용 버전**: v7.3.1
- **변경 일자**: 2026-04-16
- **상태**: ✅ 완료 (2026-04-16). 빌드 검증 완료 — 런타임 로그 재현은 다음 SSE 호출 시 확인.
- **원본 요구사항**: `docs/origins/원본_요구사항_CR051_SSE_SecurityContext_전파_20260416.md`

### CR-052 | SessionStore append-only persist — conversation_sessions 중복키 근본 해소
- **변경 타입**: 버그수정
- **배경**: 매 `appendMessage` 호출마다 전체 세션을 재저장하는 기존 `persistToDb` 구조(`findBySessionId` 후 `save` + `deleteBySessionId` + 전체 메시지 재저장)가 두 개의 가상 스레드에서 동시에 실행되면 둘 다 빈 결과를 보고 새 엔티티 INSERT → `conversation_sessions_session_id_key` UNIQUE 제약 충돌. 기능에는 영향 없으나 매 메시지마다 ERROR 스택 + 배치 롤백 부하.
- **변경 내용**:
  1. `persistToDb`를 `upsertSession` + `appendNewMessages` 두 트랜잭션으로 분리.
  2. 세션 INSERT 경쟁은 `DataIntegrityViolationException` 1회 재시도 래퍼로 흡수 (재시도 시 재조회 → UPDATE 경로).
  3. 메시지는 `countBySessionId`로 DB 기존 개수 조회 후 **신규분만 INSERT** (`deleteBySessionId` + 전체 재저장 제거). N개 메시지 기준 O(N²) → O(신규).
  4. DB count > memory 시 경고 로그만 남기고 건너뜀 (수동 삭제 등 예외 상황 방어).
  5. `ConversationMessageRepository.countBySessionId` 신규 메서드 추가.
- **변경 사유**: CR-045 잔여 이슈의 근본 해소. append-only 구조로 전환해 동시성·I/O·로그 노이즈를 동시에 개선. 단기 retry만으로는 불필요한 트랜잭션 충돌·롤백 부하가 지속됨.
- **기존 부품 재사용**: `TransactionTemplate`, 기존 repository. 호출부(`appendMessage`, `OrchestratorEngine`) 변경 없음.
- **영향 모듈**: `SessionStore`, `ConversationMessageRepository`
- **영향도**: Medium (persist 시맨틱 변경 — 메시지 편집/삭제 경로는 현재 없음)
- **영향 범위**: BIZ-002(세션 메시지 영속)
- **영향 설계서**: 없음 (persist 구조만 내부 최적화)
- **요청자**: sykim | **승인자**: sykim (2026-04-16) | **적용 버전**: v7.3.1
- **변경 일자**: 2026-04-16
- **상태**: ✅ 완료 (2026-04-16). 빌드 통과. CR-046(Chat 실시간 제어) Phase 3 Soft Delete와 동거 확인 — `findBySessionIdIncludingDeleted` 경로는 CR-046 주석 유지.
- **원본 요구사항**: `docs/origins/원본_요구사항_CR052_SessionStore_중복키_20260416.md`

### CR-054 | Aimbase 플랫폼 공통 HttpRequestTool — 범용 REST 호출 Tool
- **대상 기능 ID**: PRD-313 (신규 — http_request Tool), BIZ-006(도구 호출 루프) 영향 없음
- **변경 타입**: 신규
- **배경**: Aimbase는 임의의 REST API를 호출할 수 있는 **범용 HTTP Tool이 없다**. 외부 시스템 연동이 필요하면 `ClaudeCodeTool`로 Claude CLI를 돌려 우회하거나 도메인 특화 Tool(예: `WebSearchTool`)을 개별 개발해왔는데, 이는 결정론적 호출에 Claude CLI 비용을 지불하는 구조적 결함이다. 직접 계기는 **FlowGuard 쪽 L2 시나리오 자동 등록 워크플로우**(FG 레포에서 별도 CR로 추진) — FG는 순수 REST(+ API Key) 인터페이스만 제공하므로 이를 호출할 공통 수단이 필요. 다만 이 수단은 FG 전용이 아니라 플랫폼 공통 자산으로 설계해야 재사용성이 확보된다.
- **변경 내용**:
  1. **HttpRequestTool 신규** (`tool/builtin/HttpRequestTool.java`): GET/POST/PUT/PATCH/DELETE 지원, query/headers/body/timeout_ms 파라미터, Java 21 HttpClient 기반 가상 스레드 실행.
  2. **Connection `REST_API` 타입 추가**: `connections.type` enum에 REST_API 확장. config JSONB에 `baseUrl`, `auth.{type,in,name,value_env,value}`, `healthPath`, `connectTimeoutMs`, `readTimeoutMs`.
  3. **인증 타입 4종**: `API_KEY`(header/query), `BEARER`, `BASIC`, `NONE`. 시크릿은 `value_env`(환경변수 참조) 권장, 평문 `value`는 dev 전용.
  4. **응답 정규화**: 4xx/5xx도 예외가 아닌 `{status, headers, body, bodyRaw, duration_ms, error}` 정상 반환 — 워크플로우 CONDITION 분기 동작을 위해 필수. JSON 자동 파싱, 비-JSON은 `bodyRaw: true`.
  5. **DomainFilterPolicy(CR-035) 통합**: 요청 직전 Connection baseUrl의 host를 정책 엔진에 전달. 초기 deny-all, 운영자가 Connection 등록 시 허용 host 추가.
  6. **감사 로깅**: 기존 `ToolCallAuditLogger` 체인 경유. body는 요약 1KB, `Authorization`/`X-Api-Key`/`Cookie` 헤더는 `***` 마스킹.
  7. **재시도**: Tool 내부 재시도 없음 — 워크플로우 `WorkflowStep.retry` 레벨에서 처리.
  8. **단위 테스트 13 케이스**(`HttpRequestToolTest.java`): WireMock으로 200/404/5xx/timeout/인증주입/마스킹/정책거부 검증. 커버리지 80%+.
  9. **가이드 문서 갱신**: `api-guide.md`에 http_request Tool 섹션, `ops-guide.md`에 REST_API Connection 등록 + DomainFilter 운영 절차.
- **변경 사유**:
  - 결정론적 REST 호출을 Claude CLI로 우회하는 비용 낭비 제거 (벤치마크 1/3 비용 이점 활용 불가 영역이었음)
  - FG 레포 측 L2 자동 등록 워크플로우의 선행 조건 — MCP는 과설계(LLM 판단 불필요), 결정론적 DAG에 적합한 Tool 필요. 워크플로우 JSON/프롬프트는 소비앱(FG) 책임, Aimbase는 인프라 제공만.
  - 플랫폼 자산 확충: OMS/WMS/bp-auth 내부 소비앱, 외부 SaaS(Slack/Jira/Notion) 등 모든 REST 연동 기반
- **기존 부품 재사용**: `ConnectionRepository`, `DomainFilterPolicy`(CR-035), `ToolCallAuditLogger`, `Tool` 인터페이스, `ToolRegistry`, Java 21 `HttpClient` (외부 라이브러리 불필요).
- **영향 모듈**: `tool/builtin/HttpRequestTool`, `tool/ToolRegistry`, `policy/DomainFilterPolicy`, `repository/ConnectionRepository`(읽기만), `db/migration/master`(connections.type enum 확장)
- **영향도**: High (신규 플랫폼 공통 Tool, 보안 경계 설정)
- **영향 범위**: 도구 레지스트리 (BIZ-006 루프 제한은 무영향), 정책 엔진 (DOMAIN_FILTER), 감사 로깅
- **영향 설계서**: T3-1(데이터 모델 — connections.type enum), T3-2(API 설계 — 신규 Tool), T1-3(비즈니스 규칙 — 필요 시 HTTP 호출 제약 추가)
- **범위 경계**: FG Connection 실제 등록(운영 데이터) / FG L2 워크플로우 JSON / LLM 프롬프트 / FG 전용 규칙은 **Aimbase에서 다루지 않음** — 소비앱(FlowGuard) 레포에서 별도 CR로 작성하고 Aimbase 기존 API(`/api/v1/workflows`, `/api/v1/connections`, `/api/v1/prompt-templates`)로 등록
- **요청자**: sykim | **승인자**: sykim (2026-04-22) | **적용 버전**: v7.9.0
- **변경 일자**: 2026-04-22
- **상태**: ✅ 완료 (2026-04-22). HttpRequestTool + WireMock 테스트 13 PASS + 가이드 문서 갱신 커밋(6b12dce). 후속 FG 측 L2 자동 등록 워크플로우는 FlowGuard 레포에서 별도 CR로 진행.
- **원본 요구사항**: `docs/origins/원본_요구사항_CR054_HttpRequestTool_20260422.md`
- **Plan 파일**: `~/.claude/plans/l2-radiant-bachman.md`

### CR-055 | Evaluator-Optimizer 워크플로우 노드 — Anthropic 6패턴 커버리지 완성
- **대상 기능 ID**: PRD-314 (신규 — EVALUATOR_LOOP StepType), BIZ-009(워크플로우 DAG) 불변식 보존
- **변경 타입**: 신규
- **배경**: Anthropic "Building Effective Agents" 6가지 패턴(Prompt Chaining / Routing / Parallelization / Orchestrator-Workers / Evaluator-Optimizer / Autonomous Agent) 대비 Aimbase 커버리지 점검 결과, **5번 Evaluator-Optimizer만 유일하게 미구현**. CR-016 LLM Judge는 정책 평가용(DENY/REQUIRE_APPROVAL)으로 워크플로우 노드에 연결되지 않았고, `PlanService.verify()`도 상태 전이만 수행하여 "생성 → 평가 → 재생성" 루프가 워크플로우에서 표현 불가. 문학 번역, 마케팅 카피, 코드 리뷰 대응 등 품질 기준은 명확하지만 한 번에 도달하기 어려운 시나리오를 시각적으로 설계할 수단이 없음.
- **변경 내용**:
  1. **`EVALUATOR_LOOP` StepType 신설**(`WorkflowStep.StepType` enum 확장, 총 9종): 노드 내부에 generator + evaluator + max_iterations + pass_criteria를 캡슐화.
  2. **노드 내부 구성**(`workflow_steps.config` JSONB): `{generator: {prompt, model, response_format}, evaluator: {prompt, model, criteria_schema}, max_iterations: 3, pass_criteria: {type, ...}}`.
  3. **종료 조건**: `max_iterations` 상한 10(BIZ-XXX로 신규 정의), `pass_criteria.type`은 `SCORE_THRESHOLD`(Judge 점수) / `JSONPATH_MATCH` / `LLM_JUDGE`(별도 프롬프트) 3종.
  4. **반복 컨텍스트 주입**: 직전 iteration 출력과 평가 피드백을 다음 generator에 `{{loop.previous_output}}`, `{{loop.feedback}}`, `{{loop.iteration}}`로 노출.
  5. **WorkflowEngine 확장**: 기존 Kahn 위상 정렬 불변. 루프는 노드 내부에서 완결되므로 DAG 외부는 여전히 단일 노드로 처리. 루프 결과는 `steps.<stepKey>.output` + `steps.<stepKey>.iterations[]` 메타로 노출.
  6. **중단/실패 정책**: max_iterations 도달 시 마지막 출력 반환 + `loop_exhausted: true` 플래그. 평가 단계 실패는 iteration 실패로 간주하고 재시도.
  7. **감사 로깅**: 각 iteration의 generator/evaluator 입출력을 `workflow_run_steps` 테이블에 개별 row로 기록(parent_step_id + iteration_index). 추적성 확보.
  8. **FE WorkflowStudio 확장**: 노드 팔레트에 EVALUATOR_LOOP 1종 추가, 속성 패널에서 generator/evaluator 프롬프트 편집 + criteria 선택 + max_iterations 슬라이더.
  9. **기본 프롬프트 템플릿**(CR-036 prompt_templates seed): `evaluator_literary_critic`, `evaluator_code_reviewer`, `evaluator_persona_copy` 3종 — 영문/한국어. 비대칭성(Generator ≠ Evaluator 관점) 원칙 반영.
- **변경 사유**:
  - Anthropic 6패턴 커버리지 100% 달성 — "워크플로우로 그리는 패턴(1·3·5)"의 표현력 완성
  - 품질 기준이 명확한 시나리오(번역/카피/리뷰 대응)에서 한 번의 LLM_CALL로 도달 못 하는 문제를 사용자가 정책적으로 해결 가능
  - 생성자/평가자 비대칭 구조를 플랫폼이 기본 템플릿으로 제공 → 사용자가 "같은 LLM 자가 평가"의 함정에 빠지지 않도록 유도
- **검토한 대안(기각)**: 옵션 B — `EVALUATOR` 노드 + `LOOP_BACK` 엣지로 임의 노드 회귀를 허용. DAG → 일반 그래프 전환 필요(사이클 허용, 방문 카운터, max_hops, validate 로직 전량 재작성). Anthropic 원문 패턴은 "닫힌 2-노드 루프"이므로 A로 충분. 다단계 루프가 필요하면 `SUB_WORKFLOW` + `EVALUATOR_LOOP` 조합으로 커버 가능. 실사용에서 표현 못 하는 케이스가 3건 이상 쌓이면 별도 CR로 B 확장.
- **기존 부품 재사용**: `WorkflowEngine`, `StepContext`(변수 치환), `LLM` 어댑터(CR-032 멀티 프로바이더), `prompt_templates`(CR-036), `workflow_run_steps` 테이블, React Flow WorkflowStudio.
- **영향 모듈**: `workflow/model/WorkflowStep`, `workflow/engine/WorkflowEngine`, `workflow/engine/steps/EvaluatorLoopStepExecutor`(신규), `db/migration/tenant`(workflow_run_steps에 parent_step_id/iteration_index 컬럼), `frontend/src/pages/WorkflowStudio`, `frontend/src/components/workflow/nodes/EvaluatorLoopNode`(신규)
- **영향도**: Medium (신규 노드 타입 1종 + FE 팔레트 + 기본 템플릿, 기존 DAG 엔진 불변식 보존)
- **영향 범위**: 워크플로우 엔진 (BIZ-009 보존), 감사 로깅, 프롬프트 템플릿 시드
- **영향 설계서**: T3-2(API 설계 — StepType enum 확장), T3-1(데이터 모델 — workflow_run_steps 컬럼 추가), T1-3(비즈니스 규칙 — max_iterations 상한 BIZ 신규)
- **범위 경계**: 일반화된 LOOP_BACK 엣지 / DAG 사이클 허용 / 런타임 max_hops 감지는 **본 CR 범위 제외** — 필요 시 별도 CR로 후속
- **요청자**: sykim | **승인자**: (대기) | **적용 버전**: v7.10.0 (예정)
- **변경 일자**: 2026-04-23
- **상태**: ✅ 설계 완료 (2026-04-23) — Sprint 52 구현 착수 대기. Q1 Evaluator 재시도 1회 + Q2 iterations[] JSONB 유지 2가지 미결 항목 사용자 승인 완료.
- **원본 요구사항**: `docs/origins/원본_요구사항_CR055_EvaluatorOptimizer_20260423.md`
- **T3 설계서**: `docs/T3-7_CR-055_EvaluatorOptimizer_설계서.md`

### CR-058 | Aimbase Chat Widget SDK — 소비앱 임베드용 채팅 + 워크플로우 + RAG 위젯
- **대상 기능 ID**: PRD-315 (신규 — 임베드 위젯 SDK), PRD-316 (신규 — 단기 위젯 토큰), PRD-317 (신규 — 워크플로우 SSE 구독), PRD-318 (신규 — RAG Citations 활성화)
- **변경 타입**: 신규
- **배경**: Aimbase를 소비하는 앱(OMS/WMS/OpenMall/Rescue/Notification 등, 모두 타 도메인)이 5개 이상으로 늘면서 **채팅 UI를 각자 구현하는 중복 비용**이 누적. Aimbase는 Chat SSE API(5 이벤트)와 세션 관리는 완성돼 있으나, **타 도메인 임베드에 필수인 인프라 4종이 누락**: (1) CORS 설정 자체 없음(Spring Boot 기본값으로 모든 오리진 거부), (2) 단기 위젯 토큰 API 없음(브라우저에 API Key 노출 금지 원칙), (3) 워크플로우 실행 이벤트가 REST 폴링만 지원(SSE 스트리밍 0% 구현), (4) RAG Citations가 `buildContextWithCitations()` 메서드 구현은 되어 있으나 호출되지 않아 ChatResponse에 citations 필드 미노출. 이 네 가지를 해소하고 위젯 SDK를 3채널(npm/UMD CDN/소스)로 배포해 소비앱이 한 줄 삽입으로 채팅 + 워크플로우 진행 가시화 + RAG 출처 카드를 얹을 수 있게 한다.
- **변경 내용**:

  **서버 (Sprint 52, 10MD)** — Phase 1~3:
  1. **CORS 동적 화이트리스트**(`CorsConfig.java` 신규 + `SecurityConfig.securityFilterChain()`에 `.cors()` 추가): `platform_settings.widget.allowed_origins` (CR-040 재사용) + 테넌트별 origin 화이트리스트 합집합으로 요청별 검증. `/api/v1/chat/**`, `/workflows/**`, `/conversations/**`, `/knowledge-sources/**`, `/sessions/issue-widget-token` 범위에만 적용 (관리 API 영향 없음).
  2. **단기 위젯 토큰 발급 API**(`POST /api/v1/sessions/issue-widget-token`, `WidgetTokenController.java` 신규): 인증은 API Key 전용(JWT로 발급 불가). TTL 기본 30분, 하드캡 1시간. scope 화이트리스트 `[chat:stream, workflow:subscribe, rag:read]` 교집합만 부여. origin 검증.
  3. **JwtProvider.generateWidgetToken()** 오버로드 + **JwtAuthenticationFilter 분기**: `type=widget` claim으로 access 토큰과 구분, scope→GrantedAuthority 매핑, origin 헤더와 claim 대조(403), `TenantContext` + `SecurityContext` 자동 설정.
  4. **SSE 쿼리 토큰 폴백**: `EventSource`는 커스텀 헤더 설정 불가 → `?access_token=` 쿼리 허용하되 **위젯 토큰만** 허용(access 토큰은 액세스 로그 유출 리스크로 헤더 전용 유지).
  5. **엔드포인트 scope 게이트**(`@EnableMethodSecurity` + `@PreAuthorize`): chat/conversations → `chat:stream`, `/workflows/runs/{id}/subscribe` → `workflow:subscribe`, `/knowledge-sources/{sid}/chunks/{cid}` → `rag:read`. 관리 API는 무매핑=기본 거부.
  6. **RAG Citations 활성화**(`OrchestratorEngine.java:244` — `buildContext()` → `buildContextWithCitations()` 교체 1줄): 이미 구현되어 있던 메서드를 호출 경로에 연결. `RetrievedChunk`에 `chunkId` 필드 추가. `Citation` DTO 포맷 `{chunk_id, source_id, document_name, score, content_preview(200자), page_number, metadata}`. `ChatResponse.citations` + `ragUsed` 필드 추가 + SSE `done` 이벤트 payload에 포함.
  7. **청크 원문 조회 API**(`GET /api/v1/knowledge-sources/{sourceId}/chunks/{chunkId}`): 위젯 원문 미리보기 패널용. EmbeddingEntity 조회 + 테넌트 격리 자동 보장 + `rag:read` scope 게이트. Parent-Child RAG 사용 시 parent_content 병행 반환.
  8. **워크플로우 `parent_run_id` 컬럼 추가**(Flyway `tenant/V{next}__add_parent_run_to_workflow_runs.sql` + `WorkflowRunEntity`): `parent_run_id UUID`, `parent_step_id VARCHAR(255)` + 인덱스. `SubWorkflowStepExecutor.java:94-100` 수정 — 서브워크플로우마다 별도 `WorkflowRunEntity` 생성 + parent 필드 설정. FE에서 부모→자식 트리 렌더 가능.
  9. **WorkflowEventPublisher 신규**(`com/platform/workflow/event/`): Spring `ApplicationEventPublisher` 래퍼. 이벤트 3종 `StepStatusChangedEvent`, `ApprovalRequiredEvent`, `RunCompletedEvent`. `WorkflowEngine.java` 5곳(288/299/312/341/323행)에 publish 주입. Virtual Thread SecurityContext 전파는 `ChatController.streamResponse:122-173` 패턴(CR-051) 100% 차용.
  10. **워크플로우 SSE 구독 엔드포인트**(`GET /api/v1/workflows/runs/{runId}/subscribe`, `WorkflowController`): SseEmitter + `@EventListener`. 필터링 규칙 — `runId` 일치 + `parent_run_id == runId` 자식 이벤트 포함(서브워크플로우 실시간 펼침). 30분 타임아웃, 15초 heartbeat comment. 이벤트 4종 — `workflow.snapshot`(구독 시점 초기 상태), `workflow.step`, `workflow.approval`, `workflow.done`.

  **프론트 (Sprint 53, 10MD)** — Phase 4~6:

  11. **pnpm workspace 전환**: 기존 `frontend/` → `apps/console/`, 신규 `packages/chat-widget/`(React 패키지 — ESM+CJS+`.d.ts`), `packages/chat-widget-embed/`(UMD + Web Component).
  12. **React 패키지 `@aimbase/chat-widget`**: 공개 API `initAimbaseChat(options)` + React 컴포넌트 `<AimbaseChat />`. 옵션 — `baseUrl`, `authResolver`, `contextProvider`, `display: 'bubble'|'inline'|'panel'`, `workflow.allowApproval(기본 false)`, `rag.previewMode`, `theme`, 이벤트 핸들러 5종(onMessage/onWorkflowStep/onApprovalRequired/onError/onTokenExpiring).
  13. **SSE 이중 핸들러**: `useChatStream`(chat 5 이벤트 파싱 + reconnect 3회 backoff), `useWorkflowStream`(workflow 4 이벤트 + EventSource 자동 재연결).
  14. **토큰 자동 갱신**: `exp - 5min` 시점 `onTokenExpiring` 콜백 → `authResolver()` 재호출 → 진행 중 SSE 유지, 다음 요청부터 신규 토큰.
  15. **마크다운 렌더**: `react-markdown` + `rehype-sanitize` (XSS 방지 필수). HTML raw 차단.
  16. **UMD + Web Component**: `<aimbase-chat base-url token-endpoint display theme-mode>` 속성형. Shadow DOM 스타일 격리. React 내부화된 UMD 번들로 Vue/바닐라/레거시 HTML 페이지 커버.
  17. **배포 채널 2종**: (A) npm `@aimbase/chat-widget` (React), (B) Aimbase 서버 정적 서빙 `/widget/v1/aimbase-chat.umd.js` (CDN 대체). 공개 npm/사내 CDN/GitHub Releases는 CR-062(후속)에서 결정.
  18. **소비앱 통합 가이드**(`docs/guides/embed-chat-widget.md`): BFF 샘플 3종(Node/Spring/FastAPI), 소비앱 샘플 3종(React/Vue/Vanilla), 보안 체크리스트, 트러블슈팅.
  19. **샘플 소비앱 + E2E 9 시나리오**(`tools/sample-consumer-app/` localhost:3999): CORS 차단/통과, 토큰 발급/만료 갱신, Scope gate, 채팅 스트림 + citations, 워크플로우 승인 이벤트 외부 처리, RAG 원문 패널, abort.

- **변경 사유**:
  - 소비앱 5개+ 확산에 따른 채팅 UI 중복 구현 비용 제거 — Intercom/Drift 스타일 임베드 SDK로 업계 표준 대응
  - 브라우저에 API Key 노출하는 안티패턴 차단 — BFF 프록시 + 단기 scope 토큰 체계 확립
  - 워크플로우 실행 가시화의 UX 공백 해소 — 현재 REST 폴링 방식은 대기 중 UX가 0
  - `buildContextWithCitations()` 메서드가 구현만 되고 미호출 상태인 기술 부채 해소
  - `parent_run_id` 컬럼 부재로 서브워크플로우 실행 트레이싱이 어렵던 문제 해결 (서버/FE 공통 혜택)
- **검토한 대안(기각)**:
  - (A) iframe 임베드만 — 1~2일 만에 MVP 가능하나 소비앱 화면 컨텍스트 주입(orderId 등)이 postMessage 브리지로 복잡, 모바일 UX 제약, 디자인 통합도 낮음. 소비앱 3개 이상에서는 SDK 방식이 장기 TCO 우위.
  - (B) React 전용 — 공수 절반이지만 Vue/레거시 HTML 소비앱 커버 불가. Web Component 병행으로 40% 공수 증가 감수하고 전체 커버리지 확보.
  - (C) 위젯 내 승인 UI 포함 — UX 균일하나 소비앱별 결재 체계(OMS 주문 승인, Rescue 반품 결재, 위임/다단계)와 충돌. 이벤트만 발행하고 소비앱 자체 플로우 재사용으로 결정.
- **기존 부품 재사용**:
  - `RAGService.buildContextWithCitations()` (이미 구현, 호출만 활성화)
  - `JwtProvider` 서명 로직 (동일 시크릿, `type` claim으로 구분)
  - `ChatController.streamResponse:122-173` Virtual Thread + SecurityContext 전파 패턴 (CR-051)
  - `TenantResolver:115-118` 쿼리 파라미터 폴백
  - `PlatformSettingsService` (CR-040) — `widget.allowed_origins` 저장 + 관리자 UI
  - `ChatController` SSE 5 이벤트 (CR-045) — 위젯이 그대로 소비
  - `/chat/{sessionId}/abort` (CR-046) — 위젯 중단 버튼
- **영향 모듈**: `config/SecurityConfig`, `config/CorsConfig`(신규), `auth/JwtProvider`, `auth/JwtAuthenticationFilter`, `api/WidgetTokenController`(신규), `api/ChatController`, `api/KnowledgeController`, `api/WorkflowController`, `orchestrator/OrchestratorEngine`, `orchestrator/ChatResponse`, `rag/RAGService`, `rag/model/RetrievedChunk`, `rag/model/Citation`(신규), `workflow/WorkflowEngine`, `workflow/event/WorkflowEventPublisher`(신규), `workflow/step/SubWorkflowStepExecutor`, `domain/WorkflowRunEntity`, `db/migration/tenant`(parent_run_id 추가), `db/migration/master`(widget settings seed), `packages/chat-widget`(신규), `packages/chat-widget-embed`(신규)
- **영향도**: High (SecurityConfig/CORS/토큰 체계 신규 + 워크플로우 엔진 이벤트 발행 주입 + 프론트 모노레포 전환 + 신규 패키지 2종 + 배포 채널 확장)
- **영향 범위**: 인증·인가 경계(위젯 토큰 + scope gate), 멀티테넌시(origin 검증 + 테넌트별 allowed_origins), 워크플로우 엔진(이벤트 publish, DAG 엔진 로직 불변), RAG 응답 포맷(하위 호환 — 기존 필드 유지 + 신규 필드 추가), Chat SSE done 이벤트(payload 확장), 프론트엔드 모노레포 구조
- **영향 설계서**: T3-1(데이터 모델 — workflow_runs.parent_run_id 컬럼 추가), T3-2(API 설계 — 신규 엔드포인트 3종: issue-widget-token / runs/{id}/subscribe / chunks/{id}), T3-3(화면 컴포넌트 — 신규 위젯 패키지 2종 구조), T1-3(비즈니스 규칙 — 위젯 토큰 TTL·scope 제약 BIZ 신규 후보)
- **범위 경계**: CDN 배포 인프라 결정(공개 npm/사내 registry/자체 CDN) / Vue·Svelte 전용 래퍼 / 음성 입력(STT) UI / 파일 업로드 / `allowApproval: true` 모드 위젯 내 승인 UI 완전 구현은 **본 CR 범위 제외** — CR-059~062 후속 CR 후보로 식별됨
- **요청자**: sykim | **승인자**: sykim (2026-04-24) | **적용 버전**: v8.0.0 (예정)
- **변경 일자**: 2026-04-24
- **상태**: ✅ **완료 (Sprint 52 + Sprint 53, 2026-04-24)**.
  - **Sprint 52 (서버)**: Phase 1~3 구현 + 신규 파일 17개(프로덕션 10 + 테스트 7) + Flyway 2개(V17 master, V54 tenant) + 신규 엔드포인트 3개. 단위 테스트 50건 추가. MCPServerManagerTest 사전 실패 2건 동시 해소하여 **회귀 497/497 PASS**. curl E2E 8시나리오 전체 PASS.
  - **Sprint 53 (프론트 축소 MVP)**: `packages/chat-widget-embed/` 신규 — Web Component `<aimbase-chat>` + UMD/ESM 듀얼 번들. token-store (만료 자동 갱신) + sse-parser (fetch 기반) + chat/workflow client + Shadow DOM 렌더러 (3 display mode: bubble/inline/panel) + Citation 원문 패널 + 워크플로우 진행 트리. 빌드 산출물: UMD **17KB** minified / ESM 25KB / .d.ts 4KB. 샘플 BFF (Node 내장 http, 외부 의존 0) + 샘플 consumer HTML 포함.
  - **소비앱 공개 배포 경로**: Aimbase 서버가 `/widget/v1/*` 경로를 **인증 없이 정적 서빙** (SecurityConfig permitAll + WebMvcConfigurer 매핑 + 30일 캐시 + Gradle copyWidgetBundle 태스크). 소비앱은 `<script src="https://aimbase.../widget/v1/aimbase-chat.umd.global.js">` 로 CDN 처럼 직접 참조하거나 `curl -O` 로 다운받아 자체 호스팅 가능. 공개 리소스 5종(UMD/ESM/.d.ts/index.html/sample-bff/server.js).
  - **통합 가이드 신규**: `docs/guides/embed-chat-widget.md` — 관리자 세팅 → BFF 구현 → 브라우저 삽입 → 트러블슈팅을 한 문서에 담은 소비앱 개발자 진입점. 같은 내용을 HTML 로 변환한 `widget/v1/index.html` 도 공개 서빙.
  - 델타 5건은 T3-9 § 9 에 기록(S3-2 스킵 / snake_case 바인딩 / OPTIONS preflight / Tenant Flyway 자동 적용 부재 / scope gate 느슨함).
  - 후속 분리: CR-059(Vue/Svelte 래퍼), CR-060(STT), CR-061(파일 업로드), CR-062(CDN 배포 인프라).
- **원본 요구사항**: `docs/origins/원본_요구사항_CR058_ChatWidget_20260424.md`
- **T3 설계서**: `docs/T3-9_CR-058_ChatWidget_설계서.md` (§ 9 Sprint 52 델타 포함)
- **Plan 파일**: `~/.claude/plans/joyful-petting-pond.md`

---

### CR-061 | 위젯 파일 업로드 (이미지/PDF Vision 첨부)

- **대상 기능 ID**: PRD-319, PRD-320, PRD-321, PRD-322, PRD-323, FE-036
- **변경 타입**: 신규
- **변경 내용**:
  1. **첨부 업로드 API 2종**: `POST /api/v1/chat/attachments` (multipart), `DELETE /api/v1/chat/attachments/{id}` — scope `chat:upload`
  2. **데이터 모델 신규**: `chat_attachments` 테이블 (V58, tenant DB) — id / session_id / media_type / size_bytes / storage_path / pages / checksum / expires_at
  3. **Chat Completions 확장**: `messages[].content[]` 에 `{type:"image"|"document", attachment_id:"..."}` 블록 수용, 기존 인라인·문자열 호환 유지
  4. **프로바이더 capability 분기**: Anthropic Claude → 네이티브 `image`/`document` 블록, OpenAI/Ollama 등 → 이미지는 네이티브, PDF는 Python 사이드카 `parse_document` 로 텍스트 추출 후 Text 블록 prepend 폴백
  5. **위젯 FE 증분**: 클립 아이콘 버튼 + 드래그앤드롭 + 썸네일(이미지)/파일명+페이지수(PDF) 칩 UI + `attachment-client.ts` + `chat-client.ts` attachment_ids 전달
  6. **보안 다층 방어**: magic number 기반 MIME 재검증 + 세션 소유권 검증 + Redis rate limit(세션 5req/min, 토큰 20req/min) + CR-058 CORS 재사용
  7. **GC 배치**: `AttachmentGcScheduler` 매 5분, `expires_at < now()` → StorageService.delete() + 레코드 삭제
  8. **런타임 설정화**: 크기/개수 제한을 `platform_settings.widget.attachment.*` 로 소프트 코드 (Phase 2 범위)
  9. **Scope 확장**: `platform_settings.widget.allowed-scopes` 기본값에 `chat:upload` 추가
  10. **API 가이드 v2.6.0 § 18** (첨부 API) + **운용 가이드 v2.5.0 § 2-6** (GC/사용량 모니터링) 신설
- **변경 사유**:
  - CR-058 Sprint 53 배포 후 소비앱 채널에서 **실사용 요청 1순위** — 상담 중 영수증/스크린샷/PDF 첨부
  - `ContentBlock.Image` + `AnthropicAdapter` Vision 경로가 이미 완성되어 있음 → **위젯 FE + 업로드 엔드포인트만 추가**하면 최소 비용으로 대응 가능
  - RAG 인제스션 연동은 파이프라인 오염·GC 복잡도 우려로 **본 CR 범위에서 제외**하고 별도 CR로 분리
- **영향 모듈**:
  - **BE**: 신규 `com.platform.attachment` 패키지(Service/Validator/Extractor/Scheduler) + `api/AttachmentController` + `domain/ChatAttachmentEntity` + `repository/ChatAttachmentRepository` + `llm/model/ContentBlock$Document` + `llm/adapter/AnthropicAdapter` 확장 + `api/ChatController.toUnifiedMessage` 분기 + `api/WidgetTokenController` 기본 scope
  - **FE**: `packages/chat-widget-embed/src/` — 신규 `attachment-client.ts` + `widget.ts` UI 증분 + `chat-client.ts` content 블록 확장 + `types.ts` export
  - **DB**: Flyway tenant `V58__create_chat_attachments.sql`
  - **Python 사이드카**: 기존 `parse_document` 재사용, 변경 없음
- **영향도**: Medium
- **영향 범위**: CR-011 (StorageService 재사용), CR-045 (Chat SSE content 확장), CR-058 (위젯 토큰/CORS/Scope), CR-040 (platform_settings)
- **영향 설계서**: T3-10 (신규), T1-3 (BIZ-099~101 추가), T3-2 (API 섹션 추가), T3-3 (위젯 컴포넌트 추가)
- **요청자**: 위젯 소비앱 실사용 피드백 | **승인자**: 기획자 | **적용 버전**: v8.1.0
- **변경 일자**: 2026-04-24
- **범위 경계**: RAG 인제스션 연동(세션 ad-hoc KnowledgeSource) / 음성(STT) / OCR 후처리 / Office 문서(DOCX/XLSX/PPTX) 위젯 첨부 / 멀티페이지 PDF UI 페이지 선택 / 첨부 영구 저장 — **본 CR 범위 제외**
- **Sprint 배치 (5MD)**:
  - Phase 1 — DB V58 + Entity/Repository (0.5MD)
  - Phase 2 — AttachmentController + Service + MimeValidator + GcScheduler (1.0MD)
  - Phase 3 — ContentBlock.Document + AdapterCapability + ChatController 분기 + PdfTextExtractor (1.0MD)
  - Phase 4 — 위젯 FE 파일 선택/드래그앤드롭/칩 프리뷰 + chat-client 확장 (1.5MD)
  - Phase 5 — BE 단위+통합+E2E 테스트 + API/운용 가이드 갱신 (1.0MD)
- **원본 요구사항**: `docs/origins/원본_요구사항_CR061_파일업로드_20260424.md`
- **T3 설계서**: `docs/T3-10_CR-061_FileUpload_설계서.md`
- **Plan 파일**: `~/.claude/plans/cr-061-file-upload.md` (구현 착수 시 생성)

---

### CR-060 | 위젯 음성 입력 (STT) — 마이크 녹음 + Whisper 변환 + 입력창 삽입

- **대상 기능 ID**: PRD-324, PRD-325, PRD-326, FE-037
- **변경 타입**: 신규
- **변경 내용**:
  1. **위젯 전용 STT API**: `POST /api/v1/chat/stt` (multipart) — scope `chat:stt`. 기존 `/api/v1/speech/stt`는 Platform JWT 전용으로 유지하고 로직은 `SpeechService` 로 추출해 공유
  2. **SpeechService 추출**: `OpenAI Whisper` 호출·multipart body 조립·에러 매핑을 서비스로 이동. `SpeechController`(Platform)/`ChatSttController`(Widget) 양쪽에서 주입 사용
  3. **런타임 설정화**: `global_config` 신규 4건 — `widget.stt.max-duration-seconds`(60) / `widget.stt.max-size-bytes`(26214400, 25MB) / `widget.stt.allowed-mime-types`(audio/webm,audio/mp4,audio/mpeg,audio/wav,audio/ogg) / `widget.stt.rate-limit-per-minute`(10). Flyway master V19 seed
  4. **Scope 확장**: `platform_settings.widget.allowed-scopes` 기본값에 `chat:stt` 추가
  5. **BIZ 규칙 신설**: BIZ-102(녹음 시간 상한) / BIZ-103(파일 크기 상한) / BIZ-104(세션당 분당 rate limit)
  6. **위젯 FE 증분**: 마이크 아이콘 버튼 + 녹음 타이머 + 파형 펄스 애니메이션 + 취소 버튼 + `stt-client.ts` 신규 + `widget.ts` 입력창 자동 삽입. 모바일 Safari 폴백(`audio/mp4`)
  7. **권한 처리**: `getUserMedia` 권한 거부/블록 상태 명시 UI + 재시도 안내. HTTPS/localhost 외 환경 감지 시 마이크 버튼 비활성
  8. **보안 다층 방어**: magic number 기반 MIME 재검증 + 세션 소유권 검증(JWT session_id) + Redis rate limit(세션 10req/min) + CR-058 CORS 재사용
  9. **감사 로그**: `audit_log` 에 `event_type=STT_TRANSCRIBE` 기록 (session_id / duration_sec / size_bytes / language). **변환 텍스트 본문은 저장하지 않음** (PII)
  10. **API 가이드 v2.7.0 § 19** (위젯 STT API) + **운용 가이드 v2.6.0 § 2-7** (STT 사용량/마이크 권한 FAQ) 신설
- **변경 사유**:
  - CR-058 위젯 배포 후 **모바일 사용성 요청** — 입력창 타이핑 불편, 음성 입력 선호도 높음
  - 기존 `SpeechController` 프록시 인프라가 이미 존재 → 위젯 전용 엔드포인트 + FE UI만 추가하면 최소 비용
  - CR-061 과 동일한 위젯 API 패턴(`/chat/*` + 전용 scope + `global_config` 설정화)을 그대로 따라 일관성 확보
- **영향 모듈**:
  - **BE**: 신규 `com.platform.speech.SpeechService` + `api/ChatSttController` + `api/SpeechController` 리팩터(서비스 주입) + `api/WidgetTokenController` 기본 scope 갱신 + `policy/SttRateLimiter`(Redis 기반)
  - **FE**: `packages/chat-widget-embed/src/` — 신규 `stt-client.ts` + `widget.ts` 마이크 UI 증분 + `types.ts` SttResult export + `styles.ts` 마이크/파형 CSS
  - **DB**: Flyway master `V19__add_widget_stt_config.sql` (global_config seed, 테이블 신규 없음)
  - **Python 사이드카**: 변경 없음
- **영향도**: Medium
- **영향 범위**: CR-011 (SpeechController 기존 자산), CR-040 (global_config 설정화), CR-058 (위젯 토큰/CORS/Scope), CR-061 (위젯 API 패턴/BIZ 연번)
- **영향 설계서**: T3-11 (신규), T1-3 (BIZ-102~104 추가), T3-2 (API 섹션 추가), T3-3 (위젯 마이크 컴포넌트 추가)
- **요청자**: 위젯 소비앱 모바일 UX 피드백 | **승인자**: 기획자 | **적용 버전**: v8.2.0
- **변경 일자**: 2026-04-24
- **범위 경계**: 실시간 스트리밍 STT(OpenAI Realtime API) / TTS 위젯 UI(Assistant 응답 낭독) / 화자 분리·타임스탬프 세그먼트 노출 / 오프라인·온디바이스 STT / Whisper 외 프로바이더 — **본 CR 범위 제외**. Realtime 스트리밍과 TTS UI 는 CR-063/CR-064 후속 후보로 식별
- **Sprint 배치 (4.5MD)**:
  - Phase 1 — `global_config` master V19 seed + `PlatformSettings` 키 상수 추가 (0.5MD)
  - Phase 2 — `SpeechService` 추출 + `SpeechController` 리팩터 + 기존 회귀 테스트 (1.0MD)
  - Phase 3 — `ChatSttController` + `SttRateLimiter` + `WidgetTokenController` scope 추가 + BIZ-102~104 enforcement (1.0MD)
  - Phase 4 — 위젯 FE 마이크 버튼 + MediaRecorder 훅 + 파형 타이머 + `stt-client.ts` + Safari 폴백 (1.5MD)
  - Phase 5 — BE 단위+통합 테스트 + FE 수동 검증 + API/운용 가이드 갱신 (0.5MD)
- **원본 요구사항**: `docs/origins/원본_요구사항_CR060_STT_20260424.md`
- **T3 설계서**: `docs/T3-11_CR-060_STT_설계서.md`
- **Plan 파일**: `~/.claude/plans/cr-060-stt-widget.md` (구현 착수 시 생성)

---

### CR-065 | SubWorkflow 자식 run 분리 생성

- **대상 기능 ID**: PRD-327, PRD-328, PRD-329
- **변경 타입**: 변경
- **변경 내용**:
  1. **자식 run 실존 트리 저장**: `SubWorkflowStepExecutor` 인라인 실행을 자식 `WorkflowRunEntity` 생성 경로로 승격. `parent_run_id` + `parent_step_id` 설정하여 부모-자식 트리 구조 DB 저장
  2. **WorkflowEngine 재진입 가능 구조 분리**: 현재 executor 레이어에서 `doExecuteAsync()` 재호출 시 순환 의존 유발 → 내부 실행 경로를 `WorkflowEngineInternal` 또는 유사 인터페이스로 분리하여 executor가 엔진을 재호출 가능하도록 의존 그래프 재설계
  3. **자식 run 조회/재시도 API**: `GET /api/v1/workflows/runs/{parentId}/children` (자식 run 목록) + `POST /api/v1/workflows/runs/{childId}/retry` (자식 단위 재시도) 신설
  4. **stepResults 자식 run 기록**: 자식 run 전용 `workflow_step_runs` 레코드 — 부모 run의 `stepResults`에는 `sub_workflow_run_id` 참조만 유지
  5. **SSE 이벤트 호환성 유지**: 기존 `workflow.step` 이벤트 payload의 `sub_workflow_id` 필드는 그대로 유지 + 신규 `sub_workflow_run_id` 추가하여 위젯 트리 UI 기존 코드 무수정 동작
  6. **위젯 FE 증분 (CR-058 SDK 업데이트)**: 트리 UI에 "자식 run 재시도" 버튼 + 자식 run 상태 개별 표시 (별도 FE 작업, 본 CR Phase 4)
- **변경 사유**:
  - CR-058 Sprint 52 구현 중 `SubWorkflowStepExecutor` child run 분리 생성을 스킵 (T3-9 § 9-1) — executor 레이어에서 `WorkflowEngine.doExecuteAsync()` 재호출 시 순환 의존 유발
  - 현재는 인라인 실행 + SSE `sub_workflow_id` 로 위젯 트리 표시 가능하나 **"자식 run 전용 stepResults 기록"** 과 **"자식 run 개별 재시도"** 는 불가
  - DB 스키마(`parent_run_id` 컬럼)는 이미 적용되어 있어 엔진 의존 구조 재설계만 해결되면 즉시 승격 가능
  - 위젯 트리 UI에서 "자식 run 재시도"·"개별 조회" 요구가 실사용에서 제기될 때 착수 (현재는 화면 표시 자체는 되므로 **긴급하지 않음**)
- **영향 모듈**:
  - **BE**: `workflow/step/SubWorkflowStepExecutor.java` (핵심 수정) + `workflow/WorkflowEngine.java` (doExecuteAsync 재진입 가능 구조로 분리) + `domain/WorkflowRunEntity.java` (parent_run_id 이미 존재, 활용만) + `api/WorkflowController.java` (자식 run 조회/재시도 API 신설)
  - **FE**: `frontend/src/components/workflow/RunTree.tsx` 또는 유사 — 자식 run 재시도 버튼 / 개별 상태 표시 (CR-058 위젯 SDK 포함)
  - **DB**: 스키마 변경 없음 — 기존 `workflow_runs.parent_run_id`, `workflow_step_runs` 활용
  - **Python 사이드카**: 변경 없음
- **영향도**: Medium (리팩토링 + 회귀 위험 있음)
- **영향 범위**: CR-009 (워크플로우 엔진 DAG 실행), CR-045 (Chat 실시간 워크플로우 SSE), CR-058 (위젯 트리 UI)
- **영향 설계서**: T3-12 (신규), T3-6 (워크플로우 엔진 의존 구조 섹션 개정), T1-3 (BIZ-009 SUB_WORKFLOW 런타임 서술 갱신)
- **요청자**: CR-058 Sprint 52 델타 (T3-9 § 9-1) | **승인자**: sykim (2026-04-24) | **적용 버전**: v8.3.0 (예약)
- **변경 일자**: 2026-04-24
- **범위 경계**: 부모-자식 외 3단 이상 중첩 SUB_WORKFLOW 재시도 UX / 자식 run 병렬 실행 제한 정책 / 자식 run 스로틀링 / 자식 run 트리 시각화 React Flow 통합 — **본 CR 범위 제외**
- **Sprint 배치 (예상 5.5MD)**:
  - Phase 0 — `WorkflowEngine`·`SubWorkflowStepExecutor` 의존 그래프 분석 + 리팩토링 전략 결정 + 사용자 승인 (0.5MD)
  - Phase 1 — `WorkflowEngine` `doExecuteAsync()` 재진입 가능 구조로 분리 (`WorkflowEngineInternal` 또는 유사 인터페이스) (1.5MD)
  - Phase 2 — `SubWorkflowStepExecutor`에서 자식 `WorkflowRunEntity` 생성 + `parent_run_id`/`parent_step_id` 설정 + stepResults 분리 기록 (1.0MD)
  - Phase 3 — 자식 run API (`GET /runs/{parentId}/children` + `POST /runs/{childId}/retry`) (0.5MD)
  - Phase 4 — 위젯 UI 자식 재시도 버튼 (CR-058 SDK 업데이트) (1.0MD)
  - Phase 5 — 회귀 테스트 (기존 SSE 이벤트 포맷 유지 검증 + 단위/통합 테스트) (1.0MD)
- **착수 시점 판단 기준**: 위젯 트리 UI에서 "자식 run 재시도"·"개별 조회" 요구가 실사용에서 제기될 때 / 현재는 화면 표시 자체는 되므로 긴급하지 않음
- **완료 기준**:
  - [ ] 자식 run이 `workflow_runs` 별도 레코드로 저장됨
  - [ ] `parent_run_id` 기반 트리 조회 API 동작
  - [ ] 자식 run 개별 재시도 API 동작
  - [ ] 기존 인라인 SSE 이벤트 포맷 호환성 유지 (위젯 기존 코드 무수정 동작)
  - [ ] 회귀 테스트 PASS (단위/통합)
- **원본 요구사항**: `docs/origins/원본_요구사항_CR065_SubWorkflow자식run_20260424.md`
- **T3 설계서**: `docs/T3-12_CR-065_SubWorkflowChildRun_설계서.md` (구현 착수 시 작성)
- **Plan 파일**: `~/.claude/plans/cr-065-subworkflow-child-run.md` (구현 착수 시 생성)

---

### CR-066 | Tenant Flyway 자동 재실행

- **대상 기능 ID**: PRD-330, PRD-331, PRD-332
- **변경 타입**: 변경
- **변경 내용**:
  1. **TenantMigrationRunner 신설**: `tenants` 테이블 활성 테넌트 목록 순회 → 각 DB에 격리된 `Flyway.migrate()` 실행 + 결과 요약 반환. 1개 테넌트 실패 시 나머지 진행 (실패 격리)
  2. **Admin API 신설**: `POST /api/v1/platform/tenants/migrate` (Master DB 전용, `SCOPE_platform:admin` 필수) — body `{tenantIds?: [...], dryRun?: bool}` / 응답 `{total, success, failed, details[]}`. 호출자 감사 로그 기록
  3. **마이그레이션 상태 조회 API**: `GET /api/v1/platform/tenants/{id}/migrations` — 적용된 버전 + 실패 이력 조회
  4. **기동 시점 자동 훅 (선택, Phase 3)**: `ApplicationReadyEvent` 리스너에서 `tenantMigrationRunner.migrateAll()` 호출 + 실패 격리 (기동 차단하지 않음) + 기동 로그에 결과 요약. `application.yml` `aimbase.tenant.migration.auto-migrate-on-startup: true/false` 플래그로 제어
  5. **TenantOnboardingService 리팩터**: 신규 테넌트 등록 시 호출되는 Flyway 실행 로직을 `TenantMigrationRunner` 로 통합 — 단일 진입점화
  6. **감사 로그 (선택)**: `tenant_migration_logs` Master 테이블 신설 — `tenant_id / from_version / to_version / triggered_by / status / error / executed_at` (운영 가시성 확보)
  7. **운영 가이드 § 2-5 개정**: 수동 `flyway migrate` 절차 → 자동/반자동 절차로 갱신. Admin API 호출 예시 + 기동 시점 자동 훅 운영 주의사항 추가
- **변경 사유**:
  - CR-058 Sprint 52 구현 중 발견 (T3-9 § 9-4) — `db/migration/tenant/V*.sql` 신규 마이그레이션이 기동 시점에 기존 활성 테넌트에 자동 적용되지 않음
  - `LocalDevInitializer` 는 `@Profile("local")` 이라 운영 경로 없음, `TenantOnboardingService` 는 신규 테넌트 등록 시점만 호출
  - 현재 배포 가이드 § 2-5 에 수동 절차로 문서화 → 테넌트 수 × 배포 횟수만큼 수동 실행
  - "A 테넌트는 V54 적용, B 테넌트는 V53 누락" 같은 사고 가능성 — 테넌트 수 늘어날수록 실수 확률 선형 증가
  - 테넌트 10개+ 증가 또는 마이그레이션 누락 사고 1회라도 발생하면 즉시 착수
- **영향 모듈**:
  - **BE**: 신규 `tenant/TenantMigrationRunner.java` (핵심) + `api/platform/TenantController.java` (Admin API 추가) + `config/FlywayMultiTenantConfig.java` (재실행 가능 구조로 정리) + `tenant/TenantOnboardingService.java` (Runner로 Flyway 로직 이관) + (선택) `domain/master/TenantMigrationLogEntity.java` + `repository/master/TenantMigrationLogRepository.java`
  - **FE**: 없음 (관리자 UI는 별도 CR로 분리, 본 CR은 API만)
  - **DB**: (선택) Flyway master 신규 마이그레이션 — `tenant_migration_logs` 테이블
  - **Python 사이드카**: 변경 없음
  - **운영**: `docs/guides/aimbase-ops-guide.md § 2-5` 개정
- **영향도**: Medium (기동 시점 자동 훅 채택 시 장애 전파 리스크)
- **영향 범위**: CR-001 (멀티테넌시 DB-per-Tenant 기반), CR-041 (Tenant 프로비저닝 흐름), CR-058 (V54 이후 tenant 마이그레이션 자동 적용 필요)
- **영향 설계서**: T3-13 (신규), T3-1 (인프라/DB-per-Tenant 마이그레이션 섹션 개정), `docs/guides/aimbase-ops-guide.md` § 2-5 (자동 절차로 갱신)
- **요청자**: CR-058 Sprint 52 델타 (T3-9 § 9-4) | **승인자**: sykim (2026-04-24) | **적용 버전**: v8.4.0 (예약)
- **변경 일자**: 2026-04-24
- **범위 경계**: 테넌트별 마이그레이션 타임아웃 정책 / 롤백 자동화 (Flyway undo 불가 — 별도 CR) / 관리자 UI 페이지 / 마이그레이션 스케줄러 (cron 기반 주기 실행) / Master DB 마이그레이션 자동화 (본 CR은 tenant DB만) — **본 CR 범위 제외**
- **Sprint 배치 (예상 4.5MD)**:
  - Phase 0 — 해결안(기동훅/Admin API/조합) 중 선택 + 실패 격리 전략 결정 (사용자 승인) (0.5MD)
  - Phase 1 — `TenantMigrationRunner` 구현 (테넌트 순회 + 격리된 Flyway 실행 + 결과 요약) + `TenantOnboardingService` Runner 통합 (1.5MD)
  - Phase 2 — Admin API `POST /platform/tenants/migrate` + `GET /platform/tenants/{id}/migrations` + (선택) 감사 로그 테이블 (1.0MD)
  - Phase 3 — (선택) `ApplicationReadyEvent` 기동 시점 자동 훅 + `application.yml` 플래그 + 실패 격리 (0.5MD)
  - Phase 4 — 운영 가이드 § 2-5 자동 절차로 개정 + 회귀 테스트 (3테넌트 환경, V55~V56 일괄 적용 + 1개 의도적 실패 격리) (1.0MD)
- **착수 시점 판단 기준**: 테넌트가 **10개 이상**으로 증가할 때 / 마이그레이션 누락 사고 **1회라도 발생**하면 즉시 / 현재는 수동 절차로 운영 중이라 급하진 않으나 **선제 대응이 합리적**
- **완료 기준**:
  - [ ] `TenantMigrationRunner`로 활성 테넌트 일괄 migrate 동작
  - [ ] Admin API `POST /platform/tenants/migrate` 동작 (호출자 감사 로그 기록)
  - [ ] 실패 격리: 1개 테넌트 실패 시 나머지 진행, 결과 요약 반환
  - [ ] 운영 가이드 § 2-5 자동 절차로 갱신
  - [ ] 회귀 테스트 (신규 테넌트 등록 경로 기존대로 동작)
- **원본 요구사항**: `docs/origins/원본_요구사항_CR066_TenantFlyway자동재실행_20260424.md`
- **T3 설계서**: `docs/T3-13_CR-066_TenantFlywayAutoMigrate_설계서.md` (구현 착수 시 작성)
- **Plan 파일**: `~/.claude/plans/cr-066-tenant-flyway-auto-migrate.md` (구현 착수 시 생성)

---

### CR-067 | EnhancedToolExecutor default bridge 본문 노출 (정공)

- **대상 기능 ID**: PRD-333 (신규 — Tool SDK bridge 본문 노출 규약 일원화)
- **변경 타입**: 버그수정
- **선택안**: **옵션 A 정공** — `EnhancedToolExecutor.execute(Map)` default bridge 자체를 본문 직렬화 반환으로 수정. MCP 우회로(옵션 B)는 비대칭 SDK 계약을 만들기에 기각
- **변경 내용**:
  1. **`EnhancedToolExecutor.execute(Map)` default bridge 정정** — `ToolResult.output` 을 표준 직렬화 규칙으로 문자열 반환, `summary` 1줄 헤더 부착, 에러 시 본문에 reason 포함. 신규 정적 헬퍼 `ToolResultRenderer.render(ToolResult)` 추출(중복 방지 + 단위 테스트 가능 단위)
  2. **본문 직렬화 표준 규칙** (`ToolResultRenderer`):
     - `output == null` && `success` → `summary` 만 반환
     - `output instanceof CharSequence` → 문자열 그대로
     - `output instanceof Map` → `content` / `stdout` / `text` / `body` / `result` / `data` 순으로 본문 키 탐색, 발견 시 그 값을 본문으로 + 잔여 키는 `\n---\n<json>` 부록. 본문 키 없으면 전체 Jackson `pretty` JSON
     - `output instanceof Collection / Number / Boolean` → Jackson `pretty` JSON
     - 그 외 객체 → Jackson `pretty` JSON, 직렬화 실패 시 `toString()` 폴백 + 경고 로그
     - `success == false` → `[ERROR] {summary}\n{body?}` 형식
     - 헤더: `summary` 가 비어있지 않으면 본문 앞에 `# {summary}\n` 부착, 본문 자체와 중복 시 헤더 생략
  3. **신규 `ToolResultRenderer` 클래스** — `tool-sdk-core` 위치, `EnhancedToolExecutor` default bridge 와 `AgentMcpServer.buildMcpServer` 두 호출처에서 공통 사용. 향후 SDK 사용자가 직접 `output→string` 변환할 때도 재사용 가능하도록 public
  4. **`AgentMcpServer.buildMcpServer` 정리** — 더 이상 분기 불필요. legacy `ToolExecutor.execute(args)` 가 본문을 자동 반환하므로 기존 한 줄 호출 유지. `McpResultTruncator.truncate` 는 그대로 통과
  5. **비-MCP 호출처 회귀 점검** — `ToolController` / `CronScheduleManager` / `RemoteTriggerTool` / `HttpRequestToolTest`(16+ 케이스) 동작 변화 확인:
     - 응답 페이로드가 메타 → 본문으로 바뀜 → 의도된 변경, API 응답 스키마 변경 없음(여전히 String)
     - HttpRequestToolTest 는 `execute(Map, ToolContext)` 신 메서드를 직접 호출하고 있어 영향 없음 (확인 후 기록)
     - ToolController 응답 사용자(FE) 도 본문 노출이 자연스러움 (ToolPlayground 등)
  6. **단위 테스트 신설**:
     - `ToolResultRendererTest` — 출력 타입별 직렬화 8 케이스 (string / Map+content / Map+stdout / Map+다중키 / Collection / Number / record / 직렬화 실패 폴백)
     - `ToolResultRendererTest` — success=false 에러 포맷 2 케이스
     - `EnhancedToolExecutorBridgeTest` — bridge 가 `execute(Map, ctx)` 호출 후 renderer 통과시키는지 1 케이스
     - `AgentMcpServerTest` — EnhancedToolExecutor 본문 노출 + legacy ToolExecutor 회귀 + 에러 직렬화 + truncator 적용 4 케이스
  7. **회귀 통합 테스트** — `HttpRequestToolTest` / `ToolControllerTest` / 기타 EnhancedToolExecutor 테스트 전수 PASS 확인
  8. **CR-050 종합 IT 재실측** — `AdapterToolLoopComparisonIT` 다시 돌려 CLI 어댑터가 파일 6개 분석 완수하는지 검증. 비용/토큰 표 갱신
  9. **SDK 가이드 갱신** — `aimbase-sdk-guide.md` 에 "ToolResult.output 직렬화 규칙" 섹션 신설. 본문 키 우선순위, 에러 포맷, 헤더 부착 규칙 명시. 가이드 v 패치
- **변경 사유**:
  - CR-050 Phase 9 종합 벤치마크에서 발견 (memory `project_cr050_status.md` § 벤치마킹 결과)
  - `EnhancedToolExecutor.execute(Map)` default bridge 가 `ToolResult.summary()` 만 반환 → `output` Map 의 본문(파일 내용·stdout·grep 결과)이 누락
  - bridge 호출처가 MCP 한 곳이 아님: `ToolController:106` / `CronScheduleManager:196` / `RemoteTriggerTool:115` / 향후 SDK 사용자 모두 영향
  - 옵션 B(MCP 분기)로 가면 "MCP 는 본문, 그 외는 메타"라는 비대칭 SDK 계약이 박혀 다음 사람이 또 함정에 빠짐
  - 정공: bridge 자체를 "신 인터페이스를 String 으로 충실히 어댑트"하는 의미로 일관화
- **영향 모듈**:
  - **BE 핵심**:
    - `backend/sdk/tool-sdk-core/src/main/java/com/platform/tool/EnhancedToolExecutor.java` (default bridge 수정)
    - `backend/sdk/tool-sdk-core/src/main/java/com/platform/tool/ToolResultRenderer.java` (신규)
  - **BE 점검**:
    - `backend/sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/AgentMcpServer.java` (변경 불필요, 동작 검증)
    - `backend/platform-core/src/main/java/com/platform/api/ToolController.java`
    - `backend/platform-core/src/main/java/com/platform/workflow/CronScheduleManager.java`
    - `backend/platform-core/src/main/java/com/platform/tool/builtin/RemoteTriggerTool.java`
  - **테스트**:
    - 신규: `ToolResultRendererTest`, `EnhancedToolExecutorBridgeTest`, `AgentMcpServerTest`(없으면 신설)
    - 회귀: `HttpRequestToolTest` 16+ 케이스 / 기타 EnhancedToolExecutor 단위 / `AdapterToolLoopComparisonIT` 재실측
  - **FE**: 없음
  - **DB**: 없음
  - **Python 사이드카**: 없음
  - **운영**: `docs/guides/aimbase-sdk-guide.md` ToolResult 직렬화 규칙 섹션 신설
- **영향도**: High (default bridge 의미를 변경하므로 SDK 호환성 분석 필수)
- **영향 범위**: CR-029 (EnhancedToolExecutor 도입 — 결함 원천) / CR-041 (Tool SDK 추출 시점 결함 이관) / CR-044 (CLI + Aimbase MCP) / CR-050 (CLI → LLM 어댑터 무력화 차단)
- **영향 설계서**: `docs/T3-1_*` 도구 SDK 섹션 / `docs/guides/aimbase-sdk-guide.md` (직렬화 규칙 신규)
- **요청자**: 사용자 (CR-050 Phase 9 후속 보고 기반) | **승인자**: sykim (2026-04-26 옵션 A 확정) | **적용 버전**: v8.5.0 (예약)
- **변경 일자**: 2026-04-26
- **범위 경계**:
  - `ToolExecutor` 인터페이스 자체 변경(`execute(Map)` 시그니처 수정/추가) — **본 CR 범위 제외** (하위 호환 깨짐, 별도 CR)
  - MCP 응답 안전 가드 (PII redaction / size cap 동적 정책) — **본 CR 범위 제외** (현재는 `McpResultTruncator` 고정값)
  - `auditPayload` 에 MCP 호출 메타 기록 — **Phase 2 분리**
  - `output` 직렬화 시 cycle reference / lazy proxy 안전 처리 — **현재는 Jackson 기본 동작 + 직렬화 실패 시 toString 폴백** (필요 시 별도 CR)
- **Sprint 배치 (예상 2.5MD — 정공 풀 코스)**:
  - Phase 0 — 옵션 결정 (옵션 A 확정) + 본문 직렬화 규칙 합의 (완료, 본 카드)
  - Phase 1 — `ToolResultRenderer` 신규 + 단위 테스트 11 케이스 (1.0MD)
  - Phase 2 — `EnhancedToolExecutor.execute(Map)` default bridge 수정 + bridge 단위 테스트 (0.25MD)
  - Phase 3 — 비-MCP 호출처 4곳 영향 분석 + 회귀 단위/IT PASS 확인 (0.5MD)
  - Phase 4 — `AgentMcpServerTest` 4 케이스 신설 + `AdapterToolLoopComparisonIT` 재실측 (0.5MD)
  - Phase 5 — `aimbase-sdk-guide.md` 직렬화 규칙 섹션 신설 + 변경 이력 패치 + 메모리 갱신 (0.25MD)
- **착수 시점 판단 기준**: CR-050 어댑터의 실용 가치를 살리려면 **즉시 착수**
- **완료 기준**:
  - [x] `ToolResultRenderer` 단위 테스트 12 케이스 PASS (2026-04-26)
  - [x] `EnhancedToolExecutor` default bridge 가 본문 직렬화 반환 (`ToolResultRenderer.render` 통과)
  - [x] bridge 호출처 정밀 분석: 핫스팟 = `AgentMcpServer:126` + `ToolRegistry:129` + `ToolCallStepExecutor:70`. 회귀 점검 결과 모두 의도된 본문 노출 변경 + `HttpRequestToolTest` 등 단위 회귀 PASS
  - [x] `AgentMcpServerTest` 5 케이스 PASS (Enhanced 본문 / legacy String 회귀 / 비즈니스 에러 [ERROR] / 런타임 예외 isError / truncator)
  - [x] **stdio MCP 직접 호출**로 결함 해소 결정적 검증: `builtin_file_read` 가 `# /path (7줄)\n1\tpackage ...` 형태로 본문+메타 노출 (이전엔 메타만)
  - [x] `AdapterToolLoopComparisonIT` 풀 IT 재실측 (2026-04-26 18:20~25, 4m 30s, BUILD SUCCESSFUL) — **CLI 가 6개 클래스 정확 식별 + 협력 흐름 작성으로 작업 완수** (CR-050 1차 실측의 "환경 제약" 거부와 정반대). API 와 결과 품질 동등. CLI 4.4× 빠름·비용은 CR-050 1차 $0.602 → $0.250 으로 절반 감소
  - [x] `aimbase-sdk-guide.md` § 7 직렬화 규칙 섹션 추가 + v1.2.0 패치
  - [x] CR-050 메모리 갱신 (CR-067 해소 표기)
- **원본 요구사항**: `docs/origins/원본_요구사항_CR067_EnhancedToolExecutor_MCPbridge_20260426.md`
- **T3 설계서**: 없음 (버그수정 + 핫픽스 규모, CR 카드 + 원본 요구사항 본문으로 대체)
- **Plan 파일**: 없음 (Phase 별로 본 카드 직접 참조)

---

### CR-068 | API 어댑터 도구 호출 회귀 5종 정공 + CLI 통제 정렬 (작년 4월 동등 회복 + 어댑터 동등성)

- **대상 기능 ID**: PRD-300 (CR-048 deferred tool), PRD-298 (CR-047), PRD-CR054 (HttpRequestTool), PRD-CR036 (시스템 프롬프트)
- **변경 타입**: 버그수정
- **발견 경위**:
  - 작년 4월 (`benchmark_1775750513.json`) OpenClaude 비교 벤치마크에서 Aimbase API 가 T2 (파일 읽기, in=19,315) / T3 (소스 분석, in=19,760) 모두 정확한 답변으로 정상 처리한 이력
  - 2026-04-26 사용자가 같은 벤치마크 재실행 요청 → T2/T3 가 in=974/8099 로 급감, 응답 환각 ("Sprint 7" — 실제 51) 또는 빈 응답
  - 두 어댑터 비교 (`run_adapter_compare.py`) 에서 API 어댑터가 `<tool_call>` XML 텍스트로 도구 흉내 + 가짜 파일명 환각, CLI 어댑터는 [CLI-OBS] 11회 정상 도구 호출
  - 4단계 디버그 로그 추가로 결함 4개 순차 발견:
    1. `ToolCallHandler.executeLoop` 에서 `rawDefs=48 filteredTools=5` — `SessionToolRegistry.filterActive` 가 도구 schema 좁힘
    2. AnthropicAdapter 호출 시 `tools.21.custom.input_schema: JSON schema is invalid` 400 에러 — `HttpRequestTool` body type 오타
    3. `[ANTHROPIC-RAW] stop_reason=tool_use content_blocks=[tool_use:builtin_glob, ...]` — API 가 진짜로 도구 호출 중인데 `actions_executed=[]` 반환
    4. `core.using_tools.prompt` (DB) 에 anti-hallucination 지시문 0건 — 작년 시스템 프롬프트의 "CRITICAL: Do NOT guess or fabricate" 핵심 지시 누락
- **결함 5종** (4종 + CLI 통제 누락):
  1. **`SessionToolRegistry` (CR-048 회귀)**: `DEFAULT_ACTIVE` 7개 + `NAME_ALIASES` 미흡으로 첫 턴 도구 schema 가 5개로 좁혀짐. 모델이 탐색 도구 (Glob 등) 못 받아 환각 답변. 작년 4월에는 deferred 메커니즘 자체가 없어 모든 도구 전달
  2. **`HttpRequestTool` (CR-054 오타)**: `body` 속성 type 이 `Object.class.getSimpleName()` = `"Object"` (대문자, JSON Schema 위반). draft 2020-12 는 lowercase `"object"` 또는 multi-type 배열만 허용 → Anthropic API 가 모든 도구 schema 호출 자체 거부 (400)
  3. **`core.using_tools.prompt` (CR-036 외부화 시 누락)**: 작년 하드코딩 `TOOL_USAGE_PROMPT` 의 "CRITICAL: Do NOT guess or fabricate file names. You MUST use the EXACT file names returned by Glob/Grep" 같은 강한 anti-hallucination 지시문이 외부화 시 빠짐. 모델이 프롬프트의 도구 사용 톤을 약하게 인식
  4. **`OrchestratorEngine` actions_executed 하드코딩**: line 438 `List.of()` 로 항상 빈 리스트 반환. 모델이 도구를 진짜 11회 호출했어도 응답 메타에 미반영 → 사용자/벤치마크가 "도구 0회" 로 오해
  5. **CLI 어댑터 통제 누락 (CR-050 SYSTEM prepend 한계)**: ClaudeCliWorker 가 우리 SYSTEM 메시지를 첫 user 메시지 앞에 prepend → CLI 본체의 Claude Code 기본 system prompt 가 우세하고 우리 prompt 는 user 명령으로만 인식. 같은 sonnet 4.6 모델인데 도구 호출 빈도/응답 길이/톤이 API 어댑터와 비대칭 (CLI 가 더 공격적·장문). `--append-system-prompt` flag 미사용
- **변경 내용**:
  1. `SessionToolRegistry.DEFAULT_ACTIVE` 에 `Glob/PathInfo/WorkspaceSnapshot` 추가 (10개) + `NAME_ALIASES` 에 `builtin_*` prefix 모두 매핑
  2. `ToolCallHandler.executeLoop` 의 `filterActive` 호출 제거 → 모든 도구 schema 를 LLM 에 전달 (작년 4월 동등). CR-048 deferred 의도와 충돌하지만 환각 부작용이 더 큼
  3. `HttpRequestTool` body type: `Object.class.getSimpleName()` → `List.of("object","array","string")`
  4. `prompt_templates.core.using_tools.prompt` UPDATE: "Do NOT guess or fabricate file names", "Do NOT answer about workspace from prior knowledge alone — verify by Read/Glob/Grep first" 등 강한 anti-hallucination 지시문 복원
  5. `ToolCallHandler` 에 ThreadLocal action tracking (`beginActionTracking` / `recordAction` / `drainActionTracking`) + `OrchestratorEngine.chat()` 에 begin/drain 끼워 ChatResponse.actions_executed 채움
  6. **CLI 통제 정렬**: `ClaudeCliWorker` 에 `systemPromptOverride` 필드 + `setSystemPromptOverride()` setter + `buildCommand` 에 `--append-system-prompt` flag 추가. `ClaudeCliWorkerPool.getOrCreateMain` 시그니처에 systemPrompt 인자 추가. `ClaudeCliLlmAdapter.chat()` 가 SYSTEM 메시지를 모아 (`collectSystemPrompt`) Pool 에 전달. CLI 본체 Claude Code prompt 끝에 우리 prompt append → CLI 의 environment 정보(cwd, env, git) 유지하면서 Aimbase 톤·도구 가이드·anti-hallucination 강제. (`--system-prompt` 완전교체는 cwd 정보까지 사라져 CLI 가 워크스페이스 인식 못 함 → 기각)
  7. 단위 테스트 9 케이스 신설 (`SessionToolRegistryTest` 4 + `ToolCallHandlerActionTrackingTest` 5)
- **변경 사유**:
  - 작년 4월 정상 → 오늘 환각/메타 누락 회귀 — 사용자 직접 지적 ("작년에는 도구 호출했다며요 그걸로 테스트해보면 되잖아요")
  - 4개 결함이 누적돼 사용자에게 "도구 안 부른 환각 답변" 으로 노출됨. 정공으로 모두 제거
- **영향 모듈**:
  - **BE 핵심 수정**:
    - `tool/registry/SessionToolRegistry.java` (DEFAULT_ACTIVE + NAME_ALIASES 확장)
    - `tool/ToolCallHandler.java` (filterActive 제거, ThreadLocal action tracking 추가)
    - `orchestrator/OrchestratorEngine.java` (begin/drain action tracking, actions_executed 채움)
    - `tool/builtin/HttpRequestTool.java` (body type 오타 수정)
    - `llm/claudecli/ClaudeCliWorker.java` (systemPromptOverride + --append-system-prompt flag + user prepend 회피)
    - `llm/claudecli/ClaudeCliWorkerPool.java` (getOrCreateMain systemPrompt 인자 추가)
    - `llm/adapter/ClaudeCliLlmAdapter.java` (collectSystemPrompt 헬퍼 + Pool 호출 시 전달)
  - **DB**: `prompt_templates.core.using_tools.prompt` UPDATE (tenant DB)
  - **테스트**: `SessionToolRegistryTest` (4) + `ToolCallHandlerActionTrackingTest` (5)
  - **FE/사이드카**: 없음
- **영향도**: High (도구 호출 회귀 = 응답 품질 회귀 + actions_executed 잘못된 메타)
- **영향 범위**: CR-048 (deferred tool — filterActive 제거), CR-054 (HttpRequestTool — schema 오타 직접 수정), CR-036 (프롬프트 외부화 — using_tools 보강), CR-029 (도구 등록), CR-067 (직전 작업, 같은 경로 추적 중 발견)
- **검증 결과 (2026-04-26 22:48, 8080 서버 + 결함 5종 모두 수정 + run_adapter_compare.py)**:
  - 수정 전: API tools=0, in=974/8099, `<tool_call>` XML 텍스트 환각, 가짜 파일명. CLI 는 자체 학습 패턴으로 도구 11회·1500토큰 장문 응답 (API 와 비대칭)
  - 수정 후: **API tools=11/7, CLI 도구 정상 + Aimbase 통제 톤 강제**, 양쪽 정확한 클래스 6개 식별, 모델 행동 동등화
  - CLI 응답에 `--system-prompt`/BIZ-100/CR-047~068 같은 우리 prompt 컨텍스트 인식 — 통제가 작동
  - `--system-prompt` (완전교체) → CLI 의 cwd/env 사라져 워크스페이스 인식 실패 → `--append-system-prompt` 채택 (CLI environment 유지 + Aimbase 톤 추가)
  - 단위 9 PASS, HttpRequestToolTest 16 PASS, CR-067 단위 19 PASS (회귀 없음)
- **요청자**: 사용자 (CR-067 IT 어댑터 비대칭 의문 추적 중 발견) | **승인자**: sykim (2026-04-26 "정공") | **적용 버전**: v8.5.1
- **변경 일자**: 2026-04-26
- **범위 경계**:
  - CR-048 deferred tool 메커니즘 자체 재설계 (sessionId 있을 때 적절한 활성 세트 동적 조정) — 본 CR 범위 제외 (별도 CR)
  - `executeLoopStream` (스트리밍 경로) action tracking — 본 CR 범위 제외 (스트리밍 시 도구 사용 시점 별도 SSE 이벤트로 노출되므로 actions_executed 누적 불요)
  - 도구별 schema 전수 검증 자동화 (CI 게이트) — 본 CR 범위 제외 (별도 CR 권장)
  - 시스템 프롬프트 길이 압축 (19K → 작년 수준) — 본 CR 범위 제외 (CR-036 외부화 의도 충돌)
- **완료 기준**:
  - [x] DEFAULT_ACTIVE + NAME_ALIASES 확장 (SessionToolRegistry)
  - [x] ToolCallHandler filterActive 제거 + 모든 도구 schema 전달
  - [x] HttpRequestTool body type 오타 수정
  - [x] prompt_templates.core.using_tools.prompt 보강
  - [x] OrchestratorEngine actions_executed ThreadLocal 누적 패턴
  - [x] 단위 9 PASS + HttpRequestToolTest 16 회귀 PASS
  - [x] run_adapter_compare.py 로 API tools=11/7 실측 + 응답 정확성 검증
- **원본 요구사항**: 본 카드 「발견 경위」 섹션 내장
- **Plan 파일**: 없음

---

### CR-069 | Claude CLI 호출 공통 빌더 — Worker / ClaudeCodeTool 잠금 정책 통일

- **대상 기능 ID**: PRD-340 (신규 — Aimbase CLI 호출 정책 단일 진실 소스)
- **변경 타입**: 변경
- **발견 경위**:
  - 사용자 지적: "어차피 둘 다 Claude CLI 사용. 둘 다 Aimbase 도구 사용하길 원함. 차이가 없어야 정상"
  - CR-067/068 작업 중 ClaudeCliWorker 의 잠금 정책 (strict-mcp-config, bypassPermissions, --tools "" sealing) 정착
  - ClaudeCodeTool (CR-044) 도 같은 CLI 호출하지만 일부 잠금 누락 (`--strict-mcp-config` 0건, default permission_mode null) → 다른 개발자도 같은 함정 빠질 위험 + 디버깅 비용 두 배
- **결함**:
  - **공통 정책 분산**: Worker 와 ClaudeCodeTool 이 같은 CLI 를 다른 정책으로 호출. 향후 CLI flag 변경 시 두 곳 따로 수정 필요. 한 쪽만 잠금 적용되면 다른 쪽이 침범 통로
  - **에이전트 배포 시 모드 외부화 부재**: 에이전트가 "Aimbase 도구만" / "CLI 본체 도구만" 사용 모드 결정을 코드 변경 없이 환경변수로 못 함
- **변경 내용**:
  1. **`ClaudeCliCommandBuilder` 신설** (`platform-core/llm/claudecli`): fluent API 로 CLI 인자 조립. `ToolMode` 단일 스위치 (AIMBASE / NATIVE / HYBRID) 로 도구 노출 정책 결정. 잠금 정책 (strict-mcp-config, permission-mode bypassPermissions, --tools "" sealing) 빌더 내부 default
  2. **`ClaudeCliAdapterConfig.toolMode`** 신규 필드 + getter/setter + `resolveToolMode()` enum 변환 헬퍼. application.yml 의 `platform.llm.anthropic-cli.tool-mode` (default `aimbase`) 외부화. 환경변수 `ANTHROPIC_CLI_TOOL_MODE` 로 에이전트 배포 시 override
  3. **`ClaudeCliWorkerPool`** 에 `defaultToolMode` 필드 + 신규 생성자 + `getOrCreateMain(runId, model, configDir, systemPrompt, toolMode)` 오버로드. AdapterConfig 가 default 주입, 호출처가 명시 시 override 가능
  4. **`ClaudeCliWorker.buildCommand`** 교체 — 직접 cmd.add 호출 → `ClaudeCliCommandBuilder.builder(...).build()` 사용. `setToolMode()` setter 추가 (Pool 이 spawn 전 호출)
  5. **`ClaudeCodeTool`** 보강 — `tool_bridge=aimbase-mcp-only` 시 `permission_mode` default 가 `bypassPermissions` 자동 적용. `--mcp-config` 가 하나라도 있으면 `--strict-mcp-config` 동반 주입 (Worker 와 동일 잠금). handledOptions 에 `--strict-mcp-config` 추가
  6. **단위 테스트 13 케이스** (`ClaudeCliCommandBuilderTest`): default AIMBASE 잠금 / Worker 시나리오 / ClaudeCodeTool 시나리오 / NATIVE / HYBRID / explicit 도구 리스트 / permissionMode override / strictMcpConfig 비활성 / system prompt 동시 적용 / resume+fork 순서 / sealNativeTools=false / toolsSpec explicit
- **변경 사유**:
  - 잠금 정책의 단일 진실 소스 — CLI flag 변경 시 빌더 한 곳만 수정
  - 에이전트별 도구 노출 모드 외부화 (환경변수)
  - ClaudeCodeTool 의 잠금 누락 (strict-mcp-config 0건) 보강 — 다른 개발자가 같은 함정 안 빠짐
- **영향 모듈**:
  - **BE 신규**: `llm/claudecli/ClaudeCliCommandBuilder.java`
  - **BE 수정**: `ClaudeCliAdapterConfig` (toolMode 필드) / `ClaudeCliWorker` (buildCommand 빌더 사용) / `ClaudeCliWorkerPool` (defaultToolMode + 오버로드) / `ClaudeCodeTool` (잠금 보강)
  - **설정**: `application.yml` 의 `platform.llm.anthropic-cli.tool-mode` 키 신규
  - **테스트**: `ClaudeCliCommandBuilderTest` (신규, 13 케이스)
  - **FE/DB/사이드카**: 없음
- **영향도**: Medium (cmd 빌더 추출 — 잠금 정책 동작은 동일, ClaudeCodeTool 의 strict-mcp-config 누락만 신규)
- **영향 범위**: CR-050 (Worker buildCommand) / CR-068 (--append-system-prompt 통합) / CR-044 (ClaudeCodeTool tool_bridge — 잠금 보강)
- **검증 결과 (2026-04-27 06:50, ANTHROPIC_CLI_TOOL_MODE=aimbase + run_adapter_compare.py)**:
  - 빌더가 만든 cmd 정확: `[claude, -p, --verbose, --input-format, stream-json, --output-format, stream-json, --tools, "", --strict-mcp-config, --mcp-config, {...}, --permission-mode, bypassPermissions, --append-system-prompt, ...]`
  - API tools=11/7, CLI 도구 정상 사용 — CR-067/068 동등성 유지
  - CLI 응답이 ClaudeCliCommandBuilder 인식: "ToolMode(AIMBASE/NATIVE/HYBRID) 단일 스위치로 도구 노출 정책... Worker와 ClaudeCodeTool 양쪽이 공유"
  - 단위: ClaudeCliCommandBuilderTest 13 PASS + Worker/Pool/Adapter 회귀 PASS + ClaudeCodeTool 컴파일 PASS
- **요청자**: 사용자 ("Tool 거 보고 구현하면 될 것을 우리가 생고생한 건가요?" → 공통화 정공 결정) | **승인자**: sykim (2026-04-27) | **적용 버전**: v8.5.2
- **변경 일자**: 2026-04-27
- **범위 경계**:
  - ClaudeCodeTool 의 buildCommand 전체를 빌더로 대체 — 본 CR 범위 제외 (json-schema, max-budget-usd, fallback-model, cli_options, --add-dir 등 ClaudeCodeTool 고유 옵션 다수, 빌더 시그니처 비대화. 잠금 정책만 통일)
  - tool-mode 의 런타임 동적 변경 (global_config) — 본 CR 범위 제외 (에이전트 배포 시점 결정으로 충분)
  - HYBRID 모드의 정밀 화이트리스트 (어떤 네이티브 도구 허용/차단) — 본 CR 범위 제외 (호출처가 allowedTools/disallowedTools 명시)
- **완료 기준**:
  - [x] ClaudeCliCommandBuilder 신설 + 단위 13 PASS
  - [x] ToolMode application.yml 외부화 (`tool-mode` 키 + ANTHROPIC_CLI_TOOL_MODE 환경변수)
  - [x] ClaudeCliWorker.buildCommand 빌더 사용 + Worker/Pool 회귀 PASS
  - [x] ClaudeCodeTool 잠금 정책 보강 (--strict-mcp-config 자동 주입 + bypassPermissions default)
  - [x] 어댑터 비교로 빌더 출력 정확성 + CR-067/068 동등성 유지 검증
- **원본 요구사항**: 본 카드 「발견 경위」 섹션 내장
- **Plan 파일**: 없음

---

### CR-070 | ClaudeCodeTool 실시간 스트리밍 중계 보강

- **변경 ID**: CR-070
- **변경 타입**: 변경
- **영향도**: Medium
- **적용 버전**: v8.5.3
- **상태**: 📝 발번
- **변경 일자**: 2026-04-27

#### 발견 경위

Claude 사용 3가지 방식((1) API / (2) CLI 어댑터 / (3) ClaudeCodeTool) 비교 과정에서 정액 플랜 ToS 경계 식별. (2)는 서버에서 외부 테넌트에 노출 시 "정액 플랜 API 재판매" 형태로 ToS 위반 소지가 큰 반면, (3)은 사용자 PC aimbase-agent 실행이라 안전. (3)이 표준 경로로 결정되었고, 사용자 PC Agent 실행 모델("두뇌도 사용자 PC, 손발도 Aimbase Tool")의 UX 완성을 위해 실시간 스트리밍 중계가 필요.

#### 변경 내용

**Phase A — 로컬 서버 실행 케이스 (당장 가치 큼):**
1. `ClaudeCodeTool.java:448-455` stdout 처리를 라인 단위 스트림으로 변경 (현재 통째로 누적 → 중간 이벤트 폐기)
2. `STREAM_SINK` ThreadLocal 패턴을 ClaudeCodeTool에 적용 (SubagentRunner와 동일 메커니즘)
3. ClaudeCliWorker의 `drainStdout()` 패턴 차용 (이미 라인 단위 NDJSON 처리 구현됨)

**Phase B — Agent 실행 케이스:**
4. Agent → 서버 진행 이벤트 push 채널 추가 (현재 동기 응답만 존재)
5. 서버가 받은 이벤트를 ChatController SSE로 중계

#### 참조 코드

- `ClaudeCodeTool.java:448-455` (현재 stdout 누적 지점)
- `ClaudeCodeTool.java:492-493` (`--output-format stream-json` 옵션)
- `ClaudeCliWorker.java:521-540` (라인 단위 스트림 모범)
- `SubagentRunner.java:45-64` (STREAM_SINK 패턴 모범)
- `ChatController.java:142-227` (사용자 SSE 출력 채널)

#### 영향 범위

- CR-042 (aimbase-agent 모듈), CR-044 (CLI 두뇌 + Aimbase 손발), CR-053 (서브에이전트 SSE)
- ClaudeCodeTool 호출처 (워크플로우 LLM_CALL, 직접 호출 등) — 스트리밍 사용은 옵션, 미사용 시 기존 동작 유지

#### 완료 기준

- [ ] Phase A.1 — ClaudeCodeTool stdout 라인 단위 reader로 변경
- [ ] Phase A.2 — `STREAM_SINK` ThreadLocal 적용 + 중간 이벤트 emit
- [ ] Phase A.3 — 단위 테스트 (스트림 이벤트 발행 검증)
- [ ] Phase B.1 — Agent → 서버 push 채널 설계
- [ ] Phase B.2 — 서버 수신 → ChatController SSE 중계
- [ ] Phase B.3 — 통합 테스트 (Agent 실행 시 사용자 SSE 수신 확인)
- [ ] 회귀 테스트 (CR-067/068 동등성 유지)

#### 범위 외

- 토큰 사용량·도구 호출 감사 로깅 보강 (후속 CR)
- Agent의 Claude 키 인지 문제 (별건 작업, CR-070 다음 진행 예정)
- (2) Claude CLI 어댑터 격리 (상용화 시점에 별도 처리)

#### 원본 요구사항

`docs/origins/원본_요구사항_CR070_ClaudeCodeTool_스트리밍중계_20260427.md`

#### Plan 파일

미작성 (Phase 별 작업 시작 시 생성)

#### 요청자/승인자

- **요청자**: 사용자 (대화)
- **승인자**: sykim
- **적용 버전**: v8.5.3

---

### CR-071 | ClaudeCliAdapter — 3경로 통일 (API / CLI어댑터 / ClaudeCodeTool)

- **변경 ID**: CR-071
- **변경 타입**: 변경
- **영향도**: High
- **적용 버전**: v8.6.0
- **상태**: ✅ 구현 완료 (2026-04-27, Phase 1~5)
- **설계서**: `docs/T3-12_CR-071_ClaudeCliAdapter_설계서.md`
- **변경 일자**: 2026-04-27
- **커밋 이력**: 3a85453 (Phase 1) → df168f8 (Phase 2) → 2554b5a (Phase 3) → 3878095 (Phase 4) → Phase 5 (가이드 + 본 항목)

#### 발견 경위

CR-070 진행 중 "Claude에게 작업 시키기"가 3개 경로((1) API / (2) CLI 어댑터 / (3) ClaudeCodeTool)로 분산되어 있다는 구조적 문제를 사용자가 지적. 인터페이스 3개 → 거버넌스(정책/Hook/max_iter/감사)가 경로마다 부분 적용. ToS 경계는 "어디서 CLI를 띄우는가"의 문제이지 "무엇을 호출하는가"가 아니므로, CLI 실행 주체(`ClaudeCliRunner`) 위치를 connection 설정으로 자유 배치하면 자연 해결됨. ClaudeCodeTool은 "ToS 우회로"라는 본질이라 도구가 아니라 LLM 어댑터로 재배치되어야 함.

#### 변경 내용

**핵심**: 모든 Claude 호출을 LLMAdapter 단일 인터페이스로 모은다. CLI를 띄우는 위치(=`ClaudeCliRunner`)를 connection 단위로 자유 배치.

```
OrchestratorEngine
  ├─ AnthropicAdapter        (Anthropic REST API — 기존)
  ├─ OpenAIAdapter
  └─ ClaudeCliAdapter        ← 이번 CR (HTTP로 Runner 호출)
       └─ ClaudeCliRunner HTTP API
            ├─ POST /v1/chat
            ├─ POST /v1/chat/stream  (NDJSON / SSE)
            └─ POST /v1/cancel
```

**명명 체계**:
- `ClaudeCliAdapter`: Aimbase 서버 in-process LLMAdapter 구현체. Runner를 HTTP로 호출.
- `ClaudeCliRunner`: 별도 프로세스 (서버/사용자 PC). HTTP 서비스. 안에서 `ClaudeCliWorker`로 CLI 실행.
- `ClaudeCliWorker` (CR-050) / `ClaudeCliCommandBuilder` (CR-069): Runner 내부 구현으로 이동.

**네이밍 정책**: API/CLI 어댑터 대칭 — `AnthropicAdapter`(REST API) vs `ClaudeCliAdapter`(CLI). 향후 OpenAI Codex / Gemini CLI는 별도 어댑터(`CodexCliAdapter`, `GeminiCliAdapter`)로 추가. 공통 추상화는 YAGNI.

**Runner 배치**:
- 내부/벤치마크: 서버 자체 (localhost:8290)
- 운영: 사용자 PC (aimbase-agent, 8190)
- connection.runner-url 정적 매핑

**CLI 모드 (CR-069 ToolMode 재활용)**: `AIMBASE` / `NATIVE` / `HYBRID` — connection 단위 선택.

**주요 작업**:
1. `ClaudeCliAdapter` 신설 (LLMAdapter 구현체)
2. `ClaudeCliRunner` HTTP API 명세 (`/v1/chat`, `/v1/chat/stream`, `/v1/cancel`)
3. aimbase-agent에 `--runner-mode` 추가 (LLM 호출 endpoint 노출)
4. `ClaudeCliLlmAdapter` (CR-050) → `ClaudeCliAdapter`로 진화 (이름 단축, 서버 in-process → HTTP 클라이언트)
5. `ClaudeCliWorker/Pool` (CR-050) + `ClaudeCliCommandBuilder` (CR-069) → Runner 내부 구현으로 이동
6. `ClaudeCodeTool` (CR-044) deprecation 마킹 + 후속 제거 계획

#### 영향 범위

- CR-042 (aimbase-agent), CR-044 (ClaudeCodeTool), CR-050 (ClaudeCliLlmAdapter), CR-069 (ToolMode/CommandBuilder), CR-070 (스트리밍 중계)
- BIZ-099 (정액 플랜 ToS 격리) / BIZ-100 (CLI 워커 상한) 재정의 — Runner 위치 기준으로 재서술
- 워크플로우 LLM_CALL 호출처 — connection 설정만 바꿔 ClaudeCliAdapter로 라우팅

#### 완료 기준

- [x] T3 설계서 작성 (`docs/T3-12_CR-071_ClaudeCliAdapter_설계서.md`)
- [x] `ClaudeCliRunner` HTTP API 명세 확정 (스트림/인증/취소)
- [x] aimbase-agent `--runner-mode` 골격 (LLM 호출 endpoint)
- [x] `ClaudeCliAdapter` 신설 + LLMAdapter 등록
- [x] `ClaudeCliLlmAdapter` 즉시 삭제 (단계적 deprecation 생략 — 운영 미사용)
- [x] `ClaudeCodeTool` 즉시 삭제 (단계적 deprecation 생략)
- [x] 단위 테스트 (RunnerController 11 + RequestContext 6 + AgentIdRequestFilter 5 + ClaudeCliAdapter 8 = 30 PASS)
- [ ] 통합 테스트 (서버 Runner + 사용자 PC Runner 양 시나리오) — Phase 5 후속 (실제 CLI 환경 필요)

#### 결정 사항 (2026-04-27 사용자 확정)

- **인증**: `X-Api-Key` 헤더 (FlowGuard/Aimbase 기존 패턴 재활용). mTLS/JWT는 후속 강화로 보존.
- **NAT 통과**: 기존 STUN/TURN + AgentRegistry 인프라 재활용 (CR-041/CR-042). 신규 작성 없음.
- **라우팅**: `X-Aimbase-Agent-Id` 요청 헤더 필수. 호출자(소비자앱/Claude Code/기타 MCP 도구)가 명시. 누락 시 400 에러. 자동화(로컬 토큰/IP 보조/디스커버리)는 별도 CR.
- **스트리밍**: 기존 SSE + STREAM_SINK + NDJSON 자산 재활용. 4단 파이프(ChatController SSE → Adapter → Runner NDJSON → CLI stream-json).

#### 범위 외

- 라우팅 자동화 — 로컬 토큰 자동 주입, IP 보조 매칭, 로컬 디스커버리 (별도 CR)
- 자체 소비자앱 SDK 자동 헤더 주입 (별도 CR)
- mTLS/JWT 보안 강화 (후속)
- OpenAI Codex / Google Gemini CLI 어댑터 (별도 후속 CR)
- **워크플로우 PARALLEL 스텝의 CLI 세션 fork/branch 라이프사이클** — `ClaudeCliWorkerPool.getOrSpawnBranchWorker / spawnForkedWorker / releaseBranchWorker / releaseForkedWorker` 는 cli-runner 모듈에 자산 그대로 보존되어 있으나, RunnerController API 로 노출되지 않았고 `ParallelStepExecutor` 의 `ClaudeCliBranchScope` 의존도 Phase 1 에서 제거됨. 영향: PARALLEL 스텝 안에서 `anthropic-cli` connection 사용 시 분기마다 같은 CLI 세션이 fork 되지 않고 별도 메인 워커가 사용됨 (느려지거나 맥락 분리). 시드 워크플로우 중 사용 사례 없음 + anthropic-cli 자체가 운영 미사용 → 실사용 영향 없음. 별도 CR 발번 (필요 시점에 메꾼다)

#### 원본 요구사항

`docs/origins/원본_요구사항_CR071_ClaudeCliAdapter_3경로통일_20260427.md`

#### Plan 파일

T3 설계서: `docs/T3-12_CR-071_ClaudeCliAdapter_설계서.md` (사용자 승인 2026-04-27).
Phase별 plan 파일은 각 Phase 시작 시 `~/.claude/plans/cr-071-phase-{n}.md` 로 생성.

#### 요청자/승인자

- **요청자**: 사용자 (대화)
- **승인자**: sykim
- **적용 버전**: v8.6.0
- **설계 승인**: 2026-04-27

---

### CR-072 | Aimbase 서버 도구 MCP endpoint 노출 (CLI 직접 호출 경로)

- **변경 ID**: CR-072
- **변경 타입**: 신규
- **영향도**: High
- **적용 버전**: v8.7.0
- **상태**: 📝 발번 (2026-04-27)
- **변경 일자**: 2026-04-27
- **관련 CR**: CR-041 (Agent Registry), CR-042 (aimbase-agent), CR-044 (CLI 두뇌 + Aimbase 손발), CR-071 (ClaudeCliAdapter — 3경로 통일)

#### 발견 경위

CR-071 마무리 단계에서 사용자 지적. CLI(Claude Code 등) 가 동작하는 동안 사용자 PC `aimbase-agent` 에 있는 **SDK 도구 14개**(FileRead/Bash/Glob/Grep 등) 외에 **Aimbase 서버에 있는 도구 30+개**(WebSearch / HttpRequest / SendMessage / ScheduleCron / NotebookEdit / LSP / Skill / Task* / Team* / RemoteTrigger / Brief / ImageAnalysis / Translation / SuggestBackgroundPR 등) 도 호출할 수 있어야 자연스럽다. 현재 `AimbaseMcpConfigGenerator` 가 생성하는 `--mcp-config` 는 `aimbase-agent --mcp-stdio` 1개만 박고 있어, CLI 가 서버 도구를 직접 호출할 채널이 없음.

#### 변경 내용 (개요)

1. **Aimbase 서버 측 MCP endpoint 신설** — `/mcp/sse` (Spring AI MCP server) 또는 stdio bridge 모드. 서버 ToolRegistry 에 등록된 도구를 MCP 도구로 자동 노출.
2. **AimbaseMcpConfigGenerator 확장** — 다중 `mcpServers` 박기:
   ```json
   {
     "mcpServers": {
       "aimbase-local":  { "command": "java", "args": ["-jar", "aimbase-agent.jar", "--mcp-stdio"] },
       "aimbase-server": { "url": "https://...../mcp/sse",
                           "headers": {"X-API-Key": "...", "X-Aimbase-Agent-Id": "..."} }
     }
   }
   ```
3. **권한/테넌트 격리** — 서버 MCP endpoint 가 X-API-Key + X-Aimbase-Agent-Id 헤더로 호출 컨텍스트(테넌트/사용자) 식별 → 도구 실행 시 TenantContext 적용. 잘못된 헤더는 401/403.
4. **도구 노출 정책** — 모든 30+ 도구를 무차별 노출하지 않고, "CLI 안에서 호출 가능한 도구" 화이트리스트 도입 (예: `mcp.cli-exposed-tools` 설정 또는 도구 메타데이터 플래그). 서버 측 관리 도구(예: TenantCreate 등)는 제외.
5. **이름 충돌 회피** — `mcp__aimbase-local__file_read` vs `mcp__aimbase-server__web_search` 처럼 prefix 분리로 자동 해결. 같은 이름의 도구가 양쪽에 있으면 정책 결정 필요 (서버 우선 / agent 우선 / 명시 prefix).
6. **Aimbase 서버 자체 OrchestratorEngine 도구 루프와의 일관성** — 같은 도구가 in-process(서버 직접 실행) 와 MCP(CLI → MCP → 서버 실행) 양쪽으로 호출 가능. 결과/감사 로그 동일 채널로 집계.

#### 후속 논의 키워드

- **MCP 서버 구현 방식**: Spring AI `mcp-spring-webmvc` 활용? 또는 자체 구현?
- **인증 흐름**: CLI 사이드 → 서버 MCP endpoint 호출 시 헤더 그대로 전달되는가? (Claude CLI MCP 클라이언트의 헤더 처리 검증 필요)
- **도구 화이트리스트 정책**: 자동 노출 vs 명시 등록 vs 도구 메타데이터(`@McpExposable` 등)
- **AIMBASE/NATIVE/HYBRID ToolMode 와의 관계** — HYBRID 모드에서 다중 MCP 서버 의미 재정리
- **TURN/STUN 경유 호출** (사용자 PC ↔ Aimbase 서버 통신 — 보통 서버는 공인 IP라 큰 문제 없음, 그러나 사용자가 사내망에서 SSE 연결 가능한지 검증)
- **워크플로우 SUB_WORKFLOW 와의 분리** — 서버 도구를 워크플로우 단계로 호출 vs CLI 안에서 직접 호출 — 두 경로의 의도 구분
- **CLI MCP 채널과 서버 OrchestratorEngine 도구 루프의 거버넌스 일관성** (정책 / max_iter / 감사 / Hook 적용)

#### 영향 범위

- `AimbaseMcpConfigGenerator` (다중 mcpServers 출력)
- 서버 측 신규 MCP Controller / `mcp-spring-webmvc` 의존
- ToolRegistry → MCP Tool 변환 어댑터 (이름 / 입력 스키마 / 출력 직렬화)
- `aimbase-api-guide.md` (MCP endpoint 문서화)
- `claudecode-mcp-setup.md` (다중 mcpServers 설정 예시 추가)

#### 완료 기준

- [ ] T3 설계서 작성 (`docs/T3-13_CR-072_Server_MCP_Endpoint_설계서.md`)
- [ ] 서버 `/mcp/sse` endpoint + 인증 + 테넌트 라우팅
- [ ] ToolRegistry → MCP 노출 어댑터 + 화이트리스트 정책
- [ ] AimbaseMcpConfigGenerator 다중 mcpServers 출력
- [ ] 단위 테스트 (MCP 핸들러 / 헤더 검증 / 도구 호출)
- [ ] 통합 테스트 (Claude Code 실 사용 — 서버 도구 호출)
- [ ] 가이드 문서 갱신 (api / claudecode-mcp-setup)

#### 범위 외

- 다른 CLI(Codex/Gemini) 의 MCP 클라이언트 호환성 (별도 후속)
- TURN/STUN 경유 SSE — 일반적으로 서버 측은 공인 IP라 불필요. 필요해지면 별도 CR
- ClaudeCliAdapter 의 in-process 도구 실행 폐기 (서버 도구 루프) — CR-072 결과에 따라 재정리

#### 원본 요구사항

CR-071 마무리 대화 (2026-04-27).
> "그런데 노출될일도 있을거잖아요.. 하다못해 claude code도 그렇잖아요"

→ CLI 가 서버 도구를 직접 호출하는 시나리오 (웹검색/외부 API/협업/스케줄/노트북 등) 가 자연스러운 사용 사례.

#### Plan 파일

미작성 (T3 설계서 진행 시 생성).

#### 요청자/승인자

- **요청자**: 사용자 (대화)
- **승인자**: sykim
- **적용 버전**: v8.7.0

---

### CR-074 | TURN-TCP (RFC 6062) ConnectionBind 모드 기반 NAT 우회 정공 구현

- **변경 ID**: CR-074
- **변경 타입**: 신규
- **영향도**: High
- **적용 버전**: v8.8.0
- **상태**: ✅ 구현 완료 + **정식 운영 e2e PASS** (2026-04-29) — 운영 서버 `59.8.160.12` (BE `aimbase-api-1` + coturn) → NAT 뒤 맥북 agent. BE 컨테이너 안에서 `curl http://<relay>/v1/health` → `200 OK {"status":"UP","active_runs":0,...}` 도달 확인. 핵심 누락 RFC 5766 §9 CreatePermission 추가 + `agent.turn.allowed-peer-ips` 필드 신설로 해결. 단위 15 PASS / 회귀 648 PASS
- **변경 일자**: 2026-04-29
- **설계서**: [T3-14_CR-074_TURN-TCP_NAT우회_설계서.md](T3-14_CR-074_TURN-TCP_NAT우회_설계서.md)
- **관련 CR**: CR-041 (Agent Registry / STUN·TURN 인프라), CR-042 (aimbase-agent), CR-071 (ClaudeCliAdapter — 3경로 통일), CR-072/073 (서버 MCP endpoint + agent Spring Boot 통합)

#### 발견 경위

CR-071/072/073 으로 ClaudeCliAdapter 3경로 통일과 서버 MCP endpoint 노출이 끝나면서, BE 가 NAT 뒤 사용자 PC 의 `aimbase-agent` 로 inbound TCP 호출을 보낼 경로가 정식으로 필요해짐. 현재 `AgentRegistryEntity.runnerEndpoint` 컬럼은 "공인 IP:포트" 가정으로 동작 — 실제 사용자 PC 가 NAT 뒤일 때는 도달 불가.

CR-041 단계에서 일부 스캐폴딩(`StunAddressResolver` / `TurnRelayClient`) 만 두고, 실제 BE→agent inbound 경로는 미해결로 남겨둔 상태였음. 사용자가 "WebSocket 상시 세션 부담을 피하고 필요할 때만 깨어나는 모델" 을 처음부터 원하셨고, 이번 CR 에서 해당 모델을 정공으로 완성한다.

#### 기존 스캐폴딩 점검 결과

- `TurnRelayClient` (sdk/tool-sdk-mcp): UDP DatagramSocket 기반 + `REQUESTED-TRANSPORT=17(UDP)` Allocate 만 구현. 현재 Aimbase 시나리오(BE→agent HTTP)에 부적합.
- `AgentLifecycle`: 결과를 `metadata.turnRelayAddress` 로 저장하지만 BE 측 사용처 없음 (dead path).
- `AgentRegistryEntity.runnerEndpoint`: BE 가 실제로 사용하는 컬럼. 여기에 **TURN 릴레이 주소 기반 HTTP base URL** 이 들어가도록 정렬해야 함.
- `ClaudeCliRunnerClient`: 일반 `HttpClient` 로 `URI.create(endpoint.runnerEndpoint() + "/v1/chat")` 호출. relay 주소가 그대로 들어가도 동작하는 구조.

#### 변경 내용 (개요)

1. **TurnTcpAllocator 신규** (`backend/sdk/tool-sdk-mcp`): TCP socket 기반 Allocate 전용 클라이언트.
   - `REQUESTED-TRANSPORT=6 (TCP)` Allocate 송신 (RFC 6062 §4.1)
   - `XOR-RELAYED-ADDRESS` 응답 파싱 → `String relayAddress`
   - control connection 은 close 하지 않고 **장기 유지** (Allocate lifetime refresh 포함)
2. **TurnConnectionBindHandler 신규** (`backend/sdk/tool-sdk-mcp`): control connection 위 `ConnectionAttempt` indication 처리 루프 (RFC 6062 §4.3).
   - indication 수신 → connection-id 추출 → TURN 에 새 TCP socket 수립 → `ConnectionBind` 송신
   - 응답 200 OK 후 해당 socket 을 agent 내부 HTTP 서버에 위임 (`Socket → ServerSocket-equivalent` 어댑터)
3. **AgentLifecycle 정렬**:
   - `turnRelayAddress` 별도 metadata key 폐기 → `metadata.runnerEndpoint = "http://<relay-ip>:<relay-port>"` 로 직접 등록
   - TURN 사용 시 `RunnerHttpServer` 가 일반 `ServerSocket.accept()` 대신 `TurnConnectionBindHandler` 가 push 하는 socket 을 처리하도록 어댑터 삽입
4. **`AgentConfig` 확장**: `turnTransport` 필드 (UDP|TCP, 기본 TCP), `turnLifetimeSeconds` (기본 600s, refresh 주기 = lifetime/2)
5. **BE 측 검증·문서화**:
   - `ClaudeCliAdapter` / `ClaudeCliRunnerClient` — 코드 수정 없음 (relay 주소 그대로 HTTP base URL 로 사용 가능 확인)
   - 가이드 갱신: `aimbase-ops-guide.md` (TURN 운영 / coturn 설정 예시), `aimbase-api-guide.md` (변경 없음 명시)
6. **e2e 시나리오**: NAT 뒤 agent → coturn(TCP allocate) → BE `/v1/chat` 호출 1회. 실측 환경 부재 시 mock-coturn 단위 테스트로 대체.

#### 후속 논의 키워드

- coturn 측 설정: `--no-tlsv1`, `--listening-port=3478`, `--tls-listening-port=5349` 등 — 현재 사내 coturn 인스턴스 설정 확인 필요
- TURN realm/shared-secret 운영: 현재 `AgentConfig.turnSharedSecret` 단일. 다중 테넌트/agent 격리는 차기 CR
- Allocate 실패 시 폴백: 직접 연결(공인 IP) → ngrok/frpc 보조 채널 → 작동 안하면 등록 거부
- agent 사이드 control connection 끊김 감지/재수립 (BIZ 신규 후보)
- 보안: relay address 가 노출되면 누구든 agent HTTP 에 도달 가능 → BE 의 `X-Api-Key` 검증이 유일한 방어선이므로 키 회전 정책 강화 필요

#### 영향 범위

- `backend/sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/TurnTcpAllocator.java` (신규)
- `backend/sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/TurnConnectionBindHandler.java` (신규)
- `backend/sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/AgentLifecycle.java` (relay 주소 → runnerEndpoint 정렬, control connection 보유)
- `backend/sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/AgentConfig.java` (turnTransport / turnLifetimeSeconds 필드)
- `backend/sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/TurnRelayClient.java` (UDP 전용 마킹 또는 deprecated)
- `backend/aimbase-agent/src/main/java/com/platform/agent/runner/RunnerHttpServer.java` (혹은 동등 클래스) — 외부 socket 위임 어댑터
- `docs/guides/aimbase-ops-guide.md` (TURN 운영 절차 추가)
- `docs/T3-14_CR-074_TURN-TCP_NAT우회_설계서.md` (후순위 — 구현 후 또는 검증 단계에서 정리)

#### 완료 기준

- [x] `TurnTcpAllocator` + `TurnConnectionBindHandler` + `TurnLoopbackBridge` 단위 테스트 (mock TURN — 10 PASS 신규)
- [x] `AgentLifecycle` TURN-TCP 분기 통합 (turnEnabled=true 시 `metadata.runnerEndpoint = "http://<relay>"`)
- [x] **agent jar 통합 완수** (CR-074 추가 작업) — `AgentProperties.turn.*` + `AgentAutoConfiguration` 14필드 + `AgentMcpServer` 자식 SpringApplication autoconfig exclude 8종 + cliArgs 포트 강제 + `AimbaseRegistrationClient` `X-Tenant-Id` 헤더 + `AgentLifecycle` 등록 실패 tolerance + 등록·Runner 모드 동시 활성화 (`agent.registration.enabled`)
- [x] **전체 e2e PASS**: 외부 curl → TURN relay(`59.8.160.12:61531`) → coturn ConnectionAttempt → agent ConnectionBind → loopback bridge → Tomcat RunnerController `/v1/health` 200 OK 응답 정상 수신
- [x] **RFC 5766 §9 CreatePermission 구현 추가** (디버깅 중 발견된 결정적 누락) — `TurnTcpAllocator.sendCreatePermission/sendRefresh` + `AgentConfig.turnAllowedPeerIps` + `AgentProperties.Turn.allowedPeerIps` + `AgentLifecycle` 자동 송신·갱신 스케줄러 (300s)
- [x] 가이드 갱신 (ops 가이드 v3.1.0)
- [x] `TurnRelayClient` (UDP) `@Deprecated` 마킹 완료
- [x] T3-14 설계서 작성 (`docs/T3-14_CR-074_TURN-TCP_NAT우회_설계서.md`)
- [x] coturn 서버 IP `59.8.160.12` 로 정정 (StunAddressResolver / AgentConfig 기본값)
- [x] flowguard_dev `connections` 시드 (`claude-cli-flowguard` 등록 / anthropic 키는 SQL 스크립트로 보관)
- [x] `widget.allowed-origins` FlowGuard FE Origin 추가 (`http://localhost:3180`, `http://59.8.160.12:3180`)
- [x] flowguard_dev tenant DB V49/V60 마이그레이션 적용 (agent_registry 테이블 + runner_capability 컬럼)
- [ ] (별도 후속 CR 후보) agent 등록 400 — scope JSON 형식 + validation message 노출 정밀 조사

#### 범위 외

- coturn 인스턴스 운영(설치/HA/로드밸런싱) 세부 — 별도 인프라 문서
- WebSocket / SSE 상시 세션 모드 (이번 CR 에서 의도적으로 회피하는 모델)
- 다중 TURN 서버 페일오버 (일단 단일 인스턴스 가정)
- TURN over TLS (TURNS) — 후속 보안 강화 CR 후보

#### 원본 요구사항

`docs/origins/원본_요구사항_TURN-TCP_NAT우회_RFC6062_20260429.md`

#### Plan 파일

미작성 (T3-14 설계서를 후순위로 미루기로 함 — 구현 진행하며 필요 시 발췌 작성).

#### 요청자/승인자

- **요청자**: 사용자 (대화 2026-04-29)
- **승인자**: sykim
- **적용 버전**: v8.8.0 (예정)

---

### CR-075 | ClaudeCliAdapter agent 라우팅 자동화 (user_ref 기반)

- **변경 ID**: CR-075
- **변경 타입**: 변경
- **영향도**: Medium
- **적용 버전**: v8.9.0
- **상태**: 구현 중
- **변경 일자**: 2026-04-29

#### 발단

CR-071 단계에서 `X-Aimbase-Agent-Id` 헤더를 호출자 책임으로 두고 자동화는 후속 CR로 분리(BIZ-101). 본 CR이 그 후속.

CR-074(NAT 우회) 통합 후 위젯에서 채팅 호출 시 헤더 매번 명시는 운영상 부적합:
- agent UUID는 **변동 식별자** (재기동 시 변경) — 매번 소비앱에 알릴 수 없음
- 위젯/소비앱이 인프라 식별자를 알아야 하는 건 추상화 깨짐

본질 정의: **agent = (사용자 PC × 사용자 세션)**. 사용자 본인이 자기 PC에서 자기 산출물을 작업하는 단위. 따라서 라우팅 키는 인프라 UUID가 아니라 **사용자 ID**(`user_ref`)가 자연스럽다.

#### 변경 내용

1. **agent 등록 페이로드에 `userId` 필드 추가**
   - `AgentRegisterRequest` DTO + `AimbaseRegistrationClient.register()` 시그니처 + `AgentProperties.userId` 필드
2. **같은 `userId`로 신규 등록 시 이전 ACTIVE 자동 DEREGISTER** — 사용자당 활성 agent 항상 0 또는 1
3. **`AgentRegistryService.resolveActiveByUserRef(userRef)`** — userId로 활성+runner 가용 agent 조회
4. **`RequestContext.userRef` ThreadLocal** — `JwtAuthenticationFilter.authenticateWidget()`에서 토큰 클레임에서 추출해 주입
5. **`ClaudeCliAdapter.resolveEndpoint()`** — 헤더 → 토큰 user_ref → 400 순으로 폴백 (헤더 명시는 디버깅 호환용 보존)

#### 변경 사유

- 운영에서 위젯/소비앱이 매번 헤더 박는 구조 비합리 (사용자 직접 지적)
- agent_id 변동성을 BE 내부에 캡슐화 → 외부 API 안정성 확보
- 위젯 토큰 user_ref 클레임 이미 존재 → 추가 인프라 불필요

#### 영향 모듈

- `platform-core`: `AgentRegistryService`, `AgentRegistryController`, `ClaudeCliAdapter`, `RequestContext`, `JwtAuthenticationFilter`
- `aimbase-agent`: `AgentProperties`, `AgentLifecycle`, `AimbaseRegistrationClient`

#### 영향 범위

- BIZ-099 (CLI ToS 경계) — 변경 없음
- BIZ-101 (CR-071에서 발번된 헤더 의무) — 본 CR로 자동화 완성
- 익명 위젯 사용자(user_ref 없음) → CLI 라우팅 불가, API 어댑터 폴백 (정책 확정)
- 한 사용자 = 활성 agent 1개 (덮어쓰기 정책 확정)

#### 원본 요구사항

`docs/origins/원본_요구사항_CR075_agent라우팅자동화_20260429.md`

#### 요청자/승인자

- **요청자**: 사용자 (대화 2026-04-29 — "별도 cr이에요? 이미 완료된 토론이 아니고?")
- **승인자**: sykim
- **적용 버전**: v8.9.0

---

### CR-083 | SessionStore 정합성 정공 정리 (멀티블록 보존 + seq 기반 idempotent)

- **변경 ID**: CR-083
- **변경 타입**: 변경
- **영향도**: High (대화 컨텍스트 손실 + LLM 누락 차단)
- **적용 버전**: v8.10.0 (예정)
- **상태**: 발번
- **변경 일자**: 2026-04-30

#### 발단

운영 로그 `Session {sid} DB has 6 messages but memory has 4 — skipping append` 로 두 번째 turn 메시지가 LLM에 전달 안 되는 보고. SessionStore 전수 점검 결과 race 외에 6개 추가 정합성 이슈 발견 — 사용자 결정 "정본으로 가시죠".

#### 7개 이슈

1. **🟠 appendNewMessages count race** — `existing < messages.size()` 후 INSERT 동안 다른 VT 끼어들기 → 카운트 비대칭
2. **🟠 appendMessage RMW race** — Redis read-modify-write lost-update → 메시지 자체 분실 (LLM 누락 진짜 원인)
3. **🔴 Tool/Image 블록 lossy 저장** — `extractText`로 Text 만 보존, ToolUse/ToolResult/Image/Thinking 영구 손실 → 환각 유발
4. **🔴 Redis/DB 진실 불일치** — TTL 만료 후 다른 세션처럼 동작
5. **🟡 VT 무제한 생성** — DB 풀 고갈 위험
6. **🟡 loadFromDb 캐시 덮어쓰기 race** — TTL 직후 첫 메시지 분실
7. **🟡 createdAt 정렬 비결정성** — 같은 ms 메시지 순서 뒤섞임

#### 변경 내용

**스키마 (Flyway V61)**:
- `conversation_messages` 에 `seq INTEGER NOT NULL` + `content_json JSONB NOT NULL` 추가
- `UNIQUE (session_id, seq)` 제약 + `idx_conv_msg_session_seq` 인덱스
- 기존 row backfill: `ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) - 1` 로 seq 부여, content → `[{type:text, text:content}]` 로 변환

**SessionStore 재작성**:
- `ConcurrentHashMap<String, Semaphore>` 로 sessionId 별 직렬화 (Semaphore(1))
- 단일 가상스레드 Executor 1개 (Thread.ofVirtual().start 무제한 → ExecutorService 단일)
- `content_json` JSONB 로 ContentBlock 리스트 보존 (Text/ToolUse/ToolResult/Image/Thinking 모두)
- `messageType` 정확히 셋팅 (ToolUse → tool_use 등)
- `INSERT ... ON CONFLICT (session_id, seq) DO NOTHING` 로 idempotent
- `loadFromDb` 캐시 SET 도 lock 안에서만 → race 차단
- `extractText` 는 검색 용도로만 유지 (DB `content` 컬럼 동기화)

**Repository**:
- `findBySessionIdOrderByCreatedAtAsc` → `findBySessionIdOrderBySeqAsc` 로 교체 (createdAt fallback 제거)

**OrchestratorEngine**:
- 호출 측 변경 없음 (`appendMessage` 시그니처 동일)

#### 변경 사유

- 운영 로그 직접 원인 차단 (이슈 1, 2)
- 멀티모달 대화 컨텍스트 영구 손실 차단 (이슈 3, 4) — CR-061 첨부 이미지·CR-054 HttpRequestTool 결과 등
- VT 폭주 + 캐시 덮어쓰기 race + 정렬 비결정성 같은 잠재 결함 일괄 정리

#### 영향 모듈

- `platform-core`:
  - `domain/ConversationMessageEntity` (seq, content_json 필드 추가)
  - `repository/ConversationMessageRepository` (seq 기반 쿼리 + ON CONFLICT)
  - `session/SessionStore` (재작성)
  - `db/migration/tenant/V61__cr083_session_store_seq_jsonb.sql` (신규)
- 회귀 영향:
  - `api/ConversationController` (메시지 응답 DTO 변경 가능 — content_json 노출 여부 결정)
  - `api/SessionResumeController` (세션 복원 시 멀티블록 반환 형식)
  - `session/SessionBriefService`, `context/ContextAssemblyEngine` (메시지 로드 경로)

#### 영향 범위

- BIZ-002 (세션 TTL 24h) — 변경 없음, 단 TTL 만료 후 정상 복원이 새로 가능
- BIZ-057 (메시지 본문 32KB / 세션당 500개) — 유지
- CR-045 (대화형 채팅 UI) — 멀티모달 메시지 정상 복원 효과
- CR-046 (Soft Delete) — 영향 없음, deletedAt 조건 유지
- CR-049 (세션 복원·지침) — 복원 정확도 향상
- CR-061 (위젯 파일 업로드) — 첨부 이미지 컨텍스트 보존
- CR-077 (세션 race retry) — 본 CR 로 흡수, retry 메커니즘은 ON CONFLICT 로 단순화

#### 영향 설계서

- T3-3 (세션·대화 저장) — content_json 컬럼·seq UNIQUE 반영 필요
- T3-6 (실행 지시서) — SessionStore 직렬화 정책 추가

#### 운영 데이터 backfill

- 기존 ToolUse/ToolResult/Image 메시지는 이미 손실됨 — 복원 불가, 텍스트만 jsonb 변환 (수용)
- backfill 후 `seq IS NOT NULL` 검증

#### 추정

5MD — 마이그레이션 1MD + SessionStore 재작성 2MD + 테스트 1MD + 회귀/배포 1MD

#### 범위 밖 (후속 별도 CR)

- 위젯 ChatRecipe 통합 (project_widget_chat_recipe_idea.md 보관 중)
- 세션 관리 UX 정책 (selector / 제목 / 새 대화) — ChatRecipe 안에 흡수

#### 원본 요구사항

`docs/origins/원본_요구사항_SessionStore_정합성_정공_20260430.md`

#### 요청자/승인자

- **요청자**: 사용자 (대화 2026-04-30 — "SessionStore append race 부터 잡고", "정본으로 가시죠")
- **승인자**: sykim
- **적용 버전**: v8.10.0 (예정)

---

### CR-084 | WorkflowEngine 임의 cycle + 동적 N-way 라우팅

- **대상 기능 ID**: BIZ-009 (워크플로우 DAG 실행)
- **변경 타입**: 신규
- **변경 내용**:
  - 워크플로우 레벨 `graph_mode` 속성 (`dag` 기본 / `cyclic`). cyclic 명시 시에만 워크리스트 스케줄러 가동, DAG 는 기존 1-pass 무변경
  - `executeCyclic` 신설 — 워크리스트(worklist) 기반 스케줄러로 임의 노드 간 순환 지원 (agent↔tool 반복 등)
  - 신규 `ROUTER` StepType + `RouterStepExecutor` — 런타임 표현식/LLM 출력으로 N개 후보 중 1개 동적 선택
  - `ExpressionEvaluator` 추출 (CONDITION 평가기 분리, ROUTER 공유)
  - 그래프 레벨 무한루프 방어: run 당 총 스텝 실행 상한 (기본 50, 절대 200) — EVALUATOR_LOOP MAX_ITERATIONS_CEILING 과 별개
  - resume 시 worklist 상태 복원 (`pending_worklist` JSONB), 이벤트에 iteration index 추가
- **변경 사유**: LangGraph 격차 분석 결과, 임의 cycle 미지원(EVALUATOR_LOOP 한 패턴 한정) + CONDITION 2갈래 고정이 오픈엔드 에이전트 표현력의 핵심 격차로 확인
- **영향 모듈**: workflow (WorkflowEngine, ConditionStepExecutor, WorkflowValidator, WorkflowStep, WorkflowRunEntity, WorkflowEventPublisher), Flyway tenant V62
- **영향도**: High (엔진 실행 루프 재설계)
- **영향 범위**: BIZ-009, CR-049(resume), CR-058(이벤트), CR-055(EVALUATOR_LOOP 공존)
- **영향 설계서**: T3-7 (신규)
- **원본 요구사항**: `docs/origins/원본_요구사항_LangGraph격차_WorkflowEngine보강_20260516.md`
- **요청자**: 사용자 (대화 2026-05-16 — LangGraph 격차 비교 후 "부족한 부분 채우려고 합니다", "2개 CR 분할", "옥인 graph_mode 플래그")
- **승인자**: sykim
- **적용 버전**: v8.11.0 (예정)
- **변경 일자**: 2026-05-16
- **구현 상태**: ✅ **P1~P5 구현 완료** (2026-05-16, dev 브랜치). P1 ExpressionEvaluator 추출 / P2 ROUTER StepType+Executor+Validator / P3 V62 graph_mode + executeCyclic 워크리스트 스케줄러 + step budget(50/200) / P4 V63 pending_worklist + cyclic resume 복원 + 이벤트 iterationIndex(nullable) / P5 api-guide v3.1.0 + ops-guide v3.2.0. **platform-core 전체 664 테스트 PASS (0 fail/error)**. DAG 경로 무변경(하위호환 단위 입증). 한계: cyclic 실 DB e2e 미검증(IT 후속), V62/V63 운영 적용·커밋 미수행. 상세 — `docs/T3-7_..._설계서.md` § 6

---

### CR-085 | WorkflowEngine State 타입드 채널/reducer + 노드 내부 토큰 스트리밍

- **대상 기능 ID**: BIZ-009 (워크플로우 State 관리)
- **변경 타입**: 신규
- **변경 내용**:
  - `StepContext` 변수 접근을 중첩 경로(`{{step.a.b[0]}}`)로 확장 — 1뎁스/실패 폴백 동작은 기존 보존 (하위호환)
  - 채널 reducer (opt-in): `output_channel` + `reduce`(replace 기본 / append / merge). 미지정 = 현행 덮어쓰기 100% 보존
  - 메시지 누적 채널 (LangGraph add_messages 대응) — cyclic 그래프 대화 누적
  - `WorkflowEventPublisher.stepToken` 추가 — 노드 내부 LLM 토큰 단위 스트리밍 (opt-in `stream_tokens`, 기본 off). orchestrator SSE 공용 어댑터 콜백 재사용 (중복 구현 금지)
- **변경 사유**: LangGraph 격차 분석 결과, State 평면 문자열 치환(reducer/누적채널 부재) + 워크플로우 이벤트 step 단위 한정이 격차로 확인
- **영향 모듈**: workflow (StepContext, WorkflowEventPublisher, LlmCallStepExecutor + 9 executor 회귀)
- **영향도**: Medium (StepContext API 개편, 하위호환 보장)
- **영향 범위**: BIZ-009, 전 StepExecutor 9종, CR-084(append reducer 시너지/이벤트 iteration 합류)
- **영향 설계서**: T3-7 (신규)
- **원본 요구사항**: `docs/origins/원본_요구사항_LangGraph격차_WorkflowEngine보강_20260516.md`
- **요청자**: 사용자 (대화 2026-05-16 — "2개 CR 분할")
- **승인자**: sykim
- **적용 버전**: v8.12.0 (예정)
- **변경 일자**: 2026-05-16 (발번) / 2026-05-18 (P1~P4 구현 완료)
- **구현 상태**: ✅ **P1~P4 구현+테스트 완료** (2026-05-18)
  - P1: `StepContext.resolveRef` 첫 토큰 namespace 분리 + `traverse(root, 토큰리스트)` 중첩 경로 탐색 (`{{s.a.b[0].c}}`). 점/대괄호 없는 ref는 기존대로 원문 유지, 1뎁스는 기존 결과 동일
  - P2: `StepContext.withStepResult(stepId, result, outputChannel, reduce)` 오버로드 + `WorkflowEngine.applyStepResult` (DAG/cyclic 2개 호출처 위임). channel 미지정 시 기존 3-arg 그대로
  - P3: `WorkflowEvents.StepToken` + `WorkflowEventPublisher.stepToken` + `LlmCallStepExecutor.callLlmStreaming` (공용 `LLMAdapter.chatStream` 콜백 재사용, CountDownLatch 패턴은 OrchestratorEngine과 동일). `stream_tokens:true` + `response_schema` 없을 때만 활성. 3-arg 생성자 유지(기존 테스트 호환)
  - P4: api-guide v3.2.0 / ops-guide v3.3.0 갱신 + 부록 A 하위호환 6항목 검증
  - **검증**: `StepContextCr085Test` 17 PASS (하위호환 8 + 표현력 확장 9) + `LlmCallStreamingCr085IT` 5 PASS+1 SKIP(env 게이트) — P3 스트리밍 e2e (mock chatStream → 실 WorkflowEventPublisher → StepToken 도달 + 하위호환 4종), platform-core 전체 **659 PASS, 0 fail/0 error, 1 SKIP**
  - **마이그레이션 없음**: StepContext API + 이벤트 record 확장만 (DB 스키마 무변경)
- **알려진 한계 (정직 기록)**: P3 토큰 스트리밍의 `iterationIndex`는 null 고정 — `StepExecutor.execute(step, context)` 인터페이스가 cyclic 회차를 전달하지 않음(설계의 DAG/기존=null 계약과 호환). cyclic 회차별 토큰 정밀 매핑은 인터페이스 확장 동반 후속 영역(별도 CR). **P3 스트리밍 e2e는 `LlmCallStreamingCr085IT`로 검증 완료** (chatStream 콜백 → 실 publisher → StepToken 도달 전 경로 + 하위호환 4종 + iterationIndex=null 고정). 실 LLM 어댑터 왕복 변형은 `CR085_REAL_LLM_IT=true` 환경 게이트로 분리(스테이징 Connection 환경에서 본문 구성 시 활성)

### CR-086 | X-Tenant-Id 헤더 신뢰 경계 강화 (헤더-토큰 테넌트 일치 강제)
- **대상 기능 ID**: BIZ-003 (Database-per-Tenant 격리), TenantResolver, JwtAuthenticationFilter
- **변경 타입**: 신규
- **변경 내용**:
  - JWT access 토큰의 `tenant_id` claim 과 요청 `X-Tenant-Id` 헤더(또는 `tenant_id` 쿼리) 값이 불일치하면 거부 (cross-tenant 사칭 차단)
  - 위젯 토큰은 기존대로 토큰 claim 단독 신뢰 유지 (헤더 미사용 경로)
  - 불일치 거부 시 명확한 4xx 응답 + 감사 로깅 (BIZ-020)
  - 단위/통합 테스트: 토큰=헤더 일치 통과 / 불일치 거부 / 헤더 누락 시 토큰 claim fallback
- **변경 사유**: 소비앱(bidding-agency) 팀이 "mcp-servers/workflows 테넌트 격리 안 됨(tenant_id 컬럼 누락 전역 공유)" 보고. **실측 검증 결과 외부 진단은 반증** — mcp/workflow 는 `TenantRepositoryConfig` 로 테넌트 라우팅 DataSource 에 바인딩되어 Database-per-Tenant 물리 격리, `aimbase_master` 에 해당 테이블 자체 없음, 운영 DB 에서 테넌트마다 행 상이(bidding_system mcp1/wf2 · axopm_companyA mcp4/wf15 · 등). 운영 prod 인증 ON 으로 토큰 없이 헤더만 호출 시 HTTP 403. 다만 실측 중 **진짜 약점 발견**: `TenantResolver` 가 `X-Tenant-Id` 헤더를 무검증 신뢰하고 `JwtAuthenticationFilter` 가 토큰 `tenant_id` claim 과 대조하지 않아, A 테넌트 토큰 + `X-Tenant-Id: B` 로 cross-tenant 라우팅 가능.
- **영향 모듈**: TenantResolver, JwtAuthenticationFilter, SecurityConfig (검토)
- **영향도**: High (멀티테넌시 보안 경계)
- **영향 범위**: BIZ-003, BIZ-020, 모든 테넌트 스코프 API (`/api/v1/**`, master 제외)
- **영향 설계서**: T3-2 (API 설계) — 검토 대상
- **원본 요구사항**: `docs/origins/원본_요구사항_테넌트격리_헤더신뢰경계_20260529.md`
- **요청자**: 사용자 (소비앱 외부 보고 전달) | **승인자**: (대기) | **적용 버전**: (미정)
- **변경 일자**: 2026-05-29 (발번) / 2026-05-29 (코드 구현)
- **구현 상태**: ✅ 코드 구현 + 테스트 통과 완료, 운영 배포 대기 (사용자 "403 즉시 차단" 정책 선택)
  - 가드: `JwtAuthenticationFilter.java:111-122` — access 토큰 일반 테넌트 경로에서 `TenantContext`(TenantResolver가 헤더/쿼리로 설정) ↔ 토큰 `tenant_id` claim 불일치 시 403 + WARN 로그. platform/apps·widget·헤더미지정은 제외(기존 동작 보존)
  - 테스트: `JwtAuthenticationFilterTenantMatchTest.java` 4케이스 (일치 통과 / 불일치 403 / 헤더없음 토큰신뢰 / platform 스킵). 실제 JwtProvider 토큰 생성 방식
  - **검증**: platform-core 전체 회귀 **681 PASS, 0 fail/0 error, 1 skip** (기존 677 무유발). 배포(`./deploy.sh be`)는 별도 승인 필요
- **실측 근거 (요약)**:
  - 외부 진단 반증: `backend/platform-core/src/main/java/com/platform/config/TenantRepositoryConfig.java:11-19` (mcp/wf = 테넌트 DataSource 바인딩), `aimbase_master` 에 mcp_servers/workflows 테이블 없음, 테넌트별 행 상이
  - 운영 런타임: http://59.8.160.12:8280 토큰 없이 `X-Tenant-Id` 헤더만 → HTTP 403 (헤더 자체 없으면 400). 운영 prod 프로파일에서 인증 동작 — "헤더만으로 200" 재현 불가
  - 진짜 약점: `TenantResolver.java:134-136` (헤더 무검증 신뢰), `JwtAuthenticationFilter.java:99` (claim 추출하나 헤더 대조 없음)
- **미해결**: 외부에서 200 받았다는 정확한 호출 로그(Authorization 헤더 포함 여부 + 정확 엔드포인트 경로) 확보 시 외부 관찰 재현 경로 100% 확정 가능

---

### CR-087 | 워크플로우 FOREACH step (동적 컬렉션 fan-out)
- **대상 기능 ID**: BIZ-009 (워크플로우 DAG 실행), WorkflowStep.StepType, 신규 ForeachStepExecutor
- **변경 타입**: 신규
- **변경 내용**:
  - 신규 `FOREACH` StepType — 런타임에 정해지는 컬렉션의 각 원소에 동일 body step을 적용(map/fan-out)
  - body 는 임의 StepExecutor 위임 (LLM_CALL/TOOL_CALL/SUB_WORKFLOW/ACTION/AGENT_CALL) — `SubWorkflowStepExecutor.getExecutors()` 패턴 재사용
  - 각 원소를 `item_var`(기본 `item`) 로 stepResults 에 임시 주입 → body 에서 `{{item}}`/`{{item.field}}`/`{{index}}` 참조 (StepContext resolve 무변경, withStepResult 재사용)
  - 실행 모드 `mode: sequential(기본) | parallel` — parallel 은 PARALLEL 선례대로 Virtual Thread, `max_concurrency`(기본 5, BIZ-100 정신) 로 동시성 상한
  - 무한 방어 `max_items`(기본 100, cyclic step budget 정신) — 초과 시 step FAIL
  - 결과 수집: `collect: append(기본) | merge | none` — CR-085 reducer 의미 재사용. step output 은 `{ output: [...], results: [...], item_count: N }`
  - 부분 실패: `on_item_error: fail(기본) | continue` — continue 시 실패 원소는 `{error, status:"failed"}` 기록 후 계속(PARALLEL 선례 동형)
  - 중첩: body=SUB_WORKFLOW 로 자연 중첩 지원(budget 부모-자식 독립). FOREACH 직접 중첩(body=FOREACH)은 1차 범위 제외 — WorkflowValidator 에서 차단
- **변경 사유**: bidding-agency CR-013(슬롯에 모인 N개 제안서 파일 각각 parse → 합쳐 패턴 추출) 구현 중 "동적 컬렉션 fan-out" 빌딩블록 부재 발견. 실측 결과 `PARALLEL`=정적 step ID 목록(런타임 N 원소 map 아님), `ROUTER`=N중 1택, `cyclic`=노드 단위 수동 배선 + ExpressionEvaluator 에 size/index/카운터 연산 부재로 컬렉션 순회 선언적 표현 불가. fan-out 은 LLM 오케스트레이션 기본 빌딩블록(LangGraph Send/map 대응)이며 Java 로 풀면 소비앱마다 재구현 → 재사용 안 됨. 워크플로우 step 으로 두어 모든 소비앱이 선언적 공유.
- **영향 모듈**: WorkflowStep(StepType enum), ForeachStepExecutor(신규), WorkflowValidator(validateForeach), WorkflowEngine(스케줄러 무변경 — 단일 노드)
- **영향도**: High (워크플로우 엔진 핵심 빌딩블록)
- **영향 범위**: BIZ-009, 전 StepExecutor(body 위임), CR-084(cyclic — FOREACH 가 cyclic/DAG 양쪽 단일 노드로 동작), CR-085(collect reducer 재사용)
- **영향 설계서**: T3-7(EVALUATOR_LOOP/cyclic 설계 계열) — 후속 보정 대상
- **원본 요구사항**: `docs/origins/원본_요구사항_워크플로우_FOREACH_MAP_step_20260529.md`
- **요청자**: 사용자 (bidding-agency CR-013 패턴 추출 중 식별) | **승인자**: (대기) | **적용 버전**: (미정)
- **변경 일자**: 2026-05-29 (발번)
- **구현 상태**: 📝 발번 완료 (설계 확정, 구현 착수 — 코드 수정은 사용자 승인 후, 빌드/배포 별도 승인)
- **마이그레이션**: 불필요 — StepType 은 JSONB `steps[].type` 문자열 (ROUTER/EVALUATOR_LOOP 선례와 동일, DB 스키마 무변경)
- **설계 결정 (열린 질문 확정)**:
  - 중첩 fan-out: body=SUB_WORKFLOW 허용(자연 중첩), max_items budget 부모-자식 독립 적용. FOREACH 직접 중첩은 1차 범위 제외(검증기 차단)
  - 부분 실패: `on_item_error` config — 기본 `fail`, `continue` 시 실패 원소 기록 후 계속

---

## 작성 가이드

**카드 구조**:
```
### [CR 번호] | [변경 제목]
- **대상 기능 ID**: ...
- **변경 타입**: 신규 | 변경 | 삭제 | 보류 | 설계보정
- **변경 내용**: ...
- **변경 사유**: ...
- **영향 모듈**: ...
- **영향도**: High | Medium | Low
- **영향 범위**: [관련 기능 ID들]
- **영향 설계서**: [수정된 T 문서 목록, 예: T3-1, T3-2]
- **요청자**: ... | **승인자**: ... | **적용 버전**: ...
- **변경 일자**: YYYY-MM-DD
```

**명명 규칙**:
- 변경 요청 ID: `CR-[순번]` (예: CR-001)
- 변경 타입: **신규** | **변경** | **삭제** | **보류** | **설계보정**
  - 설계보정: 기능은 동일하나 설계서 내용이 수정된 경우 (구현 중 설계 이탈, 아키텍처 리팩터링 등)
- 영향도: **High** (아키텍처/다수 모듈) | **Medium** (단일 모듈) | **Low** (단일 기능)
