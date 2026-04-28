# Aimbase 운용 가이드

> **v1.7.0** | 2026-04-10 | Aimbase v6.4.0 기준

Aimbase 플랫폼을 운영하기 위한 관리자 가이드입니다.
소비앱 연동은 [aimbase-api-guide.md](aimbase-api-guide.md)를 참조하세요.

---

## 접속 정보

| 용도 | URL | 비고 |
|------|-----|------|
| **관리 UI** | `http://{서버IP}:3200` | React SPA (nginx 프록시) |
| **REST API** | `http://{서버IP}:8280/api/v1` | Spring Boot |
| **RAG Sidecar** | `http://{서버IP}:8281` | Python MCP Server (내부용) |

> 관리 UI에 접속하면 `/api/**` 요청이 BE로 자동 프록시됩니다. 소비앱에서 API를 직접 호출할 때만 8280 포트를 사용하세요.

---

## 대상 독자

| 역할 | 사용 범위 | 이 문서에서 다루는 내용 |
|------|----------|----------------------|
| **플랫폼 관리자** (SUPER_ADMIN) | Aimbase Platform UI | 테넌트 생성, 구독/과금, API Key 발급, 모니터링 |
| **테넌트 관리자** (ADMIN) | Aimbase 테넌트 UI | Connection, 정책, 프롬프트, 스키마, 워크플로우, RAG, MCP 설정 |
| **소비앱 개발자** | — | 이 문서 대상 아님 → [aimbase-api-guide.md](aimbase-api-guide.md) |

---

## 1. 초기 세팅 플로우

새 서비스를 Aimbase에 온보딩할 때의 순서입니다.

```
[1] 테넌트 생성 → [2] Connection 등록 → [3] 정책 세팅 → [4] 지식소스 준비
     ↓                                                        ↓
[5] 프롬프트/스키마 등록                                   [6] RAG 인제스션
     ↓                                                        ↓
[7] 워크플로우 구성 ─────────────────────────────────────→ [8] API Key 발급 → 소비앱 연동
```

각 단계를 아래에서 상세히 설명합니다.

---

## 2. 플랫폼 관리 (SUPER_ADMIN)

> 모든 `/api/v1/platform/**` 엔드포인트는 Master DB에서 동작합니다.

### 2-1. 테넌트 관리

테넌트는 Database-per-Tenant 격리 단위입니다. 테넌트 생성 시 전용 DB 스키마가 자동 프로비저닝됩니다.

**UI 경로**: Platform > Tenants

| 작업 | API | 설명 |
|------|-----|------|
| 목록 조회 | `GET /platform/tenants` | status, domain_app 필터 가능 |
| 생성 | `POST /platform/tenants` | DB 스키마 자동 생성 + Flyway 마이그레이션 |
| 상세 조회 | `GET /platform/tenants/{id}` | 구독, 사용량 이력 포함 |
| 수정 | `PUT /platform/tenants/{id}` | 테넌트 정보 변경 |
| 일시 정지 | `POST /platform/tenants/{id}/suspend` | 접근 차단 (데이터 보존) |
| 재활성화 | `POST /platform/tenants/{id}/activate` | 정지 해제 |
| 삭제 | `DELETE /platform/tenants/{id}` | DB 포함 완전 삭제 (비가역) |

**생성 시 필수 필드**:

```json
{
  "name": "LexFlow CompanyA",
  "identifier": "lexflow_companya",
  "domainApp": "lexflow",
  "dbHost": "db.example.com",
  "dbPort": 5432,
  "dbName": "aimbase_lexflow_companya",
  "adminEmail": "admin@companya.com"
}
```

> **주의**: `identifier`는 생성 후 변경 불가. 소비앱에서 `X-Tenant-Id` 헤더로 사용하는 값이므로 신중히 결정하세요.

### 2-2. 구독/과금 관리

**UI 경로**: Platform > Subscriptions

| 작업 | API | 설명 |
|------|-----|------|
| 구독 목록 | `GET /platform/subscriptions` | 전체 테넌트 플랜 현황 |
| 플랜 변경 | `PUT /platform/subscriptions/{tenantId}` | 플랜, 쿼터 변경 |

### 2-3. 사용량 모니터링

**UI 경로**: Platform > Monitoring

| 작업 | API | 설명 |
|------|-----|------|
| 대시보드 | `GET /platform/usage` | 전체 플랫폼 사용량 집계 |

### 2-4. 시스템 API Key 관리

소비앱이 JWT 없이 Aimbase API를 호출할 수 있도록 API Key를 발급합니다.

**UI 경로**: Platform > API Keys

| 작업 | API | 설명 |
|------|-----|------|
| 발급 | `POST /platform/api-keys` | domainApp + tenantId 바인딩 |
| 목록 | `GET /platform/api-keys` | tenantId 필터 가능 |
| 폐기 | `DELETE /platform/api-keys/{id}` | 즉시 비활성화 |
| 재발급 | `POST /platform/api-keys/{id}/regenerate` | 기존 키 폐기 → 동일 설정 신규 발급 |

**발급 예시**:

```json
{
  "name": "LexFlow CompanyA 연동키",
  "domainApp": "lexflow",
  "tenantId": "lexflow_companya",
  "scope": null,
  "expiresAt": null
}
```

> **주의**: 발급 시 반환되는 `apiKey` 값은 최초 1회만 조회 가능합니다. 즉시 안전한 곳에 저장하세요.

### 2-5. 임베드 위젯(Chat Widget) 운영 [CR-058]

소비앱 브라우저에 채팅 + 워크플로우 + RAG 출처 카드를 얹기 위한 전역 설정. 소비앱 BFF 가 API Key 로 Aimbase 의 `POST /api/v1/sessions/issue-widget-token` 을 호출해 단기 JWT 를 대리 발급받고 브라우저로 전달하는 구조이므로, 아래 설정이 비어 있으면 CORS 미허용 또는 발급 거부로 위젯이 붙지 않는다.

**UI 경로**: Platform > Runtime Settings (CR-040) → `widget.*` 카테고리
**저장소**: Master DB `global_config` 테이블. `PlatformSettingsService` 5분 캐시 적용. 수정 시 감사 로그 기록.

| 설정 키 | 타입 | 기본값 | 설명 |
|---------|------|--------|------|
| `widget.allowed-origins` | CSV | *(빈 문자열)* | 위젯 임베드를 허용할 전역 Origin. 비어 있으면 위젯 호출이 전부 CORS 거부된다. 예: `https://oms.company.com,https://rescue.company.com` |
| `widget.allowed-scopes` | CSV | `chat:stream,chat:upload,workflow:subscribe,rag:read` | 위젯 토큰에 부여 가능한 scope 화이트리스트. 발급 요청 scope 와 교집합만 적용. CR-061: `chat:upload` 미포함 시 위젯 파일 첨부 동작 불가 |
| `widget.token-ttl-seconds` | int | `1800` | 위젯 토큰 기본 TTL (초) |
| `widget.token-max-ttl-seconds` | int | `3600` | 위젯 토큰 최대 TTL 하드캡. 요청 TTL 이 이를 초과하면 cap 적용 + 로그 |

**Tenant Flyway 마이그레이션 자동 적용 [CR-066]**:

CR-066 부터 신규 tenant 마이그레이션(`V54` / `V58` 등)은 **Admin API 또는 기동 훅으로 자동 재실행** 가능하다. `TenantMigrationRunner` 는 `Flyway.migrate()` 의 `baselineOnMigrate` / `outOfOrder` / `ignoreMigrationPatterns("*:missing")` / `repair()` 옵션을 이미 적용하므로 **재실행이 안전**하다. 1개 테넌트 실패는 격리되어 나머지 진행.

**(A) Admin API — 권장 방식**:

```bash
# 1. 전체 활성 테넌트 일괄 migrate (dryRun=true 로 먼저 확인)
curl -X POST http://<aimbase-host>:8181/api/v1/platform/tenants/migrate \
  -H "Authorization: Bearer <SUPER_ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"dryRun": true}'
# → {"success": true, "data": {"total": 3, "success": 3, "failed": 0, "skipped": 0,
#     "details": [{"tenantId":"dev","dbName":"aimbase_dev","status":"SUCCESS","migrationsApplied":2}, ...]}}

# 2. 실제 적용 (body 생략 시 활성 테넌트 전체)
curl -X POST http://<aimbase-host>:8181/api/v1/platform/tenants/migrate \
  -H "Authorization: Bearer <SUPER_ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{}'

# 3. 특정 테넌트만 migrate
curl -X POST http://<aimbase-host>:8181/api/v1/platform/tenants/migrate \
  -H "Authorization: Bearer <SUPER_ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"tenantIds": ["dev","prod-a"]}'

# 4. 단일 테넌트 현황 조회 (적용 버전 + 대기 버전)
curl -H "Authorization: Bearer <SUPER_ADMIN_TOKEN>" \
  http://<aimbase-host>:8181/api/v1/platform/tenants/dev/migrations
# → {"success": true, "data": {"tenantId":"dev","dbName":"aimbase_dev",
#     "currentVersion":"58","applied":[...], "pendingVersions":[]}}
```

**(B) 기동 시점 자동 훅 — opt-in**:

`application.yml` 또는 환경변수로 켠다. 기본값 `false`.

```yaml
platform:
  tenant:
    migration:
      auto-migrate-on-startup: true   # 또는 TENANT_AUTO_MIGRATE_ON_STARTUP=true
```

켜면 `ApplicationReadyEvent` 시 활성 테넌트 전체에 migrate 수행 후 로그 출력:
```
CR-066 startup auto-migrate done — total=3, success=3, failed=0, skipped=0
```

실패가 있어도 기동은 계속 진행됨 (실패 격리). 실패한 테넌트는 Admin API 로 재시도한다.

**(C) 레거시 수동 절차 (Admin API 불가 시 폴백)**:

