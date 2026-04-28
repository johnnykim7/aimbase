# Aimbase REST API 통합 가이드

> **v2.0.0** | 2026-04-10 | Aimbase v6.3.0 기준

Swagger만으로는 알 수 없는 시나리오별 흐름, 파라미터 조합, 주의사항을 다룹니다.

---

## 1. 시작하기

### Base URL

| 환경 | URL | 비고 |
|------|-----|------|
| 로컬 개발 (IDE) | `http://localhost:8080/api/v1` | Spring Boot 직접 실행 |
| Docker Compose | `http://localhost:8280/api/v1` | BE 컨테이너 (8280→8080) |

Docker Compose 기동 시 포트 매핑:

| 서비스 | 호스트 포트 | 컨테이너 포트 | 용도 |
|--------|-----------|-------------|------|
| Frontend (nginx) | **3200** | 3000 | React SPA + API 프록시 |
| Backend (Spring Boot) | **8280** | 8080 | REST API |
| RAG Sidecar (Python) | **8281** | 8000 | MCP / RAG Pipeline |

> **Docker 환경에서는** `http://localhost:3200`으로 접속하면 FE에서 `/api/**` 요청을 BE로 자동 프록시합니다. 별도로 BE 포트(8280)를 직접 호출할 필요가 없습니다.

### 인증 방식

Aimbase는 **JWT 토큰**과 **시스템 API Key** 두 가지 인증을 지원합니다.

#### 방식 1: JWT 토큰 (사용자 로그인)

```bash
# 로그인
curl -X POST /api/v1/auth/login \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: {tenant_id}" \
  -d '{"email":"user@example.com","password":"password"}'

# 응답에서 access_token 추출
{
  "data": {
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "token_type": "Bearer"
  }
}
```

JWT 사용 시 필수 헤더:

| 헤더 | 설명 |
|------|------|
| `Authorization` | `Bearer {access_token}` |
| `X-Tenant-Id` | 테넌트 식별자 (예: `axopm_companyA`) |
| `Content-Type` | `application/json` |

#### 방식 2: 시스템 API Key (서비스 연동) — 권장

외부 시스템(AXOPM, ChatPilot 등)에서 Aimbase API를 호출할 때 사용합니다.

```bash
curl -X POST /api/v1/knowledge-sources/search \
  -H "Content-Type: application/json" \
  -H "X-API-Key: plat-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -d '{"query": "검색어", "topK": 5}'
```

API Key 사용 시 필수 헤더:

| 헤더 | 설명 |
|------|------|
| `X-API-Key` | 발급받은 시스템 API Key |
| `Content-Type` | `application/json` |

> **장점**: API Key에 `domain_app`(제품)과 `tenant_id`(테넌트)가 바인딩되어 있어 `Authorization`, `X-Tenant-Id` 헤더가 모두 불필요합니다. 테넌트 라우팅이 자동으로 처리됩니다.

**API Key 발급**: 플랫폼 관리자(SUPER_ADMIN)가 `/api/v1/platform/api-keys` 엔드포인트로 발급합니다.

```bash
# 키 발급 (SUPER_ADMIN JWT 필요)
curl -X POST /api/v1/platform/api-keys \
  -H "Authorization: Bearer {admin_token}" \
  -H "X-Tenant-Id: {admin_tenant}" \
  -d '{
    "name": "AXOPM CompanyA 연동키",
    "domainApp": "axopm",
    "tenantId": "axopm_companyA"
  }'
# → { "data": { "apiKey": "plat-xxxx...", "note": "이 키는 다시 조회할 수 없습니다." } }
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `name` | O | 키 식별 이름 |
| `domainApp` | O | 제품 식별자 (예: `axopm`, `chatpilot`) |
| `tenantId` | X | 바인딩할 테넌트. 생략 시 도메인 전용 키 |
| `scope` | X | 접근 범위 제한 (JSONB) |
| `expiresAt` | X | 만료 일시. 생략 시 무기한 |

> **주의**: 발급 시 반환되는 `apiKey` 값은 **최초 1회만 조회 가능**합니다. 안전한 곳에 즉시 저장하세요.

---

## 2. 지식 학습 (Knowledge Ingestion)

### 2-1. 파일 업로드 학습

소스 생성 → 파일 업로드 → 인제스션 3단계입니다.

```bash
# 1) 소스 생성
curl -X POST /api/v1/knowledge-sources \
  -d '{
    "name": "매뉴얼 문서",
    "type": "file",
    "embeddingModel": "BAAI/bge-m3"
  }'
# → { "data": { "id": "src_xxx", ... } }

# 2) 파일 업로드
curl -X POST /api/v1/knowledge-sources/{id}/upload \
  -F "file=@manual.pdf"
# → { "data": { "filePath": "...", "status": "uploaded" } }

# 3) 인제스션 실행
curl -X POST /api/v1/knowledge-sources/{id}/sync
# → { "data": { "status": "syncing" } }
```

**지원 파일**: pdf, docx, xlsx, pptx, csv, txt, md, html

**제약사항**:
- file 타입은 **소스당 파일 1개**. 재업로드 시 이전 파일을 덮어씁니다.
- 여러 파일을 관리하려면 파일별로 소스를 생성하세요.
- sync는 **소스 전체 재인제스션**입니다. 기존 임베딩을 삭제하고 다시 생성합니다.

### 2-2. 텍스트 직접 학습 (개별 문서 단위)

외부 시스템에서 엔티티 CUD마다 실시간으로 학습시킬 때 사용합니다.

```bash
# 1) 소스 생성 (최초 1회)
curl -X POST /api/v1/knowledge-sources \
  -d '{
    "name": "entity-index",
    "type": "api",
    "embeddingModel": "BAAI/bge-m3"
  }'

# 2) 개별 문서 인제스션
curl -X POST /api/v1/knowledge-sources/{id}/ingest-text \
  -d '{
    "content": "문서 내용 전체 텍스트",
    "documentId": "entity_12345"
  }'
# → { "data": { "chunks_created": 3, "success": true } }
```

**동작 방식**:
- `documentId` 기준으로 청킹 → 임베딩 → 저장
- 같은 `documentId`로 재호출하면 기존 임베딩을 삭제 후 재생성 (upsert)
- 소스의 `chunkingConfig`와 `embeddingModel` 설정을 자동 적용

**개별 문서 삭제**:

```bash
DELETE /api/v1/knowledge-sources/{id}/documents/{documentId}
# → { "data": { "sourceId": "...", "documentId": "...", "deletedChunks": 3 } }
```

엔티티 삭제 시 해당 문서의 임베딩만 제거합니다.

**요구사항**: Python RAG 사이드카(port 8002)가 기동 중이어야 합니다. 미기동 시 503 반환.

### 2-3. URL 크롤링 학습

```bash
curl -X POST /api/v1/knowledge-sources \
  -d '{
    "name": "기술 블로그",
    "type": "url",
    "config": { "urls": ["https://blog.example.com/post1", "https://blog.example.com/post2"] }
  }'

curl -X POST /api/v1/knowledge-sources/{id}/sync
```

### 2-4. 청킹 전략

소스 생성 시 `chunkingConfig`로 지정합니다.

| 전략 | 설명 | 적합한 경우 |
|------|------|-----------|
| `fixed` | 고정 크기 (기본 512자, 50 overlap) | 정형 데이터, 짧은 텍스트 |
| `semantic` | 의미 기반 분할 (사이드카) | 일반 문서, 긴 텍스트 |
| `contextual` | 각 청크에 LLM 생성 컨텍스트 부여 | 검색 정확도 최우선 |
| `parent_child` | 부모(1024)+자식(256) 계층 구조 | 문맥 보존이 중요한 경우 |

```json
{
  "chunkingConfig": {
    "strategy": "semantic",
    "incremental": true
  }
}
```

- `incremental: true` — SHA-256 해시 비교로 변경된 청크만 재임베딩 (sync 시 유용)

### 2-5. 임베딩 모델

| 모델 | 차원 | 특징 |
|------|------|------|
| `BAAI/bge-m3` (기본) | 1024 | 다국어, 한국어 강함, 로컬 실행 |
| `text-embedding-3-small` | 1536 | OpenAI API, 키 필요 |

소스 생성 시 `embeddingModel` 파라미터로 지정. **모델이 다른 소스끼리 통합 검색하면 정확도가 저하**됩니다.

---

## 3. 지식 검색 (Knowledge Search)

```bash
curl -X POST /api/v1/knowledge-sources/search \
  -d '{
    "query": "서버 메모리 누수 원인",
    "sourceId": "src_xxx",
    "topK": 5
  }'
