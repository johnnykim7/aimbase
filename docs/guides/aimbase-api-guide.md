# Aimbase REST API 통합 가이드

> **v3.13.1** | 2026-06-18 | Aimbase v8.18.0 기준

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
| `graphMode` | string | X | 실행 모드. `dag`(기본, 생략 시) = 위상정렬 1-pass. `cyclic` = 워크리스트 스케줄러, 임의 노드 순환 + ROUTER N-way 분기 추종 (CR-084) |

**스텝 타입과 config**:

| type | config 키 | 설명 |
|------|----------|------|
| `LLM_CALL` | `connection_id`, `system`, `prompt`, `response_schema`, `max_tokens`, `stream_tokens`(CR-085), `output_channel`/`reduce`(CR-085) | LLM 호출 (토큰 초과 시 자동 에스컬레이션+분할, CR-028) |
| `TOOL_CALL` | `tool`, `input` | 도구 호출 (ToolRegistry 등록 도구) |
| `ACTION` | `actionType`, `config` | 액션 실행 (write, notify) |
| `CONDITION` | `expression`, `true_step`, `false_step` | 2갈래 조건 분기 |
| `ROUTER` | `routes` | N-way 동적 라우팅 (CR-084). 표현식/LLM 출력으로 N개 후보 중 1개 선택 |
| `PARALLEL` | `branches` | 병렬 실행 |
| `HUMAN_INPUT` | `message` | 사람 승인 대기 |
| `EVALUATOR_LOOP` | `generator`, `evaluator`, `pass_criteria`, `max_iterations` | 생성→평가 반복 (CR-055) |
| `FOREACH` | `items`, `body`, `item_var`, `mode`, `max_concurrency`, `max_items`, `collect`, `on_item_error` | 동적 컬렉션 fan-out (CR-087). 런타임 컬렉션의 각 원소에 body step 적용 (map) |

> **주의**: 스텝 타입은 `TOOL_CALL`입니다 (`TOOL_USE` 아님). config에서 도구 이름은 `tool` (`tool_name` 아님), 입력은 `input` (`arguments` 아님).

> **`LLM_CALL`이 Claude CLI 커넥터(`adapter=anthropic-cli`)를 가리킬 때의 라우팅 (CR-103)** — 어느 PC/서버의 agent(Runner)로 보낼지는 다음 우선순위로 결정된다: ① 요청 헤더 `X-Aimbase-Agent-Id` → ② 위젯 토큰의 `user_ref` 클레임(CR-075) → ③ **커넥터 `config.agent_name`** → ④ 모두 없으면 400(`cli_routing_missing`). 워크플로우는 헤더/토큰 없이 실행되므로 보통 ③번 — 커넥터 `config.agent_name`에 라우팅 대상 agent 이름을 박아두면 소비앱이 agent-id를 몰라도 자동 라우팅된다. 대상 agent가 미등록/오프라인이면 `cli_agent_offline`로 스텝 실패한다.

**ROUTER 스텝 (N-way 동적 라우팅, CR-084)** — `CONDITION`의 true/false 2갈래를 일반화. `routes` 배열을 정의 순서대로 평가해 `when` 표현식이 참인 첫 route의 `to`로 분기, 모두 실패 시 `default:true` route 사용:

```json
{
  "id": "route_by_intent",
  "type": "ROUTER",
  "config": {
    "routes": [
      { "when": "{{classify.output}} equals 'refund'",  "to": "refund_step" },
      { "when": "{{classify.output}} contains 'urgent'", "to": "urgent_step" },
      { "default": true, "to": "fallback_step" }
    ]
  }
}
```

- `when` 표현식 문법은 `CONDITION`과 동일 (`contains`/`equals`/숫자비교/불리언)
- 각 route는 `when` 또는 `default:true` 중 정확히 하나 + `to`(대상 스텝 id) 필수
- `default:true` route는 최대 1개. 정의 순서와 무관하게 `when` route가 모두 실패할 때만 채택
- **임의 순환은 `graphMode:"cyclic"`에서만 동작** — DAG 모드에선 ROUTER 스텝이 실행은 되나 `next_step` 추종 분기는 cyclic 모드 전용

**FOREACH 스텝 (동적 컬렉션 fan-out, CR-087)** — 런타임에 정해지는 컬렉션의 각 원소에 동일한 `body` 스텝을 적용(map)합니다. `PARALLEL`(정적 branch)·`ROUTER`(N중 1택)로는 표현할 수 없던 "각 원소에 같은 처리"를 단일 노드로 선언합니다. LangGraph의 `Send`/map에 대응합니다.

```json
{
  "id": "parse_each_sample",
  "type": "FOREACH",
  "config": {
    "items": "{{fetch_samples.output}}",
    "item_var": "item",
    "mode": "parallel",
    "max_concurrency": 5,
    "max_items": 100,
    "collect": "append",
    "on_item_error": "continue",
    "body": {
      "type": "TOOL_CALL",
      "config": { "tool": "parse_document", "input": { "url": "{{item.downloadUrl}}" } }
    }
  },
  "depends_on": ["fetch_samples"]
}
```

| config 키 | 기본값 | 설명 |
|-----------|--------|------|
| `items` | (필수) | 반복할 컬렉션. `{{step.output}}` 등 List를 가리키는 단일 참조, 또는 인라인 List |
| `body` | (필수) | 각 원소에 적용할 스텝 정의(`type`+`config`). `LLM_CALL`/`TOOL_CALL`/`SUB_WORKFLOW`/`ACTION`/`AGENT_CALL` 등. **`FOREACH` 직접 중첩 불가** — 중첩이 필요하면 body=`SUB_WORKFLOW` |
| `item_var` | `item` | 각 원소를 바인딩할 변수명. body에서 `{{item.field}}`(원소가 Map) 또는 `{{item.value}}`(원소가 스칼라)로 참조 |
| `mode` | `sequential` | `sequential`(순차) \| `parallel`(Virtual Thread 병렬) |
| `max_concurrency` | `5` | `parallel` 시 동시 실행 상한 |
| `max_items` | `100` | 처리 상한. 컬렉션이 초과하면 스텝 FAIL(무한 방어) |
| `collect` | `append` | 결과 수집: `append`(List 누적) \| `merge`(Map 병합) \| `none`(미수집) |
| `on_item_error` | `fail` | 원소 실패 시: `fail`(전체 중단) \| `continue`(실패 원소를 `{status:"failed", error}` 기록 후 계속) |

- body에서 현재 인덱스는 `{{index.value}}`로 참조 (0-based)
- 출력: `{ "output": [...], "results": [...], "item_count": N, "failed_count": M }` — 이후 스텝에서 `{{parse_each_sample.output}}`로 N개 결과 List 참조 (예: LLM_CALL에 합쳐 패턴 추출)
- **단일 노드**라 DAG/cyclic 양 모드에서 동일하게 동작. 마이그레이션 불필요(StepType은 JSONB 문자열)

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