기존 수동 절차(테넌트별 `psql -f V__.sql` + `flyway_schema_history` INSERT 스크립트 루프)는 여전히 유효하다. 단 **CR-066 의 Admin API 가 주(主) 수단**이고, 수동은 긴급 상황(BE 기동 불가 등) 시 폴백으로만 사용한다.

<details>
<summary>레거시 수동 스크립트 (접기)</summary>

```bash
# 각 활성 테넌트 DB 목록 (master 에서 조회)
psql -U platform -h <master-host> -p 5432 aimbase_master \
  -tAc "SELECT id, db_name FROM tenants WHERE status='active';"

# 각 테넌트 DB 에 V__ 적용 (WHERE NOT EXISTS 가드 필수 — flyway_schema_history 유니크 제약 없음)
for TENANT_DB in aimbase_tenant_dev aimbase_tenant_<other>; do
  psql -U platform -h <tenant-host> -p 5432 "$TENANT_DB" \
    < backend/platform-core/src/main/resources/db/migration/tenant/V58__cr061_chat_attachments.sql
  psql -U platform -h <tenant-host> -p 5432 "$TENANT_DB" -c "
    INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
    SELECT COALESCE(MAX(installed_rank),0)+1, '58', 'cr061 chat attachments', 'SQL',
           'V58__cr061_chat_attachments.sql', 0, 'ops-deploy', 0, true
    FROM flyway_schema_history
    WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='58');"
done
```

</details>

> 신규 온보딩하는 테넌트에는 `TenantOnboardingService` 가 자동으로 최신까지 migrate 하므로 별도 조치 불필요.

**관리자 수정 절차 (psql 직접)**:
```bash
psql -U platform -h localhost -p 5432 aimbase_master
# 전역 허용 Origin 추가 (CSV 교체)
UPDATE global_config SET config_value='https://oms.company.com,https://rescue.company.com',
    updated_by='ops', updated_at=NOW()
  WHERE config_key='widget.allowed-origins';
```
> `PlatformSettingsService` 캐시가 최대 5분 유지되므로 즉시 반영이 필요하면 Runtime Settings UI 에서 "캐시 비우기" 를 실행하거나 서버 재시작.

**등록 체크리스트 (신규 소비앱 온보딩)**:
- [ ] 소비앱 origin 을 `widget.allowed-origins` 에 추가
- [ ] 소비앱 BFF 가 보관할 API Key 를 § 2-4 절차로 발급
- [ ] 소비앱 BFF 가 `/api/v1/sessions/issue-widget-token` 호출 테스트 → JWT 수신 확인
- [ ] 브라우저에서 `Origin` 헤더가 whitelist 에 있는지 확인 (서버 로그 `Widget token denied — origin '...' not in whitelist` 검색으로 확인)

**공통 실수**:
- CORS 가 안 된다고 `allowed-origins` 에 `*` 를 넣으면 동작하지 않는다 — 목록에 있는 구체 origin 문자열과 정확히 일치해야 한다 (프로토콜 + 도메인 + 포트)
- `scopes` 를 전부 제거하면 모든 발급이 400. 최소 `chat:stream` 은 남긴다
- `widget.token-max-ttl-seconds` 를 1시간 이상으로 늘리지 않는다. 브라우저 유출 시 피해 시간을 제한하는 핵심 안전장치

**로그/감사**:
- `widget.*` 설정 변경은 `audit_logs` 에 `platform_setting_change` 이벤트로 기록
- 위젯 토큰 발급 성공/실패는 `WidgetTokenController` 로거 경유 (origin, scope 교집합, TTL cap 여부)

---

### 2-6. 위젯 파일 첨부 운영 [CR-061]

CR-061 은 위젯에서 업로드한 이미지/PDF 를 `chat_attachments` 테이블(테넌트 DB) + StorageService(로컬 또는 S3) 에 저장한다. 관리자는 용량 모니터링과 설정 조정, GC 상태 확인을 담당한다.

**UI 경로**: Platform > Runtime Settings → `widget.attachment.*` 카테고리
**저장소 경로**: `widget-attachments/{session_id}/{원본파일명}` — StorageService 활성 구현체(`LocalStorageService` 는 `storage.local.base-path` 아래, `S3StorageService` 는 설정 버킷)
**GC**: `AttachmentGcScheduler` 가 매 5분 최대 100건 만료 배치 삭제 (스토리지 → DB 순, 스토리지 실패해도 DB 정리 진행)

| 설정 키 | 타입 | 기본값 | 설명 |
|---------|------|--------|------|
| `widget.attachment.max-image-bytes` | int | `10485760` (10MB) | 이미지 단일 최대. 초과 시 400 FG-ATT-4002 (BIZ-099) |
| `widget.attachment.max-pdf-bytes` | int | `33554432` (32MB) | PDF 단일 최대. Anthropic document 블록 상한과 동일 (BIZ-099) |
| `widget.attachment.max-per-session` | int | `10` | 세션당 활성 첨부 상한. 초과 시 409 FG-ATT-4091 (BIZ-100) |
| `widget.attachment.ttl-seconds` | int | `86400` (24h) | 세션 TTL 과 동기화 권장. GC 가 `expires_at < now()` 기준으로 정리 (BIZ-101) |

**스토리지 사용량 모니터링 (권장)**:
```sql
-- 테넌트 DB 에서 실행
SELECT
    DATE_TRUNC('day', created_at) AS day,
    COUNT(*) AS files,
    SUM(size_bytes) / 1024 / 1024 AS total_mb,
    SUM(CASE WHEN media_type = 'application/pdf' THEN 1 ELSE 0 END) AS pdfs,
    SUM(CASE WHEN media_type LIKE 'image/%' THEN 1 ELSE 0 END) AS images
FROM chat_attachments
WHERE created_at > NOW() - INTERVAL '30 days'
GROUP BY day ORDER BY day DESC;

-- 만료 대기 건 (GC 배치가 처리할 대상)
SELECT COUNT(*), MAX(expires_at) FROM chat_attachments WHERE expires_at < NOW();
```

**GC 스케줄러 관찰**:
- 애플리케이션 로그에서 `Attachment GC — purged=X storageFailed=Y total=Z` 5분 주기로 기록
- `storageFailed > 0` 가 지속되면 StorageService 권한/네트워크 점검 필요
- Docker 재기동 후 첫 GC 는 60초 후 (`initialDelay = 60_000`)

**장애 대응**:
- **스토리지 용량 초과**: 우선 기본값을 낮춰 신규 업로드 차단 (`max-image-bytes=0` 일시 설정) → 볼륨 증설/S3 버킷 정리 → 복구. 기존 업로드 건은 TTL 로 자동 정리되므로 24h 내 자연 감소
- **Python 사이드카 다운 시 PDF 폴백 실패**: 비-Anthropic 모델로 PDF 첨부 시 `PdfTextExtractor.extract()` 가 빈 텍스트 반환(graceful) — 사용자에겐 "첨부 문서: {filename}\n(텍스트 추출 실패)" 가 보인다. 운영자는 Python 사이드카 상태를 먼저 점검
- **chat_attachments 테이블 스캔 느려짐**: `idx_chat_attachments_session`, `idx_chat_attachments_expires` 인덱스가 있으나 수백만 건 누적 시 `VACUUM ANALYZE chat_attachments` 주기적으로 실행

**배포 전 체크리스트 (CR-061 이후 업그레이드)**:
- [ ] § 2-5 의 V58 수동 마이그레이션을 모든 기존 테넌트 DB 에 적용
- [ ] Master `global_config.widget.allowed-scopes` 값이 있는 테넌트 — **수동으로 `chat:upload` 추가 필수** (폴백 기본값은 새 문자열이 되지만 DB 에 이미 값이 있으면 폴백 미적용)
- [ ] `/widget/v1/aimbase-chat.umd.global.js` 번들이 최신(24KB+)인지 확인 — Gradle `copyWidgetBundle` 태스크가 빌드 시 자동 수행
- [ ] 위젯 샘플 consumer 로 이미지 1장 / PDF 1건 업로드 → 채팅 전송 → 응답 확인 (골든 패스)

---

### 2-7. 위젯 음성 입력 STT 운영 [CR-060]

**기본값 seed** (V19 마이그레이션 — BE 재기동 시 Flyway 가 자동 적용. 기존 테넌트에 수동 적용 필요 없음, 마스터 DB 1회만 작동):

```bash
# 신규 DB면 BE 가 알아서 Flyway 실행. 기존 운영 DB에도 master 는 1개 이므로 재기동 1회로 충분.
# 수동 확인만 필요한 경우:
psql -U platform -h localhost -p 5432 -d aimbase_master -c "
SELECT config_key, config_value FROM global_config WHERE config_key LIKE 'widget.stt.%';"
```

**런타임 설정** (`global_config` — 관리자 UI 또는 psql UPDATE 로 변경):

| Key | 기본값 | 의미 | BIZ |
|-----|--------|------|-----|
| `widget.stt.max-duration-seconds` | 60 | 녹음 시간 상한 (Whisper duration 응답 기준 사후 검증) | BIZ-102 |
| `widget.stt.max-size-bytes` | 26214400 (25MB) | 업로드 크기 상한. Whisper API 절대 상한 | BIZ-103 |
| `widget.stt.allowed-mime-types` | `audio/webm,audio/mp4,audio/mpeg,audio/wav,audio/ogg` | 허용 오디오 MIME (magic number 판정 기준) | — |
| `widget.stt.rate-limit-per-minute` | 10 | 세션당 분당 호출 한도 | BIZ-104 |
| `widget.stt.default-language` | `auto` | 요청에 `language` 가 없을 때 Whisper 에 전달할 값. `auto` 면 자동 감지 | — |

**사용량 모니터링**:

