# Frontend 개발 계획 — Aimbase Admin UI

## Context

백엔드(Phase 1 Core + Multi-Tenancy + Phase 2 MCP)가 완성된 상태에서, 현재 프론트엔드는 `<h1>Aimbase</h1>` 스켈레톤만 존재한다. `docs/LLMPlatformAdmin.jsx`를 레퍼런스로 삼아 실제 API와 연결된 Admin UI를 구현한다.

**Goal**: 다크테마 Admin UI (대시보드 ~ 플랫폼 관리 전체 페이지) 를 React + TypeScript로 구현하고 실제 백엔드 API와 연결한다.

---

## 기술 결정

- **스타일**: `LLMPlatformAdmin.jsx`와 동일한 인라인 CSS (Tailwind 설치 안 함)
- **컬러/폰트**: `theme.ts` 파일로 COLORS/FONTS 상수 추출 후 공통 임포트
- **상태관리**: TanStack Query v5 (설치 추가 필요: `@tanstack/react-query`)
- **라우팅**: react-router-dom v6 (이미 설치됨)
- **HTTP**: axios (이미 설치됨)

---

## 의존성 추가

```bash
npm install @tanstack/react-query@5
```

`package.json` dependencies에 `"@tanstack/react-query": "^5.62.0"` 추가.

---

## 파일 구조 (전체 신규 생성 목록)

```
frontend/
├── index.html                          수정: Google Fonts + CSS reset + keyframes
├── package.json                        수정: @tanstack/react-query 추가
├── src/
│   ├── main.tsx                        수정: QueryClientProvider + BrowserRouter 추가
│   ├── App.tsx                         교체: react-router-dom Routes 트리
│   ├── theme.ts                        신규: COLORS, FONTS 상수
│   │
│   ├── types/
│   │   ├── api.ts                      신규: ApiResponse<T>, Pagination 제네릭
│   │   ├── admin.ts                    신규: DashboardStats, ActionLog, Approval
│   │   ├── connection.ts               신규: Connection, ConnectionRequest
│   │   ├── mcp.ts                      신규: MCPServer, MCPServerRequest, MCPToolDef
│   │   ├── schema.ts                   신규: Schema 타입
│   │   ├── policy.ts                   신규: Policy, PolicyRule, SimulateRequest
│   │   ├── prompt.ts                   신규: Prompt, PromptVersion
│   │   ├── workflow.ts                 신규: Workflow, WorkflowStep, WorkflowRun
│   │   ├── knowledge.ts                신규: KnowledgeSource, IngestionLog
│   │   ├── auth.ts                     신규: User, Role
│   │   └── tenant.ts                   신규: Tenant, Subscription, PlatformUsage
│   │
│   ├── api/
│   │   ├── client.ts                   신규: axios 인스턴스 + 인터셉터
│   │   ├── admin.ts                    신규: dashboard, action-logs, usage, approvals
│   │   ├── connections.ts              신규: CRUD + /test
│   │   ├── mcp.ts                      신규: CRUD + /discover + /disconnect
│   │   ├── schemas.ts                  신규: CRUD
│   │   ├── policies.ts                 신규: CRUD + /simulate
│   │   ├── prompts.ts                  신규: CRUD
│   │   ├── workflows.ts                신규: CRUD + /run + /runs
│   │   ├── knowledge.ts                신규: CRUD + /sync + /search + /ingestion-logs
│   │   ├── auth.ts                     신규: users, roles, api-key
│   │   ├── monitoring.ts               신규: models, routing, retrieval-config
│   │   └── platform.ts                 신규: tenants, subscriptions, platform usage
│   │
│   ├── hooks/
│   │   ├── useAdmin.ts                 신규: TanStack Query hooks (dashboard, logs, approvals)
│   │   ├── useConnections.ts           신규
│   │   ├── useMCPServers.ts            신규
│   │   ├── useSchemas.ts               신규
│   │   ├── usePolicies.ts              신규
│   │   ├── usePrompts.ts               신규
│   │   ├── useWorkflows.ts             신규
│   │   ├── useKnowledge.ts             신규
│   │   ├── useAuth.ts                  신규
│   │   ├── useMonitoring.ts            신규
│   │   └── usePlatform.ts              신규
│   │
│   ├── components/
│   │   ├── common/
│   │   │   ├── Badge.tsx               신규: 색상별 배지 (LLMPlatformAdmin.jsx 기반)
│   │   │   ├── StatCard.tsx            신규: 통계 카드
│   │   │   ├── ActionButton.tsx        신규: 버튼 (primary/danger/success/ghost)
│   │   │   ├── DataTable.tsx           신규: 제네릭 테이블 + TableRow
│   │   │   ├── Modal.tsx               신규: 오버레이 모달 래퍼
│   │   │   ├── FormField.tsx           신규: 라벨 + 입력 래퍼
│   │   │   ├── EmptyState.tsx          신규: 빈 상태 (미구현 Phase용)
│   │   │   └── LoadingSpinner.tsx      신규: 로딩 스피너
│   │   └── layout/
│   │       ├── Sidebar.tsx             신규: 좌측 네비게이션
│   │       ├── PageHeader.tsx          신규: 페이지 제목 + 액션 슬롯
│   │       └── AppShell.tsx            신규: Sidebar + <Outlet>
│   │
│   └── pages/
│       ├── Dashboard.tsx               신규
│       ├── Connections.tsx             신규
│       ├── MCPServers.tsx              신규
│       ├── Schemas.tsx                 신규
│       ├── Policies.tsx                신규
│       ├── Prompts.tsx                 신규
│       ├── Workflows.tsx               신규
│       ├── Knowledge.tsx               신규 (Phase 3 스켈레톤)
│       ├── Auth.tsx                    신규
│       ├── Monitoring.tsx              신규
│       └── platform/
│           ├── Tenants.tsx             신규 (슈퍼어드민)
│           ├── Subscriptions.tsx       신규 (슈퍼어드민)
│           └── PlatformMonitoring.tsx  신규 (슈퍼어드민)
```