```

**응답**:
```json
{
  "data": {
    "query": "서버 메모리 누수 원인",
    "results": [
      {
        "content": "JVM 힙 메모리 8GB 초과 후 OOM 발생...",
        "score": 9.4998,
        "sourceId": "src_xxx",
        "sourceName": "entity-index",
        "metadata": { ... }
      }
    ]
  }
}
```

- `sourceId` 생략 시 **전체 소스 통합 검색** (점수순 병합)
- `topK` 기본값: 5

---

## 4. 워크플로우

### 4-1. 워크플로우 생성

소스 생성 → 스텝 정의 → 실행의 흐름입니다.

```bash
curl -X POST /api/v1/workflows \
  -d '{
    "name": "Evidence 요건 설계 (EV-DESIGN)",
    "domain": "axopm",
    "triggerConfig": { "type": "manual" },
    "errorHandling": { "strategy": "stop_on_first", "maxRetries": 1 },
    "inputSchema": {
      "type": "object",
      "required": ["outcomeId", "statement"],
      "properties": {
        "outcomeId": { "type": "string", "description": "Outcome UUID" },
        "statement": { "type": "string", "description": "Outcome 선언문" }
      }
    },
    "outputSchema": {
      "type": "object",
      "required": ["suggestedRequirements", "reasoning"],
      "properties": {
        "suggestedRequirements": { "type": "array", "items": { "type": "object" } },
        "reasoning": { "type": "string" }
      }
    },
    "steps": [
      {
        "id": "design_evidence",
        "name": "Outcome 분석 후 증거 요건 구조 제안",
        "type": "LLM_CALL",
        "config": {
          "connection_id": "{connection_id}",
          "system": "당신은 OPM 전문가입니다. 증거 요건을 제안하세요.",
          "prompt": "Outcome: {{statement}}",
          "response_schema": {
            "type": "object",
            "required": ["suggestedRequirements", "reasoning"],
            "properties": {
              "suggestedRequirements": { "type": "array", "items": { "type": "object" } },
              "reasoning": { "type": "string" }
            }
          }
        },
        "depends_on": []
      }
    ]
  }'
# → { "data": { "id": "wf_xxx", "name": "Evidence 요건 설계 (EV-DESIGN)", ... } }
```

**필수/선택 필드**:

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `id` | string | O | 워크플로우 ID (생성 시 자동 생성, 수정 시 필수) |
| `name` | string | O | 워크플로우 이름 |
| `triggerConfig` | object | O | 트리거 설정 (`{ "type": "manual" }` 등) |
| `steps` | array | O | 스텝 배열 (아래 스텝 타입 참조) |
| `domain` | string | X | 도메인 분류 |
| `inputSchema` | object | X | 실행 시 입력 검증 JSON Schema |
| `outputSchema` | object | X | 출력 스키마 |
| `errorHandling` | object | X | 에러 전략 (`strategy`, `maxRetries`) |
| `projectId` | string | X | 프로젝트 귀속 (생략 시 `X-Project-Id` 헤더) |

**스텝 타입과 config**:

| type | config 키 | 설명 |
|------|----------|------|
| `LLM_CALL` | `connection_id`, `system`, `prompt`, `response_schema`, `max_tokens` | LLM 호출 (토큰 초과 시 자동 에스컬레이션+분할, CR-028) |
| `TOOL_CALL` | `tool`, `input` | 도구 호출 (ToolRegistry 등록 도구) |
| `ACTION` | `actionType`, `config` | 액션 실행 (write, notify) |
| `CONDITION` | `expression` | 조건 분기 |
| `PARALLEL` | `branches` | 병렬 실행 |
| `HUMAN_INPUT` | `message` | 사람 승인 대기 |

> **주의**: 스텝 타입은 `TOOL_CALL`입니다 (`TOOL_USE` 아님). config에서 도구 이름은 `tool` (`tool_name` 아님), 입력은 `input` (`arguments` 아님).

**스텝 간 데이터 참조**:

스텝 config 내에서 `{{stepId.output}}` 형태로 이전 스텝 결과를 참조합니다. `{{input.fieldName}}`은 워크플로우 실행 시 전달한 입력값입니다.

```json
{
  "id": "analyze",
  "type": "LLM_CALL",
  "config": {
    "prompt": "검색 결과: {{search.output}}\n질문: {{input.query}}"
  },
  "depends_on": ["search"]
}
```

### 4-2. 워크플로우 수정

PUT은 **전체 교체**(full replace)입니다. 변경하지 않는 필드도 모두 포함해야 합니다.

```bash
curl -X PUT /api/v1/workflows/{id} \
  -d '{
    "id": "{id}",
    "name": "Evidence 요건 설계 v2",
    "triggerConfig": { "type": "manual" },
    "steps": [ ... ],
    "inputSchema": { ... },
    "outputSchema": { ... },
    "errorHandling": { "strategy": "stop_on_first", "maxRetries": 2 }
  }'
```

> **주의**: body에 `id` 필드가 필수입니다 (path의 `{id}`와 동일 값).

### 4-3. 워크플로우 조회

```bash
# 목록
GET /api/v1/workflows

# 상세 (inputSchema 포함)
GET /api/v1/workflows/{id}
```

### 4-4. 실행

```bash
curl -X POST /api/v1/workflows/{id}/run \
  -d '{
    "outcomeId": "outcome-123",
    "statement": "직원은 연간 보안 교육을 이수해야 한다"
  }'
# → { "data": { "id": "run_xxx", "status": "running" } }
```

> 실행 시 body는 `inputSchema`에 정의된 필드를 직접 전달합니다 (래핑 없이 flat).

### 4-5. 결과 폴링

워크플로우는 **비동기 실행**입니다. 결과를 얻으려면 폴링이 필요합니다.

```bash
# 3초 간격으로 폴링 (권장 최대 20회)
GET /api/v1/workflows/{workflowId}/runs/{runId}
```

**완료 응답**:
```json
{
  "data": {
    "status": "completed",
    "stepResults": {
      "design_evidence": {
        "output": "{ \"suggestedRequirements\": [...], \"reasoning\": \"...\" }",
        "_startedAt": "2026-03-28T02:28:13.948Z",
        "_completedAt": "2026-03-28T02:28:18.165Z",
        "_durationMs": 4217
      }
    }
  }
}
```

### 4-6. 실행 상태

| status | 설명 |
|--------|------|
| `running` | 실행 중 |
| `completed` | 정상 완료 |
| `failed` | 스텝 실행 실패 |
| `pending_approval` | HUMAN_INPUT 승인 대기 |

### 4-7. 삭제

```bash
DELETE /api/v1/workflows/{id}
# → 204 No Content (연관된 실행 이력도 함께 삭제)
```

---

## 5. 에러 처리

| HTTP 코드 | 원인 | 조치 |
|-----------|------|------|
| 400 | 요청 파라미터 오류 | 필수 필드 확인 |
| 401 | 토큰 만료 또는 잘못된 인증 | 재로그인 |
| 403 | 권한 없음, `X-Tenant-Id` 누락, API Key 만료 | 인증 헤더 확인 |
| 404 | 리소스 없음 | ID 확인 |
| 503 | RAG 사이드카 미기동 | 사이드카 상태 확인 (port 8002) |

---

## 6. 주의사항

| 항목 | 설명 |
|------|------|
| 사이드카 DB 연결 | Python RAG 사이드카는 `DB_NAME` 환경변수로 테넌트 DB 지정. 멀티테넌트 시 확인 필요 |
| sync vs ingest-text | sync = 소스 전체 재처리. 개별 문서 학습에는 반드시 `ingest-text` 사용 |
| file 소스 제약 | 소스당 파일 1개. 여러 파일은 소스를 분리하거나 `ingest-text` 사용 |
| 워크플로우 비동기 | 즉시 결과를 받을 수 없음. 반드시 폴링 구현 필요 |
| 임베딩 모델 혼용 | 같은 소스 내에서 모델을 변경하면 기존 임베딩과 차원 불일치 발생 |

---

## 7. 도구 관리 (확장) [CR-029]

### 7-1. 도구 목록 조회

등록된 도구의 contract(입출력 스키마)를 포함하여 조회합니다.

```bash
GET /api/v1/tools
```

### 7-2. 도구 계약 상세

```bash
GET /api/v1/tools/{toolName}/contract
```

### 7-3. 도구 직접 실행

워크플로우 없이 개별 도구를 직접 호출합니다.

```bash
curl -X POST /api/v1/tools/{toolName}/execute \
  -d '{ "input": { "query": "검색어" } }'