```bash
# 최근 1시간 STT 호출 건수 (감사 로그 기반, 변환 텍스트 본문은 저장되지 않음)
psql -U platform -h localhost -p 5432 -d {tenant_db} -c "
SELECT
  DATE_TRUNC('minute', created_at) AS minute,
  COUNT(*) AS calls,
  SUM((detail->>'size_bytes')::bigint) AS total_bytes,
  AVG((detail->>'duration_sec')::float) AS avg_duration_sec
FROM audit_logs
WHERE action = 'stt_transcribe'
  AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY 1 ORDER BY 1 DESC;"
```

```bash
# 현재 Redis rate-limit 카운터 확인 (세션별)
redis-cli -h localhost -p 6379 --scan --pattern 'stt:rate:*' | head -20
redis-cli -h localhost -p 6379 get "stt:rate:sess-abc"
```

**장애 대응**:
- **OpenAI Connection 미설정 / 키 만료**: 사용자에게 503 `STT_PROVIDER_UNAVAILABLE` 반환. 운영자는 테넌트 Connection 페이지에서 OpenAI 항목 점검
- **Whisper 5xx 지속**: 502 `STT_UPSTREAM_ERROR` 로 사용자에게 반환됨. OpenAI Status 페이지 확인 + 일시적이면 재시도 안내
- **Redis 다운**: `SttRateLimiter` 는 fail-open — rate limit 검사 통과. 로그에 `STT rate limiter Redis failure, failing open` 기록. OpenAI 호출 비용 급증 우려 있으니 Redis 복구 우선
- **특정 테넌트 남용**: `widget.stt.rate-limit-per-minute` 를 일시적으로 낮춤 (e.g., 3). 변경 즉시 5분 캐시 TTL 후 반영

**사용자 측 트러블슈팅 FAQ (소비앱 운영자가 사용자에게 전달)**:
- "마이크 권한이 필요합니다" → 브라우저 주소창의 🔒 아이콘 → "마이크" 허용. iOS Safari 는 매 세션 재허용 요구 가능 (정상)
- "HTTPS 환경에서만 사용 가능" → 위젯은 HTTPS 페이지 또는 `localhost` 에서만 동작. HTTP 페이지에 임베드 시 마이크 버튼 자동 숨김
- "음성 입력 버튼이 안 보임" → 위젯 토큰에 `chat:stt` scope 이 있는지 BFF 확인. `widget.allowed-scopes` 에 `chat:stt` 포함되어야 함
- "잠시 후 다시 시도해주세요 (분당 10회 제한)" → BIZ-104 rate limit. `widget.stt.rate-limit-per-minute` 상향 또는 사용자 대기

**배포 전 체크리스트 (CR-060 업그레이드)**:
- [ ] BE 재기동으로 V19 Flyway 적용 확인 — `SELECT * FROM flyway_schema_history_master WHERE version='19'` 에 `success=t` 로우 존재
- [ ] `global_config.widget.allowed-scopes` 에 `chat:stt` 포함 확인
- [ ] `/widget/v1/aimbase-chat.umd.global.js` 번들이 최신(33KB+)인지 확인 — Gradle `copyWidgetBundle` 태스크가 빌드 시 자동 수행
- [ ] 테넌트 Connection 에 OpenAI 등록 + `connected` 상태 확인 (위젯 STT 는 테넌트 OpenAI 키를 공유)
- [ ] 샘플 consumer HTTPS 환경에서 마이크 버튼 노출 → 녹음 5초 → 입력창 자동 삽입 확인 (골든 패스)

---

## 3. 테넌트 관리 (ADMIN)

테넌트 관리자가 Aimbase UI에서 수행하는 설정 작업입니다.

### 3-1. Connection (LLM·HTTP 연결) 관리

LLM 프로바이더(Anthropic, OpenAI, Ollama 등) 및 외부 어댑터(HTTP, SEARCH 등) 연결을 등록합니다. `type` 컬럼으로 구분되며 자유 문자열(e.g., `LLM`, `HTTP`, `SEARCH`).

**UI 경로**: Connections

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /connections` | type 필터 가능 |
| 생성 | `POST /connections` | LLM/Write/Notification 어댑터 |
| 상세 | `GET /connections/{id}` | 설정 상세 |
| 수정 | `PUT /connections/{id}` | 키, 모델 변경 |
| 삭제 | `DELETE /connections/{id}` | 연결 제거 |
| 테스트 | `POST /connections/{id}/test` | 연결 상태 확인 |

**LLM Connection 생성 예시**:

```json
{
  "name": "Claude Sonnet",
  "type": "LLM",
  "provider": "ANTHROPIC",
  "config": {
    "apiKey": "sk-ant-...",
    "model": "claude-sonnet-4-20250514",
    "maxTokens": 4096
  }
}
```

> **권장**: Connection 생성 후 반드시 `test` API로 연결 상태를 확인하세요.

**HTTP Connection 생성 예시** [CR-054]:

범용 HTTP 요청 도구(`http_request`)가 참조하는 Connection. 외부 REST API(FlowGuard, 소비앱 내부 API, 외부 SaaS 등) 호출 시 공통 자격 보관소로 사용됩니다.

```json
{
  "id": "flowguard-local",
  "name": "FlowGuard Local",
  "adapter": "http",
  "type": "HTTP",
  "config": {
    "baseUrl": "http://59.8.160.12:8180",
    "auth": {
      "type": "API_KEY",
      "in": "header",
      "name": "X-Api-Key",
      "value_env": "FLOWGUARD_API_KEY"
    },
    "healthPath": "/actuator/health",
    "readTimeoutMs": 30000
  }
}
```

- **인증 타입**: `API_KEY`(header 권장), `BEARER`, `BASIC`, `NONE`
- **시크릿 저장**: 운영환경은 반드시 `value_env`(환경변수 참조). 평문 `value`는 개발환경 전용
- **감사**: 실행 시 `Authorization`/`X-Api-Key`/`Cookie` 헤더는 `***`로 마스킹되어 `tool_executions`에 기록
- **정책 연계**: 외부 호출 범위는 `DomainFilterPolicy`(CR-035)로 통제 — 허용 host 화이트리스트 기반으로 운영

**운영 체크리스트**:
1. `healthPath` 지정 후 Connection 생성 → `POST /connections/{id}/test`로 연결 확인
2. 허용할 외부 도메인(host)을 `DomainFilterPolicy`에 등록 (초기 deny-all 권장)
3. 시크릿 회전 시 Connection `config.auth.value_env` 참조만 유지하고 환경변수 재배포

### 3-2. 정책 (Policy) 관리

요청에 대한 허용/거부/승인 규칙을 정의합니다. priority 내림차순으로 평가되며, 첫 DENY/REQUIRE_APPROVAL에서 중단됩니다.

**UI 경로**: Policies

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /policies` | domain 필터 가능 |
| 생성 | `POST /policies` | 규칙 정의 |
| 상세 | `GET /policies/{id}` | 규칙 상세 |
| 수정 | `PUT /policies/{id}` | 규칙 변경 |
| 삭제 | `DELETE /policies/{id}` | 규칙 제거 |
| 활성화 토글 | `PATCH /policies/{id}/activate` | 활성/비활성 전환 |
| 시뮬레이션 | `POST /policies/simulate` | 정책 적용 결과 사전 확인 |

**정책 생성 예시**:

```json
{
  "name": "민감 정보 차단",
  "domain": "SECURITY",
  "priority": 100,
  "action": "DENY",
  "rules": {
    "conditions": [
      { "field": "content", "operator": "contains", "value": "주민등록번호" }
    ]
  },
  "isActive": true
}
```

> **팁**: 정책 변경 전 `simulate` API로 의도한 대로 동작하는지 사전 검증하세요.

### 3-3. 프롬프트 관리

프롬프트 템플릿을 버전 단위로 관리합니다.

**UI 경로**: Prompts

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /prompts` | user, project 필터 가능 |
| 생성 | `POST /prompts` | 새 프롬프트 버전 |
| 상세 | `GET /prompts/{id}/{version}` | 버전별 상세 |
| 수정 | `PUT /prompts/{id}/{version}` | 버전 수정 |
| 삭제 | `DELETE /prompts/{id}/{version}` | 버전 삭제 |
| 테스트 | `POST /prompts/{id}/{version}/test` | 변수 바인딩 테스트 |

### 3-4. 스키마 관리

구조화된 출력(Structured Output)을 위한 JSON Schema를 버전 관리합니다.

**UI 경로**: Schemas

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /schemas` | user, project 필터 가능 |
| 생성 | `POST /schemas` | 새 스키마 버전 |
| 상세 | `GET /schemas/{id}/{version}` | 버전별 상세 |
| 삭제 | `DELETE /schemas/{id}/{version}` | 버전 삭제 |
| 검증 | `POST /schemas/{id}/{version}/validate` | 데이터 검증 테스트 |

### 3-5. 워크플로우 관리

DAG 기반 워크플로우를 설계하고 실행합니다. Workflow Studio(비주얼 에디터)에서 노드를 배치하거나, API로 직접 정의할 수 있습니다.