**중첩 경로 참조 (CR-085)** — `{{ns.key}}` 1뎁스 외에 Map/List 중첩 경로를 지원합니다. 기존 1뎁스 참조와 실패 시 빈 문자열 폴백 동작은 그대로 유지됩니다(하위호환).

| 형식 | 의미 |
|------|------|
| `{{step.a.b.c}}` | 중첩 Map 경로 |
| `{{step.items[1]}}` | List 인덱스 접근 (0-based) |
| `{{step.rows[0].name}}` | List → Map 혼합 경로 |
| `{{input.cfg.timeout}}` | input 도 중첩 경로 지원 |

경로가 중간에 끊기거나(예: String에 더 들어감) 인덱스 범위를 벗어나면 기존과 동일하게 빈 문자열로 치환됩니다.

**채널 reducer (CR-085)** — 스텝 config에 `output_channel` + `reduce`를 지정하면 결과를 채널에 누적할 수 있습니다. 미지정 시 기존 동작(스텝 결과 덮어쓰기) 100% 보존. `{{stepId.field}}` 참조는 reducer 사용 여부와 무관하게 항상 동작합니다.

| `reduce` | 동작 |
|----------|------|
| `replace` (기본) | 채널을 결과로 덮어쓰기 (현행 동작) |
| `append` | 채널을 List로 보고 결과를 원소로 추가 — cyclic 그래프 메시지 누적 (LangGraph `add_messages` 대응) |
| `merge` | 채널을 Map으로 보고 결과 키들을 얕은 병합 |

```json
{
  "id": "turn",
  "type": "LLM_CALL",
  "config": {
    "prompt": "이전 대화: {{messages}}\n사용자: {{input.msg}}",
    "output_channel": "messages",
    "reduce": "append"
  }
}
```
> cyclic 그래프에서 `turn` 노드가 N회 실행되면 `{{messages[0].msg}}` … `{{messages[n].msg}}`로 누적 대화를 참조할 수 있습니다 (CR-084 cyclic + CR-085 append 시너지).

**노드 내부 토큰 스트리밍 (CR-085)** — `LLM_CALL` 스텝 config에 `stream_tokens: true`를 지정하면 노드 내부 LLM 토큰 델타가 SSE로 스트리밍됩니다(opt-in). 미지정 시 기존 step 단위 이벤트만 발행(하위호환).

- 적용 조건: `response_schema`가 없는 텍스트 응답 스텝에만 (구조화 출력/자동분할과 충돌하므로 schema 지정 시 자동 비활성)
- SSE 이벤트: `step_token` (`stepId`, `tokenDelta`, `type`=`text`|`thinking`, `iterationIndex` nullable)
- orchestrator(채팅) SSE와 동일한 공용 스트리밍 경로 재사용 — 별도 구현 없음

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

#### 진행 단계 표시 — `currentStepName` / `steps[]` (run 단건 조회)

run **단건** 조회 2종 — `GET /api/v1/workflows/{id}/runs/{runId}` 과 `GET /api/v1/workflows/runs/{runId}` — 은
기존 필드에 더해 **사람이 읽는 현재 스텝 이름**과 **전체 스텝 진행 목록**을 함께 반환합니다.
화면에 "지금 어느 단계인지" / 진행바를 그릴 때 사용합니다.

```json
{
  "data": {
    "status": "running",
    "currentStep": "verify_and_gapcheck",
    "currentStepName": "분석 결과 검증 중",
    "steps": [
      {"id": "fetch_opportunity",  "name": "공고 정보 불러오는 중", "status": "completed"},
      {"id": "verify_and_gapcheck","name": "분석 결과 검증 중",     "status": "running"},
      {"id": "save",               "name": "추출 결과 저장",        "status": "pending"}
    ]
  }
}
```

- `currentStep` — 스텝 **id**(개발용 식별자, 기존과 동일). `currentStepName` — 그 id 에 해당하는 WF 정의 step 의 `name`.
- `steps[]` — WF 정의 순서대로 `{id, name, status}`. `status` 값:
  - `completed` — 결과가 적재된 스텝(`stepResults` 에 키 존재)
  - `running` — 현재 진행 스텝(run 이 진행 중일 때)
  - `pending` — 아직 시작 안 한 스텝
  - run 이 terminal(`completed`/`failed`/`cancelled`)이면 현재 스텝은 그 종료 상태로 표시
- WF 정의를 찾지 못하면 `currentStepName=null`, `steps=[]` 로 graceful (나머지 필드 정상).
- **하위호환**: 기존 필드(`currentStep`, `stepResults`, `status`, `startedAt` …)는 그대로 유지 — 신규 필드만 추가.
- 목록 조회(`GET /workflows/{id}/runs`, `GET /workflows/runs`)에는 추가되지 않습니다. 전체 step 목록이
  필요하면 `GET /workflows/{id}`(WF 정의)를 한 번 캐싱해 처리하세요.

### 4-6. 실행 상태

| status | 설명 |
|--------|------|
| `running` | 실행 중 |
| `completed` | 정상 완료 |
| `failed` | 스텝 실행 실패 |
| `pending_approval` | HUMAN_INPUT 승인 대기 |
| `cancelled` | 중지됨 (사용자 요청) |

### 4-7. 실행 중지 [CR-105]

진행 중인 run 을 **협조적으로** 중지합니다.

```bash
curl -X POST /api/v1/workflows/runs/{runId}/cancel
# → { "data": { "id": "run_xxx", "status": "running" 또는 "cancelled" } }
```

| run 상태 | 동작 | 응답 status |
|----------|------|------------|
| `running` | 중지 표식을 세움. **현재 실행 중인 스텝은 끝까지 수행**한 뒤, 다음 스텝 경계에서 `cancelled` 로 종료. 응답은 표식 직후라 아직 `running` 일 수 있음 — 실제 종료는 § 4-5 폴링으로 확인. | `running` |
| `pending_approval` | 승인 대기 중이라 즉시 `cancelled` 로 전이. | `cancelled` |
| `completed`/`failed`/`cancelled` | 이미 종료 — 변경 없이 반환 (멱등). | 그대로 |
| 미존재 runId | `404 Not Found` | — |

> ⚠️ **즉시성 한계**: 협조적 중지라 진행 중인 한 스텝(예: 긴 LLM 호출)은 강제로 끊지 않고 끝낸 뒤 멈춥니다. 즉시 종료가 필요한 게 아니라 "더 이상 다음 스텝으로 진행하지 말라"는 의미입니다.

#### 소비앱 UI 권장 패턴

**cancel 응답의 `status` 를 그대로 화면에 박지 마세요.** `running` run 을 취소하면 응답이 아직 `running` 이라, 이걸 "중지됨"으로 표시하면 거짓이 됩니다. cancel API 는 "중지 요청 접수"만 하고 실제 종료는 다음 스텝 경계에서 일어납니다.