---

## 구현 순서 (단계별)

### Phase A: 기반 (다른 모든 파일이 의존)

1. **`package.json`** — @tanstack/react-query 추가
2. **`index.html`** — Google Fonts 링크 + CSS reset + @keyframes pulse/fadeIn + scrollbar 스타일
3. **`src/theme.ts`** — COLORS, FONTS 상수 (LLMPlatformAdmin.jsx에서 추출)
4. **`src/types/api.ts`** — `ApiResponse<T>`, `Pagination` 인터페이스
5. **`src/api/client.ts`** — axios 인스턴스 (baseURL: `/api/v1`, timeout: 15000, 에러 인터셉터)

### Phase B: 공통 컴포넌트

6. Badge, StatCard, ActionButton, DataTable, Modal, FormField, EmptyState, LoadingSpinner
7. AppShell, Sidebar, PageHeader

### Phase C: App 진입점

8. **`src/main.tsx`** — QueryClientProvider + BrowserRouter 래핑
9. **`src/App.tsx`** — react-router-dom Routes 트리 전체 정의

### Phase D: 페이지 구현 (우선순위 순)

10. Dashboard — 대시보드 (가장 중요, API 연동 검증)
11. Connections — 연결 관리
12. MCPServers — MCP 서버 관리
13. Policies — 정책 관리
14. Schemas — 스키마 관리
15. Prompts — 프롬프트 관리
16. Workflows — 워크플로우
17. Auth — 사용자/역할 관리
18. Monitoring — 모니터링
19. Knowledge — RAG 소스 (Phase 3 스켈레톤)
20. platform/Tenants, platform/Subscriptions, platform/PlatformMonitoring — 슈퍼어드민

---

## 핵심 구현 상세

### `src/theme.ts`
```typescript
export const COLORS = {
  bg: "#0a0b0f", surface: "#12131a", surfaceHover: "#1a1b24",
  surfaceActive: "#22232e", border: "#2a2b38", borderLight: "#3a3b48",
  text: "#e8e9ed", textMuted: "#8b8d9a", textDim: "#5a5c6a",
  accent: "#22d3ee", accentDim: "#0e7490",
  success: "#34d399", successDim: "#065f46",
  warning: "#fbbf24", warningDim: "#78350f",
  danger: "#f87171", dangerDim: "#7f1d1d",
  purple: "#a78bfa", purpleDim: "#4c1d95",
} as const;

export const FONTS = {
  mono: "'JetBrains Mono', 'Fira Code', monospace",
  sans: "'DM Sans', 'Pretendard', sans-serif",
  display: "'Space Grotesk', 'Pretendard', sans-serif",
} as const;
```