```

### 7-4. 입력 검증

도구에 전달할 입력이 contract에 맞는지 사전 검증합니다.

```bash
curl -X POST /api/v1/tools/{toolName}/validate \
  -d '{ "input": { "query": "검색어" } }'
```

### 7-5. 컨텍스트·토큰 효율 [CR-048]

Aimbase는 토큰 비용 절감을 위해 세 가지 최적화를 자동 적용합니다. **소비앱 코드 변경 불필요**, 동작 변화만 인지하면 됩니다.

- **Deferred Tool 스키마 주입**: 세션 초기에는 기본 도구 6종(Read/Edit/Grep/Bash/TodoWrite/ToolSearch)만 LLM tool defs에 포함. 나머지 도구는 모델이 `ToolSearch`로 검색하면 다음 턴부터 자동 활성화.
- **Tool Result Storage**: 81920B 초과 tool result는 내부 저장소에 원본 보관. 체인에는 `{type:"tool_result_ref", id, summary}` stub만 주입되어 토큰 소모 감소. 모델이 원본이 필요하면 내부 `ReadToolResult` 도구로 자율 복구. TTL 24h.
- **Adaptive Thinking**: Connection의 thinking 모드가 ADAPTIVE일 때 budget을 턴별 복잡도(직전 턴 tool_use 수, 에러 발생, 질문 길이)에 따라 자동 조정.

소비앱이 tool result 이력을 전수 저장할 필요가 있다면 `/api/v1/tool-executions` 대신 평상시 응답을 그대로 사용해도 됩니다 — ref stub도 JSON으로 그대로 내려갑니다.

### 7-6. HTTP 요청 도구 (`http_request`) [CR-054]

Aimbase 에이전트/워크플로우가 임의의 외부 REST API를 호출할 때 사용하는 범용 Tool. 기존 `connections` 테이블에 `type="HTTP"` 레코드를 등록한 뒤 `connection_id`로 참조한다. 인증 헤더는 Connection 메타에서 자동 주입되며 4xx/5xx 응답도 예외가 아닌 status 코드로 반환되어 워크플로우 CONDITION 분기가 동작한다.

**Connection 등록 (type=HTTP)**

```bash
curl -X POST /api/v1/connections \
  -H "X-API-Key: ..." \
  -d '{
    "id": "external-api",
    "name": "External API",
    "adapter": "http",
    "type": "HTTP",
    "config": {
      "baseUrl": "https://example.com",
      "auth": {
        "type": "API_KEY",
        "in": "header",
        "name": "X-Api-Key",
        "value_env": "EXTERNAL_API_KEY"
      },
      "readTimeoutMs": 30000
    }
  }'
```

인증 타입: `API_KEY`(header), `BEARER`, `BASIC`, `NONE`. 시크릿은 `value_env`(환경변수 참조)를 권장하고 평문 `value`는 개발 환경 한정.

**도구 실행 예시**

```bash
curl -X POST /api/v1/tools/http_request/execute \
  -H "X-API-Key: ..." \
  -d '{
    "input": {
      "connection_id": "external-api",
      "method": "POST",
      "path": "/v1/items",
      "query": {"tenant": "acme"},
      "headers": {"X-Trace": "abc"},
      "body": {"name": "widget"},
      "timeout_ms": 10000
    }
  }'
```

**응답 구조**

```json
{
  "status": 201,
  "headers": {"content-type": "application/json"},
  "body": {"id": "...", "...": "..."},
  "bodyRaw": false,
  "duration_ms": 42,
  "error": null
}
```

- `status`: HTTP 상태 코드. 네트워크 실패(connect/read timeout, DNS)는 `null`이며 `error.kind` = `timeout` 또는 `io_error`
- `bodyRaw`: `application/json`이 아니면 `true` (body는 원본 문자열)
- `duration_ms`: 실제 요청 소요 시간

**워크플로우 사용 패턴 (TOOL_CALL 스텝)**

```json
{
  "id": "call_external",
  "type": "TOOL_CALL",
  "config": {
    "tool": "http_request",
    "input": {
      "connection_id": "external-api",
      "method": "GET",
      "path": "/status",
      "query": {"id": "{{input.targetId}}"}
    }
  }
}
```

이후 CONDITION 스텝에서 `{{call_external.structured_data.status}} equals 200`으로 분기 가능.

**주의**
- 기본 타임아웃 30초, 최대 120초. 더 긴 호출은 별도 Tool(SDK) 사용 권장
- 인증 헤더/`Authorization`/`X-Api-Key`/`Cookie`는 감사 로그에 `***`로 마스킹되어 남음
- 워크플로우 레벨 재시도(`WorkflowStep.retry`)만 사용 — Tool 내부 재시도 없음

---

## 8. 세션 메타 [CR-029]

세션에 커스텀 메타데이터를 부착하여 관리합니다.

```bash
# 세션 메타 조회
GET /api/v1/conversations/{sessionId}/meta

# 세션 메타 수정
curl -X PUT /api/v1/conversations/{sessionId}/meta \
  -d '{ "tags": ["important"], "summary": "요약 텍스트" }'
```

---

## 9. 도구 실행 이력 [CR-029]

도구 실행 기록을 세션 또는 워크플로우 실행 단위로 조회합니다.

```bash
# 세션 기준 조회
GET /api/v1/tool-executions?session_id={sessionId}

# 워크플로우 실행 기준 조회
GET /api/v1/tool-executions?workflow_run_id={runId}

# 상세 조회
GET /api/v1/tool-executions/{id}
```

---

## 10. Context Recipe [CR-029]

컨텍스트 조립 레시피를 정의하고 미리보기합니다.

```bash
# 레시피 생성
curl -X POST /api/v1/context-recipes \
  -d '{
    "name": "기본 레시피",
    "layers": [
      { "type": "system_prompt", "priority": 1 },
      { "type": "rag", "sourceId": "src_xxx", "priority": 2 },
      { "type": "conversation_history", "priority": 3 }
    ],
    "budget": { "maxTokens": 8000 },
    "freshness": "real_time"
  }'

# 조립 미리보기
curl -X POST /api/v1/context-recipes/{id}/preview \
  -d '{ "query": "테스트 질문" }'
```

---

## 11. Domain Config [CR-029]

도메인(소비앱) 단위 기본 설정을 관리합니다.

```bash
# 도메인 설정 생성
curl -X POST /api/v1/domain-configs \
  -d '{
    "domainApp": "axopm",
    "defaultRecipeId": "recipe_xxx",
    "toolAllowlist": ["rag_search", "web_search"],
    "runtime": {
      "maxTokens": 4096,
      "temperature": 0.7
    }
  }'

# 도메인별 조회
GET /api/v1/domain-configs/{domainApp}
```

---

## 12. 서브에이전트 [CR-030]

서브에이전트는 메인 세션에서 독립적인 LLM 에이전트를 생성하여 작업을 위임하는 기능입니다.

### 12-1. 단일 에이전트 실행

```bash
POST /api/v1/agents/run
Content-Type: application/json

{
  "description": "코드 리뷰 에이전트",
  "prompt": "다음 코드를 리뷰해주세요: ...",
  "model": "claude-sonnet",
  "connectionId": "conn-1",
  "isolation": "NONE",
  "runInBackground": false,
  "timeoutMs": 120000,
  "parentSessionId": "sess-abc-123"
}
```

**주요 파라미터:**

| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `description` | O | 에이전트 목적 (3-5 단어) |
| `prompt` | O | 에이전트에게 전달할 작업 프롬프트 |
| `model` | X | LLM 모델 (null이면 기본값) |
| `connectionId` | X | LLM 커넥션 ID |
| `isolation` | X | `NONE` (기본) 또는 `WORKTREE` (git worktree 격리) |
| `runInBackground` | X | `true`면 비동기 실행, 즉시 RUNNING 상태 반환 |
| `timeoutMs` | X | 타임아웃 (기본 120,000ms) |
| `parentSessionId` | X | 부모 세션 ID (결과 병합용) |

**응답:**

```json
{
  "success": true,
  "data": {
    "subagentRunId": "uuid",
    "sessionId": "subagent-uuid",
    "status": "COMPLETED",
    "output": "리뷰 결과...",
    "exitCode": 0,
    "usage": { "inputTokens": 500, "outputTokens": 1200 },
    "durationMs": 3500
  }
}
```

### 12-2. 멀티에이전트 조율 실행

```bash
POST /api/v1/agents/orchestrate
Content-Type: application/json

