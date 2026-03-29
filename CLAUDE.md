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
├── App.tsx        # 라우팅 정의
├── main.tsx       # 엔트리포인트
├── theme.ts       # 디자인 토큰
├── api/           # API 클라이언트 (Axios)
├── pages/         # 라우트 컴포넌트
│   └── platform/  # Super Admin 페이지
├── components/    # 재사용 컴포넌트
│   ├── layout/    # AppShell, Sidebar, PageHeader
│   └── common/    # DataTable, Modal, Badge, FormField 등
├── hooks/         # 커스텀 훅 (useXxx)
├── types/         # TypeScript 인터페이스
└── store/         # 상태 관리
```

### 컴포넌트 규칙
- 페이지 컴포넌트: pages/ 에 위치, 라우트 단위
- 공통 컴포넌트: components/common/ 에 위치
- 모든 API 호출은 hooks/ 에서 React Query로 래핑
- 스타일: theme.ts 색상/폰트 변수 직접 참조 (인라인 스타일)

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
- 워크플로우 DAG 실행: Kahn 알고리즘 위상 정렬 (BIZ-009)
- 임베딩: text-embedding-3-small, 1536차원 (BIZ-012)
- 모든 주요 이벤트 감사 로깅 필수 (BIZ-020)

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
- [x] Sprint 22: ClaudeCodeTool 안정화 및 확장 (cli_options, 에러분류, 서킷브레이커, 알림) [CR-011]
- [x] Sprint 22: 오케스트레이터 지능화 (GenericCircuitBreaker, FallbackChain, IntentClassifier, ConversationSummarizer) [CR-012]
- [x] Sprint 23: 캐시 + 메모리 아키텍처 (ResponseCacheService, MemoryService 4계층) [CR-012]
- [x] Sprint 23: 파일업로드/대화히스토리 (StorageService, ConversationController DB영속) [CR-009/010]
- [x] Sprint 24: 멀티모달/트레이싱 (멀티모달 API, TraceService, 구조화출력 완성) [CR-009/010]
- [x] Sprint 25: 비용대시보드/검색설정 (ModelPricing, AdminController, FE 차트) [CR-009/010]

## 참조 문서
- `docs/T1-*` — 요구사항 명세 (T1-1 ~ T1-8)
- `docs/T2-*` — 아키텍처 설계 (T2-1 ~ T2-2)
- `docs/T3-*` — 상세 설계 (T3-1 ~ T3-6)
- `docs/T4-*` — 검증/운영 산출물 (T4-1 ~ T4-7)
- `docs/T3-6_실행_지시서.md` — 구현 마스터 문서

## 주의사항
- `/api/v1/platform/**` 엔드포인트는 Master DB만 접근 (BIZ-024)
- JSONB 컬럼 변경 시 기존 데이터 호환성 확인 필수
- Flyway 마이그레이션은 Master/Tenant 별도 경로 사용
- Virtual Threads 사용 시 synchronized 블록 주의 (pinning)
- pgvector HNSW 인덱스는 대량 데이터 시 빌드 시간 고려
