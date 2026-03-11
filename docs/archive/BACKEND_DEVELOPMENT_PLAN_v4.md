# 백엔드 개발 설계 플랜 (v4 — Multi-Tenancy)

## Context

`docs/DEVELOPER_HANDOFF_v4.md` + `docs/UNIVERSAL_LLM_PLATFORM_ARCHITECTURE_v4.md` 기반.
**Java 21 + Spring Boot 3.4.2 + Virtual Threads** 백엔드 구현 플랜.

**v4 핵심 변경**: Database-per-Tenant 멀티테넌시 추가
- Master DB (1개): 테넌트 레지스트리, 구독/쿼터 관리
- Tenant DB (테넌트당 1개): 기존 17개 테이블 완전 격리
- Service/Repository/Controller는 테넌트를 모름 — DataSource 라우팅이 인프라에서 투명하게 처리

**현재 구현 완료 (Phase 1 Steps 1~11)**:
- Gradle 멀티 모듈, Docker Compose, Flyway 마이그레이션
- LLMAdapter (Anthropic/OpenAI/Ollama), WriteAdapter (PostgreSQL), NotifyAdapter (WebSocket/Slack)
- PolicyEngine, SchemaRegistry, SessionStore, OrchestratorEngine, EventBus (Redis Streams)
- 전체 REST API Controllers (14개), Admin대시보드 Controller
- Integration Test (TestContainers)

**남은 작업**: v4 멀티테넌시 인프라 추가 (Phase 1 확장, 5~7주)

---

## 전체 개발 Phase

| Phase | 내용 | 주차 |
|-------|------|------|
| **1** | Core Foundation + **Multi-Tenancy** | 1~**7**주 |
| **2** | MCP & Tools — Tool Use 루프 | 8~11주 |
| **3** | RAG & Knowledge — 지식 수집/검색 파이프라인 | 12~15주 |
| **4** | Workflow & Orchestration — DAG 실행 | 16~20주 |
| **5** | Policy & Safety — 조건/승인/마스킹 | 21~24주 |
| **6** | Extensions & Production — 확장/모니터링 | 25~30주 |

---

## v4 프로젝트 구조 (추가 패키지)

```
platform-core/src/main/java/com/platform/
├── PlatformApplication.java
├── config/
│   ├── AppConfig.java              (기존)
│   ├── RedisConfig.java            (기존 → tenant prefix 추가)
│   ├── SecurityConfig.java         (기존 → JWT tenant_id 추출 추가)
│   ├── WebSocketConfig.java        (기존)
│   ├── TenantDataSourceConfig.java ★ 신규
│   └── FlywayMultiTenantConfig.java ★ 신규
├── tenant/                         ★ 신규 패키지
│   ├── TenantContext.java          ThreadLocal tenant_id 보관
│   ├── TenantResolver.java         Servlet Filter — JWT/헤더/서브도메인에서 tenant_id 추출
│   ├── TenantRoutingDataSource.java AbstractRoutingDataSource 구현
│   └── TenantDataSourceManager.java HikariCP DataSource 캐시 관리
│   ├── onboarding/
│   │   └── TenantOnboardingService.java  DB생성→Flyway→시드→Admin 프로비저닝
│   └── quota/
│       └── QuotaService.java       LLM토큰/스토리지/리소스 수 제한 체크
├── domain/
│   ├── master/                     ★ 신규 (Master DB 전용 엔티티)
│   │   ├── TenantEntity.java
│   │   ├── TenantAdminEntity.java
│   │   ├── SubscriptionEntity.java
│   │   ├── GlobalConfigEntity.java
│   │   └── TenantUsageSummaryEntity.java
│   └── (기존 엔티티들 — Tenant DB용, 패키지명 유지)
├── repository/
│   ├── master/                     ★ 신규 (Master DB 전용 Repository)
│   │   ├── TenantRepository.java
│   │   ├── TenantAdminRepository.java
│   │   ├── SubscriptionRepository.java
│   │   ├── GlobalConfigRepository.java
│   │   └── TenantUsageSummaryRepository.java
│   └── (기존 Repository들 — Tenant DB용, 패키지명 유지)
└── api/
    ├── PlatformController.java     ★ 신규 (슈퍼어드민 /api/v1/platform/*)
    └── (기존 Controller들 — 변경 없음)

platform-core/src/main/resources/
└── db/migration/
    ├── master/                     ★ 신규 Flyway 경로
    │   ├── V1__init_master_schema.sql   (tenants, tenant_admins, subscriptions, global_config, tenant_usage_summary)
    │   └── V2__create_master_indexes.sql
    └── tenant/                     ★ 기존 마이그레이션 이동
        ├── V1__create_connections.sql
        ├── V2__create_mcp_servers.sql
        ├── ... (기존 V1~V17)
        └── V18__install_pgvector.sql
```

