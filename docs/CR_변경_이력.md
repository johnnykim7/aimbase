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
| CR-011 | RAG A++ 고도화 + TTS/STT API (Contextual Retrieval, Parent-Child, Query Transform, RAGAS, Citation, TTS, STT) | 변경 | High | v3.1.0 |
| CR-012 | 로컬 임베딩 전환 — BGE-M3 기본화 + 임베딩 제공자 선택 | 변경 | Medium | v3.2.0 |
| CR-013 | 문서 분석/생성 파이프라인 (Document Intelligence) | 변경 | High | v3.8.0 |
| CR-014 | RAG 인제스션 파이프라인 사이드카 일원화 | 버그수정 | Medium | v3.2.1 |
| CR-015 | 정책 엔진 Rule Type 확장 + UI 개선 | 변경 | Medium | v3.3.0 |
| CR-016 | RAG 평가 LLM Judge 도입 (RAGAS 고도화) | 변경 | High | v3.4.0 |
| CR-017 | 워크플로우 스튜디오 ConfigPanel 완성 + 모델 라우팅 정책 UI | 변경 | High | v3.5.0 |
| CR-018 | 문서 생성 MCP 도구 — LLM 코드 생성 기반 실시간 문서 생산 | 신규 | High | v3.6.0 |
| CR-019 | 플랫폼 공통 Tool 확장 | 신규 | High | v3.7.0 |
| CR-020 | Aimbase 관리 MCP 도구 노출 (Self-Service Workflow) | 신규 | High | v4.0.0 |
| CR-021 | Project 계층 도입 — 회사 내 프로젝트별 리소스 스코핑 | 신규 | High | v4.0.0 |
| CR-022 | 사용자별 리소스 소유 — created_by 기반 개인 리소스 필터링 | 변경 | Medium | v4.1.0 |
| CR-023 | 테넌트 소비앱 메타데이터 — domain_app 컬럼 도입 | 변경 | Medium | v4.1.0 |
| CR-024 | 개별 텍스트 인제스션 API (ingest-text) | 신규 | Low | v4.2.0 |
| CR-025 | 시스템 API Key 관리 — 독립 엔티티 + 관리 UI + 해시 버그 수정 | 신규 | High | v4.3.0 |
| CR-026 | Metronic 9 UI 프레임워크 전환 — Tailwind CSS + Metronic 컴포넌트 기반 FE 전면 교체 | 변경 | High | v5.0.0 |
| CR-027 | 로그인 테넌트 자동 resolve — Master DB 사용자-테넌트 매핑 기반 인증 흐름 개선 | 변경 | High | v5.1.0 |
| CR-028 | LLM_CALL 토큰 초과 자동 처리 — 에스컬레이션 + 자동분할(Auto-Split) | 변경 | High | v5.2.0 |

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

### CR-011 | RAG A++ 고도화 + TTS/STT API
- **대상 기능 ID**: PRD-052(확장), PRD-053(확장), PRD-116~PRD-127(신규), PY-023~PY-028(신규)
- **변경 타입**: 변경
- **변경 내용**: RAG 파이프라인을 경쟁 플랫폼(Dify) 대비 A++ 수준으로 격상하는 5단계 고도화 + AI 튜터 연동용 TTS/STT API
  - **Phase 1: Contextual Retrieval + Parent-Child 청크** (PRD-116~118, PY-023~024)
    - PRD-116 Contextual Retrieval: 청킹 시 LLM으로 문서 맥락 프리픽스 자동 생성 후 임베딩 (Anthropic Contextual Retrieval 기법)
    - PRD-117 Parent-Child 계층적 청크 검색: 작은 청크로 매칭 → 부모(큰) 청크를 반환하여 충분한 컨텍스트 제공
    - PRD-118 Parent-Child FE 설정: 지식소스 생성/편집 UI에 계층적 청킹 옵션 추가
    - PY-023 contextual_chunk 도구: LLM 호출로 청크별 맥락 요약 프리픽스 생성
    - PY-024 parent_child_search 도구: child 매칭 → parent 반환 하이브리드 검색
  - **Phase 2: Query Transformation 고도화** (PRD-119, PY-025)
    - PRD-119 Query Transform 전략 확장: HyDE(가상 답변 생성 검색), Multi-Query(질문 확장), Step-Back(상위 개념 변환) BE 오케스트레이션
    - PY-025 transform_query 고도화: 기존 PY-005 도구에 HyDE/Multi-Query/Step-Back 실제 LLM 연동 완성
  - **Phase 3: RAG 평가 파이프라인 (RAGAS)** (PRD-120~121, PY-026)
    - PRD-120 RAG 평가 API: Faithfulness, Context Relevancy, Answer Relevancy, Context Precision/Recall 5개 메트릭 엔드포인트
    - PRD-121 RAG 평가 대시보드 (FE): 평가 결과 시각화, 시계열 품질 추이 차트
    - PY-026 evaluate_rag_quality 도구: RAGAS 프레임워크 기반 5개 메트릭 자동 산출
  - **Phase 4: 고급 문서 처리** (PRD-122~123, PY-027)
    - PRD-122 테이블 구조 추출: PDF/DOCX 내 표를 Markdown 테이블로 변환하여 청킹
    - PRD-123 OCR 지원: 스캔 PDF/이미지 텍스트 추출 (Tesseract/PaddleOCR 연동)
    - PY-027 advanced_parse 도구: unstructured 고급 파싱 (테이블/OCR/레이아웃 인식)
  - **Phase 5: 시맨틱 캐시 + 증분 인제스션 + Citation** (PRD-124~125, PY-028)
    - PRD-124 시맨틱 캐시: Redis에 질문 임베딩 저장, 유사 질문(코사인 > 0.95) 캐시 히트로 비용/지연 절감
    - PRD-125 인라인 Citation: LLM 답변에 `[1]`, `[2]` 출처 번호 삽입, 문서명/페이지 링크 제공
    - PY-028 semantic_cache 도구: 질문 임베딩 비교 + 캐시 저장/조회
    - 증분 인제스션: 문서 해시 비교 → 변경분만 재처리 (기존 IngestionPipeline 확장)
  - **Phase 6: TTS/STT API** (PRD-126~127)
    - PRD-126 TTS API: OpenAI TTS API 프록시 엔드포인트. 텍스트 → 음성 변환, 다국어/음성 모델 선택, 스트리밍 오디오 반환
    - PRD-127 STT API: OpenAI Whisper API 프록시 엔드포인트. 음성 파일 → 텍스트 변환, 한국어/영어 지원