### `src/types/api.ts`
```typescript
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  error?: string;
  pagination?: Pagination;
}
export interface Pagination {
  page: number; size: number;
  totalElements: number; totalPages: number;
}
```

### `src/api/client.ts`
- axios 인스턴스: baseURL=`/api/v1`, timeout=15000
- 응답 인터셉터: `err.response?.data?.error ?? err.message`로 에러 메시지 정규화

### API 응답 형태 (백엔드 확인 완료)

| 엔드포인트 | 응답 타입 |
|-----------|----------|
| `GET /admin/dashboard` | `{ cost_today_usd, tokens_today, pending_approvals, active_connections }` |
| `POST /connections` | `ConnectionRequest { id, name, adapter, type, config: Map }` |
| `POST /connections/{id}/test` | `{ ok: boolean, latencyMs: int }` |
| `POST /mcp-servers` | `MCPServerRequest { id, name, transport, config: Map, autoStart? }` |
| `POST /mcp-servers/{id}/discover` | `{ serverId, toolCount, tools: UnifiedToolDef[] }` |

### TanStack Query 패턴

```typescript
// 조회
export const useConnections = (params?) =>
  useQuery({
    queryKey: ['connections', params],
    queryFn: () => connectionsApi.list(params).then(r => r.data.data),
  });

// 변경 (invalidate로 자동 갱신)
export const useCreateConnection = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: connectionsApi.create,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['connections'] }),
  });
};
```

- Dashboard 액션 로그: `refetchInterval: 5000` (5초 폴링)
- Dashboard 통계: `refetchInterval: 30000`

### 라우팅 구조 (`App.tsx`)
```typescript
<Routes>
  <Route element={<AppShell />}>
    <Route index element={<Dashboard />} />
    <Route path="connections" element={<Connections />} />
    <Route path="mcp-servers" element={<MCPServers />} />
    <Route path="schemas" element={<Schemas />} />
    <Route path="policies" element={<Policies />} />
    <Route path="prompts" element={<Prompts />} />
    <Route path="workflows" element={<Workflows />} />
    <Route path="knowledge" element={<Knowledge />} />
    <Route path="auth" element={<Auth />} />
    <Route path="monitoring" element={<Monitoring />} />
    <Route path="platform">
      <Route index element={<Navigate to="tenants" replace />} />
      <Route path="tenants" element={<Tenants />} />
      <Route path="subscriptions" element={<Subscriptions />} />
      <Route path="monitoring" element={<PlatformMonitoring />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Route>
</Routes>
```

### Sidebar 네비게이션 구성
```typescript
const NAV_ITEMS = [
  { path: '/', icon: '📋', label: '대시보드' },
  { path: '/connections', icon: '🔌', label: '연결 관리' },
  { path: '/mcp-servers', icon: '🔧', label: 'MCP / Tool' },
  { path: '/schemas', icon: '📝', label: '스키마' },
  { path: '/policies', icon: '🛡️', label: '정책' },
  { path: '/prompts', icon: '💬', label: '프롬프트' },
  { path: '/workflows', icon: '⚡', label: '워크플로우' },
  { path: '/knowledge', icon: '📚', label: 'Knowledge' },
  { path: '/auth', icon: '👤', label: '사용자/권한' },
  { path: '/monitoring', icon: '📊', label: '모니터링' },
];
const PLATFORM_ITEMS = [
  { path: '/platform/tenants', icon: '🏢', label: '테넌트 관리' },
  { path: '/platform/subscriptions', icon: '💳', label: '구독 관리' },
  { path: '/platform/monitoring', icon: '🌐', label: '플랫폼 현황' },
];
```
- `NavLink`의 `isActive`로 현재 경로 감지, 활성 항목에 `background: COLORS.surfaceActive`, `borderLeft: 2px solid ${COLORS.accent}` 적용