권장 흐름:

1. **cancel 호출 성공(2xx)** → 버튼을 `중지 중…`(비활성) 상태로 (낙관적 표시)
2. **응답 `status` 분기**
   - `cancelled` → 바로 `중지됨` 확정 (pending_approval 이었거나 이미 종료된 run)
   - `running` → 아직 안 멈춤. **실제 종료를 기다려야 함** (아래 3)
3. **실제 종료 확인** — 둘 중 하나
   - **SSE 구독 중이면** (`GET /workflows/runs/{runId}/subscribe`, § 17-6): 스트림의 `workflow.done`(status=`cancelled`) 이벤트로 확정 — 별도 폴링 불필요 (권장)
   - **폴링**: `GET /workflows/runs/{runId}` (§ 4-5) 를 2~3초 간격으로 → `status` 가 terminal(`cancelled`/`completed`/`failed`)이 되면 확정 + 폴링 종료

```javascript
// 폴링 예시 (React Query useMutation + 후속 폴링)
async function cancelRun(runId) {
  const { data } = await api.post(`/api/v1/workflows/runs/${runId}/cancel`);
  if (data.data.status === 'cancelled') return 'cancelled';      // 즉시 확정
  // running → terminal 될 때까지 폴링
  while (true) {
    await sleep(2500);
    const { data: run } = await api.get(`/api/v1/workflows/runs/${runId}`);
    const s = run.data.status;
    if (['cancelled', 'completed', 'failed'].includes(s)) return s; // 확정
  }
}
```

> 경계 케이스: `running` 을 취소했는데 그 사이 워크플로우가 마지막 스텝까지 끝나버리면 최종 status 가 `completed` 일 수 있습니다(중지보다 완료가 빨랐던 경우). UI 는 "중지됨"만이 아니라 **terminal 상태 자체**를 받아 표시하세요.

#### 강제 취소 `?force=true` [CR-116]

협조적 cancel(§ 4-7 기본)은 **다음 스텝 경계에서만** 표식을 검사합니다. 그래서 진행 중인 한 스텝(특히 AGENT_CALL 의 CLI worker)이 **응답 전 hang** 하면(예: CLI 초기화 미도달로 turn timeout 까지 대기) 협조적 cancel 이 즉시 먹지 않습니다. 이때 `force=true` 를 쓰면 그 교착을 끊습니다.

```bash
curl -X POST "/api/v1/workflows/runs/{runId}/cancel?force=true"
# → { "data": { "id": "run_xxx", "status": "cancelled" } }   # 즉시 cancelled
```

- **같은 엔드포인트**입니다. `force` 파라미터를 생략하면(또는 `false`) 기존 협조적 동작 그대로 — **하위호환**.
- `force=true` 동작: 이 run 이 띄운 CLI worker(AGENT_CALL)를 **즉시 kill** 하고 run 을 **즉시 `cancelled`** 로 종료(스텝 경계까지 기다리지 않음). 응답 status 가 바로 `cancelled` 이므로 § 4-7 의 후속 폴링이 필요 없습니다.
- 협조적 표식도 함께 남겨, worker 매핑을 못 찾는 경우(이미 종료/멀티노드)에도 다음 스텝 경계 폴백이 작동합니다.
- `pending_approval`/terminal 은 기본 cancel 과 동일하게 처리(force 무관).

> 권장: 평상시엔 협조적 cancel(`force` 없음)을 쓰고, "취소를 눌렀는데 한참 안 멈춘다" 싶을 때 사용자에게 **강제 취소** 버튼을 노출하세요.

#### 재실행 [CR-116]

종료된(또는 취소한) run 을 **같은 워크플로우 + 같은 입력**으로 새 run 으로 다시 실행합니다.

```bash
curl -X POST /api/v1/workflows/runs/{runId}/rerun
# → 202 Accepted, { "data": { "id": "<새 runId>", "status": "running", ... } }
```

- 원 run 의 `input_data` 와 `workflowId` 를 그대로 사용 — 새 `runId` 가 발급되고 **원 run 은 보존**됩니다.
- 응답의 새 `runId` 로 § 4-5 조회/§ 17-6 SSE 구독을 이어가세요.
- run 미존재 / 해당 워크플로우 미존재 시 `404`.

### 4-8. 삭제

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
| POST | `/workflows/runs/{runId}/cancel` | 실행 중지 (협조적) [CR-105] / `?force=true` 강제 취소 — CLI worker 즉시 kill [CR-116] |
| POST | `/workflows/runs/{runId}/rerun` | 재실행 — 같은 입력으로 새 run [CR-116] |
| POST | `/workflows/runs/{runId}/approve` | HUMAN_INPUT 스텝 승인/거부 |
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

### 17-9. 워크플로우 실행 이벤트 시간순 조회 — `GET /api/v1/workflows/runs/{runId}/events` [CR-090]

실시간 SSE(§17-6) 연결을 유지하기 부담스러울 때, **실행이 끝난 뒤(또는 폴링으로 중간에) 한 번 호출**하면 "어떤 단계에서 어떤 도구를 어떤 input 으로 불렀고 무엇을 받았다" 흐름을 시간순 배열로 받는다. 로그처럼 그대로 출력하기 좋다.

**인증**: `Authorization: Bearer <위젯 토큰>` 또는 `?access_token=<위젯 토큰>` (§17-6 과 동일하게 공통 JWT 필터가 처리). 기본 인증 사용자도 가능.

```bash
curl "$AIMBASE/api/v1/workflows/runs/$RUN_ID/events" \
  -H "Authorization: Bearer $WIDGET_TOKEN"
```

**응답** (`data` 는 시간순(`created_at`, `id` ASC) 이벤트 배열):

```json
{
  "success": true,
  "data": [
    { "id": 1, "event_type": "STEP_START", "step_id": "fetch_order", "iteration": null,
      "tool_name": null, "duration_ms": null,
      "payload": { "step_type": "TOOL_CALL" },
      "trace_id": null, "subagent_run_id": null, "created_at": "2026-06-04T10:00:00.123Z" },
    { "id": 2, "event_type": "TOOL_USE", "step_id": "fetch_order", "iteration": 0,
      "tool_name": "http_request", "duration_ms": null,
      "payload": { "input_keys": ["url","method"], "input_preview": "{url=https://…, method=GET}" },
      "trace_id": null, "subagent_run_id": null, "created_at": "2026-06-04T10:00:00.130Z" },
    { "id": 3, "event_type": "TOOL_RESULT", "step_id": "fetch_order", "iteration": 0,
      "tool_name": "http_request", "duration_ms": 1420,
      "payload": { "output_size": 8231, "ok": true },
      "trace_id": null, "subagent_run_id": null, "created_at": "2026-06-04T10:00:01.550Z" },
    { "id": 4, "event_type": "LLM_RESPONSE", "step_id": "summarize", "iteration": 1,
      "tool_name": null, "duration_ms": 2100,
      "payload": { "model": "claude-…", "in_tok": 1820, "out_tok": 240, "finish_reason": "end_turn" },
      "trace_id": "abc123", "subagent_run_id": null, "created_at": "2026-06-04T10:00:03.700Z" },
    { "id": 5, "event_type": "STEP_END", "step_id": "summarize", "iteration": null,
      "tool_name": null, "duration_ms": 2100,
      "payload": { "output_size": 240 },
      "trace_id": null, "subagent_run_id": null, "created_at": "2026-06-04T10:00:03.710Z" }
  ]
}
```