- **변경 사유**: RAG 평가 매트릭스에서 A → A++ 격상 목표. AI 튜터 프로젝트(WHI-AIM-004/005)에서 TTS/STT API 필요 (비교표 D등급 해소). Contextual Retrieval(49% 정확도 향상), Parent-Child(컨텍스트 충분성), RAGAS(품질 측정 루프), Citation(사용자 신뢰도) 확보
- **영향 모듈**: RAG, Python-RAG Pipeline, Python-평가, 오케스트레이터, 세션(캐시), FE(지식소스, 평가), 채팅(TTS/STT 신규)
- **영향도**: High
- **영향 범위**: PRD-052, PRD-053, PRD-116~PRD-127(신규), PY-023~PY-028(신규)
- **영향 설계서**: T1-1, T1-2, T1-3, T1-7, T2-1, T3-2, T3-5, T3-6, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.1.0
- **변경 일자**: 2026-03-21
- **요구사항 원본**:
  > AI 튜터 백엔드 플랫폼 비교 분석에서 Aimbase RAG가 A 등급으로 평가됨. 사용자가 "A++로 격상시키려면 어떤걸 더 구현해야하나요?" 질문.
  >
  > 분석 결과 10개 미구현 영역 도출:
  > 1. Contextual Retrieval (임팩트 ★★★★★) — Anthropic 기법, 청크에 문서 맥락 프리픽스 → 검색 정확도 49% 향상
  > 2. Parent-Child 계층 청크 검색 (★★★★★) — 작은 청크 매칭 → 큰 부모 청크 반환
  > 3. Query Transformation 고도화 (★★★★) — HyDE, Multi-Query, Step-Back
  > 4. RAG 평가 파이프라인 RAGAS (★★★★) — Faithfulness, Context Relevancy 등 5개 메트릭
  > 5. 고급 문서 처리 (★★★★) — 테이블 구조 추출, OCR
  > 6. 시맨틱 캐시 (★★★) — 유사 질문 캐시 히트
  > 7. Graph RAG (★★★) — 지식 그래프 + 벡터 검색 결합 (향후 고려, 본 CR 범위 외)
  > 8. 증분 인제스션 (★★★) — 문서 해시 비교 → 변경분만 재처리
  > 9. 출처 추적 Citation (★★★) — 인라인 [1][2] 인용 번호
  > 10. 검색 분석 대시보드 (★★) — (향후 고려, 본 CR 범위 외)
  >
  > 비교 매트릭스 원본 (RAG 항목):
  > | 평가 항목 | Aimbase | Open WebUI | n8n | Dify |
  > | RAG 파이프라인 | A | A | B+ | A+ |
  >
  > Phase 1~3 구현 시 A+ 확보, Phase 4~5까지 시 A++ (Dify 대비 우위).
  > AI 튜터 맥락에서 Contextual Retrieval(교재 맥락 보존)과 Citation(학생에게 출처 제시)이 특히 중요.

### CR-012 | 로컬 임베딩 전환 — BGE-M3 기본화 + 임베딩 제공자 선택
- **대상 기능 ID**: PY-002(변경), BIZ-012(변경)
- **변경 타입**: 변경
- **변경 내용**: 기본 임베딩 모델을 유료 OpenAI에서 무료 오픈소스로 전환하고, 임베딩 제공자를 선택 가능하게 변경
  - Python 사이드카 기본 모델: KoSimCSE(768d) → BAAI/bge-m3(1024d)로 변경. KoSimCSE/OpenAI도 환경변수로 선택 가능
  - DB 벡터 컬럼: vector(1536) 고정 → vector (차원 제한 없음)으로 마이그레이션. 모델 교체 시 스키마 변경 불필요
  - BE EmbeddingService: @ConditionalOnProperty로 Optional화. OpenAI API key 없이도 백엔드 기동 가능
  - BE EmbeddingConfig: 수동 @Configuration으로 API key 존재 시에만 OpenAiEmbeddingModel 빈 등록
  - 시맨틱 캐시(PRD-124) query_embedding도 가변 차원으로 변경
- **변경 사유**: OpenAI text-embedding-3-small(유료)보다 BGE-M3(무료)가 MTEB 벤치마크 성능 우위. 로컬 테스트/개발 시 API key 의존성 제거. Python 사이드카에 이미 sentence-transformers 인프라 존재하여 추가 비용 없음
- **영향 모듈**: RAG(Python-임베딩, Java-Fallback), DB(마이그레이션), 설정(BE)
- **영향도**: Medium
- **영향 범위**: PY-002, BIZ-012, PRD-124
- **영향 설계서**: T1-1, T1-3, T1-4, T2-1, T3-1, T3-5, T3-6, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.2.0
- **변경 일자**: 2026-03-22
- **요구사항 원본**: `docs/origins/원본_요구사항_로컬_임베딩_전환_BGE-M3_20260322.md`