**UI 경로**: Workflows (목록) / Workflow Studio (편집)

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /workflows` | 전체 워크플로우 |
| 생성 | `POST /workflows` | 새 워크플로우 |
| 상세 | `GET /workflows/{id}` | inputSchema 포함 |
| 수정 | `PUT /workflows/{id}` | 스텝/연결 변경 |
| 삭제 | `DELETE /workflows/{id}` | 워크플로우 제거 |
| 실행 | `POST /workflows/{id}/run` | 수동 실행 |
| 실행 이력 | `GET /workflows/{id}/runs` | 실행 이력 목록 |
| 실행 결과 | `GET /workflows/{id}/runs/{runId}` | 개별 실행 결과 |

**노드 타입** (Studio 팔레트):
- `LLM_CALL` / `TOOL_CALL` / `CONDITION` / `PARALLEL` / `HUMAN_INPUT` / `ACTION` / `AGENT_CALL` — 기존
- `EVALUATOR_LOOP` (v7.10, CR-055) — 아래 별도 설명

**EVALUATOR_LOOP (평가-최적화 루프)** [CR-055]

한 노드 내부에서 생성 → 평가 → 재생성 루프를 반복. Anthropic "Evaluator-Optimizer" 패턴.

사용처 예: 문학 번역 품질 개선, 마케팅 카피 페르소나 평가, 코드 리뷰 대응.

- **팔레트**: 🔁 "평가-최적화 루프" (보라색)
- **속성 편집**: `max_iterations` (1-10, 기본 3) + `generator`/`evaluator`/`pass_criteria` 3개 JSON 블록
- **Evaluator 프롬프트**: 기본 템플릿 3종 제공 (`evaluator.literary_critic`, `evaluator.code_reviewer`, `evaluator.persona_copy` — 영문. 한국어/커스터마이징은 prompt_templates 테이블에 `version=2`로 추가)
- **통과 조건 3종**:
  - `SCORE_THRESHOLD` (권장 기본) — evaluator 응답의 `score` 필드를 threshold와 비교
  - `JSONPATH_MATCH` — 단순 dot-path (`$.passed`) 기반 매칭
  - `LLM_JUDGE` — evaluator 응답의 `passed` 필드 그대로 사용
- **실행 결과 뷰**: WorkflowDetail 페이지에서 iteration별 아코디언으로 전개 (score 배지, gen/eval 소요시간, 에러/재시도 표시)
- **비대칭성 원칙**: generator와 evaluator에 **다른 역할 프롬프트**를 사용해야 개선폭이 큼. 같은 모델로 자가 평가 시 WARN 로그

**FE JSON 편집 힌트** (`{{loop.*}}` 변수):
- `{{loop.iteration}}` — 0부터 시작하는 현재 반복 인덱스
- `{{loop.previous_output}}` — 직전 generator 출력
- `{{loop.feedback}}` — 직전 evaluator feedback
- `{{loop.generator_output}}` — (evaluator 시점) 현재 iteration의 generator 출력

### 3-6. 지식소스 (Knowledge Source) 관리

RAG를 위한 지식소스를 등록하고 인제스션합니다.

**UI 경로**: Knowledge

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /knowledge-sources` | 전체 소스 |
| 생성 | `POST /knowledge-sources` | 소스 생성 (file/api/url) |
| 상세 | `GET /knowledge-sources/{id}` | 소스 상세 |
| 수정 | `PUT /knowledge-sources/{id}` | 청킹/임베딩 설정 변경 |
| 삭제 | `DELETE /knowledge-sources/{id}` | 소스 + 임베딩 삭제 |
| 파일 업로드 | `POST /knowledge-sources/{id}/upload` | 파일 업로드 |
| 전체 인제스션 | `POST /knowledge-sources/{id}/sync` | 전체 재인제스션 |
| 텍스트 인제스션 | `POST /knowledge-sources/{id}/ingest-text` | 개별 문서 upsert |
| 문서 삭제 | `DELETE /knowledge-sources/{id}/documents/{docId}` | 개별 문서 임베딩 삭제 |
| 검색 | `POST /knowledge-sources/search` | 벡터 검색 |
| 인제스션 로그 | `GET /knowledge-sources/{id}/ingestion-logs` | 인제스션 이력 |

**소스 생성 예시** (file 타입):

```json
{
  "name": "판례 데이터베이스",
  "type": "file",
  "embeddingModel": "BAAI/bge-m3",
  "chunkingConfig": {
    "strategy": "contextual",
    "chunkSize": 512,
    "overlap": 50
  }
}
```

> **주의**: `sync`는 소스 전체를 재인제스션합니다(기존 임베딩 삭제 후 재생성). 증분 업데이트는 `ingest-text`를 사용하세요.

### 3-7. MCP 서버 관리

Model Context Protocol 서버를 등록하여 도구를 확장합니다.

**UI 경로**: MCP Servers

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /mcp-servers` | 등록된 서버 목록 |
| 등록 | `POST /mcp-servers` | 새 MCP 서버 등록 |
| 상세 | `GET /mcp-servers/{id}` | 서버 상세 |
| 수정 | `PUT /mcp-servers/{id}` | 설정 변경 |
| 삭제 | `DELETE /mcp-servers/{id}` | 서버 제거 |
| 도구 탐색 | `POST /mcp-servers/{id}/discover` | 서버에서 도구 자동 등록 |
| 연결 해제 | `POST /mcp-servers/{id}/disconnect` | 서버 연결 해제 |

### 3-8. LLM 라우팅 설정

요청 조건에 따라 어떤 LLM Connection을 사용할지 라우팅 규칙을 정의합니다.

**UI 경로**: Monitoring > Routing

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /routing` | 전체 라우팅 규칙 |
| 활성 규칙 | `GET /routing/active` | 현재 활성 규칙만 |
| 생성 | `POST /routing` | 새 라우팅 규칙 |
| 상세 | `GET /routing/{id}` | 규칙 상세 |
| 수정 | `PUT /routing/{id}` | 규칙 변경 |
| 삭제 | `DELETE /routing/{id}` | 규칙 제거 |

### 3-9. 검색 설정 (Retrieval Config)

RAG 검색 파이프라인의 동작을 세부 조정합니다.

**UI 경로**: Knowledge > Retrieval Config

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /retrieval-config` | 검색 설정 목록 |
| 생성 | `POST /retrieval-config` | 새 검색 설정 |
| 상세 | `GET /retrieval-config/{id}` | 설정 상세 |
| 수정 | `PUT /retrieval-config/{id}` | 설정 변경 |
| 삭제 | `DELETE /retrieval-config/{id}` | 설정 제거 |

### 3-10. RAG 평가 (Evaluation)

RAG 품질을 RAGAS 메트릭으로 평가하고, LLM 출력을 검증합니다.

**UI 경로**: RAG Evaluation

| 작업 | API | 설명 |
|------|-----|------|
| 상태 확인 | `GET /evaluations/status` | 평가 MCP 서버 가용 여부 |
| RAG 평가 | `POST /evaluations/rag` | 단건 RAG 품질 평가 |
| LLM 출력 평가 | `POST /evaluations/llm-output` | 환각/독성 평가 |
| 프롬프트 비교 | `POST /evaluations/prompt-comparison` | 프롬프트 회귀 테스트 |
| RAGAS 배치 평가 | `POST /evaluations/rag-quality` | 비동기 배치 평가 |
| 평가 결과 | `GET /evaluations/rag-quality/{id}` | 개별 평가 결과 |
| 평가 이력 | `GET /evaluations/rag-quality` | 소스별 평가 이력 |

### 3-11. 사용자/역할 관리

테넌트 내 사용자 계정과 역할을 관리합니다.

**UI 경로**: Users / Roles

**사용자**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /users` | 사용자 목록 |
| 생성 | `POST /users` | 사용자 추가 |
| 상세 | `GET /users/{id}` | 사용자 상세 |
| 수정 | `PUT /users/{id}` | 이름, 역할 변경 |
| 비활성화 | `DELETE /users/{id}` | 소프트 삭제 |
| API Key 재발급 | `POST /users/{id}/api-key` | 사용자 API Key 재생성 |

**역할**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /roles` | 역할 목록 |
| 생성 | `POST /roles` | 역할 추가 |
| 상세 | `GET /roles/{id}` | 권한 포함 상세 |
| 수정 | `PUT /roles/{id}` | 역할/권한 변경 |
| 삭제 | `DELETE /roles/{id}` | 역할 제거 |

### 3-12. 프로젝트 관리

리소스(프롬프트, 스키마, 지식소스 등)를 프로젝트 단위로 묶어 관리합니다.

**UI 경로**: Projects

**프로젝트 CRUD**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /projects` | user 필터 가능 |
| 생성 | `POST /projects` | 프로젝트 생성 |
| 상세 | `GET /projects/{id}` | 멤버, 리소스 포함 |
| 수정 | `PUT /projects/{id}` | 프로젝트 정보 변경 |
| 삭제 | `DELETE /projects/{id}` | 프로젝트 삭제 |

**멤버 관리**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /projects/{id}/members` | 멤버 목록 |
| 추가 | `POST /projects/{id}/members` | 멤버 추가 |
| 역할 변경 | `PUT /projects/{id}/members/{userId}` | 멤버 역할 변경 |
| 제거 | `DELETE /projects/{id}/members/{userId}` | 멤버 제거 |

**리소스 할당**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /projects/{id}/resources` | type 필터 가능 |
| 할당 | `POST /projects/{id}/resources` | 리소스 연결 |
| 해제 | `DELETE /projects/{id}/resources/{type}/{resourceId}` | 리소스 연결 해제 |

### 3-13. Native Tool 관리 [CR-029]

9종 네이티브 도구를 조회하고 직접 실행 테스트할 수 있습니다.

**UI 경로**: Tools

**네이티브 도구 목록**:

| 도구명 | 역할 |
|--------|------|
| `rag_search` | RAG 벡터 검색 |
| `web_search` | 웹 검색 |
| `code_interpreter` | 코드 실행 |
| `file_reader` | 파일 읽기 |
| `file_writer` | 파일 쓰기 |
| `calculator` | 수학 연산 |
| `json_transformer` | JSON 변환 |
| `http_client` | HTTP 요청 |
| `claude_code` | Claude Code 실행 |

**도구 API**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /tools` | contract 포함 조회 |
| 계약 상세 | `GET /tools/{toolName}/contract` | 입출력 스키마 상세 |
| 직접 실행 | `POST /tools/{toolName}/execute` | 도구 단독 실행 |
| 입력 검증 | `POST /tools/{toolName}/validate` | contract 기반 검증 |

**직접 실행 테스트 예시**:

```bash
# rag_search 도구 직접 실행
curl -X POST http://localhost:8280/api/v1/tools/rag_search/execute \
  -H "X-API-Key: plat-xxxx" \
  -H "Content-Type: application/json" \
  -d '{ "input": { "query": "서버 장애 대응", "topK": 3 } }'
