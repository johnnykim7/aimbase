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
    "baseUrl": "http://localhost:8180",
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

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
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