{
  "agents": [
    { "description": "분석 에이전트", "prompt": "코드 분석..." },
    { "description": "테스트 에이전트", "prompt": "테스트 작성..." }
  ],
  "execution": "parallel",
  "parentSessionId": "sess-abc-123"
}
```

- `execution`: `"parallel"` (병렬, 기본) 또는 `"sequential"` (순차)
- 응답에 `mergedOutput`, `successCount`, `failCount`, 개별 `agents` 결과 포함

### 12-3. 상태 조회 및 관리

```bash
# 실행 상태 조회
GET /api/v1/agents/{runId}

# 부모 세션의 서브에이전트 목록
GET /api/v1/agents/session/{parentSessionId}

# 강제 취소
POST /api/v1/agents/{runId}/cancel

# 활성 에이전트 현황
GET /api/v1/agents/active
```

### 12-4. Worktree 격리

`isolation: "WORKTREE"` 설정 시 git worktree 기반 격리 환경에서 실행됩니다.
- 에이전트 완료 후 변경사항이 없으면 worktree 자동 정리
- 변경사항이 있으면 `worktreePath`와 `branchName`이 결과에 포함
- 주기적 스캔(30초)으로 타임아웃된 에이전트 자동 감지

### 12-5. 워크플로우 AGENT_CALL 스텝

워크플로우 DAG에서 `AGENT_CALL` 스텝 타입으로 서브에이전트를 실행할 수 있습니다.

```json
{
  "id": "s3",
  "name": "코드 리뷰",
  "type": "AGENT_CALL",
  "config": {
    "description": "리뷰 에이전트",
    "prompt": "{{s2.output}} 를 리뷰해줘",
    "isolation": "WORKTREE",
    "timeout_ms": 60000
  },
  "dependsOn": ["s2"]
}
```

멀티에이전트 config:

```json
{
  "config": {
    "agents": [
      { "description": "agent-1", "prompt": "..." },
      { "description": "agent-2", "prompt": "..." }
    ],
    "execution": "parallel"
  }
}
```

---

## 13. API 엔드포인트 요약

### 인증

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/auth/login` | 로그인 → access_token 발급 |
| POST | `/auth/refresh` | 토큰 갱신 |

### 시스템 API Key 관리 (SUPER_ADMIN)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/platform/api-keys` | 키 발급 (domainApp 필수, tenantId 선택) |
| GET | `/platform/api-keys` | 키 목록 조회 (?tenantId= 필터) |
| DELETE | `/platform/api-keys/{id}` | 키 폐기 (비활성화) |
| POST | `/platform/api-keys/{id}/regenerate` | 키 재발급 (기존 키 폐기 → 동일 설정 신규 발급) |

### 지식 소스

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/knowledge-sources` | 목록 조회 |
| POST | `/knowledge-sources` | 소스 생성 |
| GET | `/knowledge-sources/{id}` | 상세 조회 |
| PUT | `/knowledge-sources/{id}` | 소스 수정 |
| DELETE | `/knowledge-sources/{id}` | 소스 삭제 |
| POST | `/knowledge-sources/{id}/upload` | 파일 업로드 |
| POST | `/knowledge-sources/{id}/sync` | 전체 인제스션 |
| POST | `/knowledge-sources/{id}/ingest-text` | 개별 텍스트 인제스션 (upsert) |
| DELETE | `/knowledge-sources/{id}/documents/{documentId}` | 개별 문서 임베딩 삭제 |
| POST | `/knowledge-sources/search` | 벡터 검색 |
| GET | `/knowledge-sources/{id}/ingestion-logs` | 인제스션 로그 |

### 워크플로우

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/workflows` | 목록 조회 |
| POST | `/workflows` | 생성 |
| GET | `/workflows/{id}` | 상세 조회 (inputSchema 포함) |
| PUT | `/workflows/{id}` | 수정 |
| DELETE | `/workflows/{id}` | 삭제 |
| POST | `/workflows/{id}/run` | 실행 |
| GET | `/workflows/{id}/runs` | 실행 이력 |
| GET | `/workflows/{id}/runs/{runId}` | 실행 결과 조회 |

### LLM 연결

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/connections` | 연결 목록 |
| POST | `/connections` | 연결 생성 |
| POST | `/connections/{id}/test` | 연결 테스트 |

### 오케스트레이션

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/chat` | LLM 대화 (정책 적용, 도구 호출 포함) |
| POST | `/chat/stream` | 스트리밍 대화 |
| POST | `/chat/{sessionId}/abort` | 진행 중 스트림 즉시 중지 [CR-046] |
| DELETE | `/conversations/{sessionId}` | 대화방 Soft Delete (본인만, deleted_at 마킹) [CR-046] |

### 도구 관리 (확장) [v4.0, CR-029]

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/tools` | 도구 목록 (contract 포함) |
| GET | `/tools/{toolName}/contract` | 도구 계약 상세 |
| POST | `/tools/{toolName}/execute` | 도구 직접 실행 |
| POST | `/tools/{toolName}/validate` | 입력 검증 |

### 세션 메타 [v4.0, CR-029]

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/conversations/{sessionId}/meta` | 세션 메타 조회 |
| PUT | `/conversations/{sessionId}/meta` | 세션 메타 수정 |

### 도구 실행 이력 [v4.0, CR-029]

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/tool-executions` | 목록 (session_id, workflow_run_id 필터) |
| GET | `/tool-executions/{id}` | 상세 |

### Context Recipe [v4.0, CR-029]

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/context-recipes` | 목록 |
| GET | `/context-recipes/{id}` | 상세 |
| POST | `/context-recipes` | 생성 |
| PUT | `/context-recipes/{id}` | 수정 |
| DELETE | `/context-recipes/{id}` | 삭제 |
| POST | `/context-recipes/{id}/preview` | 조립 미리보기 |

### Domain Config [v4.0, CR-029]

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/domain-configs` | 목록 |
| GET | `/domain-configs/{domainApp}` | 상세 |
| POST | `/domain-configs` | 생성 |
| PUT | `/domain-configs/{domainApp}` | 수정 |
| DELETE | `/domain-configs/{domainApp}` | 삭제 |

### 서브에이전트 [v4.1, CR-030]

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/agents/run` | 단일 에이전트 실행 (fg/bg) |
| POST | `/agents/orchestrate` | 멀티에이전트 병렬/순차 실행 |
| GET | `/agents/{runId}` | 실행 상태 조회 |
| GET | `/agents/session/{parentSessionId}` | 부모 세션별 목록 |
| POST | `/agents/{runId}/cancel` | 강제 취소 |
| GET | `/agents/active` | 활성 에이전트 현황 |

