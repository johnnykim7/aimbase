# 원본 요구사항: Metronic 9 UI 프레임워크 전환

> 요청일: 2026-03-28
> 요청자: sykim

## 변경 요청 배경

현재 Aimbase 프론트엔드는 UI 라이브러리 없이 100% 인라인 스타일(CSS-in-JS)로 구현되어 있다.
프로덕션 품질의 UI/UX를 확보하기 위해 Metronic 9 (React/Tailwind) 기반으로 전면 교체한다.

## 핵심 요구사항

1. **UI만 교체, 로직 무변경**
   - hooks/ (14개), api/ (17개), types/ (19개), store/ 는 절대 변경하지 않음
   - 특수 라이브러리(@xyflow/react, dagre, recharts)도 그대로 유지
   - 모든 CRUD 동작, 데이터 흐름, 라우팅 구조 보존

2. **Metronic 9.4.3 소스 활용**
   - 소스 위치: `/Users/sykim/Documents/GitHub/bp-platform/metronic-v9.4.3`
   - React Starter Kit (TypeScript/Vite): `metronic-tailwind-react-starter-kit/typescript/vite/`
   - Tailwind CSS 4 기반, CVA(Class Variance Authority), Radix UI, Lucide React 아이콘

3. **LNB(사이드바) 스타일 유지**
   - 현재와 유사한 좌측 고정 220px 사이드바 유지
   - 이모지 아이콘 → Lucide React 아이콘으로 교체

4. **점진적 마이그레이션**
   - Sprint 단위로 나눠서 각 Sprint가 독립적으로 동작 가능하게 진행
   - 인프라 → 레이아웃 → 공통 컴포넌트 → 페이지 순서

## 현재 FE 현황 (교체 대상)

- **레이아웃**: AppShell, Sidebar(281줄), PageHeader — 인라인 스타일
- **공통 컴포넌트 8개**: ActionButton, Badge, DataTable, Modal, FormField, StatCard, EmptyState, LoadingSpinner
- **페이지 ~20개**: Dashboard, Connections, Policies, Knowledge, Schemas, Workflows, WorkflowStudio, WorkflowDetail, RagEvaluation, Documents, Projects, Login, Monitoring, MCPServers, Prompts, Auth + platform/ 4개
- **테마**: theme.ts (COLORS, FONTS 상수)
- **스타일링**: 100% 인라인 스타일, CSS 파일 없음

## 대화 원본 요약

- "지금상태에서 ui를 metronic으로 전부 교체하는건 큰작업이겠지만 로직에는 변경이 없게이 ui만 할수 있나요?"
- "로직에만 문제없으면 시간은 걸려도 됩니다."
- "대형작업이니 CR케스케이드 및 docs/origins에도 다 남겨주세요"
- "LNB스타일은 지금과 유사."
- Metronic 버전: 9 (React/Tailwind) 선택, 소스 이미 보유