**이벤트 6 종**:

| event_type | 의미 | 주요 payload 필드 |
|------------|------|------------------|
| `STEP_START` | 단계 진입 | `step_type` |
| `TOOL_USE` | 도구 호출 시작 | `input_keys`, `input_preview` (input 100자 미리보기) |
| `TOOL_RESULT` | 도구 결과 | `output_size`, `ok`, `error`(실패 시) |
| `LLM_RESPONSE` | LLM 응답 1회 | `model`, `in_tok`, `out_tok`, `finish_reason` |
| `STEP_END` | 단계 정상 종료 | `output_size` (+ 최상위 `duration_ms`) |
| `STEP_FAILED` | 단계 실패 | `error`(500자), `attempts` (+ 최상위 `duration_ms`) |

**필드 주의**:
- `iteration` — 같은 단계 내 도구 루프 회차(0-based). 단계 레벨 이벤트(START/END/FAILED)는 `null`
- `trace_id` / `subagent_run_id` — 서브에이전트·심층 분석과 join 하기 위한 키
- payload 는 비어 있으면 `null` 로 내려갈 수 있음
- ~~얇은 버전이라 prompt/response 원본은 저장하지 않음~~ → **CR-102 부터 본문 전문도 적재** (아래 § 17-10)

### 17-10. 본문 전문 조회 + 횡단 실행 내역 [CR-102]

§ 17-9 의 payload 는 메타(미리보기 ≤100자)다. **품질 분석(프롬프트↔응답 정독, 단계 간 데이터 전달 검토)** 을 위해 CR-102 부터 본문 전문(절단 없음)을 함께 적재하며, 다음 4가지로 조회한다.

**(1) 이벤트 배열에 본문 동반** — `?include_body=true`

```bash
curl "$AIMBASE/api/v1/workflows/runs/$RUN_ID/events?include_body=true" \
  -H "Authorization: Bearer $TOKEN"
```

각 이벤트에 4개 필드가 추가된다 (해당 없는 이벤트는 `null`):

| 필드 | 적재 이벤트 | 내용 |
|------|------------|------|
| `prompt_text` | `LLM_RESPONSE` | `[SYSTEM]\n…\n\n[PROMPT]\n…` 합성 입력 전문 |
| `response_text` | `LLM_RESPONSE` | 응답 본문 전문 |
| `input_json` | `TOOL_USE` | 도구 input 전문 (JSON 객체) |
| `output_text` | `TOOL_RESULT` / `STEP_END` | 도구 결과 / 단계 결과 본문 전문 |

> ⚠️ LLM 프롬프트 전문 × 수십 이벤트면 응답이 수 MB 가 될 수 있다. 화면용이면 (2) 단건 조회를 권장.

**(2) 이벤트 단건 본문** — `GET /api/v1/workflows/runs/{runId}/events/{eventId}` — 본문 전문을 **항상 포함**해 1건 반환. 타임라인은 § 17-9 로 가볍게 띄우고, 사용자가 행을 펼칠 때만 이걸 부르는 패턴 (Aimbase 관리 FE "실행 내역" 화면이 이 패턴).

**(3) 전체 워크플로우 횡단 run 목록** — `GET /api/v1/workflows/runs?page=0&size=20&workflow_id=&status=` — 특정 워크플로우에 얽매이지 않은 최신순 run 목록. `workflow_id` / `status`(running/completed/failed/pending_approval/cancelled) 필터 옵션, 응답은 표준 `pagination` 동반.

**(4) run 단건 (워크플로우 id 없이)** — `GET /api/v1/workflows/runs/{runId}` — (3) 의 행에서 상세 진입할 때 사용.

본문은 **절단 없이 전문 적재**되므로 run 당 저장 용량이 증가한다. 보관기간(TTL) 정책은 후속 CR 예정.

### 17-11. 내부 도구 루프 가시화 — AGENT_CALL / CLI 어댑터 [CR-102 2차]

§ 17-9 의 이벤트는 처음엔 워크플로우 스텝 레벨만 적재했다. CR-102 2차부터 **LLM 이 스스로 도구를 부르는 내부 루프까지** 같은 이벤트 형태로 적재된다 — 어느 어댑터를 쓰든 타임라인 모양이 동일하다.

| 경로 | 루프 주체 | 적재 내용 |
|------|----------|----------|
| `AGENT_CALL` + API 어댑터 | Aimbase BE 도구 루프 | 회차별 `LLM_RESPONSE`(iteration 0..N, 응답 본문만) + 도구마다 `TOOL_USE`(input 전문)/`TOOL_RESULT`(output 전문). `subagent_run_id` 채워짐 — 멀티에이전트 병렬도 구분 가능 |
| `LLM_CALL`/`AGENT_CALL` + CLI 어댑터 | CLI 내부 자율 루프 | CLI(stream-json)의 tool_use/tool_result 를 Worker 가 관찰·페어링해 `TOOL_USE`/`TOOL_RESULT` 로 적재 (`duration_ms` 는 관찰 근사값). CLI 가 도구 루프를 자체 완결하는 구조(CR-050)는 그대로 — 관찰 전용 운반 |
| `LLM_CALL` + API 어댑터 | 루프 없음 | 기존 § 17-10 그대로 |

**필드 의미 보강**:
- `iteration` — 내부 루프 회차(0-based). 스텝 레벨 이벤트는 `null`
- 루프 회차의 `LLM_RESPONSE` 는 **응답 본문만** 적재 (`prompt_text` null) — 회차 prompt 는 이전 대화 전체의 누적 중복이라 제외. 도구 결과는 `TOOL_RESULT` 이벤트로 별도 적재되므로 흐름 재구성 가능
- CLI 관찰 건 중 tool_result 미페어링(비정상 종료 등)은 `TOOL_USE` 만 적재

**구조화 출력 본문 폴백**: `response_schema` 를 쓰는 LLM_CALL 은 응답이 tool_use 블록이라 textContent 가 비는데, 이 경우 `response_text`/STEP_END `output_text` 에 structured_data JSON 이 적재된다 (이전엔 빈 문자열).

**§17-6(SSE) 과의 선택 기준**: 실시간 진행 표시가 필요하면 `/subscribe`(SSE), "실행 단위 로그를 사후/폴링으로 본다" 면 `/events`. 둘은 독립적으로 병행 사용 가능하다.