```

**Workspace Policy 설정**:

도구 실행 시 보안 정책을 적용할 수 있습니다.

| 설정 | 설명 | 예시 |
|------|------|------|
| `allowed_roots` | 파일 접근 허용 경로 | `["/data/workspace", "/tmp"]` |
| `denied_paths` | 접근 차단 경로 | `["/etc", "/root", "**/.env"]` |
| `secret_patterns` | 비밀 감지 패턴 (정규식) | `["sk-[a-zA-Z0-9]+", "password\\s*="]` |

### 3-14. Context Recipe 설정 [CR-029]

컨텍스트 조립 레시피를 생성하여 LLM에 전달할 컨텍스트를 체계적으로 구성합니다.

**UI 경로**: Context Recipes

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /context-recipes` | 전체 레시피 |
| 생성 | `POST /context-recipes` | 새 레시피 |
| 상세 | `GET /context-recipes/{id}` | 레시피 상세 |
| 수정 | `PUT /context-recipes/{id}` | 레시피 변경 |
| 삭제 | `DELETE /context-recipes/{id}` | 레시피 제거 |
| 미리보기 | `POST /context-recipes/{id}/preview` | 조립 결과 미리보기 |

**레시피 생성 예시**:

```json
{
  "name": "AXOPM 기본 레시피",
  "layers": [
    { "type": "system_prompt", "priority": 1 },
    { "type": "rag", "sourceId": "src_xxx", "priority": 2, "topK": 5 },
    { "type": "conversation_history", "priority": 3, "maxTurns": 10 }
  ],
  "budget": { "maxTokens": 8000 },
  "freshness": "real_time"
}
```

**주요 설정**:

| 항목 | 설명 |
|------|------|
| `layers` | 컨텍스트 레이어 배열. `type`: system_prompt, rag, conversation_history, tool_result 등 |
| `budget.maxTokens` | 전체 컨텍스트 토큰 상한 |
| `priority` | 레이어 우선순위 (낮을수록 먼저 조립, 토큰 부족 시 높은 priority부터 제거) |
| `freshness` | `real_time` (매 요청 재조립) 또는 `cached` (캐시 활용) |

### 3-15. Domain Config 관리 [CR-029]

도메인(소비앱) 단위로 기본 설정을 등록하여, 해당 도메인의 모든 요청에 일괄 적용합니다.

**UI 경로**: Domain Configs

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /domain-configs` | 전체 도메인 설정 |
| 생성 | `POST /domain-configs` | 도메인 설정 등록 |
| 상세 | `GET /domain-configs/{domainApp}` | 도메인별 상세 |
| 수정 | `PUT /domain-configs/{domainApp}` | 설정 변경 |
| 삭제 | `DELETE /domain-configs/{domainApp}` | 설정 제거 |

**등록 예시 (AXOPM)**:

```json
{
  "domainApp": "axopm",
  "defaultRecipeId": "recipe_xxx",
  "toolAllowlist": ["rag_search", "web_search", "calculator"],
  "runtime": {
    "maxTokens": 4096,
    "temperature": 0.7,
    "defaultConnectionId": "conn_xxx"
  }
}
```

| 필드 | 설명 |
|------|------|
| `domainApp` | 도메인 식별자 (테넌트 생성 시 지정한 값) |
| `defaultRecipeId` | 기본 Context Recipe. 요청에 recipe 미지정 시 자동 적용 |
| `toolAllowlist` | 허용 도구 목록. 미지정 시 전체 허용 |
| `runtime` | LLM 호출 기본값 (maxTokens, temperature, defaultConnectionId) |

### 3-16. 서브에이전트 관리 [CR-030]

서브에이전트는 메인 세션에서 독립적인 LLM 에이전트를 생성하여 병렬/순차로 작업을 위임하는 기능입니다.

**관리 엔드포인트:**

| 작업 | 경로 | 설명 |
|------|------|------|
| 단일 실행 | `POST /agents/run` | 포그라운드/백그라운드 실행 |
| 멀티 조율 | `POST /agents/orchestrate` | 병렬 또는 순차 실행 |
| 상태 조회 | `GET /agents/{runId}` | 실행 결과 확인 |
| 목록 조회 | `GET /agents/session/{parentSessionId}` | 부모 세션별 목록 |
| 강제 취소 | `POST /agents/{runId}/cancel` | 실행 중인 에이전트 중단 |
| 활성 현황 | `GET /agents/active` | 현재 실행 중인 에이전트 수 |

**Worktree 격리:**
- `isolation: "WORKTREE"` 설정 시 git worktree 기반 격리 환경에서 실행
- 변경사항 없으면 자동 정리, 있으면 `worktreePath`/`branchName` 반환
- 타임아웃 스캔(30초 간격), 고아 worktree 정리(5분 간격) 자동 수행

**워크플로우 통합:**
- `AGENT_CALL` 스텝 타입으로 DAG에서 서브에이전트 실행 가능
- 단일 에이전트 또는 멀티에이전트(parallel/sequential) 지원
- `{{input.key}}`, `{{stepId.field}}` 변수 치환 지원

---

## 4. 운영 시나리오

### 시나리오 A: 새 소비앱 온보딩 (예: LexFlow)

```
1. [플랫폼 관리자] 테넌트 생성
   POST /platform/tenants  { identifier: "lexflow_companya", domainApp: "lexflow", ... }

2. [플랫폼 관리자] API Key 발급
   POST /platform/api-keys  { domainApp: "lexflow", tenantId: "lexflow_companya" }

3. [테넌트 관리자] LLM Connection 등록
   POST /connections  { provider: "ANTHROPIC", config: { apiKey: "...", model: "..." } }
   POST /connections/{id}/test  → 연결 확인

4. [테넌트 관리자] 기본 정책 세팅
   POST /policies  { name: "토큰 제한", domain: "RATE_LIMIT", ... }
   POST /policies  { name: "민감정보 차단", domain: "SECURITY", ... }

5. [테넌트 관리자] 지식소스 + 인제스션
   POST /knowledge-sources  { name: "판례DB", type: "file", embeddingModel: "BAAI/bge-m3" }
   POST /knowledge-sources/{id}/upload  → 파일 업로드
   POST /knowledge-sources/{id}/sync  → 인제스션

6. [소비앱 개발자] API Key로 연동 시작
   → aimbase-api-guide.md 참조
```

### 시나리오 B: 기존 테넌트에 새 워크플로우 추가

```
1. [테넌트 관리자] 프롬프트 등록
   POST /prompts  { name: "법률 분석", template: "...", variables: [...] }

2. [테넌트 관리자] 스키마 등록 (구조화된 출력이 필요한 경우)
   POST /schemas  { name: "분석 결과", jsonSchema: { ... } }

3. [테넌트 관리자] 워크플로우 생성
   POST /workflows  { name: "판례 분석 파이프라인", steps: [...] }
   → 또는 Workflow Studio에서 비주얼로 설계

4. [테넌트 관리자] 테스트 실행
   POST /workflows/{id}/run  { input: { ... } }
   GET /workflows/{id}/runs/{runId}  → 결과 확인

5. [소비앱 개발자] 워크플로우 실행 API 연동
   → aimbase-api-guide.md § 4 참조
```

### 시나리오 C: RAG 품질 모니터링

```
1. [테넌트 관리자] RAGAS 배치 평가 실행
   POST /evaluations/rag-quality  { sourceId: "...", testSet: [...] }

2. [테넌트 관리자] 결과 확인
   GET /evaluations/rag-quality/{id}
   → faithfulness, answer_relevancy, context_precision 등 메트릭 확인

3. [테넌트 관리자] 품질이 낮으면 조치
   - 청킹 전략 변경: PUT /knowledge-sources/{id}  { chunkingConfig: { strategy: "contextual" } }
   - 검색 설정 조정: PUT /retrieval-config/{id}  { topK: 10, reranking: true }
   - 재인제스션: POST /knowledge-sources/{id}/sync
```

### 시나리오 D: 도메인 앱 설정 + Context Recipe 구성 [CR-029]

```
1. [테넌트 관리자] Context Recipe 생성
   POST /context-recipes  {
     name: "AXOPM 기본",
     layers: [
       { type: "system_prompt", priority: 1 },
       { type: "rag", sourceId: "src_xxx", priority: 2, topK: 5 },
       { type: "conversation_history", priority: 3 }
     ],
     budget: { maxTokens: 8000 }
   }

2. [테넌트 관리자] Recipe 미리보기로 검증
   POST /context-recipes/{id}/preview  { query: "테스트 질문" }
   → 조립 결과 확인 (토큰 사용량, 레이어별 내용)

3. [테넌트 관리자] Domain Config 등록
   POST /domain-configs  {
     domainApp: "axopm",
     defaultRecipeId: "{recipeId}",
     toolAllowlist: ["rag_search", "calculator"],
     runtime: { maxTokens: 4096, temperature: 0.7 }
   }

4. [테넌트 관리자] 도구 실행 테스트
   POST /tools/rag_search/execute  { input: { query: "검색 테스트" } }
   → 정상 응답 확인

5. [테넌트 관리자] 도구 실행 이력 확인
   GET /tool-executions?session_id={sessionId}
   → 실행 기록, 소요 시간, 상태 확인
```

### 시나리오 E: 테넌트 일시 정지 / 재활성화

```
1. [플랫폼 관리자] 정지
   POST /platform/tenants/{id}/suspend
   → 해당 테넌트의 모든 API 호출 차단, 데이터는 보존

2. [플랫폼 관리자] 재활성화
   POST /platform/tenants/{id}/activate
   → 즉시 서비스 재개