---

## v4 신규 구현 목록 (Phase 1 확장)

### Step 12: Multi-Tenancy 인프라

**12-1. Master DB 엔티티 (5개)**

`domain/master/TenantEntity.java`:
```java
@Entity @Table(name = "tenants")
// 필드: id(String), name, status(active/suspended/deleted),
//       dbHost, dbPort, dbName, dbUsername, dbPasswordEncrypted,
//       createdAt, updatedAt
```

`domain/master/SubscriptionEntity.java`:
```java
@Entity @Table(name = "subscriptions")
// 필드: tenantId, plan, llmMonthlyTokenQuota,
//       maxConnections, maxKnowledgeSources, maxWorkflows,
//       storageGb, validFrom, validUntil
```

`domain/master/TenantUsageSummaryEntity.java`:
```java
@Entity @Table(name = "tenant_usage_summary")
// PK: tenantId + yearMonth (복합)
// 필드: totalInputTokens, totalOutputTokens, storageUsedMb, apiCallCount
```

`domain/master/GlobalConfigEntity.java`, `domain/master/TenantAdminEntity.java` 동일 패턴

**12-2. TenantContext (ThreadLocal)**

```java
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    public static void setTenantId(String tenantId) { CURRENT_TENANT.set(tenantId); }
    public static String getTenantId() { return CURRENT_TENANT.get(); }
    public static void clear() { CURRENT_TENANT.remove(); }
}
```

**12-3. TenantResolver (Servlet Filter)**

추출 우선순위: JWT `tenant_id` claim > `X-Tenant-Id` 헤더 > 서브도메인
- `/api/v1/platform/*` 경로는 Master DB만 사용 (TenantContext 설정 불필요)
- Filter 처리 후 반드시 `TenantContext.clear()` 호출 (메모리 누수 방지)

**12-4. TenantRoutingDataSource**

```java
public class TenantRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getTenantId(); // null이면 Master DB 사용
    }
}
```

**12-5. TenantDataSourceManager**

- Master DB `tenants` 테이블에서 테넌트 DB 연결 정보 조회
- HikariCP DataSource 생성 후 `Map<tenantId, DataSource>` 캐시
- `TenantRoutingDataSource.afterPropertiesSet()` 시 기존 테넌트들 미리 로드
- 테넌트 상태 `suspended` 시 DataSource 제거 (요청 자동 거부)

**12-6. TenantDataSourceConfig + FlywayMultiTenantConfig**

```java
@Configuration
public class TenantDataSourceConfig {
    @Bean @Primary
    public DataSource dataSource(TenantDataSourceManager manager) {
        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.setTargetDataSources(manager.getAllDataSources());
        routing.setDefaultTargetDataSource(masterDataSource()); // Master DB
        return routing;
    }
}

@Configuration
public class FlywayMultiTenantConfig {
    // Master Flyway: db/migration/master/
    // Tenant Flyway: db/migration/tenant/ — 각 테넌트 DB에 별도 실행
}
```

**12-7. TenantOnboardingService**

프로비저닝 5단계:
1. Master DB에 TenantEntity INSERT
2. PostgreSQL에 새 데이터베이스 생성 (`CREATE DATABASE llm_platform_{tenantId}`)
3. pgvector extension 설치 (`CREATE EXTENSION IF NOT EXISTS vector`)
4. Flyway `db/migration/tenant/` 경로로 새 DB 마이그레이션 실행
5. 초기 Admin 계정 + 기본 역할/정책 시드 데이터 삽입
6. TenantDataSourceManager 캐시에 등록

**12-8. QuotaService**

LLM 호출 전, RAG 수집 전, 워크플로우 생성 전 쿼터 체크:
```java
public QuotaCheckResult checkLLMQuota(String tenantId, int estimatedTokens) {
    // Master DB에서 subscriptions 조회
    // tenant_usage_summary에서 이번 달 사용량 확인
    // 초과 시 QuotaExceededException (HTTP 429)
}
```

**12-9. PlatformController (슈퍼어드민 전용)**

```
GET    /api/v1/platform/tenants           테넌트 목록 (Master DB 조회)
POST   /api/v1/platform/tenants           테넌트 생성 (TenantOnboardingService)
GET    /api/v1/platform/tenants/{id}      테넌트 상세 + 사용량
PUT    /api/v1/platform/tenants/{id}      테넌트 정보 수정
POST   /api/v1/platform/tenants/{id}/suspend   테넌트 중지
POST   /api/v1/platform/tenants/{id}/activate  테넌트 활성화
DELETE /api/v1/platform/tenants/{id}      테넌트 삭제 (DB 포함)
PUT    /api/v1/platform/subscriptions/{tenantId}  쿼터/플랜 변경
GET    /api/v1/platform/usage             전체 사용량 대시보드
```