### Agent Registry [v2.0, CR-041]

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/agents/register` | 원격 에이전트 등록 |
| DELETE | `/agents/{id}` | 에이전트 해제 |
| GET | `/agents?status=ACTIVE` | 에이전트 목록 조회 |
| POST | `/agents/{id}/heartbeat` | 하트비트 |

---

## 14. Agent Registry API [CR-041]

원격 에이전트(소비앱)가 MCP 서버로 도구를 노출하고, Aimbase에 자가 등록/해제하는 API.

### 14-1. 에이전트 등록

```
POST /api/v1/agents/register
Content-Type: application/json
```

**Request Body:**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| agentName | string | Y | 에이전트 이름 |
| publicAddress | string | Y | 에이전트 공인 IP 주소 |
| mcpPort | integer | Y | MCP 서버 포트 |
| toolNames | string[] | N | 도구 이름 목록 (MCP 탐색 실패 시 폴백) |
| metadata | object | N | 추가 메타데이터 |

**Response (201):**
```json
{
  "data": {
    "id": "uuid",
    "agentName": "flowguard-agent",
    "publicAddress": "1.2.3.4",
    "mcpPort": 8190,
    "status": "ACTIVE",
    "toolsCache": [...],
    "registeredAt": "2026-04-10T...",
    "lastHeartbeatAt": "2026-04-10T..."
  }
}
```

### 14-2. 에이전트 해제

```
DELETE /api/v1/agents/{id}
```

**Response:** 204 No Content

### 14-3. 에이전트 목록 조회

```
GET /api/v1/agents?status=ACTIVE
```

**Query Parameters:**
| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| status | ACTIVE | 상태 필터 (ACTIVE, STALE, DEREGISTERED) |

**Response (200):**
```json
{
  "data": [
    {
      "id": "uuid",
      "agentName": "flowguard-agent",
      "publicAddress": "1.2.3.4",
      "mcpPort": 8190,
      "status": "ACTIVE",
      "toolsCache": [{"name": "builtin_file_read", "description": "..."}],
      "registeredAt": "2026-04-10T...",
      "lastHeartbeatAt": "2026-04-10T..."
    }
  ]
}
```

### 14-4. 하트비트

```
POST /api/v1/agents/{id}/heartbeat
```

**Response (200):**
```json
{"status": "ok"}
```

> BIZ-078: 하트비트 간격 60초 권장. BIZ-079: 5분 무응답 시 STALE 처리.

---

## 15. 세션 복원 [CR-049]

장기 대화 세션이 자동 압축으로 잘려나간 뒤에도 무손실 재개가 가능하다. 압축 시점에 `COMPACT_BOUNDARY` 메시지 마커가 자동 삽입되며, 소비앱은 Resume API로 압축 경계 이후 메시지 + 보존 컨텍스트를 한 번에 가져온다.

### 15-1. 세션 재개

```
POST /api/v1/sessions/{sessionId}/resume
```

**Response (200)**:
```json
{
  "session_id": "sess_xxx",
  "resumed_at": "2026-04-16T10:00:00Z",
  "boundary": {
    "summary": "사용자 요청으로 인증 모듈 리팩터링 진행 중...",
    "compacted_count": 42,
    "tokens_saved": 18420,
    "boundary_at": "2026-04-16T09:30:00Z"
  },
  "messages": [
    { "id": "msg_1", "role": "assistant", "content": "...", "created_at": "..." }
  ],
  "preserved_context": {
    "memory": {},
    "active_tools": ["Read", "Edit", "Grep"]
  }
}
```

**제약**:
- 24h TTL 이내 active 세션만 (BIZ-002). 만료 세션은 향후 archived 조회 API로 분리.
- 본인(user_id 일치)만 호출 가능 → 그 외 403.
- 진행 중(streaming) 세션은 409 — 중지 후 재개 권장.
- COMPACT_BOUNDARY가 없는 세션이면 전체 메시지가 반환됨.

---

## 16. 테넌트/프로젝트 시스템 지침 [CR-049]

테넌트별 톤·금지사항·산업 특화 지침을 코드 배포 없이 DB에서 편집할 수 있다. 우선순위는 PROJECT > TENANT > GLOBAL이며 **cascade append**로 병합된다(replace 아닌 누적, BIZ-098).

### 16-1. 프롬프트 템플릿 조회

```
GET /api/v1/prompt-templates?scope=TENANT|PROJECT&projectId=...&category=core&is_active=true
```

### 16-2. 프롬프트 템플릿 저장 (upsert)

```
PUT /api/v1/prompt-templates
```

**Body**:
```json
{
  "key": "core.system.prefix",
  "scope": "TENANT",
  "project_id": null,
  "category": "core",
  "name": "Tenant System Prefix",
  "template": "당신은 본 테넌트의 산업 특화 어시스턴트입니다...",
  "language": "ko",
  "is_active": true
}
```

**Response 400**: scope=PROJECT인데 projectId 누락
**Response 403**: 권한 부족 (슈퍼어드민=GLOBAL, 테넌트 관리자=TENANT/PROJECT)

### 16-3. 프로젝트 지침 (편의 엔드포인트)

```
GET  /api/v1/projects/{projectId}/instructions
PUT  /api/v1/projects/{projectId}/instructions
```

scope=PROJECT 자동 설정.

### 16-4. 미리보기 (cascade 병합 결과)

```
GET /api/v1/prompt-templates/preview?tenantId=...&projectId=...
```

**Response**: GLOBAL/TENANT/PROJECT 각 본문 + merged 결과 + total_length_bytes. 8KB 초과 시 `warning` 필드 포함.

### 16-5. 권한·감사

- secret 패턴(API_KEY=, sk-, ghp_ 등) 감지 시 audit_log 기록 + Response 헤더 `X-Secret-Warning` 반환. 저장은 진행됨.
- 버저닝은 기존 `prompt_templates.version` 컬럼 재사용 (편집 이력 보관).

---

## 17. 임베드 위젯 (Chat Widget) [CR-058]

> 📖 **소비앱 개발자는 먼저 [통합 가이드](embed-chat-widget.md) (또는 공개 URL `https://aimbase.../widget/v1/`)를 읽으세요.** 이 절은 API 엔드포인트 레퍼런스입니다.
>
> **공개 서빙 리소스** (인증 없이 접근 가능):
> - `/widget/v1/aimbase-chat.umd.global.js` — UMD 번들 (&lt;script&gt; 로드용, ~17KB)
> - `/widget/v1/aimbase-chat.esm.js` — ESM (번들러용, ~25KB)
> - `/widget/v1/aimbase-chat.d.ts` — TypeScript 타입
> - `/widget/v1/index.html` (또는 `/widget/v1/`) — HTML 렌더링된 통합 가이드
> - `/widget/v1/sample-bff/server.js` — Node 샘플 BFF (외부 의존 0)

소비앱(OMS/WMS/OpenMall/Rescue 등, 모두 Aimbase와 다른 도메인) 브라우저에 채팅 + 워크플로우 실행 가시화 + RAG 출처 카드를 얹기 위한 3-Tier 임베드 경로. **브라우저에 테넌트 API Key 노출 금지 원칙** 에 따라 소비앱 BFF 가 API Key 로 서버간 호출하여 단기 JWT(`type=widget`) 를 대리 발급받고 브라우저로 전달한다.

### 17-1. 전제 조건 (관리자 세팅)

운영자가 `global_config` 의 다음 키를 채워야 위젯 발급/CORS 가 동작한다. 빈 값 상태에서는 CORS 미허용으로 위젯이 붙지 않는다.

| 설정 키 | 형식 | 예시 |
|---------|------|------|
| `widget.allowed-origins` | CSV | `https://oms.company.com,https://rescue.company.com` |
| `widget.allowed-scopes` | CSV | `chat:stream,workflow:subscribe,rag:read` (기본) |
| `widget.token-ttl-seconds` | int | `1800` (기본 30분) |
| `widget.token-max-ttl-seconds` | int | `3600` (하드캡 1시간) |

관리 방법은 운용 가이드 § 위젯 운영 참조.

### 17-2. 단기 위젯 토큰 발급 — `POST /api/v1/sessions/issue-widget-token`

**인증**: `X-API-Key` 헤더 **필수** (JWT 로는 불가 — 브라우저가 직접 발급받는 경로 차단).

**요청**:
```bash
curl -X POST $AIMBASE/api/v1/sessions/issue-widget-token \
  -H "X-API-Key: $MY_TENANT_API_KEY" \
  -H "X-Tenant-Id: $MY_TENANT_ID" \
  -H "Content-Type: application/json" \
  -d '{
        "project_id": "rescue",
        "user_ref": "user_123",
        "session_hint": "order_detail_page",
        "ttl_seconds": 1800,
        "origin": "https://oms.company.com",
        "scopes": ["chat:stream", "workflow:subscribe", "rag:read"]
      }'
```

**응답**:
```json
{
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expires_at": "2026-04-24T11:30:00Z",
    "refresh_after": 1500,
    "scopes": ["chat:stream", "workflow:subscribe", "rag:read"]
  }
}
```

**검증 로직**:
- `ttl_seconds > widget.token-max-ttl-seconds` 이면 max 로 cap + 로그
- `scopes ∩ widget.allowed-scopes` 만 부여 (교집합 비어 있으면 400)
- `origin` 이 `widget.allowed-origins` 화이트리스트에 없으면 400
- `TenantContext` 가 설정되지 않은 호출은 401

**JWT claims**: `sub=user_ref`, `tenant_id`, `project_id`, `user_ref`, `scopes[]`, `origin`, `type: "widget"`, `exp`.

### 17-3. 위젯 토큰으로 Aimbase API 호출

브라우저는 `Authorization: Bearer <token>` 헤더, SSE 는 `?access_token=<token>` 쿼리로 전달 (EventSource 가 커스텀 헤더를 못 다루므로). **access 토큰은 쿼리 전달 금지**(로그 유출 리스크) — 위젯 토큰만 쿼리 파라미터를 수용한다.

### 17-4. 채팅 + RAG Citations — `POST /api/v1/chat/completions`

기존 엔드포인트 그대로. CR-058 에서 **응답에 `citations` / `rag_used` 필드** 가 추가되었다 (`rag_source_id` 지정 + 검색 결과 존재 시에만 포함).

