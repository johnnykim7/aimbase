# [프로젝트명] CLAUDE.md 템플릿

> 2. Architecture에서 초안 작성 | 4. Implementation에서 지속 갱신
>
> Claude Code가 세션 시작 시 자동으로 읽는 파일. 프로젝트 루트에 배치.

---

아래 내용을 프로젝트 루트의 `CLAUDE.md`에 작성한다.
**프로젝트 유형에 따라 해당하는 섹션만 포함한다.**

```markdown
# ── 공통 라이브러리 import (해당하는 것만) ──
@AI-SDLC_공통라이브러리_레퍼런스.md
# ↑ BE 공통 라이브러리. 없으면 이 줄 삭제.

@[FE 팀 Claude Code 프롬프트 경로]
# ↑ FE 공통 라이브러리/프롬프트. 없으면 이 줄 삭제.

# 프로젝트 개요

[프로젝트명] - [한 줄 설명]
프로젝트 유형: [BE / FE / 풀스택]

## 기술 스택

### 백엔드 (BE/풀스택)
- 프레임워크: [NestJS + TypeScript / FastAPI / Spring Boot / ...]
- DB: [PostgreSQL / MySQL / ...]
- 캐시: [Redis / ...]
- 인증: [JWT / Session / ...]
- ORM: [TypeORM / Prisma / MyBatis / ...]

### 프론트엔드 (FE/풀스택)
- FE 기술스택은 FE 템플릿의 CLAUDE.md 참조 (여기에 중복 기재하지 않는다)

## BE 아키텍처 규칙 (BE/풀스택)

- modules/{name}/{name}.module.ts
- entities/{Name}.entity.ts
- 로직은 Service에만 (Controller 금지)
- ApiResponse<T> 래퍼 사용

## FE 아키텍처 규칙 (FE/풀스택)

- 상태 관리: [Pinia stores/ / Zustand stores/]
- API 클라이언트: [composables/useApi.ts / lib/api-client.ts]
- 컴포넌트: components/ui/ (범용), components/domain/ (도메인)
- 페이지: [pages/ / app/]
- 미들웨어: [middleware/auth.ts, middleware/admin.ts]

## FE 공통 라이브러리 (FE/풀스택 — 있는 경우)

- 공통 컴포넌트 직접 구현 금지: [Button, Input, Modal, DataTable, Layout 등]
- 공통 레이아웃 타입: [sidebar, topnav 등]
- 프로젝트 전용 컴포넌트: components/domain/ 에 생성

## 네이밍 규칙

- 엔티티: PascalCase / 테이블: snake_case
- API: kebab-case / 변수: camelCase
- 컴포넌트 파일: PascalCase (UserProfile.tsx)
- 유틸/훅 파일: camelCase (useAuth.ts)

## 핵심 비즈니스 규칙

- [규칙 1]
- [규칙 2]

## 테스트 전략

> 테스트 코드 작성 시 반드시 이 전략을 따른다.
> 개별 테스트 케이스 목록은 docs/T3-5_단위테스트_명세.md 참조.

### 테스트 범위

| 레이어 | 필수 여부 | 테스트 대상 | 파일 패턴 |
|--------|----------|------------|----------|
| Service / UseCase | 필수 | 비즈니스 로직, 상태 전이 | {name}.service.spec.ts |
| Repository | 선택 | 복잡한 쿼리만 | {name}.repository.spec.ts |
| Controller / Router | 선택 | 통합테스트로 대체 가능 | {name}.controller.spec.ts |
| Store (FE) | 필수 | 상태 변경, 비동기 액션 | {name}.store.test.ts |
| Composable / Hook (FE) | 필수 | 재사용 로직 | use{Name}.test.ts |
| Component (FE) | 선택 | 주요 인터랙션만 | {Name}.test.ts |
| E2E | 선택 | 핵심 사용자 흐름만 | {scenario}.e2e.ts |

### 모킹 전략

- 외부 API (PG, 배송 등): **전부 Mock** — 실제 호출 금지
- DB: [테스트 DB 사용 / In-memory DB / Repository Mock 중 선택]
- 메시지큐 / 이벤트: Mock으로 발행·수신 검증
- 시간 의존 로직: Clock Mock 사용 (예: 24시간 자동 취소)
- FE API 호출: MSW 또는 Mock 함수로 응답 시뮬레이션

### 커버리지 기준

- Service 레이어: [80% 이상 / 라인 기준]
- 전체: [목표 수치 또는 "Service 필수, 나머지 권장"]
- 커버리지 미달 시: CI에서 [경고 / 실패] 처리

### 테스트 작성 원칙

- 하나의 테스트는 하나의 행동만 검증한다 (단일 assert 원칙)
- 테스트 이름은 "상황_행동_기대결과" 패턴: 미결제_24시간경과_자동취소됨
- FSM 전이 테스트: 허용 전이 + 금지 전이 모두 검증
- 이벤트 테스트: 이벤트 발행 여부 + 페이로드 검증
- 정책 테스트: 정책값 변경 시 동작 변경 검증

## 현재 진행 상태

(Sprint 완료 시 갱신)
- Sprint 1: 완료
- Sprint 2: 진행중 (BE 완료, FE 진행중)
- Sprint 3: 미착수

## 참조 문서

- 실행스펙: ./docs/execution-spec.md
- 단위테스트 명세: ./docs/T3-5_단위테스트_명세.md

## 주의사항

(구현 중 발견된 주의점 누적)
- 예: TypeORM Enum은 @Column({type:'enum',enum:...}) 필수

## bp-common-lib 기여 후보 (BE 공통 라이브러리 사용 시)

| 항목 | 설명 | 발견 위치 | 판단 근거 |
|------|------|-----------|----------|
| (구현 중 발견 시 추가) | | | |
```
