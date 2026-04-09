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