**Citation 객체 포맷** (`List<Map>`):
```json
{
  "index": 1,
  "chunk_id": "a7c9f3b0-...",
  "source_id": "kb_rescue",
  "document_name": "반품정책.pdf",
  "score": 0.87,
  "content_preview": "반품 접수 후 7일 이내 처리합니다.",
  "page_number": 4
}
```

**비스트림**: `ChatResponse` 바디에 `citations: [...]`, `rag_used: true` 포함.

**스트림 (`stream=true`)**: 기존 5개 SSE 이벤트(`delta` / `thinking` / `tool_use_start` / `tool_result` / `done`) 중 **`done` payload 가 확장**되었다:
```
event: done
data: {"done": true, "citations": [...], "rag_used": true}
```
citations 가 비어 있거나 RAG 미사용 시 해당 필드는 생략(JSON_INCLUDE_NON_NULL).

### 17-5. 청크 원문 조회 — `GET /api/v1/knowledge-sources/{sourceId}/chunks/{chunkId}`

citation 클릭 시 위젯이 원문 미리보기 패널을 렌더하기 위해 호출.

**Scope**: `rag:read` (위젯 토큰) 또는 기본 인증.

```bash
curl "$AIMBASE/api/v1/knowledge-sources/kb_rescue/chunks/a7c9f3b0-... " \
  -H "Authorization: Bearer $WIDGET_TOKEN"
```

**응답**:
```json
{
  "data": {
    "chunk_id": "a7c9f3b0-...",
    "source_id": "kb_rescue",
    "document_name": "반품정책.pdf",
    "document_id": "doc-123",
    "chunk_index": 5,
    "content": "전문 원문…",
    "metadata": {"page_number": 4, "...": "..."},
    "page_number": 4,
    "parent_id": "uuid-...",       // Parent-Child RAG 사용 시
    "parent_content": "부모 청크 …"
  }
}
```

**에러**:
- `400 Bad Request` — chunkId 가 UUID 포맷 아님
- `404 Not Found` — 소스 없음 또는 해당 소스에 속한 청크 없음 (타 소스 청크 ID 를 섞어 넣어도 404)

### 17-6. 워크플로우 실행 SSE — `GET /api/v1/workflows/runs/{runId}/subscribe`

위젯이 워크플로우 실행 진행 상황 / 승인 대기 / 종료를 실시간 표시하기 위해 구독한다.

**Scope**: `workflow:subscribe` (위젯 토큰) 또는 기본 인증.

```bash
curl -N "$AIMBASE/api/v1/workflows/runs/$RUN_ID/subscribe?access_token=$WIDGET_TOKEN"
```

- 타임아웃 30분
- 연결 직후 `workflow.snapshot` 이벤트 1 회 (현재 DB 상태)
- 이미 종료된 런이면 `workflow.snapshot` + `workflow.done` 즉시 발행 후 close
- 실행 중에는 `WorkflowEventPublisher` 가 발행하는 이벤트를 매칭해 브라우저로 forward

**이벤트 4 종 payload 스펙**:

```
event: workflow.snapshot
data: {"run_id":"...","workflow_id":"wf-1","session_id":"sess-1","status":"running",
       "current_step":"step_a","step_results":{...},"started_at":"..."}

event: workflow.step
data: {"run_id":"...","step_id":"fetch_order","status":"running",
       "started_at":"2026-04-24T10:00:00Z"}

// 완료 시 (+ sub_workflow_id / output_preview)
event: workflow.step
data: {"run_id":"...","step_id":"fetch_order","status":"completed",
       "started_at":"...","completed_at":"...","duration_ms":1420,
       "sub_workflow_id":"platform:file-analysis",
       "output_preview":{"records":12,"status":"ok"}}

event: workflow.approval
data: {"run_id":"...","step_id":"confirm_refund","policy_id":"confirm_refund",
       "reason":"10만원 초과","approvers":["manager@company.com"],"timeout_at":"..."}

event: workflow.done
data: {"run_id":"...","status":"completed","duration_ms":3420}
```

**부모-자식 라우팅**: 서브워크플로우 자식 이벤트(`parent_run_id != null`)는 자식 구독자와 부모 구독자 모두에게 fan-out. 부모 위젯이 서브워크플로우를 트리로 표시할 수 있게 한다.

### 17-7. 위젯 통합 체크리스트 (소비앱 측)

- [ ] 소비앱 BFF 에 `/my-bff/aimbase-token` 프록시 엔드포인트 구현 (API Key 는 서버 env 에만 보관)
- [ ] 브라우저는 `Authorization: Bearer` 헤더 + SSE 는 `?access_token=` 쿼리로 전송
- [ ] 토큰 만료 5 분 전(`refresh_after` 필드) 에 `/my-bff/aimbase-token` 재호출 → 신규 토큰 교체 (진행 중 SSE 는 유지)
- [ ] 운영자가 `widget.allowed-origins` 에 소비앱 도메인 추가했는지 확인
- [ ] `allowApproval: false` 기본 — `workflow.approval` 이벤트는 소비앱 자체 결재 플로우로 전달

### 17-8. 클라이언트 측 단순 예시 (React)

```tsx
// BFF 토큰 발급 (자기 서버로 프록시)
async function fetchWidgetToken() {
  const res = await fetch('/my-bff/aimbase-token', { method: 'POST' });
  return res.json();  // { token, expires_at, refresh_after }
}

// 채팅 스트림
const { token } = await fetchWidgetToken();
const resp = await fetch(`${baseUrl}/api/v1/chat/completions`, {
  method: 'POST',
  headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ stream: true, session_id, messages, rag_source_id: 'kb_rescue' }),
});
// resp.body 를 ReadableStream → SSE 파싱 (delta / thinking / tool_use_start / tool_result / done)

// 워크플로우 구독
const es = new EventSource(`${baseUrl}/api/v1/workflows/runs/${runId}/subscribe?access_token=${token}`);
es.addEventListener('workflow.step', (ev) => { const data = JSON.parse(ev.data); ... });
es.addEventListener('workflow.approval', (ev) => { /* 소비앱 결재 플로우 트리거 */ });
es.addEventListener('workflow.done', () => es.close());

// 원문 조회
await fetch(`${baseUrl}/api/v1/knowledge-sources/${sourceId}/chunks/${chunkId}`,
  { headers: { 'Authorization': `Bearer ${token}` } });
```

---

## 18. 위젯 파일 첨부 (이미지/PDF Vision) [CR-061]

CR-058 위젯이 채팅 메시지에 이미지·PDF 를 첨부할 수 있도록 하는 API. RAG 인제스션은 하지 않고 **해당 메시지 1회에만 LLM 컨텍스트로 투입**된다 (세션 TTL 과 함께 자동 GC). 직접 소비하는 주체는 위젯 SDK 이며, 소비앱 BFF 는 `chat:upload` scope 을 위젯 토큰에 허용만 하면 된다.

### 18-1. 전제 — 위젯 토큰 Scope
BFF 가 `/sessions/issue-widget-token` 호출 시 scope 배열에 `chat:upload` 를 포함해야 한다. `widget.allowed-scopes` 기본값은 `[chat:stream, chat:upload, workflow:subscribe, rag:read]` 로 확장되어 있다.

### 18-2. `POST /api/v1/chat/attachments`

**Scope**: `chat:upload`
**Content-Type**: `multipart/form-data`

| 파라미터 | 필수 | 설명 |
|---------|:-:|------|
| `session_id` | ✅ | 첨부가 속할 세션 — 타 세션에서 참조 시 403 |
| `file`       | ✅ | 파일 (PNG/JPEG/GIF/WEBP/PDF) — magic number 재검증 |

**응답 201**
```json
{
  "success": true,
  "data": {
    "attachment_id": "a1b2c3d4-...",
    "filename": "receipt.pdf",
    "media_type": "application/pdf",
    "size_bytes": 1048576,
    "pages": 3,
    "expires_at": "2026-04-25T12:00:00Z"
  }
}
```

**에러 코드**
| HTTP | 코드 | 원인 |
|:---:|------|------|
| 400 | FG-ATT-4001 | 지원하지 않는 MIME (위젯은 PNG/JPEG/GIF/WEBP/PDF만) |
| 400 | FG-ATT-4002 | 크기 초과 (BIZ-099: 이미지 10MB / PDF 32MB) |
| 403 | FG-ATT-4031 | 세션 소유권 불일치 |
| 404 | FG-ATT-4041 | 첨부 없음 또는 만료 |
| 409 | FG-ATT-4091 | 세션당 활성 첨부 초과 (BIZ-100: 최대 10개) |
| 422 | FG-ATT-4221 | magic number 와 Content-Type 헤더 불일치 |

