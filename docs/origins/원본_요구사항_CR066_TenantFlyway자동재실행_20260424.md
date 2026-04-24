# 원본 요구사항 — CR-066 Tenant Flyway 자동 재실행

**수집일**: 2026-04-24
**출처**: `docs/T3-9_CR-058_ChatWidget_설계서.md § 9-4` (line 795~798) — CR-058 Sprint 52 구현 중 관찰된 운영 공백
**발번 결정**: 2026-04-24 마스터 세션에서 "B안 (CR-065/066)" 으로 확정. 인계서 `docs/origins/CR065_066_발번_인계서_20260424.md` 참조

---

## 배경

CR-058 의 `db/migration/tenant/V54__*.sql` 을 포함한 tenant 마이그레이션이 **기동 시점에 기존 활성 테넌트에 자동으로 재실행되지 않는다**.

- `LocalDevInitializer` 는 `@Profile("local")` 이고 운영 경로 없음
- `TenantOnboardingService` 는 **신규 테넌트 등록 시점만** 호출됨
- 배포 가이드 `docs/guides/aimbase-ops-guide.md § 2-5` 에 **수동 절차**로 문서화되어 있음

## 문제

- 테넌트 수 × 배포 횟수 만큼 **수동** `flyway migrate` 실행 필요
- "A 테넌트는 V54 적용됐는데 B 테넌트는 V53 누락" 같은 사고 가능
- 테넌트 수 늘어날수록 실수 확률 선형 증가
- 현재는 테넌트가 소수라 감내 가능하나, **선제 대응이 합리적**

## 요구사항

### 핵심
- 활성 테넌트 일괄 `Flyway.migrate()` 실행 수단 제공
- 1개 테넌트 실패 시 나머지 진행 (**실패 격리**)
- 호출자·대상·결과 감사 로그

### 해결안 후보 (설계 시 결정 필요)

1. **기동 시점 일괄 migrate** — `ApplicationReadyEvent` 훅에서 `tenants` 테이블 전체 순회 → 각 DB 에 `Flyway.migrate()`
   - 장점: 배포 직후 자동 반영
   - 단점: 기동 시간 증가 (테넌트 수에 비례), 실패 시 기동 차단 리스크

2. **Admin API** — `POST /api/v1/platform/tenants/migrate` (Master DB only)
   - 장점: 명시적 제어, 실패 격리
   - 단점: 여전히 수동 트리거 (배포 시 관리자가 호출해야 함)

3. **조합** — 기동 시점 자동 + Admin API 재실행 + 상태 조회 API (`GET /tenants/{id}/migrations`)
   - 장점: 안전망 + 명시적 제어 + 관측성 모두 확보
   - 단점: 구현 분량 증가

### 상세 기능

- **TenantMigrationRunner**: 테넌트 순회 + 격리된 Flyway 실행 + 결과 요약 반환
- **Admin API**:
  - `POST /api/v1/platform/tenants/migrate` — body `{tenantIds?, dryRun?}` / 응답 `{total, success, failed, details[]}`
  - `GET /api/v1/platform/tenants/{id}/migrations` — 적용된 버전 + 실패 이력
  - 권한: `SCOPE_platform:admin` 필수
- **TenantOnboardingService 리팩터**: Flyway 실행 로직을 Runner 로 이관, 단일 진입점화
- **감사 로그**: `tenant_migration_logs` Master 테이블 신설 (tenant_id / from_version / to_version / triggered_by / status / error / executed_at)
- **운영 가이드 개정**: § 2-5 수동 절차 → 자동 절차로 갱신

## 영향 모듈 (개발 착수 시 점검)

- `backend/platform-core/src/main/java/com/platform/config/FlywayMultiTenantConfig.java` — 핵심 수정
- `backend/platform-core/src/main/java/com/platform/tenant/TenantOnboardingService.java` — 재실행 가능 구조로 정리
- `backend/platform-core/src/main/java/com/platform/api/platform/TenantController.java` — Admin API 신설 시
- 신규: `backend/platform-core/src/main/java/com/platform/tenant/TenantMigrationRunner.java`
- 신규 (선택): `backend/platform-core/src/main/java/com/platform/domain/master/TenantMigrationLogEntity.java`
- 신규 (선택): Flyway master 마이그레이션 `V__create_tenant_migration_logs.sql`
- 운영: `docs/guides/aimbase-ops-guide.md § 2-5`

## 영향도

**Medium**
- 기동 시점 적용을 택하면 장애 전파 리스크 (마이그레이션 실패가 기동 실패로)
- Admin API 택하면 안전하지만 여전히 수동
- 조합안 권장

## 착수 시점 판단 기준

- 테넌트가 **10개 이상**으로 증가할 때
- 마이그레이션 누락 사고 **1회라도 발생**하면 즉시
- 현재는 수동 절차로 운영 중이라 급하진 않으나 **선제 대응이 합리적**

## 완료 기준

- [ ] `TenantMigrationRunner` 로 활성 테넌트 일괄 migrate 동작
- [ ] Admin API `POST /platform/tenants/migrate` 동작 (호출자 감사 로그 기록)
- [ ] 실패 격리: 1개 테넌트 실패 시 나머지 진행, 결과 요약 반환
- [ ] 운영 가이드 § 2-5 자동 절차로 갱신
- [ ] 회귀 테스트 (신규 테넌트 등록 경로 기존대로 동작)
- [ ] 통합 테스트 (3테넌트 환경에서 V55~V56 일괄 적용 + 1개 의도적 실패 격리)

---

## 참고 — 원문 인용

`docs/T3-9_CR-058_ChatWidget_설계서.md § 9-4` 원문:

> ### 9-4. Tenant Flyway 자동 적용 경로 부재
> - **관찰**: `db/migration/tenant/V54__*.sql` 은 기동 시점에 **기존 활성 테넌트에 자동으로 재실행되지 않는다**. `LocalDevInitializer` 는 `@Profile("local")` 이고, `TenantOnboardingService` 는 신규 테넌트 등록 시점만 호출.
> - **영향**: CR-058 이후 배포 시 각 활성 테넌트 DB 에 수동 적용 필요. 운영 가이드 § 2-5 에 수동 절차 문서화.
> - **후속**: 기동 시점 또는 Admin API 로 일괄 migrate 수단 도입은 별도 CR 후보 (CR-058 범위 외).