### CR-013 | 문서 분석/생성 파이프라인 (Document Intelligence)
- **대상 기능 ID**: PRD-130~PRD-133(신규)
- **변경 타입**: 변경
- **변경 내용**: CR-018(코드 실행 기반) 위에 스키마 기반 문서 생성 레이어(Level 3) 추가
  - **Level 1 (DAG 조립으로 가능)**: 파싱 → LLM 분석/요약 → Markdown/JSON 구조화 출력 — CR-018로 구현 완료
  - **Level 2 (변환기 추가 필요)**: 구조화된 출력 → PDF/DOCX/PPTX 변환 — CR-018로 구현 완료
  - **Level 3 (제품화 레이어)**: 소비자 앱 수준 정교함 — 본 CR에서 구현
    - 중간 스키마 설계: Presentation(slides + 6종 레이아웃) / Document(sections + 7종 블록) JSON 스키마
    - 테마/브랜드 프리셋 5종: default, corporate_blue, modern_dark, minimal, warm (컬러/폰트/여백/행간)
    - 후처리 규칙: 불릿 120자 축약, 슬라이드당 7개 제한, 테이블 12행 분할
    - 스키마 검증: 레이아웃/콘텐츠 타입/필수 필드 검증 + errors/warnings 반환
    - 3개 렌더러: PPTX(python-pptx), DOCX(python-docx), PDF(reportlab)
  - **MCP 도구 3개**: generate_document_from_schema, validate_document_schema, list_document_themes
  - **BE API 4개**: POST /schema/generate, POST /schema/generate-json, POST /schema/validate, GET /themes
- **변경 사유**: CR-018의 코드 실행 방식은 LLM 출력 품질에 따라 결과가 불안정. 스키마 기반 접근은 렌더러가 레이아웃/테마를 제어하므로 일관된 품질 보장. 브랜드 가이드라인 적용, 비개발자 사용 가능
- **영향 모듈**: Python 사이드카(document_intelligence.py), BE(DocumentController 확장), MCP 도구
- **영향도**: High
- **영향 범위**: PRD-130~PRD-133(신규)
- **영향 설계서**: T1-1, T3-2, T3-6
- **구현 상태**: ✅ 구현 완료 (2026-03-25) — document_intelligence.py(렌더러 3종 + 테마 5종 + 후처리 + 검증), MCP 도구 3개, BE API 4개
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.8.0
- **변경 일자**: 2026-03-25
- **요구사항 원본**: Genspark 등 문서 분석 서비스 벤치마킹 논의에서 도출. CR-018(코드 실행)으로 Level 1~2 해결 후, Level 3(스키마 기반 제품화 레이어) 구현 요청.

### CR-015 | 정책 엔진 Rule Type 확장 + UI 개선
- **대상 기능 ID**: PRD-060, PRD-061
- **변경 타입**: 변경
- **변경 내용**: 정책 엔진의 Rule Type 선택 UI 추가 및 지원 타입 확장
  - **현재 문제**: FE 정책 생성 시 rule type을 선택하는 UI가 없어서, 규칙 JSON을 직접 작성해야 함. BE는 cost_limit, token_limit, content_filter, rate_limit 등 다양한 타입을 지원하지만 FE에서 활용 불가
  - **개선 내용**:
    - FE 정책 생성 모달에 Rule Type 드롭다운 추가 (cost_limit, token_limit, content_filter, rate_limit, model_filter, time_restriction)
    - 타입별 전용 입력 폼 제공 (예: cost_limit → 금액/기간 입력, content_filter → 키워드 목록)
    - 정책 시뮬레이션 결과에서 어떤 규칙이 적용되었는지 표시
- **변경 사유**: 인수테스트 Round 4에서 발견. 정책 생성은 가능하나 rule type 선택 UI 부재로 실질적 사용 불가
- **영향 모듈**: FE(정책 페이지), BE(PolicyEngine 검증 로직 보완)
- **영향도**: Medium
- **영향 범위**: PRD-060, PRD-061
- **영향 설계서**: T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.3.0
- **구현 상태**: ✅ 구현 완료 (2026-03-25) — FE RuleTypeForm 컴포넌트(6개 타입 전용 폼), BE PolicyEngine 6개 action 타입 지원
- **변경 일자**: 2026-03-25

### CR-014 | RAG 인제스션 파이프라인 사이드카 일원화
- **대상 기능 ID**: PRD-048, PRD-052, PY-001
- **변경 타입**: 버그수정
- **변경 내용**: 파일 인제스션 파이프라인을 Java(Tika) 중심에서 Python 사이드카 일원화로 변경
  - **문제**: Java IngestionPipeline이 Tika로 문서 파싱 시 텍스트 파일도 빈 결과 반환, PDF/DOCX 등 복합 포맷 파싱 품질 미흡
  - **해결**: 파일 업로드 시 Java BE는 StorageService로 파일 저장만 수행하고, 파싱→청킹→임베딩→DB저장 전체를 Python MCP 사이드카에 위임
  - Python `ingest_file` MCP 도구 신규 추가: 파일 경로 수신 → 확장자별 파싱(txt/md/csv 직접, pdf/docx/pptx 라이브러리) → 청킹 → 임베딩 → pgvector 저장
  - Java `MCPRagClient.ingestFile()` 신규 추가: MCP 호출 래퍼
  - Java `IngestionPipeline`: type="file" 시 MCP 사이드카 가용하면 `ingestFile()` 위임, 불가 시 기존 Java 폴백
  - FE Knowledge.tsx: Phase 3 플레이스홀더 안내문 제거 (기능 구현 완료)
- **변경 사유**: 인수테스트 Round 5에서 파일 인제스션 실패 발견. Python이 문서 파싱(pymupdf, python-docx, unstructured 등) 생태계가 우수하고, 사이드카에 이미 임베딩/청킹 인프라가 있어 전체 파이프라인 위임이 합리적
- **영향 모듈**: RAG(Java IngestionPipeline, Python ingestor), FE(Knowledge)
- **영향도**: Medium
- **영향 범위**: PRD-048, PRD-052, PY-001
- **영향 설계서**: T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.2.1
- **변경 일자**: 2026-03-24