```js
// 실행 끝난 뒤(또는 N초 폴링) 한 번만 — 로그처럼 출력
const res = await fetch(`${baseUrl}/api/v1/workflows/runs/${runId}/events`,
  { headers: { Authorization: `Bearer ${token}` } });
const events = (await res.json()).data;
events.forEach(e =>
  console.log(`[${e.created_at}] ${e.event_type} ${e.step_id} `
    + `${e.tool_name ?? ''} ${e.duration_ms != null ? e.duration_ms + 'ms' : ''}`));
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
- `document` 블록 (PDF): **CR-095 비전 게이트** — PDF 를 사이드카 텍스트 추출(`parse_document`/OCR)이 아니라 **LLM 모델 비전**으로 파싱한다 (openclaude 1:1). 크기/모델지원에 따라 3분기:
  - **≤ 3MB & PDF 지원 어댑터**(Anthropic/Bedrock Claude) → base64 `DocumentBlockParam` 통째 — 모델이 PDF 를 직접 비전으로 봄
  - **> 3MB or PDF 미지원 어댑터** → Python 사이드카 `pdf_to_images`(poppler) 로 페이지를 JPEG(100 DPI) 렌더 → `ImageBlockParam` 배열로 전달 (모델이 페이지 이미지를 비전으로 봄). 페이지 상한 기본 20
  - **이미지조차 미지원 어댑터** → 기존 `PdfTextExtractor`(`parse_document` + OCR) 텍스트 폴백 — `"[첨부 문서: {filename}]\n{text}\n\n"` 메시지 앞에 prepend
- 임계값은 `aimbase.pdf.*` 로 조정: `inline-max-bytes`(3MB), `target-raw-max-bytes`(20MB), `max-pages-per-read`(20), `image-dpi`(100), `inline-page-threshold`(10)

**페이지 분할 가드 (AGENT 자율 `parse_document` 도구 경로)** — AGENT 가 `parse_document` 로 PDF 를 읽을 때, 페이지 수가 `inline-page-threshold`(기본 10)를 초과하면 서버가 **통째 처리하지 않고** `{status:"too_many_pages", total_pages, instruction}` 를 반환한다. 모델은 안내대로 `pages` 파라미터(`"1-10"`, `"11-20"` 등, 최대 `max-pages-per-read`)로 나눠 재호출한다. 큰 PDF(예: 29페이지)를 한 턴에 통째 읽으려다 발생하던 turn timeout 을 모델 자율 분할로 해소 (openclaude `getPDFPageCount > PDF_AT_MENTION_INLINE_THRESHOLD` 가드 1:1). 위젯/채팅 **첨부** 경로는 1회성(모델 재호출 불가)이라 가드를 적용하지 않고 첫 `max-pages-per-read` 페이지만 싣는다.

> **왜 비전인가**: `parse_document`(unstructured/OCR 직렬, 건당 ~38초)는 표·레이아웃·도표를 뭉갠다. 모델 비전은 페이지를 그림으로 직접 이해 → 복잡한 문서 품질↑·지연↓. OCR 경로(§ 20)는 결정론·오프라인 추출이 필요할 때만.

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

## 20. 전통적 OCR (Tesseract) [CR-092]

비전 모델 우회와 분리된 결정론·오프라인 OCR. 사이드카 Tesseract + pdf2image + pdfplumber 조합. Vision 모델(CR-061 이미지/PDF 첨부)이 모델 토큰을 쓰는 반면 OCR 은 0원·재현가능. RAG 인제스션과 결정론적 추출에 적합.

### 20-1. 사이드카 MCP 툴 (BE 내부에서 호출)

- `read_pdf(file_base64, extract_images=false, ocr_enabled=false, ocr_languages="kor+eng", ocr_max_pages=50)` — `ocr_enabled=true` 면 pdfplumber 로 페이지 추출 후 텍스트가 빈 페이지만 Tesseract 로 OCR. 응답에 `pages[].source` (`embedded`/`ocr`/`failed`), `total_pages`, `truncated`, `warnings` 포함.
- `ocr_image(file_base64, languages="kor+eng")` — 단일 이미지(JPG/PNG/etc.) 전체를 Tesseract 로 OCR. 응답 `{text, success, languages}` 또는 `{success:false, error}`.

언어 화이트리스트 (BIZ-106): `eng`, `kor`, `jpn`, `chi_sim`, `chi_tra`, `fra`, `deu`, `spa`. 그 외 코드는 거부. 페이지 상한 (BIZ-105): 기본 50, 초과 시 처음 N 페이지만 OCR 하고 `truncated:true`.

### 20-2. BE PDF 첨부 — 자동 OCR fallback (BIZ-107)

소비앱이 위젯/채팅으로 PDF 첨부 시 BE `PdfTextExtractor` 가 다음을 수행한다.

1. 1차: `parse_document` (unstructured) 호출 — 텍스트 박힌 PDF 는 빠르게 추출
2. 추출 결과 길이 < `aimbase.ocr.fallback-threshold-chars` (기본 50) → 스캔 PDF 로 판정
3. 2차: `read_pdf(ocr_enabled=true)` 자동 호출 → Tesseract OCR

`aimbase.ocr.enabled=false` 로 OFF 가능. `aimbase.ocr.*` 4 키는 `global_config` 에서 런타임 변경 가능 (CR-040 관리 UI).

### 20-3. `ocr_image` Built-in Tool (CLI 자율 호출)

`ocr_image` 는 BE Built-in Tool 로 등록되어 있고 `McpExposurePolicy.CLI_EXPOSED` 화이트리스트에 포함된다. 따라서 Claude CLI / 워크플로우 도구 루프에서 다음과 같이 노출된다.

- MCP 노출 이름: `mcp__aimbase-server__ocr_image` (CR-072 server MCP endpoint 통해)
- 입력 스키마: `{file_base64: string, languages?: string}`
- 출력: `{text, languages, character_count}`
- 권한: `READ_ONLY` (감사 로그 자동 기록)

### 20-4. 비용 / 성능 / Vision 모델 선택 가이드

| 항목 | OCR (CR-092) | Vision 모델 (CR-061) |
|---|---|---|
| 비용 | $0 (로컬) | 모델 토큰 |
| 결정론 | O | X |
| 오프라인 | O | X |
| 손글씨/표 레이아웃 | 약함 | 강함 |
| 권장 사용처 | RAG 인제스션, 결정론적 추출 | 채팅 즉시 응답, 손글씨/표 |

RAG 인제스션은 OCR 우선, 위젯 채팅은 Vision 우선. BE `PdfTextExtractor` 만 텍스트 0자 자동 fallback (호출자 선택 불필요).

### 20-5. 설정 키 (global_config)

| 키 | 기본값 | 의미 |
|---|---|---|
| `aimbase.ocr.enabled` | `true` | OCR 전역 ON/OFF |
| `aimbase.ocr.languages` | `kor+eng` | 기본 OCR 언어 (BIZ-106 화이트리스트) |
| `aimbase.ocr.max-pages` | `50` | read_pdf 호출당 페이지 상한 (BIZ-105) |
| `aimbase.ocr.fallback-threshold-chars` | `50` | PdfTextExtractor fallback 트리거 임계 (BIZ-107) |

환경변수 오버라이드: `AIMBASE_OCR_ENABLED` / `AIMBASE_OCR_LANGUAGES` / `AIMBASE_OCR_MAX_PAGES` / `AIMBASE_OCR_FALLBACK_THRESHOLD`.

### 20-6. 로컬 PC 문서 파싱 (CR-100 — 파싱 다리)

사용자 로컬 PC 에 있는 문서("내 PC `/Users/me/docs` 의 PDF 분석해줘")는 서버 파일시스템에 없으므로 `parse_document(file_path=...)` 로 읽을 수 없다. 로컬 agent(aimbase-agent) 안의 Claude CLI 가 파일 byte 를 base64 로 서버 사이드카에 올리는 경로를 사용한다.

**`parse_document` 입력 3종 (택1):**

| 입력 | 읽는 주체 | 용도 |
|---|---|---|
| `url` | 사이드카 (HTTP 다운로드) | 공개 URL 문서 |
| `file_path` | 사이드카 (서버 파일시스템 직접 read) | 서버 workspace 내 문서 |
| `content` | BE (base64 디코드) → 사이드카 | **로컬 PC 문서** (CR-100) |

**로컬 시나리오 흐름:**

```
CLI → builtin_file_read(file_path, as_base64=true)   # 로컬 byte → base64 (10MB 상한)
    → parse_document(content=base64, file_type="docx")
    → BE ParseDocumentTool: PDF 면 비전(document/image 블록 주입), 그 외 사이드카 텍스트 추출
