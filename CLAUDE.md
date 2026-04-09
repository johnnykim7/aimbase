# CLAUDE.md - Aimbase

## 프로젝트 개요
- 멀티테넌트 LLM 오케스트레이션 플랫폼
- 여러 LLM 프로바이더(Anthropic, OpenAI, Ollama)를 통합 관리
- 정책 엔진, 워크플로우, RAG, MCP 도구 통합 지원
- Database-per-Tenant 멀티테넌시 아키텍처

## 기술 스택

### Backend
- Java 21 (Virtual Threads 활성화)
- Spring Boot 3.4.2
- Spring Data JPA + Hibernate 6.3 (Hypersistence Utils for JSONB)
- PostgreSQL 16 + pgvector
- Redis 7 (세션, 캐시, Rate Limiting)
- Flyway (멀티DB 마이그레이션)
- Gradle 8.x (Kotlin DSL)

### Frontend
- React 18 + TypeScript 5.6
- Vite 5.4
- Tailwind CSS 4 + Metronic 9.4.3 (UI 프레임워크)
- Lucide React (아이콘)
- class-variance-authority (cva) + clsx + tailwind-merge
- TanStack React Query 5.90
- React Router 6.27
- Axios 1.7

## BE 아키텍처 규칙

### 프로젝트 구조
```
backend/platform-core/src/main/java/com/platform/
├── api/           # REST Controllers (@RestController)
├── domain/        # JPA Entities
├── repository/    # JPA Repositories
├── config/        # Spring 설정 클래스
├── orchestrator/  # 핵심 요청 오케스트레이션
├── llm/           # LLM 어댑터 (adapter/, router/)
├── tool/          # 도구 레지스트리 & 실행
├── agent/         # 서브에이전트 (SubagentRunner, WorktreeManager, AgentOrchestrator)
├── action/        # 액션 실행 (write/notify)
├── workflow/      # 워크플로우 엔진
├── rag/           # RAG (파싱, 청킹, 임베딩, 검색)
├── policy/        # 정책 엔진 & 감사 로깅
├── tenant/        # 멀티테넌시 (DataSource 라우팅)
├── session/       # 세션 관리 (Redis)
├── mcp/           # MCP 서버 관리
└── monitoring/    # 메트릭 & 모니터링
```

### 레이어 규칙
- Controller → Service/Engine → Repository 순서
- Controller는 요청 검증 + 응답 래핑만 담당
- 비즈니스 로직은 Engine/Service 레이어에 위치
- 모든 API 응답은 ApiResponse<T> 래퍼 사용