### 18-3. `DELETE /api/v1/chat/attachments/{attachment_id}?session_id=...`

**Scope**: `chat:upload`
**응답 204** — 세션 소유권 OK, 스토리지+DB 삭제 완료
**에러 403** — 다른 세션 소유

### 18-4. 채팅에 첨부 포함 — `POST /api/v1/chat/completions`

`messages[].content[]` 에 신규 블록 2종이 추가된다:

```json
{
  "model": "auto",
  "session_id": "...",
  "stream": true,
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "image",    "attachment_id": "a1b2..." },
        { "type": "document", "attachment_id": "b3c4..." },
        { "type": "text",     "text": "이 영수증 금액을 정리하고 PDF 내용과 비교해줘" }
      ]
    }
  ]
}
```

**처리 규칙** — 서버가 요청 모델의 어댑터 capability 를 확인한 뒤:
- `image` 블록: 이미지 지원 어댑터(Anthropic/OpenAI/Bedrock/Ollama/Vertex)는 모두 네이티브 `ImageBlockParam` 으로 전달
- `document` 블록: Anthropic/Bedrock Claude 는 네이티브 PDF `DocumentBlockParam` 전달, 그 외 프로바이더는 Python 사이드카 `parse_document` 로 텍스트 추출 후 `"[첨부 문서: {filename}]\n{text}\n\n"` 를 메시지 앞에 prepend 하는 폴백 경로 사용

기존 `image_url` (OpenAI 스타일 data: URI) 과 문자열 content 도 호환 유지.

### 18-5. 위젯 SDK 사용 예

위젯을 한 줄로 얹은 경우(`<aimbase-chat>`) 파일 첨부는 UI 에서 자동으로 처리된다 — 📎 아이콘 클릭 · 드래그앤드롭 · 클립보드 붙여넣기(Ctrl+V) 3가지 입력 방법. SDK 내부적으로 위 API 3종(`POST /attachments`, `DELETE /attachments/{id}`, `POST /completions`) 를 자동 호출한다.

소비앱 개발자는 BFF 에서 토큰 scope 에 `chat:upload` 만 포함시키면 된다.

### 18-6. 제약 요약 (BIZ 규칙 → API 반영)
- **이미지 10MB / PDF 32MB** (BIZ-099) — `widget.attachment.max-image-bytes`, `widget.attachment.max-pdf-bytes` 로 조정 가능
- **세션당 활성 첨부 10개** (BIZ-100) — `widget.attachment.max-per-session`
- **TTL 24h** (BIZ-101) — `widget.attachment.ttl-seconds`. 5분마다 `AttachmentGcScheduler` 가 만료 건 정리 (스토리지 → DB 순)

---

## 19. 위젯 음성 입력 STT (Whisper) [CR-060]

위젯이 마이크로 녹음한 오디오를 OpenAI Whisper API 로 텍스트 변환해 입력창에 자동 삽입한다. **녹음 후 일괄 전송** 방식 (Whisper 는 실시간 스트리밍 미지원). Platform 경로(`/api/v1/speech/stt`) 는 그대로 유지되며 본 CR 에서는 위젯 전용 엔드포인트가 신설됐다.

### 19-1. 전제 — 위젯 토큰 Scope
BFF 가 `/sessions/issue-widget-token` 호출 시 scope 배열에 `chat:stt` 를 포함해야 한다. `widget.allowed-scopes` 기본값은 `[chat:stream, chat:upload, chat:stt, workflow:subscribe, rag:read]` 로 확장되어 있다.

### 19-2. `POST /api/v1/chat/stt`

**Scope**: `chat:stt`
**Content-Type**: `multipart/form-data`

| 파라미터 | 필수 | 설명 |
|---------|:-:|------|
| `file` | ✅ | 오디오 파일. MIME 허용: `audio/webm`, `audio/mp4`, `audio/mpeg`, `audio/wav`, `audio/ogg` (magic number 재검증) |
| `language` | ❌ | `auto` 또는 ISO-639-1 (ko/en/ja/…). 기본 `widget.stt.default-language` (초기 `auto`) |
| `session_id` | ❌ | 감사 로그 기록 단위. 없으면 `anonymous` 로 간주 |

**응답 200**
```json
{
  "success": true,
  "data": {
    "text": "안녕하세요 오늘 날씨가 좋네요",
    "language": "ko",
    "duration_sec": 3.4
  }
}
```

**에러 코드**
| HTTP | 코드 | 원인 |
|:---:|------|------|
| 400 | STT_FILE_MISSING | `file` 파라미터 누락 또는 빈 파일 |
| 400 | STT_FILE_TOO_LARGE | BIZ-103: `widget.stt.max-size-bytes` 초과 (기본 25MB) |
| 400 | STT_FILE_TOO_LONG | BIZ-102: Whisper duration 응답이 `widget.stt.max-duration-seconds` 초과 (기본 60s) |
| 400 | STT_INVALID_MIME | magic number 검출 실패 또는 허용 MIME 목록 외 |
| 400 | STT_INVALID_MULTIPART | multipart body 읽기 실패 |
| 429 | STT_RATE_LIMITED | BIZ-104: 세션당 분당 호출 한도 초과 (기본 10/min) |
| 502 | STT_UPSTREAM_ERROR | OpenAI Whisper 5xx 응답 |
| 503 | STT_PROVIDER_UNAVAILABLE | 테넌트에 연결된 OpenAI Connection 없음 |
| 504 | STT_TIMEOUT | Whisper 호출 타임아웃 (기본 120s) |

**예시 (curl)**
```bash
curl -X POST https://aimbase.company.com/api/v1/chat/stt \
  -H "Authorization: Bearer $WIDGET_TOKEN" \
  -F "file=@recording.webm" \
  -F "language=auto" \
  -F "session_id=sess-abc"
```

### 19-3. 위젯 SDK 사용 예

위젯을 한 줄로 얹은 경우(`<aimbase-chat>`) 음성 입력은 UI 에서 자동으로 처리된다 — 🎤 아이콘 클릭으로 녹음 시작/정지, 60초 자동 종료, 결과가 입력창에 삽입된다. SDK 는 `SttClient` 를 public 으로도 export 한다:

```ts
import { createWidget, SttClient, SttError } from "@aimbase/chat-widget-embed";

// 수동 사용 (고급) — 별도 녹음 UI 를 구현하고 Aimbase 서버에만 붙일 때
const stt = new SttClient(tokens); // tokens: TokenStore
try {
  const result = await stt.transcribe({
    baseUrl: "https://aimbase.company.com",
    sessionId: "sess-abc",
    blob: recordedBlob,      // MediaRecorder 결과
    language: "auto",
  });
  console.log(result.text, result.language, result.duration_sec);
} catch (e) {
  if (e instanceof SttError) {
    if (e.code === "STT_RATE_LIMITED") { /* ... */ }
  }
}
```

### 19-4. 제약 요약 (BIZ 규칙 → API 반영)
- **녹음 시간 60초 이내** (BIZ-102) — `widget.stt.max-duration-seconds`
- **파일 크기 25MB 이내** (BIZ-103) — `widget.stt.max-size-bytes` (Whisper API 상한)
- **세션당 분당 10회** (BIZ-104) — `widget.stt.rate-limit-per-minute`. Redis 장애 시 fail-open (가용성 우선)
- 변환 텍스트 본문은 **감사 로그에 저장되지 않음** — PII 보호. 메타데이터(duration/size/language/mime) 만 기록

### 19-5. 기존 Platform 경로와의 관계