### CR-016 | RAG 평가 LLM Judge 도입 (RAGAS 고도화)
- **대상 기능 ID**: PRD-120(확장), PY-026(확장)
- **변경 타입**: 변경
- **변경 내용**: RAG 평가 메트릭을 임베딩 유사도 기반에서 LLM Judge 기반으로 전환
  - **현재 문제**: Faithfulness/Answer Relevancy/Context Relevancy 등 RAGAS 메트릭이 코사인 유사도로만 산출되어 절대적 품질 지표로 신뢰하기 어려움. 실제 RAGAS 프레임워크는 LLM이 답변/컨텍스트를 판단하는 방식
  - **개선 내용**:
    - Faithfulness: LLM이 답변의 각 문장이 컨텍스트에 근거하는지 판단 (claim-level 분해)
    - Context Relevancy: LLM이 검색된 컨텍스트가 질문에 관련 있는지 판단
    - Answer Relevancy: LLM이 답변이 질문에 적절한지 판단
    - 기존 임베딩 유사도 메트릭은 "빠른 평가" 모드로 유지 (LLM 비용 절감용)
    - 평가 실행 시 mode 선택: "fast"(임베딩 유사도) vs "accurate"(LLM Judge)
  - **인수테스트 관찰 결과**: 동일 PPTX 문서에 대해 Fixed/Semantic 전략 비교 시 임베딩 기반 메트릭이 40~50%대에 정체되며, 전략 변경 효과를 정확히 반영하지 못함
- **변경 사유**: 인수테스트 Round 6에서 발견. 현재 RAGAS 점수가 상대적 비교용으로만 유용하고 절대적 품질 판단에 한계. LLM Judge 도입으로 실제 RAGAS 프레임워크와 동등한 평가 품질 확보
- **영향 모듈**: Python-RAG Pipeline(evaluator.py), BE(EvaluationController 모드 파라미터), FE(평가 모드 선택 UI)
- **영향도**: High
- **영향 범위**: PRD-120, PY-026
- **영향 설계서**: T3-2, T3-5, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.4.0
- **구현 상태**: ✅ 구현 완료 (2026-03-25) — evaluator.py fast/accurate 모드, _llm_judge_*() 함수, FE evalMode 토글
- **변경 일자**: 2026-03-25

### CR-017 | 워크플로우 스튜디오 ConfigPanel 완성 + 모델 라우팅 정책 UI
- **대상 기능 ID**: PRD-086(확장), PRD-087(확장)
- **변경 타입**: 변경
- **변경 내용**: 워크플로우 스튜디오의 노드 설정 패널 완성 및 모델 선택/정책 라우팅 UI 추가
  - **현재 문제 1 — ConfigPanel 타입 불일치**: FE ConfigPanel의 `getConfigFields()`가 `"llm"` 키를 기대하지만, 실제 노드 데이터는 `"LLM_CALL"`로 저장되어 설정 필드가 표시되지 않음
  - **현재 문제 2 — 모델 라우팅 UI 부재**: 스텝별 LLM 연결/모델 선택이 API 직접 호출로만 가능. FE에서 설정 불가
  - **개선 내용**:
    - ConfigPanel 타입 매핑 수정 (LLM_CALL ↔ llm, TOOL_CALL ↔ tool 등)
    - LLM_CALL 스텝에 연결(Connection) 드롭다운 추가 — 등록된 LLM 연결 목록에서 선택
    - 모델 라우팅 모드 선택: "고정 모델"(connection_id+model 지정) vs "자동(정책 기반)"(model: "auto")
    - 자동 모드 시 정책 엔진의 model_filter/cost_limit 규칙에 따라 ModelRouter가 최적 모델 선택
    - 스텝별 품질/비용 트레이드오프 설정: 프리셋 제공 (고품질/균형/저비용)
    - 프롬프트, system 메시지, temperature 등 config 필드 완성
  - **활용 시나리오**:
    - 품질 중요 스텝 (보고서 생성): Claude Opus 고정
    - 비용 절감 스텝 (간단한 분류/추출): Haiku 또는 auto(정책이 저비용 모델 선택)
    - 혼합 워크플로우: step1=Haiku(분류), step2=Sonnet(분석), step3=Opus(최종 생성)
- **변경 사유**: 인수테스트 Round 7에서 발견. 워크플로우 스튜디오에서 노드 클릭 시 이름/타입만 표시되고 핵심 설정(연결, 모델, 프롬프트)을 설정할 수 없음. 또한 스텝별 품질/비용 트레이드오프 제어가 실무에서 핵심 요구사항
- **영향 모듈**: FE(워크플로우 스튜디오 ConfigPanel), BE(WorkflowStep name 필드 추가 완료)
- **영향도**: High
- **영향 범위**: PRD-086, PRD-087
- **영향 설계서**: T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.5.0
- **구현 상태**: ✅ 구현 완료 (2026-03-25) — getConfigFields() LLM_CALL/llm 매핑 수정, connection_id/model/tool_choice/response_schema 필드 완성
- **변경 일자**: 2026-03-25

