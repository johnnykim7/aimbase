# Aimbase 변경 이력 (Change Request Log)

> ai-sdlc 템플릿: v2.7.0 기반

> 기능 변경 및 설계 보정을 기록한다. git commit과 별개로, 비즈니스·설계 수준의 변경을 추적한다.
> T1~T4 설계 문서의 단일 파일 원칙에 따라, 이 문서가 변경 이력의 단일 진실 소스이다.

## 전체 요약

| CR 번호 | 변경 제목 | 변경 타입 | 영향도 | 적용 버전 |
|---------|----------|----------|--------|----------|
| CR-001 | 초기 시스템 구축 | 신규 | High | v1.0.0 |
| CR-002 | Python 사이드카 아키텍처 도입 | 변경 | High | v2.0.0 |
| CR-003 | MCP 클라이언트 통합 완성 | 변경 | High | v2.1.0 |
| CR-004 | Sprint 18 고급 기능 구현 완성 | 변경 | Medium | v2.2.0 |
| CR-005 | 워크플로우 비주얼 스튜디오 | 변경 | Medium | v2.3.0 |
| CR-006 | 도구 선택 제어 (Tool Selection Control) | 변경 | Medium | v2.4.0 |
| CR-007 | 구조화된 출력 (Structured Output) | 변경 | High | v2.5.0 |
| CR-008 | LLM 연결 테스트 실제 검증 | 버그수정 | Medium | v2.5.1 |
| CR-009 | Python 사이드카 알파 기능 | 변경 | High | v3.0.0 |
| CR-010 | 플랫폼 핵심 강화 | 변경 | High | v3.0.0 |
| CR-011 | ClaudeCodeTool 안정화 및 확장성 개선 | 변경 | High | v3.1.0 |
| CR-012 | LLM 컨텍스트 설계 보강 | 변경 | High | v3.2.0 |
| CR-013 | API Rate Limit 방어 (TokenBucket) | 변경 | Medium | v3.3.0 |

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