### 미구현 Phase 처리 (Knowledge, Workflows 일부)
- API가 404/500 반환 시 `EmptyState` 컴포넌트 노출
- `"Phase 3 — 백엔드 구현 예정"` 메시지 표시
- TanStack Query: `retry: false` 설정으로 불필요한 재시도 방지
- API 코드는 완성해두어 Phase 3/4 완성 시 자동 연동

---

## 페이지별 핵심 UI 구성 (LLMPlatformAdmin.jsx 기반)

| 페이지 | 레이아웃 핵심 |
|--------|-------------|
| **Dashboard** | 4개 StatCard + 실시간 액션 로그(5초 폴링) + 모델별 사용량 바 + 승인대기 패널 |
| **Connections** | 카드 그리드 `auto-fill minmax(340px)` + 좌측 컬러 보더(connected=green) + 연결 유형 탭 모달 |
| **MCPServers** | 서버 카드 + transport 배지 + 툴 chip 목록 + discover 버튼(실시간 갱신) |
| **Schemas** | DataTable + JSON 에디터 textarea(mono font) + 검증 테스트 패널 |
| **Policies** | 아코디언 카드 + 규칙 목록(조건→액션 좌측 컬러 보더) + 시뮬레이션 패널 |
| **Prompts** | 2컬럼(목록 280px + 에디터) + 변수 chip + A/B 테스트 패널 |
| **Workflows** | DataTable + 하단 비주얼 플로우 뷰(노드 박스 + 화살표, dot-grid 배경) |
| **Knowledge** | 소스 카드 + 동기화 버튼 + 검색 테스트 패널 (Phase 3 스켈레톤) |
| **Auth** | 탭(사용자/역할) + DataTable + API 키 생성 버튼 |
| **Monitoring** | 4개 StatCard + 모델 성능 테이블 + 인라인 SVG 라인차트 |
| **platform/Tenants** | DataTable + 상태 토글(활성화/정지) + 테넌트 생성 모달 |
| **platform/Subscriptions** | 쿼터 테이블 + 인라인 편집 폼 |
| **platform/PlatformMonitoring** | StatCard + 테넌트별 사용량 테이블 + 인라인 SVG 바차트 |

---

## 수정 대상 기존 파일

| 파일 | 변경 내용 |
|------|----------|
| `frontend/index.html` | Google Fonts `<link>` 태그, `<style>` global reset, keyframes |
| `frontend/package.json` | `@tanstack/react-query` 의존성 추가 |
| `frontend/src/main.tsx` | `QueryClientProvider` + `BrowserRouter` 래핑 |
| `frontend/src/App.tsx` | Routes 트리로 전면 교체 |

---

## 검증 방법

1. **빌드 확인**: `npm run build` — TypeScript 오류 없이 완료
2. **개발 서버**: `npm run dev` 후 `http://localhost:3000` 접속
3. **대시보드**: 백엔드 실행 시 실제 API 데이터 표시 확인
4. **연결 관리**: 새 연결 모달 → 저장 → 목록 갱신 확인
5. **MCP**: discover 버튼 클릭 시 툴 목록 갱신 확인
6. **정책 시뮬레이션**: 환불 금액 입력 → 결과 확인
7. **라우팅**: 각 사이드바 메뉴 클릭 시 URL 변경 + 페이지 전환 확인
8. **Phase 3 graceful degradation**: Knowledge 페이지가 EmptyState 표시 (백엔드 미구현)

---

## 참고 파일

- `docs/LLMPlatformAdmin.jsx` — UI 디자인 전체 레퍼런스 (색상, 레이아웃, 컴포넌트 패턴)
- `docs/DEVELOPER_HANDOFF_v4.md` — 페이지 목록, 기술 스택 가이드
- `backend/platform-core/src/main/java/com/platform/api/AdminController.java` — 대시보드 API 필드명
- `backend/platform-core/src/main/java/com/platform/api/ConnectionController.java` — 연결 관리 DTO
- `backend/platform-core/src/main/java/com/platform/api/MCPController.java` — MCP 서버 DTO