### CR-018 | 문서 생성 MCP 도구 — LLM 코드 생성 기반 실시간 문서 생산
- **대상 기능 ID**: PRD-130~ (신규), CR-013 구체화
- **변경 타입**: 신규
- **변경 내용**: LLM이 Python 코드를 실시간 생성하여 각종 문서 파일을 생산하는 MCP 도구 추가
  - **CR-013과의 관계**: CR-013(보류)에서 정의한 Level 2~3 범위를 "LLM 코드 생성 + 서버 실행" 접근법으로 구체화. CR-013의 "템플릿 치환/변환기" 접근 대신, LLM이 python-pptx/python-docx/reportlab 등의 코드를 직접 생성하여 실행하는 방식 채택 (Genspark 참고)
  - **핵심 아키텍처**:
    - Python 사이드카에 문서 생성 MCP 도구 등록 (기존 RAG 사이드카와 통합 또는 별도 프로세스)
    - LLM이 사용자 요청을 분석하여 문서 생성 Python 코드를 생성
    - 사이드카가 샌드박스 환경에서 코드 실행 → 파일 생성 → 다운로드 URL 반환
  - **지원 포맷**:
    - PPTX: python-pptx (슬라이드 객체 직접 생성)
    - DOCX: python-docx (문서 객체 직접 생성)
    - PDF: reportlab 또는 weasyprint (HTML→PDF 변환)
    - XLSX: openpyxl (스프레드시트 생성)
    - CSV: 표준 라이브러리 csv
    - HTML: Jinja2 템플릿 렌더링
  - **사용자 흐름**:
    1. 채팅에서 "이 데이터로 IR자료 만들어줘" 등 자연어 요청
    2. LLM이 도구 호출 결정 → `document_generate` MCP 도구 호출
    3. LLM이 포맷/내용에 맞는 Python 코드 생성
    4. 사이드카가 코드 실행 → 파일 생성
    5. 생성된 파일 URL 반환 → FE에서 다운로드
  - **RAG 연동**: 지식 소스에서 검색한 내용을 기반으로 문서 생성 가능 (RAG → 문서 생성 파이프라인)
  - **보안 고려사항**:
    - 코드 실행 샌드박스 (허용 라이브러리 화이트리스트)
    - 파일 크기 제한, 실행 시간 제한
    - 생성 파일 임시 저장 + TTL 자동 삭제
- **변경 사유**: RAG로 읽어들인 지식을 검색만 하는 것이 아니라 문서로 재생산하는 기능 필요. Genspark 등 AI 슬라이드/문서 생성 도구가 LLM으로 python-pptx 코드를 실시간 생성하여 PPTX를 만드는 접근법이 검증됨. MCP 도구로 구현하여 기존 채팅 인터페이스에서 자연어로 문서 생성 요청 가능
- **영향 모듈**: Python 사이드카(MCP 도구), BE(파일 다운로드 API), FE(다운로드 UI), 오케스트레이터(도구 호출)
- **영향도**: High
- **영향 범위**: PRD-130~ (신규), PRD-048(MCP 도구 확장)
- **영향 설계서**: T1-1, T2-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.6.0
- **구현 상태**: ✅ 구현 완료 (2026-03-25) — document_generator.py(샌드박스 코드 실행+보안), template_manager.py(템플릿 CRUD), DocumentController.java(생성/다운로드/템플릿 API), MCP 도구 7개 등록
- **변경 일자**: 2026-03-25
- **요구사항 원본**: `docs/origins/원본_요구사항_문서생성_사이드카_20260325.md`

### CR-019 | 플랫폼 공통 Tool 확장

- **대상 기능 ID**: PRD-134 ~ PRD-146
- **변경 타입**: 신규
- **변경 내용**:
  1. 문서 읽기 Tool 4종 (read_pptx, read_docx, read_excel, read_pdf)
  2. 포맷 변환 Tool (convert_format) — LibreOffice headless 활용
  3. Python 실행 Tool (run_python) — 샌드박스 코드 실행
  4. 차트 렌더링 Tool (render_chart) — matplotlib 기반
  5. 웹 Tool 2종 (web_search, web_fetch)
  6. 알림 발송 Tool (send_notification) — 이메일/슬랙/웹훅
  7. 이미지 분석 Tool (analyze_image) — LLM Vision 멀티모달
  8. 번역 Tool (translate_text) — LLM 체인
  9. Tool 목록 조회 API (GET /api/v1/tools) — 워크플로우 편집기 연동
- **변경 사유**: 범용 LLM 오케스트레이션 플랫폼으로서 도구 커버리지 확대. 소비앱이 공통으로 활용할 수 있는 범용 Tool을 플랫폼 레벨에서 제공. Tool = LLM이 의도를 가지고 호출하는 단위로 정의
- **영향 모듈**: Python 사이드카 (MCP Tool), Spring Boot BE (ToolController, ActionEngine 확장)
- **영향도**: High
- **영향 범위**: PRD-134 ~ PRD-146
- **영향 설계서**: T1-1, T1-2, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v3.7.0
- **구현 상태**: ✅ 구현 완료 (2026-03-25) — TranslationTool(LLM 연결 선택), NotificationTool(bp-notification 연동), ImageAnalysisTool, chart_renderer(한글 폰트), ToolController API, MCPRagClient ToolRegistry 자동 등록
- **변경 일자**: 2026-03-25
- **요구사항 원본**: `docs/origins/원본_요구사항_플랫폼_공통_Tool_확장_20260325.md`

### CR-020 | Aimbase 관리 MCP 도구 노출 (Self-Service Workflow)

- **대상 기능 ID**: PRD-147 ~ PRD-160
- **변경 타입**: 신규
- **변경 내용**:
  1. 조회용 MCP 도구 — `aimbase_list_step_types`, `aimbase_list_tools`, `aimbase_list_connections`, `aimbase_list_prompts`, `aimbase_list_knowledge_sources`, `aimbase_list_schemas`
  2. 워크플로우 관리 MCP 도구 — `aimbase_workflow_create`, `aimbase_workflow_list`, `aimbase_workflow_get`, `aimbase_workflow_update`, `aimbase_workflow_delete`, `aimbase_workflow_validate`, `aimbase_workflow_run`, `aimbase_workflow_run_status`
  3. 도구 description에 StepType별 config 스펙, DAG 규칙(dependsOn, 변수 바인딩), 최대 루프 5회 등 작성 규칙 포함 → 별도 가이드 문서 불필요
  4. 기존 REST API(WorkflowController 등) 재사용, MCP 도구가 내부적으로 같은 서비스 호출
  5. project_id 파라미터 지원 (CR-021 연계)
- **변경 사유**: 도메인 팀이 Aimbase UI를 배우지 않고도, AI 어시스턴트(Claude Code 등)를 통해 자연어로 워크플로우를 등록/관리할 수 있게 함. MCP 도구 description에 규칙을 포함하여 별도 문서 전달 불필요
- **영향 모듈**: MCP Server (Spring AI MCP), WorkflowEngine, ToolRegistry
- **영향도**: High
- **영향 범위**: PRD-147 ~ PRD-160
- **영향 설계서**: T1-1, T1-2, T3-2, T3-5, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.0.0
- **변경 일자**: 2026-03-25
- **선행 CR**: CR-021 (Project 계층이 있어야 project_id 스코핑 가능)