```

### 시나리오 F: 멀티에이전트 워크플로우 구성 [CR-030]

```
1. [테넌트 관리자] 워크플로우에 AGENT_CALL 스텝 추가
   POST /workflows
   body.steps = [
     { "id": "s1", "type": "LLM_CALL", ... },
     { "id": "s2", "type": "AGENT_CALL",
       "config": {
         "agents": [
           { "description": "코드 분석", "prompt": "{{s1.output}} 분석" },
           { "description": "테스트 생성", "prompt": "{{s1.output}} 테스트" }
         ],
         "execution": "parallel"
       },
       "dependsOn": ["s1"]
     }
   ]

2. [소비앱] 워크플로우 실행
   POST /workflows/{id}/execute
   → s1 완료 → s2에서 2개 에이전트 병렬 실행 → 결과 병합

3. [운영자] 에이전트 모니터링
   GET /agents/active → 활성 에이전트 현황
   GET /agents/session/{sessionId} → 세션별 실행 이력
   POST /agents/{runId}/cancel → 필요 시 강제 종료
```

### 시나리오 G: 원격 에이전트 관리 [CR-041]

**시나리오**: 소비앱이 Aimbase Agent SDK를 사용해 원격 에이전트로 등록하고, Aimbase가 해당 에이전트의 도구를 오케스트레이션에 활용한다.

**사전 조건:**
- 소비앱이 `aimbase-tool-sdk-mcp` 의존성 추가
- 소비앱이 Agent MCP 서버 기동 완료

**절차:**

```
1. [소비앱] AgentLifecycle.start() 호출
   → MCP 서버 기동 + STUN 주소 탐색 + Aimbase 자동 등록

2. [운영자] 등록된 에이전트 확인
   GET /agents?status=ACTIVE

3. [Aimbase] 에이전트의 도구가 30초 내 ToolRegistry에 자동 동기화

4. [소비앱/오케스트레이터] LLM 오케스트레이션 시 원격 도구 자동 호출 가능

5. [소비앱] 에이전트 종료 시 AgentLifecycle.close() 호출
   → Aimbase에서 자동 해제
```

**주의사항:**
- BIZ-079: 5분간 하트비트 없으면 STALE 처리됨
- 같은 주소:포트로 재등록 시 기존 등록 갱신
- STALE 에이전트가 하트비트 재개하면 자동 ACTIVE 복구

### 시나리오 I: 채팅 세션 운영 — 중지·끼어들기·삭제 [CR-046]

**상황**: 사용자가 채팅 중 잘못된 질문을 했거나, 응답이 너무 길어지거나, 대화방을 정리하려는 경우.

**중지 (Abort)**:
1. FE 채팅창에서 [중지] 버튼 클릭
2. `POST /api/v1/chat/{sessionId}/abort` 호출 → BE가 즉시 LLM 스트림 + 도구 루프 중단
3. 부분 메시지는 `[중단됨] {지금까지 받은 텍스트}`로 DB 저장. 중지 시점까지의 토큰만 과금.
4. 운영 영향: LLM 토큰·도구 비용이 즉시 끊김 (이전엔 BE 가상 스레드가 계속 돌며 과금됨).

**자동 끼어들기 (옵션 B)**:
- 사용자가 응답 도중 새 메시지 전송 시 BE가 자동으로 이전 스트림 abort 후 새 요청 처리 (ChatGPT 표준).
- 별도 조작 없이 자연스럽게 동작.

**대화방 삭제 (Soft Delete)**:
1. 좌측 사이드바 대화방 호버 → 휴지통 아이콘 → 확인 모달 → DELETE 호출
2. `deleted_at` 컬럼만 마킹 (실제 데이터 보존). 모든 목록/조회는 `deleted_at IS NULL` 필터.
3. 권한: **본인 세션만 삭제 가능**. 관리자 강제 삭제 미제공.
4. 활성 스트림 존재 시 자동 abort.
5. 감사·과금 로그(`tool_execution_log`/`usage_logs`/`audit_logs`/`traces`/`session_briefs`)는 보존 (FK 미연결 의도적).
6. **휴지통/복구 UI 미제공** — DB 직접 `UPDATE deleted_at = NULL`로만 복구 가능 (DBA 권한).

**모니터링**:
- `usage_logs.metadata->>'aborted'`가 `true`인 비율로 사용자 중지 빈도 추적
- `conversation_sessions WHERE deleted_at IS NOT NULL` 카운트로 삭제 추세 확인

### 시나리오 H: 독립 실행형 Agent 설치/운영 [CR-042]

**시나리오**: 코드 작성 없이 `aimbase-agent` 설치 패키지(dmg/msi)를 고객 PC에 설치하여 Aimbase 원격 도구 에이전트로 활용한다.

**사전 조건:**
- Aimbase 서버 기동 중
- 고객에게 API Key 발급 완료

**절차:**

```
1. [고객] 설치 파일 실행
   macOS: AimbaseAgent.dmg → Applications 드래그
   Windows: AimbaseAgent.msi → 설치 마법사

2. [고객] 설정 파일 편집
   ~/.aimbase-agent/config/application.yml
   → agent.aimbase-url, agent.api-key 설정

3. [자동] OS 서비스로 자동 기동
   macOS: launchd (com.platform.aimbase-agent)
   Windows: WinSW 서비스

4. [자동] Aimbase 서버에 자동 등록 + 하트비트 시작

5. [운영자] 등록된 에이전트 확인
   GET /agents?status=ACTIVE

6. [Aimbase] 에이전트의 14개 도구 자동 동기화
```

**도구 비활성화:**
- `agent.disabled-tools: [bash]` — 보안상 위험한 도구 제외 가능

**모니터링:**
- 로그: `~/.aimbase-agent/logs/agent.log` (14일 보관, 100MB 상한)
- 상태: `~/.aimbase-agent/status.json` (5분 주기 갱신)

**업그레이드:**
- 새 설치 패키지를 덮어 설치. 사용자 설정(`~/.aimbase-agent/config/`)은 유지됨

### 시나리오 J: ClaudeCodeTool 다중계정 운영 [CR-043]

**목적**: 여러 OAuth/API Key 계정을 풀로 등록해 (1) 테넌트별 격리, (2) 공용 라운드로빈, (3) 계정 실패 시 자동 페일오버를 제공.

**등록 플로우**:
1. 계정 등록 — `agent_accounts` 테이블에 레코드 삽입 (또는 Admin API 사용)
   - `auth_type`: `oauth_token` 또는 `api_key`
   - `auth_token`: OAuth 토큰 또는 Anthropic API Key
   - `agent_type`: `claude_code`
   - `priority`: 선택 우선순위 (높을수록 먼저)
   - `max_concurrent`: 계정당 동시 실행 한도
2. 할당 매핑 — `agent_account_assignments`로 (테넌트, 앱)↔계정 연결
   - 테넌트별 전용: `tenant_id=<uuid>, app_id=null`
   - 공용 라운드로빈: `assignment_type='round_robin'` (테넌트/앱 비움)
3. 토큰 배포 — `POST /api/v1/platform/agent-accounts/{id}/deploy-token`
4. 헬스체크 — 60초 주기 자동 실행, `GET /api/v1/platform/agent-accounts/pool-status`로 상태 확인

**호출중 자동 재시도 (CR-043 핵심)**:
- 특정 계정으로 실행 중 인증 실패(401/403) · Rate Limit(429) · 5xx 서버 장애 발생 시,
  해당 계정은 GenericCircuitBreaker에 실패 기록 → 다음 후보 계정으로 자동 재시도
- 최대 재시도 횟수는 `claude-code.max-retry` (기본 2회)
- 재시도 간 backoff는 `claude-code.retry-backoff-ms` (기본 500ms, exponential: 500/1500/3500…)
- 비재시도 에러(프롬프트 오류, 파일 없음, 도구 거부 등)는 즉시 실패 반환
- 사용자 노출 메시지: `"다중 계정 시도 후 실패: <마지막 에러>"` — 계정 ID는 audit log에만 기록

**설정 예시 (`application.yml`)**:
```yaml
claude-code:
  enabled: true
  max-retry: 2           # 추가 재시도 횟수 (총 시도 = 1 + max-retry)
  retry-backoff-ms: 500  # 0이면 즉시 재시도
