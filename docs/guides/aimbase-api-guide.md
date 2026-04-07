# Aimbase REST API 통합 가이드

> **v1.6.0** | 2026-04-07 | Aimbase v4.1.0 기준

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
    "name": "Evidence 요건 설계 v2",
    "triggerConfig": { "type": "manual" },
    "steps": [ ... ],
    "inputSchema": { ... },
    "outputSchema": { ... },
    "errorHandling": { "strategy": "stop_on_first", "maxRetries": 2 }
  }'
```

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

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| v1.6.0 | 2026-04-07 | 서브에이전트 API 6개 엔드포인트 추가, 워크플로우 AGENT_CALL 스텝 타입, Worktree 격리 실행 (CR-030) |
| v1.5.0 | 2026-04-05 | 도구 관리 확장(contract/execute/validate), 세션 메타, 도구 실행 이력, Context Recipe, Domain Config 엔드포인트 추가 (CR-029) |
| v1.4.0 | 2026-03-28 | LLM_CALL 스텝 `max_tokens` config 키 추가. 토큰 초과 자동 처리(에스컬레이션+자동분할) 설명 (CR-028) |
| v1.3.0 | 2026-03-28 | 워크플로우 생성/수정/삭제 REST API 예제 추가. 스텝 타입 레퍼런스(TOOL_CALL, LLM_CALL 등) 및 스텝 간 데이터 참조 문법 명세 |
| v1.2.0 | 2026-03-28 | 시스템 API Key 인증 추가 (CR-025). `X-API-Key` 헤더로 JWT 없이 인증 가능. API Key 관리 엔드포인트 4개 추가 |
| v1.1.0 | 2026-03-28 | 개별 문서 삭제 API 추가 (`DELETE /{id}/documents/{documentId}`). ingest-text를 upsert 동작으로 변경 |
| v1.0.0 | 2026-03-28 | 초판 작성. 지식 학습(파일/텍스트/URL), 검색, 워크플로우 실행 시나리오 포함. CR-024 ingest-text API 반영 |