### CR-021 | Project 계층 도입 — 회사 내 프로젝트별 리소스 스코핑

- **대상 기능 ID**: PRD-161 ~ PRD-170
- **변경 타입**: 신규
- **변경 내용**:
  1. `projects` 테이블 신규 — 회사(Tenant DB) 내 프로젝트 정의
  2. `project_members` 테이블 신규 — 사용자 ↔ 프로젝트 N:M 매핑 (프로젝트별 역할: admin/editor/viewer)
  3. `project_resources` 테이블 신규 — 프로젝트 ↔ 리소스 N:M 매핑 (resource_type + resource_id)
  4. 리소스 스코핑 정책:
     - **회사 소속 (현재 그대로)**: Connection, Policy, User, Role
     - **회사 소속 + 프로젝트 할당 (N:M)**: Knowledge Source, Prompt, Schema
     - **프로젝트 소속 (project_id nullable)**: Workflow — 있으면 프로젝트 전용, null이면 회사 공유
  5. API 요청 시 `X-Project-Id` 헤더로 프로젝트 스코프 지정 (미지정 시 회사 전체)
  6. 프로젝트별 접근 제어 — 사용자는 소속 프로젝트 리소스 + 미할당(회사 공유) 리소스만 접근
  7. 기존 데이터 마이그레이션 — project_id null (회사 공유)로 유지, 하위 호환
- **변경 사유**: 도메인 팀(AXOPM, LexFlow 등)이 같은 회사 내에서 프로젝트별로 워크플로우/지식소스를 독립 관리. Connection 등 인프라 자원은 회사 레벨에서 공유. SaaS/On-premise 모두 동일 구조
- **영향 모듈**: Tenant(TenantResolver), 전체 Controller/Service (프로젝트 스코핑), FE (프로젝트 선택 UI)
- **영향도**: High
- **영향 범위**: PRD-161 ~ PRD-170, 기존 전체 리소스 API 영향
- **영향 설계서**: T1-1, T1-2, T1-3, T2-1, T3-1, T3-2, T3-5, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.0.0
- **변경 일자**: 2026-03-25

### CR-022 | 사용자별 리소스 소유 — created_by 기반 개인 리소스 필터링

- **대상 기능 ID**: PRD-171 ~ PRD-175
- **변경 타입**: 변경
- **변경 내용**:
  1. 주요 테넌트 엔티티에 `created_by` 컬럼 추가 (VARCHAR 100, nullable, FK → users.id)
     - 대상: knowledge_sources, workflows, projects, prompts, schemas
  2. 리소스 생성 시 JWT 인증 사용자 ID를 `created_by`에 자동 기록
  3. 목록 조회 API에 `?my=true` 파라미터 추가 — 현재 로그인 사용자가 생성한 리소스만 반환
  4. LexFlow처럼 프로젝트 없이 사용자 단위로 리소스를 관리하는 소비앱 지원
  5. 기존 데이터는 created_by = null (전체 공유)로 유지, 하위 호환
- **변경 사유**: 소비앱에 따라 프로젝트 계층 없이 사용자 개인별 리소스 관리가 필요 (예: LexFlow — 각 사용자가 자기 문서를 업로드/검색). created_by 필드로 리소스 소유권 추적 + 개인 필터링 지원
- **영향 모듈**: KnowledgeController, WorkflowController, PromptController, SchemaController, ProjectController
- **영향도**: Medium
- **영향 범위**: PRD-171 ~ PRD-175, 기존 리소스 CRUD API 전체
- **영향 설계서**: T1-1, T1-3, T3-1, T3-2, T3-5, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.1.0
- **변경 일자**: 2026-03-25
- **선행 CR**: CR-021 (프로젝트 계층)

### CR-023 | 테넌트 소비앱 메타데이터 — domain_app 컬럼 도입

- **대상 기능 ID**: PRD-176 ~ PRD-178
- **변경 타입**: 변경
- **변경 내용**:
  1. Master DB `tenants` 테이블에 `domain_app` 컬럼 추가 (VARCHAR 50, nullable)
     - 소비앱 식별: AXOPM, LexFlow, ChatPilot 등
  2. 테넌트 생성 API에 `domainApp` 필드 추가
  3. 테넌트 목록 조회 시 `?domain_app=AXOPM` 필터 지원
  4. 테넌트 수정 API에서 domainApp 변경 지원
  5. FE 테넌트 관리 UI에 소비앱 컬럼 표시 + 필터 드롭다운
- **변경 사유**: 테넌트 ID (`axopm_companyA`)로 소비앱을 유추할 수 있으나, 명시적 메타데이터로 관리해야 플랫폼 대시보드에서 소비앱별 테넌트 그룹핑/필터링이 가능. 향후 소비앱별 기능 활성화(Feature Flag) 기반이 됨
- **영향 모듈**: TenantEntity(Master), PlatformController, FE Tenants.tsx
- **영향도**: Medium
- **영향 범위**: PRD-176 ~ PRD-178, 테넌트 관리 API
- **영향 설계서**: T1-1, T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.1.0
- **변경 일자**: 2026-03-25

---

### CR-024 | 개별 텍스트 인제스션 API (ingest-text)