```

**Circuit Breaker 튜닝**:
- 기본 임계값: 연속 실패 3회 시 OPEN, 5분간 차단 후 HALF_OPEN 전환
- 수동 리셋: `POST /api/v1/platform/agent-accounts/{id}/circuit-reset`

**장애 대응 runbook**:
1. `pool-status` 조회 → `circuitState=OPEN` 계정 확인
2. 해당 계정 토큰 만료 여부 점검 (`extract-and-save-token` 후 재발급)
3. 재발급된 토큰으로 `deploy-token` → `circuit-reset`
4. 이후 테스트 요청으로 `healthy` 상태 확인

---

## 5. 운영 주의사항

### 보안
- LLM API Key는 Connection의 `config` 필드에 암호화 저장됨. UI에서 마스킹 표시
- 시스템 API Key는 발급 시 1회만 노출 — 분실 시 재발급 필요
- 테넌트 간 데이터 격리는 Database-per-Tenant로 보장. 교차 접근 불가

### 성능
- RAG 인제스션(`sync`)은 대용량 파일 시 수 분 소요 가능. 비동기 처리됨
- pgvector HNSW 인덱스는 대량 데이터 시 빌드 시간이 증가. 오프피크에 실행 권장
- 워크플로우 DAG 실행은 Virtual Threads 기반. 병렬 스텝 자동 분배

### 임베딩
- 기본 모델: BGE-M3 (1024차원, 로컬 실행)
- OpenAI text-embedding-3-small 선택 가능 (외부 API 호출)
- 모델 변경 시 해당 소스의 전체 재인제스션 필요

### 정책
- priority 숫자가 높을수록 먼저 평가
- 첫 DENY 또는 REQUIRE_APPROVAL 매칭 시 평가 중단
- 정책 변경 후 `simulate`로 반드시 검증

### 컨텍스트·토큰 효율 운영 (CR-048)

**SessionToolRegistry (Deferred Tool 스키마 런타임 주입)**
- 세션 초기 활성 도구: `Read`, `Edit`, `Grep`, `Bash`, `TodoWrite`, `ToolSearch`, `ReadToolResult` (기본값)
- 그 외 도구는 이름+1줄 설명만 system prompt 후미 텍스트 블록에 노출됨. 모델이 `ToolSearch`로 검색해야 스키마 주입됨
- 기본 활성 세트 변경은 PlatformSettings `deferred_tool.default_active` 수정 (CR-040)
- 세션 종료(24h TTL) 시 레지스트리 엔트리 자동 삭제. Redis 백업 사용 시 `SESSION_TOOL_REG:{sessionId}` 키 확인 가능

**Tool Result Storage (TTL 24h)**
- 81920B 초과 tool result는 `tool_result_storage` 테이블에 원본 저장 + 체인에는 요약 stub 주입
- `expires_at < NOW()` 레코드는 일일 스케줄러로 삭제. 수동 삭제 시 `DELETE FROM tool_result_storage WHERE expires_at < NOW()`
- 모니터링: `aimbase.tool_result_storage.size_bytes` Micrometer gauge. 급증 시 임계치(기본 81920B) 조정 검토
- 트러블슈팅:
  - 모델이 "이전 결과가 보이지 않음" 보고 → 해당 result_id 만료 또는 타 세션 이관 확인
  - `ReadToolResult` 403 → 감사 로그(`TOOL_RESULT_READ`)에서 session_id 불일치 사례 확인
  - 테이블 비대화 → 임계치 상향 또는 TTL 단축(세션 TTL 범위 내)

**Adaptive Thinking 동적 조정**
- ADAPTIVE 모드 커넥션만 동적 budget 적용. DISABLED/ENABLED는 기존 동작 유지
- 공식 설정 키 5종은 PlatformSettings에서 런타임 조정 가능:
  - `adaptive_thinking.base_budget` (4000)
  - `adaptive_thinking.tool_calls_multiplier` (1.5)
  - `adaptive_thinking.error_multiplier` (2.0)
  - `adaptive_thinking.long_question_multiplier` (1.3)
  - `adaptive_thinking.cap` (32000)
- A/B 튜닝: `session_metadata.thinking_budget` + 당 턴의 재시도/에러 발생 여부를 주기 집계하여 공식 조정
- 비용 모니터링: 특정 테넌트의 thinking_budget 평균이 지속 상승하면 cap 하향 또는 공식 완화 검토

---

### 시나리오 K: 테넌트/프로젝트 시스템 지침 운영 [CR-049]

테넌트 관리자가 코드 배포 없이 자신의 테넌트(또는 특정 프로젝트)에 대해 시스템 지침을 편집할 수 있다. 우선순위는 PROJECT > TENANT > GLOBAL이며 cascade append로 병합된다(BIZ-098).

**적용 단계**:
1. **테넌트 관리자 로그인** → 좌측 메뉴 "설정" → "시스템 지침" 탭 진입
2. monaco editor에서 지침 본문 작성 (markdown). 예: "본 테넌트는 결제 도메인이며 PCI-DSS 준수를 항상 명시할 것"
3. 우측 미리보기 패널에서 `GLOBAL + TENANT + PROJECT` 합산 결과 확인
4. 길이 진행 바가 노란색(8KB 초과) 경고 시 압축 또는 분리
5. "저장" 클릭 → version 자동 증가 + audit_log 기록

**프로젝트 단위 지침**:
- 프로젝트 상세 페이지 → "프로젝트 지침" 탭에서 동일 절차
- 프로젝트 지침은 해당 프로젝트 컨텍스트에서만 cascade 마지막 단계로 append됨

**권한 규칙**:
- 슈퍼어드민: GLOBAL 편집 가능 (`/platform/prompt-templates`)
- 테넌트 관리자: TENANT/PROJECT 편집 가능
- 일반 사용자: 메뉴 노출 안 됨

**운영 주의**:
- secret 패턴(API_KEY=, sk-, ghp_ 등) 입력 시 인라인 경고 + audit_log 기록. 저장은 진행되지만 시스템 프롬프트로 그대로 노출되므로 별도 비밀 저장소(Connection 환경변수 등)로 옮기기 권장
- cascade 합산 길이 8KB 초과는 경고만 발행하고 잘라내지 않는다 — 토큰 비용 직결이므로 주기적으로 정리
- 버저닝은 prompt_templates.version 컬럼 재사용 — 이전 버전 비교는 DB 직접 조회 또는 향후 별도 UI

---

### 시나리오 L: 장기 세션 재개 + 압축 경계 [CR-049]

자동 압축으로 잘려나간 장기 대화를 사용자가 무손실 재개할 수 있다.

**자동 동작**:
- ContextWindowManager가 토큰 임계 초과를 감지해 압축 수행 시, 압축된 메시지 그룹 뒤에 `COMPACT_BOUNDARY` 메시지를 자동 INSERT
- boundary_meta JSONB에 `{summary, compacted_count, tokens_saved}` 저장
- LLM 컨텍스트 직렬화 시 본문은 전달되지 않고 메타데이터로만 전달

**사용자 동작**:
1. Chat 페이지 좌측 대화방 목록에서 압축 경계가 있는 세션은 "재개" 버튼 노출
2. 클릭 시 `POST /sessions/{id}/resume` 호출 → 가장 최근 boundary 이후 메시지 + preserved_context 반환
3. MessageList에 `CompactBoundaryDivider` 컴포넌트가 표시됨 (접기/펼치기 가능, summary + "N개 메시지 / Xk 토큰 절약" 메타)
4. 위쪽 압축 메시지는 기본 접힘 — 펼치면 이전 맥락 표시(보존된 부분만)

**제약**:
- 24h TTL 이내 active 세션만 (BIZ-002). 만료 세션은 향후 archived 별도 조회로 분리
- 본인(user_id 일치)만 재개 가능
- 진행 중(streaming) 세션은 409 — 중지(abort, CR-046) 후 재개

**운영 모니터링**:
- Resume 사용률(=호출 수 / boundary 보유 세션 수)
- boundary 평균 tokens_saved
- 압축 빈도 비정상 증가 시 컨텍스트 임계값 검토

---

### 시나리오 M: Stop Hook 검증 게이트 운영 [CR-049]

사용자 정의 검증 hook(예: TodoWrite 미완료 차단, 테스트 FAIL 차단)이 실효성을 갖도록 STOP hook BLOCK 시 루프 재진입이 강제된다.

**동작 흐름**:
1. 도구 루프 종료 직전 STOP hook이 동기 호출됨 (BIZ-096 게이팅 hook)
2. hook 결과가 BLOCK이면 BLOCK reason을 새 user 메시지로 주입하고 루프 재진입
3. 모델은 reason을 보고 보완 작업 수행 → 다시 종료 시도
4. 동일 BLOCK reason이 한 턴 내 3회 초과 누적되면 강제 종료 + 사용자에게 시스템 메시지로 알림 (BIZ-097)

**Hook 등록 예 (관리자)**:
- 정책 페이지에서 STOP hook 등록 → script로 TodoWrite 상태 검사
- 미완료 todo 존재 시 `{ decision: 'BLOCK', reason: 'TODO 미완료: 인증 모듈 테스트' }` 반환

**운영 모니터링**:
- BLOCK → 재진입 → PASS 전환율(검증 hook 효과 지표)
- 동일 reason 3회 초과 강제 종료 발생률 — 임계값 조정 후보
- max_iterations 우선 종료 vs BIZ-097 강제 종료 비율

### 시나리오 N: 임베드 위젯 SDK 온보딩 [CR-058]

새 소비앱(예: OMS / Rescue) 이 채팅 + 워크플로우 진행 가시화 + RAG 출처 카드를 자기 UI 에 얹을 때의 표준 절차.

**1) Aimbase 관리자 작업 (운영자)**
```bash
# ① 소비앱 전용 API Key 발급 (§ 2-4 참조)
curl -X POST $AIMBASE/api/v1/platform/api-keys \
  -H "Authorization: Bearer $SUPER_ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"OMS 위젯 BFF", "domainApp":"oms", "tenantId":"rescue_prod"}'
# → apiKey 는 BFF env 에만 저장, 브라우저 번들 금지

# ② 소비앱 origin 을 화이트리스트에 추가
psql -U platform -h localhost -p 5432 aimbase_master \
  -c "UPDATE global_config SET config_value='https://oms.company.com',
      updated_by='ops', updated_at=NOW()
      WHERE config_key='widget.allowed-origins';"
