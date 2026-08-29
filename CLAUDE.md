# CLAUDE.md - Aimbase

> **이 파일은 "몰라서 틀리는 것"만 담는다.** 조회성·히스토리는 `docs/` 로 뺐고,
> 각 링크에 **언제 열어야 하는지**를 적어 뒀다.

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
- 에이전트 하트비트 간격 60초 (BIZ-078)
- 에이전트 stale 임계값 5분 (BIZ-079)
- 원격 도구 동기화 주기 30초 (BIZ-080)
- Claude CLI 어댑터는 테넌트 피처 플래그 허용 시에만 활성화, 상용 외부 테넌트는 ToS 경계상 비활성 유지 (BIZ-099)
- run당 CLI 워커 기본 상한 5개, 초과 시 큐잉(Semaphore 대기) (BIZ-100)

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

**Sprint 0~51 + CR-050 완료.** 미완 1건 — CR-039 고급 확장 도구
(TeamCreate/Delete, NotebookEditTool, LSPTool, FE 팀 관리 UI).

> 📖 **`docs/Sprint_진행_이력.md` 를 읽는다 —**
> 특정 기능이 어느 스프린트·CR 에서 들어왔는지 추적할 때 / PRD 번호로 구현 시점을 찾을 때.

## 참조 문서
- `docs/T1-*` — 요구사항 명세 (T1-1 ~ T1-8)
- `docs/T2-*` — 아키텍처 설계 (T2-1 ~ T2-2)
- `docs/T3-*` — 상세 설계 (T3-1 ~ T3-6)
- `docs/T4-*` — 배포 게이트/데모 (T4-3, T4-7). 검증/운영은 AI-TOLC(aimbase-tolc)에서 관리
- `docs/T3-6_실행_지시서.md` — 구현 마스터 문서
- `docs/guides/aimbase-api-guide.md` — REST API 통합 가이드 (소비앱 연동용, 버전 관리됨)
- `docs/guides/aimbase-ops-guide.md` — 운용 가이드 (플랫폼/테넌트 관리자용, 버전 관리됨)
- `docs/guides/aimbase-sdk-guide.md` — Tool SDK 사용 가이드 (소비앱 Agent 구축용, 버전 관리됨)

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