- **대상 기능 ID**: PRD-179
- **변경 타입**: 신규
- **변경 내용**:
  1. `POST /api/v1/knowledge-sources/{id}/ingest-text` 엔드포인트 추가
     - Request: `{ "content": "텍스트", "documentId": "entity_123" }`
     - 동작: 기존 documentId 임베딩 삭제 → 청킹 → 임베딩 → 저장 (upsert)
  2. `DELETE /api/v1/knowledge-sources/{id}/documents/{documentId}` 엔드포인트 추가
     - 특정 문서의 임베딩만 삭제
  3. `EmbeddingRepository.deleteBySourceIdAndDocumentId()` 메서드 추가
  4. 기존 `MCPRagClient.ingestDocument()` 내부 메서드를 REST로 노출하는 래퍼
  5. 소스의 chunkingConfig/embeddingModel 설정을 자동 적용
  6. 인제스션/삭제 후 소스의 chunkCount 갱신
- **변경 사유**: 기존 API(upload+sync)는 소스 전체 재인제스션 구조로, 외부 시스템(AXOPM 등)에서 엔티티 CUD마다 개별 문서를 실시간 학습시키는 시나리오를 지원하지 못함. 내부적으로 개별 인제스션 기능은 있었으나 REST로 노출되지 않았음
- **영향 모듈**: KnowledgeController
- **영향도**: Low
- **영향 범위**: PRD-179, Knowledge API
- **영향 설계서**: T3-2
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.2.0
- **변경 일자**: 2026-03-28

---

### CR-025 | 시스템 API Key 관리 — 독립 엔티티 + 관리 UI + 해시 버그 수정

- **대상 기능 ID**: PRD-180 ~ PRD-186
- **변경 타입**: 신규
- **변경 내용**:
  1. **ApiKey 독립 엔티티 신설** — 기존 users.api_key_hash에서 분리
     - 테이블: `api_keys` (id, name, key_hash, scope, user_id FK, expires_at, last_used_at, is_active, created_by, created_at)
     - 서비스 연동 목적의 시스템 키를 사람 계정과 분리하여 관리
  2. **API Key CRUD 엔드포인트** — `/api/v1/admin/api-keys`
     - POST: 키 생성 (name, scope, expires_at 지정)
     - GET: 키 목록 조회 (마스킹된 키, 마지막 사용일, 만료일 표시)
     - DELETE /{id}: 키 폐기 (is_active=false)
     - POST /{id}/regenerate: 키 재발급 (기존 키 폐기 + 신규 발급)
  3. **해시 방식 통일 (버그 수정)** — UserController.regenerateApiKey()의 BCrypt 저장을 SHA-256으로 통일
     - ApiKeyAuthenticationFilter가 SHA-256으로 비교하므로, 저장도 SHA-256 사용
     - 새 api_keys 테이블에서도 동일하게 SHA-256 해시
  4. **ApiKeyAuthenticationFilter 확장** — 새 api_keys 테이블 우선 조회, 기존 users.api_key_hash는 하위 호환으로 유지
     - last_used_at 자동 갱신
     - 만료(expires_at) 검증 추가
     - scope 기반 접근 제어 (향후 확장 가능)
  5. **FE 관리 페이지** — Settings > API Keys 페이지 신규
     - 키 목록 (name, 마스킹된 키 앞 8자, scope, 마지막 사용일, 만료일, 상태)
     - 생성 모달 (name, scope 선택, 만료일 설정)
     - 생성 직후 1회 키 표시 + 클립보드 복사
     - 폐기/재발급 버튼
  6. **기존 users.api_key_hash 하위 호환**
     - 기존 사용자 기반 API Key도 당분간 동작 유지 (마이그레이션 기간)
     - UserController의 regenerateApiKey 해시를 SHA-256으로 수정
  7. **Flyway 마이그레이션**
     - Tenant DB: V32__create_api_keys_table.sql
- **변경 사유**: 현재 API Key가 사용자 엔티티에 종속되어 있어, 시스템 연동용 서비스 키를 독립적으로 관리할 수 없음. 사람 계정(admin@companyA.com)을 연동에 사용하면 비밀번호 변경/비활성화 시 연동이 끊김. 또한 생성(BCrypt)과 검증(SHA-256) 해시 불일치로 API Key 인증이 동작하지 않는 버그 존재
- **영향 모듈**: Auth (ApiKeyAuthenticationFilter), User (UserController), 신규 ApiKey 엔티티/Controller, FE (신규 페이지)
- **영향도**: High
- **영향 범위**: PRD-107 (API Key 인증 수정), PRD-180 ~ PRD-186 (신규)
- **영향 설계서**: T1-1, T1-2, T3-1, T3-2, T3-5, T3-6, T4-1
- **요청자**: sykim | **승인자**: - | **적용 버전**: v4.3.0
- **변경 일자**: 2026-03-28
- **선행 CR**: CR-010 (인증 체계)

---

### CR-026 | Metronic 9 UI 프레임워크 전환 — Tailwind CSS + Metronic 컴포넌트 기반 FE 전면 교체
- **대상 기능 ID**: FE 전체 (PRD-055~085 UI 대응)
- **변경 타입**: 변경
- **변경 내용**:
  1. **UI 프레임워크 전환** — 100% 인라인 스타일 → Metronic 9.4.3 (Tailwind CSS 4 + CVA + Radix UI)
  2. **Tailwind CSS 인프라 도입** — tailwindcss, @tailwindcss/vite, clsx, tailwind-merge, CVA
  3. **Metronic UI 컴포넌트 도입** — ~25개 컴포넌트 (Button, Badge, Dialog, Card, Table, DataGrid, Input, Select, Tabs, Accordion 등)
  4. **아이콘 시스템 교체** — 이모지 → Lucide React 아이콘
  5. **레이아웃 교체** — AppShell, Sidebar(LNB), PageHeader → Metronic 레이아웃 + Tailwind 스타일 (LNB 220px 유지)
  6. **공통 컴포넌트 교체** — ActionButton, Badge, Modal, DataTable, FormField, StatCard, EmptyState, LoadingSpinner → Metronic 래퍼
  7. **페이지 ~20개 스타일 교체** — 인라인 스타일 → Tailwind 클래스 (로직 무변경)
  8. **theme.ts 전환** — COLORS/FONTS 인라인 상수 → CSS 변수 + Tailwind 유틸리티 클래스
  - **변경하지 않는 영역**: hooks/(14개), api/(17개), types/(19개), store/, workflow/utils/, 특수 라이브러리(@xyflow/react, dagre, recharts)