---

### Step 13: 기존 파일 수정

**13-1. SessionStore.java**
```java
// 기존: "session:" + sessionId
// 변경: "tenant:" + TenantContext.getTenantId() + ":session:" + sessionId
```

**13-2. SecurityConfig.java**
```java
// JWT 검증 시 tenant_id claim 추출 → TenantResolver로 위임
// /api/v1/platform/** → ROLE_SUPER_ADMIN 전용 접근 제어 추가
```

**13-3. docker-compose.yml**
```yaml
services:
  postgres-master:        # 포트 5432 — Master DB (tenants, subscriptions 등)
    image: postgres:16
    environment:
      POSTGRES_DB: llm_platform_master
  postgres-tenant-dev:    # 포트 5433 — 개발용 테넌트 DB (1개 샘플)
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: llm_platform_tenant_dev
  redis: ...
  ollama: ...
```

**13-4. Flyway 마이그레이션 경로 재구성**
```
기존: db/migration/V1__.sql ~ V17__.sql
변경:
  db/migration/master/V1__init_master_schema.sql
  db/migration/master/V2__create_master_indexes.sql
  db/migration/tenant/V1__create_connections.sql ~ V17__(기존 그대로)
```

---

## 구현 우선순위 (v4 확장 7주)

```
Week 1~5 : (이미 완료) Phase 1 Steps 1~11
Week 6   : Step 12-1~12-3 — Master DB 엔티티 + Repository 5개 + TenantContext + TenantResolver
Week 7   : Step 12-4~12-6 — TenantRoutingDataSource + DataSourceManager + DataSourceConfig
           Step 12-7      — TenantOnboardingService
Week 8   : Step 12-8~12-9 — QuotaService + PlatformController
           Step 13        — SessionStore, SecurityConfig, docker-compose, Flyway 재구성
           Integration test 업데이트 (테넌트 A/B 격리 검증)
```

---

## 핵심 설계 원칙

| 항목 | 결정 |
|------|------|
| 멀티테넌시 전략 | Database-per-Tenant (완전 격리) |
| DataSource 라우팅 | `TenantRoutingDataSource` (AbstractRoutingDataSource) |
| Tenant ID 전파 | `TenantContext` (ThreadLocal) — Virtual Thread 안전 |
| Flyway | Master/Tenant 이중 경로 분리 |
| Redis | `tenant:{id}:` prefix로 namespace 격리 |
| 슈퍼어드민 API | `/api/v1/platform/*` — Master DB만 접근 |
| Service/Repository | **테넌트를 전혀 모름** — 인프라에서 투명하게 처리 |
| 쿼터 체크 | Master DB `subscriptions` + `tenant_usage_summary` |

---

## 검증 방법

1. **테넌트 격리 E2E**:
   - `POST /api/v1/platform/tenants` → 테넌트 A 자동 프로비저닝 확인
   - 테넌트 A 컨텍스트로 Connection 생성 → 테넌트 B 컨텍스트에서 조회 시 빈 목록
   - `POST /api/v1/platform/tenants/{id}/suspend` → 테넌트 A 요청 자동 거부 확인

2. **쿼터 체크**:
   - 테넌트 LLM 토큰 한도 초과 시 HTTP 429 응답 확인

3. **기존 통합 테스트 유지**:
   - `PlatformIntegrationTest.java` — 단일 테넌트로 기존 API 동작 검증 유지

4. **API 문서**: `http://localhost:8080/swagger-ui.html` — `/api/v1/platform/*` 엔드포인트 확인

5. **헬스체크**: `http://localhost:8080/actuator/health` — Master DB + Tenant DB 연결 상태

---

## 마일스톤 (v4 기준)

| 시점 | 체크포인트 |
|------|-----------|
| Week 5 ✅ | Phase 1 Core 완료 — LLM + Action + Chat API + Admin 대시보드 |
| Week 7 | Multi-Tenancy 인프라 완료 — TenantRoutingDataSource + DataSourceManager |
| **Week 8** | **Phase 1 최종 완료** — TenantOnboardingService + QuotaService + PlatformController |
| Week 11 | Phase 2 완료 — MCP Tool Use 루프 |
| Week 15 | Phase 3 완료 — RAG 지식 수집 + 검색 |
| Week 20 | Phase 4 완료 — 워크플로우 DAG 실행 |
| Week 24 | Phase 5 완료 — Policy + 승인 플로우 |
| Week 30 | Phase 6 완료 — v1.0 릴리즈 후보 |