```

- `builtin_file_read` 의 `as_base64=true` 는 바이너리(PDF/DOCX/XLSX 등)도 base64 로 반환한다 (일반 모드는 바이너리를 메타만 반환). 10MB 초과 시 거부 — stdio/relay 페이로드 보호.
- `parse_document(content=...)` 는 PDF 면 비전 경로(§ 18-4 와 동일, BE 가 디코드해 별도 user 메시지로 주입), 그 외는 사이드카 `parse_document(file_content)` 텍스트 추출. 50MB 상한 + base64 유효성 검증.
- 라우팅(어느 PC 의 agent 냐)은 토큰 `user_ref` 매핑(CR-075)으로 자동 결정 — 소비앱이 agent-id 를 몰라도 된다.

---

## 21. URL 파일 다운로드 (`download_file`) [CR-107]

URL 에서 파일을 받아 워크스페이스에 **원본 그대로(바이너리 무손실)** 저장하는 네이티브 도구. 에이전트/워크플로우(`TOOL_CALL` step)에서 자율 호출하며, Claude CLI 경로(MCP)에도 노출된다.

**입력:**

| 파라미터 | 필수 | 설명 |
| `url` | ✓ | 다운로드 소스 URL (`http`/`https` 만 허용) |
| `file_path` | ✓ | 저장 경로 (절대 또는 워크스페이스 상대). 부모 디렉토리 자동 생성 |
| `overwrite` | | 기존 파일 덮어쓰기 허용 (기본 `false` — 이미 있으면 실패) |

**출력:** `{ file_path, bytes_written, source_url, created, overwritten }`

- 경로 검증은 `file_write` 와 동일한 워크스페이스 화이트리스트(L1 resolver + L2 독립 게이트)를 거친다 — 화이트리스트 밖 경로는 거부.
- 다운로드 상한 50MB, connect 15s / request 120s. HTTP 2xx 아니면 실패.
- `file_write` 와의 차이: `file_write` 는 텍스트 본문 쓰기, `download_file` 은 URL→바이너리 저장. `parse_document(url=...)` 와의 차이: `parse_document` 는 다운로드 후 텍스트로 변환해 반환하지만 `download_file` 은 원본 파일을 워크스페이스에 그대로 남긴다 (ZIP/이미지/바이너리 등 후속 처리용).

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
| v3.13.1 | 2026-06-18 | **현행화 — 헤더 버전 동기화 + CR-103 CLI 라우팅 보강** (문서만). 헤더 표기를 변경이력 최신(v3.13.0)에 맞춰 `v3.13.1 / Aimbase v8.18.0 기준`으로 정정(이전 헤더는 v3.5.0 으로 멈춰 있었음). § 4-1 스텝 타입 표 아래 **`LLM_CALL`이 `adapter=anthropic-cli` 커넥터를 가리킬 때의 라우팅 우선순위**(① 헤더 `X-Aimbase-Agent-Id` → ② 위젯 토큰 `user_ref` → ③ 커넥터 `config.agent_name`(CR-103) → ④ 400 `cli_routing_missing`) 노트 추가 — 워크플로우는 헤더/토큰 없이 실행되므로 커넥터 `config.agent_name`로 자동 라우팅. 코드 실측: `ClaudeCliAdapter.resolveAgent`. API 표면 무변화 |
| v3.13.0 | 2026-06-18 | **CR-116 — 워크플로우 run 강제 취소 + 재실행** (§ 4-7). ① **강제 취소**: 기존 `POST /workflows/runs/{runId}/cancel` 에 `?force=true` 파라미터 추가(같은 엔드포인트, 미지정 시 기존 협조적 동작 = **하위호환**). 협조적 cancel 은 스텝 경계에서만 표식을 검사해, AGENT_CALL 의 CLI worker 가 응답 전 hang 하면 즉시 안 먹는다 → `force=true` 면 그 run 이 띄운 CLI worker 를 즉시 kill(`ActiveCliWorkerRegistry` 로 workflowRunId→childSessionId 역추적 후 CR-114 와 동일 Runner cancel 부품 재사용) 하고 즉시 `cancelled` 로 종료(후속 폴링 불요). worker 매핑 못 찾는 경우 협조적 표식 폴백. ② **재실행**: `POST /workflows/runs/{runId}/rerun` 신설 — 원 run 의 `input_data`+`workflowId` 로 새 run 실행(원 run 보존, 새 runId 반환, 202). DB 스키마/마이그레이션 무변경(인메모리 레지스트리, input_data 기존 컬럼). 단위 — ActiveCliWorkerRegistry 5 + WorkflowEngineCancel force 3 + 회귀 GREEN(71 PASS) |
| v3.12.0 | 2026-06-18 | **run 단건 조회에 진행 단계 이름·진행 목록 추가** (§ 4-5). run **단건** 조회 2종(`GET /workflows/{id}/runs/{runId}`, `GET /workflows/runs/{runId}`) 응답에 `currentStepName`(현재 `currentStep` id 에 해당하는 WF 정의 step 의 사람이 읽는 `name`) + `steps[]`(정의 순서대로 `{id, name, status}`, status = completed/running/pending, run terminal 시 현재 스텝은 종료 상태) 신규 추가. 소비앱이 "지금 어느 단계인지"/진행바를 step id 매핑 캐싱 없이 바로 표시. status·steps 도출은 BE 인메모리 lookup(WF 정의 1회 조회) — DB 스키마/마이그레이션 무변경. WF 정의 미존재 시 `currentStepName=null`·`steps=[]` graceful. 기존 필드(`currentStep`/`stepResults`/`status` …) 그대로 유지 = **하위호환**. 목록 조회(`/runs`)는 미적용(전체 step 은 `GET /workflows/{id}` 캐싱 권장) |
| v3.11.1 | 2026-06-15 | **CR-095 후속 — PDF 페이지 분할 가드** (§ 18-4). 13MB/29p PDF AGENT_CALL(`parse_document`) 300초 turn timeout 해소. 근본원인=openclaude `getPDFPageCount > PDF_AT_MENTION_INLINE_THRESHOLD(10)` 가드 포팅 누락(3MB 크기 게이트만 가져옴) → >3MB PDF 첫 20p 통째 이미지화로 거대 입력. 해결: 사이드카 `pdf_page_count` MCP 도구 신설(렌더 없이 페이지 수) + `pdf_to_images` `total_pages` 반환 + `PdfVisionResolver` 페이지 가드(`aimbase.pdf.inline-page-threshold:10` 초과 & `pages` 미지정 시 `too_many_pages` 반환 → 모델이 `pages`로 분할 호출) + `parse_document` 도구 `pages` 파라미터 추가. 위젯/채팅 첨부는 1회성이라 가드 미적용(첫 max-pages-per-read). 사이드카 pdf_images 20 + ocr 26 + PdfVisionResolver 14 + tool/mcp.server 회귀 GREEN. 기존 동작 무변경 |
| v3.11.0 | 2026-06-14 | **CR-107 — URL 파일 다운로드 도구 (`download_file`)** (§ 21). URL 에서 파일을 받아 워크스페이스에 원본 바이너리로 저장하는 네이티브 도구 신설. 입력 `url`(http/https) + `file_path`(+ `overwrite`), 출력 `{file_path, bytes_written, source_url, created, overwritten}`. 경로 검증은 `file_write` 와 동일 화이트리스트(L1 resolver + L2 게이트) 재사용, 다운로드 상한 50MB, connect 15s/request 120s, HTTP 2xx 외 실패, 기존 파일은 `overwrite` 없으면 거부(원본 보존). `file_write`(텍스트)·`parse_document`(다운로드 후 텍스트 변환)와 달리 바이너리 원본을 그대로 저장 — ZIP/이미지 등 후속 처리용. `SdkToolBeanConfig` @Bean 등록 + `McpExposurePolicy.CLI_EXPOSED` 추가(42→43, API/CLI 경로 동일 노출). `DownloadFileToolTest` 7 PASS + platform-core 회귀 GREEN. 기존 동작 무변경(신규 도구만 추가) |
| v3.10.1 | 2026-06-14 | **CR-105 — § 4-7 소비앱 UI 권장 패턴 보강** (문서만). cancel 응답 `status` 를 그대로 화면에 박지 말 것 — `running` 취소 시 응답이 아직 `running` 이므로 "중지 요청 접수→`중지 중…` 낙관적 표시→SSE `workflow.done` 또는 `GET /runs/{runId}` 폴링으로 terminal 확정" 흐름 + 폴링 예시 코드 + 경계 케이스(중지보다 완료가 빨라 `completed` 로 끝날 수 있음) 추가. API 표면 무변화 |
| v3.10.0 | 2026-06-14 | **CR-105 — 워크플로우 실행 중지 (협조적)** (§ 4-7). `POST /api/v1/workflows/runs/{runId}/cancel` 신설 — 진행 중 run 을 스텝 경계에서 안전하게 중지. `running` 은 중지 표식만 세우고 백그라운드 실행 루프가 다음 스텝 경계에서 `cancelled` 로 종료(현재 스텝은 끝까지 수행 — 즉시성 없음), `pending_approval` 은 즉시 `cancelled` 전이 + 대기 승인 엔티티 정리, terminal(completed/failed/cancelled) 은 무변경 멱등, 미존재 404. DAG·cyclic 양 실행 경로에 체크포인트 삽입, run 종료 시 표식 정리(메모리 누수 방지). 메모리 플래그라 멀티노드 시 `running` 은 인스턴스 로컬(다른 노드 실행 run 미인터셉트), `pending_approval` 은 DB 기반이라 노드 무관. `WorkflowEngineCancelTest` 9 PASS + workflow 회귀 GREEN. 기존 동작 무변경(신규 엔드포인트만 추가) |
| v3.9.0 | 2026-06-12 | **CR-102 2차 — 내부 도구 루프 가시화** (§ 17-11). AGENT_CALL 서브에이전트의 BE 도구 루프(회차별 LLM_RESPONSE + TOOL_USE/TOOL_RESULT 전문, subagent_run_id 연결) + CLI 어댑터 내부 자율 루프 관찰(`LLMResponse.observedToolEvents` 운반 — Worker stream-json tool_use/tool_result 페어링) 을 run 타임라인에 적재. 구조화 출력(response_schema) 본문 폴백 — response_text/output_text 에 structured_data JSON 적재 (이전 빈 문자열). API 표면 변화 없음(이벤트 적재 범위 확대) |
| v3.8.0 | 2026-06-12 | **CR-102 — 워크플로우 실행 본문 전문 적재 + 조회** (§ 17-10). `workflow_run_events` 에 본문 전문 4컬럼(prompt_text/response_text/input_json/output_text, V66) 적재 — LLM 프롬프트↔응답·도구 input↔output·단계 결과를 절단 없이 정독 가능. 조회 4종: § 17-9 `?include_body=true` / 이벤트 단건 `GET /runs/{runId}/events/{eventId}`(본문 항상 포함) / 횡단 run 목록 `GET /workflows/runs`(workflow_id·status 필터+페이지네이션) / run 단건 `GET /workflows/runs/{runId}`. TTL(보관기간)은 후속 CR |
| v3.7.0 | 2026-06-08 | **CR-100 — 로컬 PC 문서 파싱 다리** (§ 20-6). 사용자 로컬 PC 문서를 서버 사이드카로 파싱하는 경로 완성. `builtin_file_read` 에 `as_base64=true` 옵션 신설(바이너리도 base64 반환, 10MB 가드), `parse_document` 에 `content`(base64) 입력 신설(url/file_path/content 3종 택1, PDF면 비전·그 외 사이드카 텍스트 추출, 50MB 가드). 흐름: CLI→`file_read(as_base64)`(로컬 byte)→`parse_document(content)`(서버 MCP CLI-level 기존 노출)→사이드카. 사이드카(Python)·MCPRagClient 는 이미 base64 입력 지원 → 무수정, Java 도구 2개만 수정. 단위 `FileReadToolTest` 4 + `ParseDocumentToolTest` content 4 추가, tool 회귀 PASS |
| v3.6.0 | 2026-06-05 | **CR-095 — PDF Native 비전 파싱** (openclaude 1:1). PDF 첨부(`document` 블록)를 사이드카 텍스트 추출(`parse_document`/OCR ~38초)이 아니라 **LLM 모델 비전**으로 파싱 (§ 18-4). 3분기: ≤3MB & PDF지원 → base64 `DocumentBlockParam` 통째 / >3MB or PDF미지원 → 사이드카 `pdf_to_images`(poppler, JPEG 100DPI) 페이지 이미지화 → `ImageBlockParam` 배열 / 이미지도 미지원 → `PdfTextExtractor` 텍스트 폴백. 임계값 `aimbase.pdf.*` (inline-max-bytes 3MB, target-raw-max-bytes 20MB, max-pages-per-read 20, image-dpi 100). **AGENT 자율 호출**(`parse_document` 도구)도 PDF 면 비전 경로 — tool_result 엔 메타, 실제 PDF/이미지는 별도 user 메시지로 주입(`ToolResult.newMessages`, openclaude newMessages 패턴). 신규 사이드카 MCP 툴 `pdf_to_images`. 단위: 사이드카 18 + PdfVisionResolver 9 + tool/rag/attachment 회귀 GREEN. **기존 동작 무변경(하위호환)** |
| v3.5.0 | 2026-06-04 | **CR-090 — 워크플로우 실행 이벤트 조회 API 문서화**. 기존 운영 라이브였으나 가이드에 누락돼 있던 `GET /api/v1/workflows/runs/{runId}/events` 추가 (§ 17-9). STEP_START/TOOL_USE/TOOL_RESULT/LLM_RESPONSE/STEP_END/STEP_FAILED 6종 이벤트를 시간순 배열로 반환 — 실시간 SSE(§17-6) 부담 없이 실행 단위 흐름을 사후/폴링 로그로 소비. 인증은 §17-6 과 동일(Bearer 또는 `?access_token=` 위젯 토큰, 공통 JWT 필터 처리). prompt/response 원본 미저장(얇은 버전), `trace_id`/`subagent_run_id` 로 심층 join. **신규 코드 없음 — 문서만 추가** |
|------|------|----------|
| v3.4.0 | 2026-06-03 | **CR-092 — 전통적 OCR (Tesseract)**. 사이드카 `read_pdf` 의 `ocr_enabled` 인자 실연결 + 신규 `ocr_image` MCP 툴 + BE `PdfTextExtractor` 텍스트 < 50자 fallback 자동 OCR + BE Built-in Tool `ocr_image` 신설 + CR-072 CLI 화이트리스트 추가(27개). 설정 4키 `aimbase.ocr.*` (`global_config` V65 seed). 언어 화이트리스트 BIZ-106 (eng/kor/jpn/chi_sim/chi_tra/fra/deu/spa), 페이지 상한 BIZ-105 (50), fallback 임계 BIZ-107 (50자). Dockerfile `tesseract-ocr-kor` 한국어 언어팩 추가. Vision 모델(CR-061)과 분리 — RAG 인제스션·결정론·오프라인 OCR 경로 (§ 20) |
| v3.3.0 | 2026-05-29 | **CR-087 — 워크플로우 FOREACH step (동적 컬렉션 fan-out)**. 신규 스텝 타입 `FOREACH` — 런타임 컬렉션(`items`)의 각 원소에 `body` 스텝(`LLM_CALL`/`TOOL_CALL`/`SUB_WORKFLOW` 등)을 적용(map). body에서 `{{item.field}}`(Map)·`{{item.value}}`(스칼라)·`{{index.value}}`(0-based) 참조. `mode`(`sequential` 기본 / `parallel`=Virtual Thread + `max_concurrency` 기본 5), `max_items`(기본 100, 초과 시 FAIL), `collect`(`append` 기본 / `merge` / `none`), `on_item_error`(`fail` 기본 / `continue`). 출력 `{output:[...], results:[...], item_count, failed_count}`. `PARALLEL`(정적 branch)·`ROUTER`(N중 1택)로 표현 못하던 map/fan-out을 단일 노드로 선언 (LangGraph `Send`/map 대응). FOREACH 직접 중첩은 차단(body=`SUB_WORKFLOW`로 중첩). **단일 노드라 DAG/cyclic 양 모드 동일 동작, 마이그레이션 불필요(StepType JSONB 문자열), 기존 워크플로우 무변경(하위호환)** — platform-core 전체 회귀 GREEN |
| v3.2.0 | 2026-05-18 | **CR-085 — State 채널 reducer + 중첩 경로 + 노드 토큰 스트리밍**. 변수 참조에 중첩 경로(`{{step.a.b[0].c}}`, List 인덱스/Map 혼합) 추가 — 기존 1뎁스 참조·실패 폴백(빈 문자열) 동작 100% 보존. 스텝 config에 `output_channel`+`reduce`(`replace` 기본 / `append`=List 누적, LangGraph `add_messages` 대응 / `merge`=Map 얕은 병합) opt-in — 미지정 시 기존 덮어쓰기 동작 그대로, `{{stepId.field}}` 참조는 항상 유지. `LLM_CALL` config에 `stream_tokens:true` opt-in 시 노드 내부 LLM 토큰 델타를 SSE `step_token`(`tokenDelta`/`type`/`iterationIndex` nullable)로 발행 — `response_schema` 없는 텍스트 응답에만 적용, orchestrator SSE 공용 스트리밍 경로 재사용. **기존 워크플로우 무변경(하위호환)** — platform-core 653 회귀 GREEN |
| v3.1.0 | 2026-05-16 | **CR-084 — 워크플로우 임의 cycle + N-way 라우팅**. 워크플로우 생성 파라미터에 `graphMode`(`dag` 기본 / `cyclic`) 추가. 신규 스텝 타입 `ROUTER`(`routes` config — `when`/`default:true` + `to`). `graphMode:cyclic` 시 워크리스트 스케줄러로 임의 노드 순환 + ROUTER `next_step` 추종, run 당 step budget(기본 50, 절대 200, `triggerConfig.max_total_steps`로 조정) 무한루프 방어. HUMAN_INPUT 중단→resume 시 worklist 자동 복원. **기존 DAG 워크플로우는 무변경(하위호환)** — `graphMode` 생략 = 기존 1-pass. SSE 스텝 이벤트에 nullable `iterationIndex` 추가(cyclic 회차 구분, DAG 는 null) |
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