- **변경 사유**: 프로덕션 품질 UI/UX 확보. 현재 인라인 스타일은 유지보수 부담 크고, hover/반응형/다크모드 지원 제한적. Metronic은 검증된 엔터프라이즈 Admin UI로 일관된 디자인 시스템 제공
- **영향 모듈**: FE 전체 (레이아웃 3개, 공통 컴포넌트 8개, 워크플로우 컴포넌트 4개, 페이지 20개, 테마 1개)
- **영향도**: High
- **영향 범위**: FE 전체 UI, BE/Python 영향 없음
- **영향 설계서**: T2-1, T1-7, T3-6, CLAUDE.md
- **요청자**: sykim | **승인자**: - | **적용 버전**: v5.0.0
- **변경 일자**: 2026-03-28
- **선행 CR**: 없음 (독립 UI 교체)

---

### CR-027 | 로그인 테넌트 자동 resolve — Master DB 사용자-테넌트 매핑 기반 인증 흐름 개선

- **대상 기능 ID**: PRD-107(변경), PRD-187~PRD-190(신규)
- **변경 타입**: 변경
- **변경 내용**:
  1. **Master DB `user_tenant_map` 테이블 신설** — email(UNIQUE) → tenant_id(FK) 매핑
     - 컬럼: id(UUID PK), email(VARCHAR 255 UNIQUE), tenant_id(VARCHAR 100 FK→tenants.id), created_at
     - 한 사용자가 여러 테넌트에 속하는 경우 향후 UNIQUE 해제 + 테넌트 선택 UI 확장 가능
  2. **AuthController.login() 변경** — `X-Tenant-Id` 헤더 없이 로그인 가능
     - 기존: `X-Tenant-Id` 필수 → 해당 Tenant DB에서 users 조회
     - 변경: `X-Tenant-Id` 미전달 시 → Master DB `user_tenant_map`에서 email로 tenant_id 조회 → 해당 Tenant DB로 라우팅 → 인증
     - `X-Tenant-Id` 전달 시 기존 동작 유지 (하위 호환)
  3. **로그인 응답에 tenant_id 추가** — 클라이언트가 이후 요청에 사용
     - 응답 필드: `{ ..., "tenant_id": "bidding_system" }`
  4. **사용자 생성/삭제 시 매핑 동기화**
     - UserController.create() → Master DB `user_tenant_map`에 INSERT
     - UserController.delete() → Master DB `user_tenant_map`에서 DELETE
     - TenantOnboardingService.seedInitialData() → 초기 admin 매핑 등록
  5. **FE client.ts 변경** — 하드코딩된 `X-Tenant-Id` 제거
     - 로그인 응답의 `tenant_id`를 localStorage에 저장
     - 이후 API 요청에 자동 첨부
  6. **FE Login.tsx 변경** — `X-Tenant-Id` 의존 제거, email+password만으로 로그인
- **변경 사유**: 현재 FE에서 `X-Tenant-Id`가 하드코딩되어 테넌트 전환이 불가능하고, 소비앱 개발자가 자기 테넌트 ID를 미리 알아야 하는 비합리적 UX. 사용자 계정 기반으로 테넌트를 자동 판별해야 올바른 인증 흐름
- **영향 모듈**: Auth(AuthController, TenantResolver), Tenant(TenantOnboardingService), User(UserController), FE(client.ts, Login.tsx)
- **영향도**: High
- **영향 범위**: PRD-107, PRD-187~PRD-190, 인증 흐름 전체
- **영향 설계서**: T3-1, T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v5.1.0
- **변경 일자**: 2026-03-28
- **선행 CR**: CR-010 (인증 체계)

---

### CR-028 | LLM_CALL 토큰 초과 자동 처리 — 에스컬레이션 + 자동분할(Auto-Split)
- **대상 기능 ID**: PRD-009 (워크플로우 DAG 실행)
- **변경 타입**: 변경
- **변경 내용**:
  `LlmCallStepExecutor`에 3단계 토큰 초과 자동 처리 전략 도입:
  1. **Phase 1: 일반 호출** (4096) — 성공 시 즉시 반환
  2. **Phase 2: 에스컬레이션** (8192) — 살짝 넘는 경우 1회 재시도
  3. **Phase 3: 자동분할** — 크게 넘는 경우
     - 3-a: LLM에게 분할 계획 요청 → 파트 목록 획득
     - 3-b: 파트별 독립 실행 (각 4096, 실패 시 1회 재시도)
     - 3-c: 파트 결과를 원본 response_schema에 맞게 취합 → 완성된 JSON 반환
  - 소비앱은 분할 여부를 알 수 없음 — 동일한 structured_data 형태로 반환
  - step config `max_tokens` 설정 지원 (상한선으로 동작)
  - 각 단계별 로깅 (에스컬레이션, 분할 계획, 파트 진행, 취합)
- **변경 사유**: AXOPM OPM 구조 자동 생성 워크플로우에서 LLM 응답이 max_tokens(4096)에서 잘려 structured_data가 빈 `{}`로 반환됨. CR-017 증거 요건 추가로 출력량 급증. 소비앱 개발자가 토큰 전략을 이해하고 관리해야 하는 구조는 플랫폼 설계 실패 — 플랫폼이 자동 처리해야 함
- **영향 모듈**: Workflow (LlmCallStepExecutor)
- **영향도**: High
- **영향 범위**: BIZ-009, 모든 LLM_CALL 스텝 사용 워크플로우
- **영향 설계서**: T3-2, T3-6
- **요청자**: sykim | **승인자**: - | **적용 버전**: v5.2.0
- **변경 일자**: 2026-03-28
- **선행 CR**: CR-007 (구조화된 출력), CR-017 (ConfigPanel)

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