```

**2) 소비앱 BFF 구현 (소비앱 개발자)**
- `/my-bff/aimbase-token` 엔드포인트 신설 — 인증된 사용자 요청에 한해 Aimbase 로 프록시
- API Key 는 서버 환경변수 (`AIMBASE_API_KEY`) 로만 보관
- 요청 시 `user_ref`, `project_id`, `origin`(고정) 을 첨부하여 Aimbase 에 단기 JWT 요청

**3) 소비앱 브라우저 통합 (프론트엔드)**
- 위젯 마운트 시 `authResolver()` 로 BFF 호출 → 토큰 수신
- `/api/v1/chat/completions` 는 `Authorization: Bearer` 헤더, SSE 구독은 `?access_token=` 쿼리
- `refresh_after` 초 경과 시 BFF 재호출로 토큰 갱신

**4) 검증 체크리스트**
- [ ] 소비앱에서 `OPTIONS /api/v1/chat/completions` 프리플라이트 200 확인
- [ ] `issue-widget-token` 성공 응답의 `scopes` 가 기대값과 일치
- [ ] 채팅 SSE `done` 이벤트 payload 에 `citations` 가 포함 (`rag_source_id` 지정 시)
- [ ] 워크플로우 실행 후 `GET /workflows/runs/{runId}/subscribe` 구독 → `workflow.snapshot` + `workflow.step` + `workflow.done` 순 수신

**문제 해결**:
- CORS 거부 → `widget.allowed-origins` 에 origin 이 빠졌거나 프로토콜/포트 불일치. 캐시 5분 TTL 대기 또는 재시작
- 401 "API Key is required to issue widget token" → BFF 가 JWT 로 호출함. 반드시 `X-API-Key` 헤더로
- 400 "origin is not allowed" → 토큰 발급 시 바디의 `origin` 과 화이트리스트 불일치
- SSE 연결은 되는데 아무 이벤트도 안 옴 → 런이 이미 종료됨(완료/실패) 가능. `GET /workflows/{id}/runs/{runId}` 로 상태 선확인

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| v3.0.0 | 2026-04-28 | **CR-072 + CR-073 — 서버 도구 MCP endpoint 노출 + agent Spring Boot 통합**. (1) `/mcp/sse` 가 서버 도구 26개를 MCP 채널로 노출 — 화이트리스트는 `McpExposurePolicy` (CLI 26 / NONE 6). 인증: `X-API-Key` (tenant 자동) + `X-Aimbase-Agent-Id` (선택). 거버넌스: PRE/POST_TOOL_USE Hook + Rate Limit (테넌트 단위, 분당 60) ✓ 적용. PolicyEngine / max_iter / 풀세트 Hook ✗ (CR-050 트레이드오프 계승). (2) `application.yml mcp.server-exposure.enabled` (기본 true) + `mcp.rate-limit.requests-per-minute` (기본 60). (3) **`--runner-mode` 플래그 폐지** (BREAKING) — `aimbase-agent` 는 `--mcp-stdio` 외 모든 진입에서 SERVLET 단일 컨텍스트. 후방 호환: 플래그 박혀있어도 무시. (4) `RunnerProperties` 에 `serverMcpBaseUrl/ApiKey/AgentId` 3종 추가 — 사용자 PC agent 가 mcpServers 에 `aimbase-server` 항목을 박아 CLI 가 직접 호출 |
| v2.9.0 | 2026-04-27 | **CR-071 ClaudeCliAdapter 운영 — 3경로 통일** (BREAKING). 기존 v2.6.0 의 `application.yml platform.llm.anthropic-cli.*` 5 설정과 in-process Worker Pool 운영 절차는 **모두 제거**. ClaudeCli 호출은 별도 프로세스 `aimbase-agent --runner-mode` (HTTP 서버, `aimbase.runner.*` 5 설정) 로 옮겨감. 운영 절차 변경: (1) **각 사용자 PC 또는 사내 서버에 aimbase-agent 설치 + `--runner-mode` 기동** (`--runner-api-key`, `--max-workers`, `--claude-binary` 옵션), (2) **agent 등록 시 metadata 에 `runnerEndpoint` + `runnerApiKeyHash` 포함** → `runner_capability=true` 자동 마킹 (V60 tenant 마이그레이션 자동 적용), (3) Connection `adapter=anthropic-cli` 사용 시 `tool_mode`(AIMBASE/NATIVE/HYBRID) 명시. 호출 시 `X-Aimbase-Agent-Id` 헤더로 라우팅, 누락 시 400. 테넌트 피처 플래그 `global_config.llm.anthropic-cli.enabled-tenants` 는 라우팅 정책 게이트로 의미 변경 (값 형식은 동일). BIZ-099 의미 변경(ToS 경계는 Runner 위치로 자연 해결), BIZ-100 (워커 5개 상한)은 Runner 내부에서 동일 적용 |
| v2.8.0 | 2026-04-24 | CR-066 Tenant Flyway 자동 재실행 — § 2-5 수동 절차 → Admin API 기반 자동 절차로 개정. `POST /api/v1/platform/tenants/migrate` (body 생략 시 활성 테넌트 전체, `tenantIds` / `dryRun` 지원) + `GET /api/v1/platform/tenants/{id}/migrations` (적용/대기 버전 조회). opt-in 기동 훅 `platform.tenant.migration.auto-migrate-on-startup` (기본 false, 실패 격리로 기동 차단 없음). 레거시 수동 스크립트는 폴백으로 접기 처리 유지 |
| v2.7.0 | 2026-04-24 | CR-060 위젯 STT 운영 — § 2-7 신설: V19 master 마이그레이션(`widget.stt.*` 5설정 seed + `widget.allowed-scopes` 에 `chat:stt` append), 사용량 모니터링 SQL(audit_logs `action=stt_transcribe`, 텍스트 본문 미저장), Redis rate-limit 카운터 확인, fail-open 경고, 마이크 권한 FAQ. `copyWidgetBundle` 최신 번들 33KB+ 확인. 테넌트 OpenAI Connection 공유(신규 키 불필요) |
| v2.6.0 | 2026-04-24 | CR-050 Claude CLI LLM 어댑터 운영 — `application.yml platform.llm.anthropic-cli.*` (enabled/timeout-seconds/max-workers-per-run/acquire-timeout-seconds/cli-binary-path) 5설정, 테넌트 피처 플래그 = `global_config.llm.anthropic-cli.enabled-tenants`(기본 빈 값=차단, `*`=전체, 쉼표 구분=선택). 사전 조건: 각 노드에 `claude` CLI 설치 + OAuth 로그인 또는 `claude_config_dir` 지정. BIZ-099 ToS 경계상 상용 외부 테넌트 비활성 유지. BIZ-100 run당 워커 5개 상한(큐잉). 프로세스 누수 감시: 정상 시 `ps -ef \| grep 'claude -p'` 는 run 종료 후 빈 결과 |
| v2.5.0 | 2026-04-24 | CR-061 위젯 파일 첨부 운영 — § 2-5 V58 수동 마이그레이션 스니펫 추가 + `widget.allowed-scopes` 기본값에 `chat:upload` 반영. § 2-6 신설: `widget.attachment.*` 설정 4종, 스토리지 사용량 모니터링 SQL, GC 스케줄러 관찰 로그 포인트, 장애 대응 가이드, 배포 전 체크리스트 |
| v2.4.0 | 2026-04-24 | CR-058 임베드 위젯(Chat Widget) 운영 — § 2-5 `widget.*` 설정 4종(allowed-origins/allowed-scopes/token-ttl-seconds/token-max-ttl-seconds), § 4 시나리오 N(위젯 SDK 온보딩 — API Key 발급 + Origin 화이트리스트 + 검증 체크리스트) |
| v2.3.0 | 2026-04-24 | CR-055 평가-최적화 루프 노드 (§ 3-5) — `EVALUATOR_LOOP` StepType, pass_criteria 3종(SCORE_THRESHOLD/JSONPATH_MATCH/LLM_JUDGE), evaluator 프롬프트 seed 3종, `{{loop.*}}` 변수 규약 |
| v2.2.0 | 2026-04-22 | CR-054 HTTP Connection 등록 절차 § 3-1 보강 — `type=HTTP` 신설, 인증 4종(API_KEY/BEARER/BASIC/NONE), `value_env` 환경변수 참조 권장, DomainFilterPolicy 연계 체크리스트 |
| v2.1.0 | 2026-04-16 | CR-049 세션 복원·지침 체계 운영 시나리오 K(테넌트/프로젝트 지침)·L(세션 재개 + Compact Boundary)·M(Stop Hook 검증 게이트) 추가 |
| v2.0.0 | 2026-04-16 | CR-048 컨텍스트·토큰 효율 운영 항목 추가 — SessionToolRegistry / Tool Result Storage TTL / Adaptive Thinking 설정 |
| v1.9.0 | 2026-04-16 | CR-043 ClaudeCodeTool 다중계정 운영 시나리오 J 추가 — 호출중 자동 재시도·페일오버·runbook |
| v1.8.0 | 2026-04-16 | CR-046 채팅 세션 운영 시나리오 I 추가 — 중지·자동 끼어들기·Soft Delete |
| v1.7.0 | 2026-04-10 | CR-042 독립 실행형 Agent 시나리오 H 추가 (§ 4) |
| v1.6.0 | 2026-04-10 | CR-041 원격 에이전트 관리 시나리오 G 추가 (§ 4) |
| v1.5.0 | 2026-04-08 | 에이전트 자율성 도구 4종 추가: ListMcpResourcesTool, ReadMcpResourceTool, RemoteTriggerTool, BriefTool. 세션 브리핑 패널 추가 (CR-038) |
| v1.4.0 | 2026-04-08 | 네이티브 도구 4종 추가: BashTool, FileWriteTool, WebSearchTool, SuggestBackgroundPRTool. ToolRegistry 자동 등록으로 도구 목록 자동 노출 (CR-037) |
| v1.3.0 | 2026-04-08 | 스케줄 관리(Cron 작업), 스킬 관리, Firecrawl 크롤링 모드, 도메인 필터링 정책 추가 (CR-035) |
| v1.2.0 | 2026-04-07 | 서브에이전트 관리(§ 3-16), 시나리오 F(멀티에이전트 워크플로우) 추가 (CR-030) |
| v1.1.0 | 2026-04-05 | Native Tool 관리(9종 도구, contract, 직접 실행, Workspace Policy), Context Recipe 설정, Domain Config 관리, 시나리오 D 추가 (CR-029) |
| v1.0.1 | 2026-03-28 | 접속 정보(포트 매핑) 섹션 추가 |
| v1.0.0 | 2026-03-28 | 초판 작성. 플랫폼 관리(테넌트/구독/API Key), 테넌트 관리(Connection~프로젝트), 운영 시나리오 4건 포함 |