### 멀티테넌시 규칙
- TenantContext.getCurrentTenantId()로 현재 테넌트 식별
- Master DB 전용: /api/v1/platform/** 엔드포인트
- Tenant DB 전용: 그 외 모든 엔드포인트
- Repository는 master/tenant 패키지로 분리

### JSONB 사용 규칙
- 동적 설정(config, rules, steps 등)은 JSONB 컬럼으로 저장
- Hypersistence JsonType 어노테이션 사용
- Java 타입: Map<String, Object> 또는 List<Map<String, Object>>

## FE 아키텍처 규칙

### 프로젝트 구조
```
frontend/src/
├── App.tsx           # 라우팅 정의
├── main.tsx          # 엔트리포인트
├── lib/utils.ts      # cn() 유틸 (clsx + tailwind-merge)
├── styles/           # Tailwind CSS 변수 + 레이아웃
├── api/              # API 클라이언트 (Axios)
├── pages/            # 라우트 컴포넌트
│   └── platform/     # Super Admin 페이지
├── components/       # 재사용 컴포넌트
│   ├── layout/       # AppShell, Sidebar, PageHeader
│   ├── common/       # DataTable, Modal, Badge, FormField 등
│   ├── ui/           # Metronic 기반 UI 프리미티브 (Button, Card, Dialog 등)
│   └── workflow/     # WorkflowStudio 전용 컴포넌트
├── hooks/            # 커스텀 훅 (useXxx)
├── types/            # TypeScript 인터페이스
└── store/            # 상태 관리
```

### 컴포넌트 규칙
- 페이지 컴포넌트: pages/ 에 위치, 라우트 단위
- 공통 컴포넌트: components/common/ 에 위치 (Metronic UI 래퍼)
- UI 프리미티브: components/ui/ 에 위치 (Metronic 9에서 복사)
- 모든 API 호출은 hooks/ 에서 React Query로 래핑
- 스타일: Tailwind CSS 4 + Metronic 9 컴포넌트 (CSS 변수 기반, `cn()` 유틸 사용)
- 아이콘: Lucide React
- 동적 색상 (recharts, React Flow): 직접 hex 상수 사용 (Tailwind 클래스 불가 영역)

### 상태 관리 규칙
- 서버 상태: React Query (useQuery/useMutation)
- 로컬 UI 상태: useState
- 전역 상태: 최소화 (Context 미사용, React Query로 대체)

## 네이밍 규칙

### Backend
- 클래스: PascalCase (e.g., `ConnectionController`, `PolicyEngine`)
- 메서드: camelCase (e.g., `createConnection`, `evaluatePolicy`)
- 패키지: lowercase (e.g., `com.platform.api`)
- 테이블: snake_case (e.g., `knowledge_sources`, `workflow_runs`)
- 컬럼: snake_case (e.g., `created_at`, `is_active`, `db_password_encrypted`)
- 인덱스: `idx_{테이블}_{컬럼}` (e.g., `idx_connections_type`)
- Enum: UPPER_SNAKE_CASE (e.g., `TOOL_USE`, `REQUIRE_APPROVAL`)

### Frontend
- 컴포넌트: PascalCase (e.g., `Dashboard.tsx`, `DataTable.tsx`)
- 훅: camelCase with `use` prefix (e.g., `useConnections`, `usePolicies`)
- 타입: PascalCase (e.g., `Connection`, `PolicyRequest`)
- API 함수: camelCase with verb (e.g., `fetchConnections`, `createPolicy`)

### API
- 경로: kebab-case (e.g., `/api/v1/knowledge-sources`, `/api/v1/mcp-servers`)
- 파라미터: snake_case (e.g., `session_id`, `connection_id`)
- 응답 필드: snake_case (e.g., `cost_today_usd`, `input_tokens`)

## 핵심 비즈니스 규칙
- 도구 호출 루프 최대 5회 (BIZ-001)
- 세션 TTL 24시간 (BIZ-002)
- Database-per-Tenant 격리 (BIZ-003)
- 정책 평가: priority 내림차순, 첫 DENY/REQUIRE_APPROVAL에서 중단 (BIZ-005)
- 워크플로우 DAG 실행: Kahn 알고리즘 위상 정렬, SUB_WORKFLOW로 공용 워크플로우 참조 실행 (BIZ-009)
- 임베딩: BGE-M3, 1024차원 기본. OpenAI text-embedding-3-small 선택 가능 (BIZ-012)
- 모든 주요 이벤트 감사 로깅 필수 (BIZ-020)
- 에이전트 메시지 본문 최대 32KB, 세션당 500개 제한 (BIZ-057)
- Built-in Agent 5타입: GENERAL/PLAN/EXPLORE/GUIDE/VERIFICATION (BIZ-058)
- Hook 이벤트 26종: Tool(3) + Orchestration(4) + Compact(2) + Subagent(2) + CR-034(14+1) (BIZ-059)
- 팀당 멤버 최대 5명 (BIZ-073)
- 세션당 활성 팀 최대 3개 (BIZ-074)
- Notebook 최대 10MB (BIZ-075)
- LSP 프로세스 5분 미사용 자동 종료 (BIZ-076)
- LSP 초기 지원 언어 3개: java/typescript/python (BIZ-077)

## 테스트 전략

| 레이어 | 필수여부 | 테스트 대상 | 파일 패턴 |
|--------|---------|-----------|----------|
| Controller | 선택 | API 엔드포인트 통합 | `*ControllerTest.java` |
| Engine/Service | 필수 | 비즈니스 로직, FSM 전환 | `*EngineTest.java`, `*ServiceTest.java` |
| Repository | 선택 | JPQL/네이티브 쿼리 | `*RepositoryTest.java` |
| FE Hook | 필수 | API 호출, 상태 관리 | `use*.test.ts` |
| FE Component | 선택 | 렌더링, 이벤트 핸들링 | `*.test.tsx` |

### 모킹 전략
- DB: TestContainers (PostgreSQL, Redis)
- LLM: Mock 어댑터 (테스트용 고정 응답)
- 외부 API: WireMock 또는 Mock 서버

### 커버리지 기준
- Engine/Service: 80% 이상
- Controller: 주요 시나리오만
- FE: 핵심 훅 100%

## 현재 진행 상태
- [x] Sprint 0: 인프라 세팅 (Gradle, Flyway, Docker Compose, CI)
- [x] Sprint 1-10: BE 구현
- [x] Sprint 11-14: FE 구현
- [x] Sprint 15-18: Python 사이드카 (RAG Pipeline, Safety, Evaluation, 고급 기능)
- [x] Sprint 19: FE 워크플로우 스튜디오 (React Flow 기반 비주얼 DAG 에디터) [CR-005]
- [x] Sprint 20: 도구 선택 제어 (ToolFilterContext, tool_choice) [CR-006]
- [x] Sprint 21: 구조화된 출력 (response_format, output_schema, FE 스키마 편집) [CR-007]
- [x] Sprint 22-25: Python 알파 + 플랫폼 강화 [CR-009, CR-010]
- [x] Sprint 26: RAG A++ Phase 1 — Contextual Retrieval + Parent-Child + Query Transform [CR-011]
- [x] Sprint 27: RAG A++ Phase 2 — RAGAS 평가 + 고급 파싱 [CR-011]
- [x] Sprint 28: RAG A++ Phase 3 — 시맨틱 캐시 + Citation + 증분 인제스션 + TTS/STT [CR-011]
- [x] Sprint 29: 로컬 임베딩 전환 (BGE-M3 기본화, 가변 차원) [CR-012]
- [x] Sprint 30-31: 플랫폼 공통 Tool 확장 + 문서 생성 MCP [CR-018, CR-019]
- [x] Sprint 32: CR-015 정책 UI 개선 + CR-016 LLM Judge + CR-017 ConfigPanel 완성 + 인프라 수정
- [x] Sprint 33: CR-020 MCP 관리 도구 (14개) + CR-021 프로젝트 계층 (BE+FE)
- [x] Sprint 34: Metronic 인프라 + 레이아웃 교체 [CR-026]
- [x] Sprint 35: 공통 컴포넌트 교체 (8개 + theme.ts) [CR-026]
- [x] Sprint 36: 기본 CRUD 페이지 9개 스타일 교체 [CR-026]
- [x] Sprint 37: 고급 페이지 6개 스타일 교체 [CR-026]
- [x] Sprint 38: WorkflowStudio + 플랫폼 4개 교체 [CR-026]
- [x] Sprint 39: 정리 + 최종 검증 [CR-026]
- [x] Sprint 40: LLM_CALL 토큰 초과 자동 처리 — 에스컬레이션 + 자동분할 [CR-028]
- [x] Sprint 41: CR-030 2단계 고도화 6 Phase (PRD-186~210) — Extended Thinking, Hook Architecture, Permission AUTO, Memory Scope, 압축 5전략, Subagent+Worktree Isolation
- [x] Sprint 42: CR-031 성능/퀄리티 메커니즘 6 Phase (PRD-211~216) — Post-Compact Recovery, MICRO_COMPACT 0비용, Extract Memories 자동화, Adaptive Thinking, Tool Result 축약, Agent 진행 요약
- [x] Sprint 43: CR-032 프로바이더 확장 6 Phase (PRD-217~221) — OpenAI Compatible shim, AWS Bedrock, Vertex AI, 에이전트별 라우팅, FE Connection 폼
- [x] Sprint 44: CR-033 에이전트 구조적 사고 체계 6 Phase (PRD-222~227, FE-015) — Plan Mode(Enter/Exit/Verify), TodoWrite, Task(Create/Get/List/Update/Output/Stop), FE 대시보드
- [x] Sprint 45: CR-034 멀티에이전트 협업 완성 6 Phase (PRD-228~233, FE-016) — SendMessageTool, Built-in Agent 5타입, Hook 이벤트 14개 추가, FE 메시지 패널
- [x] Sprint 46: CR-035 Tool/Policy 확장성·자동화 6 Phase (PRD-234~240, FE-017) — ScheduleCron(3 Tool), SkillTool, ToolSearchTool, Firecrawl 연동, DOMAIN_FILTER 정책, FE 관리 UI
- [x] Sprint 47: CR-037 핵심 도구 네이티브화 4 Phase (PRD-241~244, FE-018) — BashTool, FileWriteTool, WebSearchTool, SuggestBackgroundPR (ClaudeCodeTool 의존 해소)
- [x] Sprint 48: CR-038 에이전트 자율성 강화 4 Phase (PRD-245~248, FE-019) — ListMcpResources, ReadMcpResource, RemoteTriggerTool, BriefTool, FE 세션 브리핑
- [x] Sprint 49: CR-036 프롬프트 외부화 + 영문 전환 + OpenClaude 포팅 10 Phase (PRD-249~260, FE-020~021) — prompt_templates 테이블, PromptTemplateService(캐시+폴백), 기존 25개 외부화, OpenClaude 48개 포팅, Python 연동, FE 관리화면
- [x] Sprint 50: CR-040 런타임 설정 관리 3 Phase (PRD-269~272, FE-023) — global_config seed 12개, PlatformSettingsService(캐시+감사), GET/PUT API, 하드코딩 7곳 교체, FE 설정 관리 페이지
- [ ] Sprint 50: CR-039 고급 확장 도구 4 Phase (PRD-265~268, FE-022) — TeamCreate/Delete(Swarm 팀 협업), NotebookEditTool(.ipynb 편집), LSPTool(코드 분석), FE 팀 관리 UI

## 참조 문서
- `docs/T1-*` — 요구사항 명세 (T1-1 ~ T1-8)
- `docs/T2-*` — 아키텍처 설계 (T2-1 ~ T2-2)
- `docs/T3-*` — 상세 설계 (T3-1 ~ T3-6)
- `docs/T4-*` — 배포 게이트/데모 (T4-3, T4-7). 검증/운영은 AI-TOLC(aimbase-tolc)에서 관리
- `docs/T3-6_실행_지시서.md` — 구현 마스터 문서
- `docs/guides/aimbase-api-guide.md` — REST API 통합 가이드 (소비앱 연동용, 버전 관리됨)
- `docs/guides/aimbase-ops-guide.md` — 운용 가이드 (플랫폼/테넌트 관리자용, 버전 관리됨)

## 가이드 문서 유지보수 규칙

### API 가이드 (`docs/guides/aimbase-api-guide.md`)
- 소비앱이 호출하는 API 엔드포인트 추가/변경/삭제 시 반드시 업데이트
- 하단 변경 이력 테이블에 버전 번호, 날짜, 변경 내용 추가
- 버전: 엔드포인트 추가 = minor(x.Y.0), 설명 보정 = patch(x.y.Z)

### 운용 가이드 (`docs/guides/aimbase-ops-guide.md`)
- 관리자용 기능(Connection, 정책, RAG, 워크플로우, 테넌트 등) 추가/변경/삭제 시 반드시 업데이트
- 새 운영 시나리오 추가 시 § 4에 시나리오 추가
- 하단 변경 이력 테이블에 버전 번호, 날짜, 변경 내용 추가
- 버전: 관리 기능 추가 = minor(x.Y.0), 설명/시나리오 보정 = patch(x.y.Z)

## 주의사항
- **PostgreSQL은 호스트 로컬(localhost:5432)에서 공용 운영. Docker 컨테이너 DB(aimbase-postgres) 접근 금지.**
  - DB 조회/생성/수정: `psql -U platform -h localhost -p 5432` 사용
  - `docker exec aimbase-postgres psql ...` 절대 사용 금지
  - 테넌트 API 성공 시 로컬 DB에 이미 프로비저닝됨 — Docker DB 확인하러 가지 말 것
- `/api/v1/platform/**` 엔드포인트는 Master DB만 접근 (BIZ-024)
- JSONB 컬럼 변경 시 기존 데이터 호환성 확인 필수
- Flyway 마이그레이션은 Master/Tenant 별도 경로 사용
- Virtual Threads 사용 시 synchronized 블록 주의 (pinning)
- pgvector HNSW 인덱스는 대량 데이터 시 빌드 시간 고려