`POST /api/v1/speech/stt` (Platform JWT 전용) 는 동작이 동일하게 유지된다. 단 응답 포맷이 기존 `{text}` → `{text, language, duration}` 로 필드가 추가됐다 (backward compatible). 실제 Whisper 호출 로직은 `SpeechService` 로 추출되어 양쪽 엔드포인트가 공유한다.

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| v3.0.0 | 2026-04-28 | **CR-072 — 서버 도구 MCP endpoint 노출** (`/mcp/sse`, `/mcp/message`). 인증: `X-API-Key` 필수 (tenant 자동 결정), `X-Aimbase-Agent-Id` 선택. MCP SSE 트랜스포트로 `WebSearch / HttpRequest / SendMessage / Notification / Brief / ImageAnalysis / Translation / NotebookEdit / LSP / Skill / ToolSearch / ListMcpResources / ReadMcpResource / RemoteTrigger / ScheduleCron / CronList / CronDelete / Task* 6종 / TodoWrite / SuggestBackgroundPR` (총 26개) 노출. 거버넌스: PRE/POST_TOOL_USE Hook ✓ + Rate Limit (테넌트 단위 분당 60, 키 스코프 `mcp:{tenantId}`) ✓ + 화이트리스트 이중 방어 ✓. `team_create / team_delete / enter_plan_mode / exit_plan_mode / verify_plan_execution / temp_cleanup` 은 `McpExposureLevel.NONE` 으로 미노출. SecurityConfig `/mcp/**` permitAll → authenticated. 끔 옵션: `mcp.server-exposure.enabled=false`. **CR-073 — `aimbase-agent --runner-mode` 플래그 폐지** (BREAKING) — `--mcp-stdio` 외 모든 진입은 SERVLET 단일 컨텍스트. 후방 호환: 플래그 박혀있어도 무시 |
| v2.9.0 | 2026-04-27 | **CR-071 ClaudeCliAdapter — 3경로 통일** (BREAKING). 기존 `ClaudeCliLlmAdapter`(서버 in-process CLI, CR-050) + `ClaudeCodeTool`(워크플로우 도구, CR-044) 모두 삭제. Anthropic Claude Code CLI 호출은 별도 프로세스 `ClaudeCliRunner`(aimbase-agent `--runner-mode`)로 분리되어 HTTP API 로 통신. **호출자 변경 사항**: `adapter=anthropic-cli` connection 사용 시 모든 요청 헤더에 `X-Aimbase-Agent-Id: <agent-id>` 필수 (누락 시 400). agent 는 `runner_capability=true`로 등록되어야 함 (`POST /api/v1/agents/register` 시 `metadata.runnerEndpoint`/`runnerApiKeyHash` 포함). `connection.config` 필드: `model` / `tool_mode`(AIMBASE/NATIVE/HYBRID, CR-069) / `config_dir` / `runner_api_key`. `tool: "claude_code"` 워크플로우는 모두 제거됨 — `LLM_CALL` 노드에서 anthropic-cli connection 으로 대체. BIZ-099 의미 변경(ToS 경계는 Runner 위치로 자연 해결) |
| v2.8.0 | 2026-04-24 | CR-060 위젯 STT 추가 — `POST /api/v1/chat/stt` (multipart, scope=chat:stt). Whisper 일괄 전송(실시간 스트리밍 없음). 녹음시간 60s / 파일 25MB / 세션당 10/min 제한(BIZ-102~104, `widget.stt.*` 로 조정). 기존 `POST /api/v1/speech/stt` 응답에 `language/duration` 필드 추가(backward compatible, `SpeechService` 로 로직 추출). `widget.allowed-scopes` 기본값에 `chat:stt` 추가 (§ 19) |
| v2.7.0 | 2026-04-24 | CR-050 Connection `adapter=anthropic-cli`(또는 `claude-cli`/`claude-max`/`claude-pro`) 지원 — Max/Pro 구독 OAuth 로 LLM_CALL. `config`에 `model`(CLI `--model` 전달), 선택 `claude_config_dir`. 테넌트 피처 플래그는 `global_config.llm.anthropic-cli.enabled-tenants` (`*`=전체, `,`구분=선택, 빈값=차단). 도구 미지원(`--tools ""` 봉인), 호출 시 `LLMRequest.sessionId` 필수 (BIZ-099/100) |
| v2.6.0 | 2026-04-24 | CR-061 위젯 파일 첨부 API 2종 추가 — `POST /chat/attachments` (multipart, scope=chat:upload), `DELETE /chat/attachments/{id}`. `messages[].content[]` 에 `{type:"image"\|"document", attachment_id}` 블록 수용. Anthropic/Bedrock 는 네이티브 PDF 블록, 그 외 프로바이더는 텍스트 추출 폴백. 크기 제한 이미지 10MB / PDF 32MB, 세션당 활성 10개, TTL 24h (BIZ-099~101). `widget.allowed-scopes` 기본값에 `chat:upload` 추가 (§ 18) |
| v2.5.1 | 2026-04-24 | CR-058 Sprint 53 공개 리소스 — `/widget/v1/*` 정적 서빙(인증 없이): UMD/ESM 번들, 통합 가이드 HTML, 샘플 BFF 코드. 소비앱이 CDN 처럼 직접 참조하거나 curl 로 다운받아 자체 호스팅 가능 (§ 17 헤더) |
| v2.5.0 | 2026-04-24 | CR-058 Chat Widget SDK 서버 엔드포인트 3종 추가 — `POST /sessions/issue-widget-token` (단기 JWT 발급, API Key 인증), `GET /knowledge-sources/{sid}/chunks/{cid}` (RAG 원문 조회), `GET /workflows/runs/{id}/subscribe` (SSE 구독). 기존 Chat API 응답/ SSE `done` payload 에 `citations` + `rag_used` 필드 추가. SSE 는 `?access_token=` 쿼리 전달 허용(widget 토큰만) (§ 17) |
| v2.4.0 | 2026-04-22 | CR-054 범용 HTTP 요청 도구(`http_request`) 추가 — Connection `type=HTTP` 등록 + 에이전트/워크플로우에서 임의 REST API 호출 지원. 인증 4종(API_KEY/BEARER/BASIC/NONE), 4xx/5xx status 반환, 감사 로그 헤더 마스킹(§ 7-6) |
| v2.3.0 | 2026-04-16 | CR-049 세션 복원·지침 체계 추가 — `POST /sessions/{id}/resume`(§ 15), 프롬프트 템플릿 scope/project_id 확장 + cascade append + 미리보기 API(§ 16) |
| v2.2.0 | 2026-04-16 | CR-048 컨텍스트·토큰 효율 안내(§ 7-5) 추가 — Deferred Tool 스키마 / Tool Result Storage / Adaptive Thinking (소비앱 코드 변경 불필요) |
| v2.1.0 | 2026-04-16 | CR-046 Chat 실시간 제어 — `POST /chat/{sessionId}/abort` 추가, `DELETE /conversations/{sessionId}` Soft Delete + 본인 권한 체크로 변경. 동시 요청 시 자동 이전 abort(옵션 B) |
| v2.0.0 | 2026-04-10 | CR-041 Agent Registry API 4개 엔드포인트 추가 (§ 14) |
| v1.9.1 | 2026-04-08 | 워크플로우 수정(PUT) 예시에 필수 `id` 필드 누락 수정. 필수/선택 필드 테이블에 `id` 추가 |
| v1.9.0 | 2026-04-08 | 에이전트 자율성 도구 4종 추가: list_mcp_resources, read_mcp_resource, remote_trigger, brief. 세션 브리핑 REST API 2개 추가 (CR-038) |
| v1.8.0 | 2026-04-08 | 네이티브 도구 4종 추가: bash(셸 실행), file_write(파일 생성), web_search(웹 검색), suggest_background_pr(PR 자동 생성). ClaudeCodeTool 의존 해소 (CR-037) |
| v1.7.0 | 2026-04-08 | 스케줄 작업 CRUD API, 스킬 CRUD API, 지식소스 crawl_mode(firecrawl) 지원, DOMAIN_FILTER 정책 규칙 추가 (CR-035) |
| v1.6.0 | 2026-04-07 | 서브에이전트 API 6개 엔드포인트 추가, 워크플로우 AGENT_CALL 스텝 타입, Worktree 격리 실행 (CR-030) |
| v1.5.0 | 2026-04-05 | 도구 관리 확장(contract/execute/validate), 세션 메타, 도구 실행 이력, Context Recipe, Domain Config 엔드포인트 추가 (CR-029) |
| v1.4.0 | 2026-03-28 | LLM_CALL 스텝 `max_tokens` config 키 추가. 토큰 초과 자동 처리(에스컬레이션+자동분할) 설명 (CR-028) |
| v1.3.0 | 2026-03-28 | 워크플로우 생성/수정/삭제 REST API 예제 추가. 스텝 타입 레퍼런스(TOOL_CALL, LLM_CALL 등) 및 스텝 간 데이터 참조 문법 명세 |
| v1.2.0 | 2026-03-28 | 시스템 API Key 인증 추가 (CR-025). `X-API-Key` 헤더로 JWT 없이 인증 가능. API Key 관리 엔드포인트 4개 추가 |
| v1.1.0 | 2026-03-28 | 개별 문서 삭제 API 추가 (`DELETE /{id}/documents/{documentId}`). ingest-text를 upsert 동작으로 변경 |
| v1.0.0 | 2026-03-28 | 초판 작성. 지식 학습(파일/텍스트/URL), 검색, 워크플로우 실행 시나리오 포함. CR-024 ingest-text API 반영 |
