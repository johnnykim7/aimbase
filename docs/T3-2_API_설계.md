# T3-2. API 설계서

> 설계 버전: 3.1 | 최종 수정: 2026-03-31 | 관련 CR: CR-009, CR-010, CR-011, CR-012, CR-014, CR-015, CR-016
> DEF-001 반영: 모든 POST/PUT 엔드포인트에 요청 스키마(필수/선택 필드, 타입, 예시) 명시

> **프로젝트**: Aimbase
> **작성일**: 2026-03-10 (역설계)

---

## 공통 사항

### API 기본 규격
- **Base URL**: `/api/v1`
- **인증**: JWT Bearer Token (향후 완전 구현)
- **응답 래퍼**: `ApiResponse<T>` → `{success, data, error, pagination}`
- **페이지네이션**: `{page, size, totalElements, totalPages}`
- **에러 코드**: HTTP 상태 코드 (400, 401, 403, 404, 409, 500)
- **날짜 형식**: ISO 8601 (YYYY-MM-DDTHH:mm:ssZ)

### 공통 응답 구조
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "pagination": { "page": 0, "size": 20, "totalElements": 100, "totalPages": 5 }
}
```

---

## 채팅 (Chat)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/chat/completions` | LLM 채팅 완료 (동기/SSE 스트리밍, 구조화 출력) | 🔒 |

### 구조화된 출력 요청 (v2.5, CR-007)

`POST /chat/completions`의 `response_format` 파라미터로 구조화된 JSON 응답을 요청한다.

**요청 확장 필드**:
```json
{
  "model": "claude-sonnet-4-5",
  "messages": [{ "role": "user", "content": "직원 정보 추출해줘" }],
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "employee_form",
      "schema": {
        "type": "object",
        "properties": {
          "name": { "type": "string" },
          "department": { "type": "string" },
          "salary": { "type": "number" }
        },
        "required": ["name", "department"]
      }
    }
  }
}
```

**스키마 참조 방식** (등록된 스키마 재사용):
```json
{
  "response_format": {
    "type": "schema_ref",
    "schema_id": "employee-form",
    "version": "v1"
  }
}
```

**응답 (구조화)**:
```json
{
  "success": true,
  "data": {
    "id": "msg_xxx",
    "model": "claude-sonnet-4-5",
    "session_id": "uuid",
    "content": [{
      "type": "structured",
      "schema": "employee_form",
      "data": {
        "name": "홍길동",
        "department": "개발팀",
        "salary": 5000000
      }
    }],
    "usage": { "input_tokens": 150, "output_tokens": 80, "cost_usd": 0.001 }
  }
}
```

**response_format 미지정 시**: 기존 동작 (type: "text" 텍스트 응답)

---

## 연결 (Connections)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/connections` | 연결 목록 조회 (페이지네이션, type 필터) | 🔒 |
| POST | `/connections` | 연결 생성 | 🔒 |
| GET | `/connections/{id}` | 연결 상세 조회 | 🔒 |
| PUT | `/connections/{id}` | 연결 수정 | 🔒 |
| DELETE | `/connections/{id}` | 연결 삭제 | 🔒 |
| POST | `/connections/{id}/test` | 연결 헬스체크 | 🔒 |

### 연결 생성/수정 요청 스키마

`POST /connections`, `PUT /connections/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 연결 ID (고유 식별자) |
| name | String | ✅ | 연결 이름 |
| adapter | String | ✅ | 어댑터 유형 (openai, anthropic, ollama, postgresql, slack 등) |
| type | String | ✅ | 연결 분류 (llm, write, notify, realtime) |
| config | Map | 선택 | 어댑터별 설정 (JSONB) |

```json
{
  "id": "conn-anthropic-main",
  "name": "Claude Main",
  "adapter": "anthropic",
  "type": "llm",
  "config": {
    "api_key": "sk-ant-...",
    "model": "claude-sonnet-4-5",
    "max_tokens": 4096
  }
}
```

---

## 커넥션 그룹 (Connection Groups) [v3.5, CR-015]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/connection-groups` | 그룹 목록 조회 (adapter 필터) | 🔒 |
| POST | `/connection-groups` | 그룹 생성 | 🔒 |
| GET | `/connection-groups/{id}` | 그룹 상세 조회 (멤버 상태 포함) | 🔒 |
| PUT | `/connection-groups/{id}` | 그룹 수정 (멤버/전략 변경) | 🔒 |
| DELETE | `/connection-groups/{id}` | 그룹 삭제 | 🔒 |
| POST | `/connection-groups/{id}/test` | 그룹 전체 헬스체크 | 🔒 |

### 그룹 생성 요청
```json
{
  "id": "grp-anthropic-prod",
  "name": "Anthropic 프로덕션 풀",
  "adapter": "Claude (Anthropic)",
  "strategy": "PRIORITY",
  "members": [
    { "connection_id": "conn-anthropic-main", "priority": 1, "weight": 100 },
    { "connection_id": "conn-anthropic-backup", "priority": 2, "weight": 100 }
  ],
  "is_default": true
}
```

### 그룹 상세 응답 (멤버 상태 확장)
```json
{
  "success": true,
  "data": {
    "id": "grp-anthropic-prod",
    "name": "Anthropic 프로덕션 풀",
    "adapter": "Claude (Anthropic)",
    "strategy": "PRIORITY",
    "members": [
      {
        "connection_id": "conn-anthropic-main",
        "connection_name": "Anthropic Main",
        "priority": 1,
        "weight": 100,
        "status": "connected",
        "circuit_breaker_state": "CLOSED",
        "usage_count": 1520
      },
      {
        "connection_id": "conn-anthropic-backup",
        "connection_name": "Anthropic Backup",
        "priority": 2,
        "weight": 100,
        "status": "connected",
        "circuit_breaker_state": "CLOSED",
        "usage_count": 42
      }
    ],
    "is_default": true,
    "is_active": true
  }
}
```

### Chat API 확장 — connection_group_id 지원
`POST /chat/completions` 요청에 `connection_group_id` 파라미터 추가:
```json
{
  "model": "anthropic/claude-sonnet-4-5",
  "messages": [...],
  "connection_group_id": "grp-anthropic-prod"
}
```
- `connection_group_id` 지정 시 → 그룹 전략에 따라 커넥션 선택 + 커넥션 레벨 폴백
- `connection_id` 지정 시 → 기존 동작 (단일 커넥션, 폴백 없음)
- 둘 다 미지정 시 → 테넌트 기본 그룹(is_default=true) 사용, 없으면 ModelRouter

### 검증 규칙
- members 내 모든 connection_id는 동일 adapter 타입이어야 함 (크로스 프로바이더 불가)
- is_default=true 그룹은 adapter당 1개만 허용
- members 비어있으면 400 에러

---

## MCP 서버 (MCP Servers)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/mcp-servers` | MCP 서버 목록 조회 | 🔒 |
| POST | `/mcp-servers` | MCP 서버 등록 | 🔒 |
| GET | `/mcp-servers/{id}` | MCP 서버 상세 조회 | 🔒 |
| PUT | `/mcp-servers/{id}` | MCP 서버 수정 | 🔒 |
| DELETE | `/mcp-servers/{id}` | MCP 서버 삭제 | 🔒 |
| POST | `/mcp-servers/{id}/discover` | 도구 탐색 | 🔒 |
| POST | `/mcp-servers/{id}/disconnect` | 서버 연결 해제 | 🔒 |

### MCP 서버 등록/수정 요청 스키마

`POST /mcp-servers`, `PUT /mcp-servers/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | MCP 서버 ID |
| name | String | ✅ | 서버 이름 |
| transport | String | ✅ | 전송 유형 (stdio, sse, http) |
| config | Map | ✅ | 전송별 설정 (JSONB) |
| autoStart | Boolean | 선택 | 서버 기동 시 자동 연결 여부 |

```json
{
  "id": "mcp-rag-pipeline",
  "name": "RAG Pipeline",
  "transport": "sse",
  "config": { "url": "http://rag-pipeline:8000/sse" },
  "autoStart": true
}
```

---

## 스키마 (Schemas)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/schemas` | 스키마 목록 조회 | 🔒 |
| POST | `/schemas` | 스키마 생성 | 🔒 |
| GET | `/schemas/{id}/{version}` | 스키마 버전별 조회 | 🔒 |
| DELETE | `/schemas/{id}/{version}` | 스키마 삭제 | 🔒 |
| POST | `/schemas/{id}/{version}/validate` | 데이터 유효성 검증 | 🔒 |

### 스키마 생성 요청 스키마

`POST /schemas`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 스키마 ID |
| version | int | ✅ | 버전 번호 (**정수**, 문자열 아님) |
| domain | String | 선택 | 도메인 분류 |
| description | String | 선택 | 설명 |
| jsonSchema | Map | ✅ | JSON Schema 정의 (**필드명 주의: `schema`가 아님**) |

```json
{
  "id": "employee-form",
  "version": 1,
  "domain": "hr",
  "description": "직원 정보 스키마",
  "jsonSchema": {
    "type": "object",
    "properties": {
      "name": { "type": "string" },
      "department": { "type": "string" }
    },
    "required": ["name"]
  }
}
```

> ⚠️ **필드명 주의**: `schema` → `jsonSchema`, `version` 타입: 문자열(`"v1"`) → 정수(`1`)

---

## 정책 (Policies)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/policies` | 정책 목록 조회 (domain 필터) | 🔒 |
| POST | `/policies` | 정책 생성 | 🔒 |
| GET | `/policies/{id}` | 정책 상세 조회 | 🔒 |
| PUT | `/policies/{id}` | 정책 수정 | 🔒 |
| DELETE | `/policies/{id}` | 정책 삭제 | 🔒 |
| PATCH | `/policies/{id}/activate` | 정책 활성화/비활성화 (query: active) | 🔒 |
| POST | `/policies/simulate` | 정책 시뮬레이션 | 🔒 |

### 정책 생성/수정 요청 스키마

`POST /policies`, `PUT /policies/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 정책 ID |
| name | String | ✅ | 정책 이름 |
| domain | String | 선택 | 도메인 분류 |
| priority | Integer | 선택 | 우선순위 (높을수록 먼저 평가) |
| matchRules | Map | ✅ | 매칭 조건 (JSONB) |
| rules | List\<Map\> | ✅ | 정책 규칙 목록 |

```json
{
  "id": "pol-pii-guard",
  "name": "PII 보호 정책",
  "domain": "security",
  "priority": 100,
  "matchRules": {
    "intents": ["*"],
    "adapters": ["*"]
  },
  "rules": [
    {
      "action": "TRANSFORM",
      "condition": "true",
      "config": { "type": "pii_mask" }
    }
  ]
}
```

### 정책 시뮬레이션 요청

`POST /policies/simulate`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| intent | String | ✅ | 시뮬레이션할 인텐트 |
| adapter | String | 선택 | 어댑터 유형 |
| sessionId | String | 선택 | 세션 ID |

---

## 프롬프트 (Prompts)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/prompts` | 프롬프트 목록 조회 | 🔒 |
| POST | `/prompts` | 프롬프트 생성 | 🔒 |
| GET | `/prompts/{id}/{version}` | 프롬프트 버전별 조회 | 🔒 |
| PUT | `/prompts/{id}/{version}` | 프롬프트 수정 | 🔒 |
| DELETE | `/prompts/{id}/{version}` | 프롬프트 삭제 | 🔒 |
| POST | `/prompts/{id}/{version}/test` | 프롬프트 테스트 렌더링 | 🔒 |

### 프롬프트 생성/수정 요청 스키마

`POST /prompts`, `PUT /prompts/{id}/{version}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 프롬프트 ID |
| version | int | ✅ | 버전 번호 (정수) |
| domain | String | 선택 | 도메인 분류 |
| type | String | ✅ | 프롬프트 유형 (system, user, template 등) |
| template | String | ✅ | 프롬프트 내용 ({{변수}} 치환 지원) |
| variables | List\<Map\> | 선택 | 변수 정의 목록 |
| isActive | Boolean | 선택 | 활성 여부 |

```json
{
  "id": "prompt-summarize",
  "version": 1,
  "domain": "general",
  "type": "system",
  "template": "다음 내용을 {{language}}로 {{length}} 이내로 요약하세요.",
  "variables": [
    { "name": "language", "type": "string", "default": "한국어" },
    { "name": "length", "type": "string", "default": "3문장" }
  ],
  "isActive": true
}
```

---

## 라우팅 (Routing)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/routing` | 라우팅 설정 목록 조회 | 🔒 |
| GET | `/routing/active` | 활성 라우팅 규칙 조회 | 🔒 |
| POST | `/routing` | 라우팅 설정 생성 | 🔒 |
| GET | `/routing/{id}` | 라우팅 설정 상세 조회 | 🔒 |
| PUT | `/routing/{id}` | 라우팅 설정 수정 | 🔒 |
| DELETE | `/routing/{id}` | 라우팅 설정 삭제 | 🔒 |

### 라우팅 설정 생성/수정 요청 스키마

`POST /routing`, `PUT /routing/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 라우팅 설정 ID |
| strategy | String | ✅ | 라우팅 전략 (intent_based, cost_optimized, latency_optimized 등) |
| rules | List\<Map\> | ✅ | 라우팅 규칙 목록 |
| fallbackChain | List\<String\> | 선택 | 폴백 모델 체인 |

```json
{
  "id": "route-default",
  "strategy": "intent_based",
  "rules": [
    {
      "intent": "code_generation",
      "model": "anthropic/claude-sonnet-4-5",
      "priority": 1
    },
    {
      "intent": "*",
      "model": "openai/gpt-4o-mini",
      "priority": 10
    }
  ],
  "fallbackChain": ["anthropic/claude-sonnet-4-5", "openai/gpt-4o"]
}
```

---

## 워크플로우 (Workflows)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/workflows` | 워크플로우 목록 조회 (domain 필터) | 🔒 |
| POST | `/workflows` | 워크플로우 생성 (**v2.5: output_schema 지원**) | 🔒 |
| GET | `/workflows/{id}` | 워크플로우 상세 조회 | 🔒 |
| PUT | `/workflows/{id}` | 워크플로우 수정 | 🔒 |
| DELETE | `/workflows/{id}` | 워크플로우 삭제 | 🔒 |
| POST | `/workflows/{id}/run` | 워크플로우 실행 (비동기) | 🔒 |
| POST | `/workflows/runs/{runId}/approve` | 워크플로우 승인 처리 | 🔒 |
| GET | `/workflows/{id}/runs` | 실행 이력 조회 | 🔒 |
| GET | `/workflows/{id}/runs/{runId}` | 실행 상세 조회 | 🔒 |

### 워크플로우 생성/수정 요청 스키마

`POST /workflows`, `PUT /workflows/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 워크플로우 ID |
| name | String | ✅ | 워크플로우 이름 |
| domain | String | 선택 | 도메인 분류 |
| triggerConfig | Map | ✅ | 트리거 설정 (JSONB) |
| steps | List\<Map\> | ✅ | 스텝 정의 목록 |
| errorHandling | Map | 선택 | 에러 처리 설정 (retry 등) |
| outputSchema | Map | 선택 | 출력 스키마 (JSON Schema) |

```json
{
  "id": "wf-employee-extract",
  "name": "직원 정보 추출",
  "domain": "hr",
  "triggerConfig": { "type": "manual" },
  "steps": [
    {
      "id": "step-1",
      "name": "정보 추출",
      "type": "LLM_CALL",
      "config": { "model": "claude-sonnet-4-5", "prompt": "..." },
      "dependsOn": []
    }
  ],
  "errorHandling": { "retryMaxAttempts": 2, "retryDelayMs": 1000 },
  "outputSchema": { "type": "object", "properties": { "name": { "type": "string" } } }
}
```

### 워크플로우 승인 요청

`POST /workflows/runs/{runId}/approve`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| approved | boolean | ✅ | 승인 여부 (true/false) |
| reason | String | 선택 | 승인/거부 사유 |

### 워크플로우 출력 스키마 (v2.5, CR-007)

`POST /workflows` 및 `PUT /workflows/{id}` 요청에 `output_schema` 필드를 추가하여 설계 시점에 출력 포맷을 바인딩한다.

**요청 확장 필드**:
```json
{
  "id": "employee-extraction",
  "name": "직원 정보 추출",
  "steps": [ ... ],
  "output_schema": {
    "schema_id": "employee-form",
    "version": "v1"
  }
}
```

또는 인라인 정의:
```json
{
  "output_schema": {
    "inline": {
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "department": { "type": "string" }
      }
    }
  }
}
```

런타임 시 마지막 LLM_CALL 스텝에 `response_format`이 자동 주입된다.

### SUB_WORKFLOW 스텝 (v3.6, CR-016)

공용 워크플로우를 서브 워크플로우로 참조 실행하는 스텝. 부모 DAG에서 하나의 노드로 표현.

```json
{
  "id": "analyze",
  "name": "소스 분석",
  "type": "SUB_WORKFLOW",
  "config": {
    "workflow_id": "file-analysis",
    "input": {
      "zip_path": "{{input.zip_path}}",
      "prompt": "코드 리뷰해줘"
    }
  },
  "dependsOn": ["step-1"]
}
```

- `workflow_id`: Master DB `platform_workflows.id` 참조
- `input`: 서브 워크플로우 입력 (변수 치환 `{{...}}` 지원)
- 마지막 서브 스텝의 output이 이 노드의 output으로 반환

---

## 플랫폼 공용 워크플로우 (Platform Workflows, v3.6, CR-016)

Master DB에 저장되며 모든 테넌트가 사용 가능. `SUPER_ADMIN` 권한 필요.

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/platform/workflows` | 공용 워크플로우 목록 조회 (category 필터) | 🔒 SUPER_ADMIN |
| POST | `/platform/workflows` | 공용 워크플로우 등록 | 🔒 SUPER_ADMIN |
| GET | `/platform/workflows/{id}` | 공용 워크플로우 상세 조회 | 🔒 SUPER_ADMIN |
| PUT | `/platform/workflows/{id}` | 공용 워크플로우 수정 | 🔒 SUPER_ADMIN |
| DELETE | `/platform/workflows/{id}` | 공용 워크플로우 삭제 | 🔒 SUPER_ADMIN |
| POST | `/platform/workflows/{id}/run` | 공용 워크플로우 실행 (현재 테넌트 컨텍스트) | 🔒 SUPER_ADMIN |

### 공용 워크플로우 생성/수정 요청 스키마

`POST /platform/workflows`, `PUT /platform/workflows/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 워크플로우 ID |
| name | String | ✅ | 워크플로우 이름 |
| description | String | 선택 | 설명 |
| category | String | 선택 | 분류 (file_analysis, text_processing, data_transform) |
| triggerConfig | Map | ✅ | 트리거 설정 |
| steps | List\<Map\> | ✅ | 스텝 정의 목록 |
| errorHandling | Map | 선택 | 에러 처리 설정 |
| outputSchema | Map | 선택 | 출력 JSON Schema |
| inputSchema | Map | 선택 | 입력 파라미터 스키마 (호출 시 필요한 입력 정의) |

```json
{
  "id": "file-analysis",
  "name": "파일 분석",
  "description": "업로드된 파일을 Claude Code로 분석",
  "category": "file_analysis",
  "triggerConfig": { "type": "api" },
  "steps": [
    { "id": "s1", "type": "TOOL_CALL", "config": { "tool": "zip_extract", "input": { "zip_path": "{{input.zip_path}}" }}},
    { "id": "s2", "type": "TOOL_CALL", "dependsOn": ["s1"], "config": { "tool": "claude_code", "input": { "prompt": "{{input.prompt}}", "working_directory": "{{s1.structured_data.temp_path}}" }}},
    { "id": "s3", "type": "TOOL_CALL", "dependsOn": ["s2"], "config": { "tool": "temp_cleanup", "input": { "temp_path": "{{s1.structured_data.temp_path}}" }}}
  ],
  "inputSchema": {
    "type": "object",
    "properties": {
      "zip_path": { "type": "string", "description": "ZIP 파일 경로" },
      "prompt": { "type": "string", "description": "분석 지시" }
    },
    "required": ["zip_path", "prompt"]
  }
}
```

### 시드 공용 워크플로우

| ID | 이름 | 카테고리 | 설명 |
|----|------|---------|------|
| file-analysis | 파일 분석 | file_analysis | ZIP → Claude Code 범용 분석 (prompt로 목적 지정) |
| code-review | 코드 리뷰 | file_analysis | 코드 품질/보안/성능 리뷰 리포트 |
| doc-generation | 문서 생성 | file_analysis | 소스에서 API 명세/아키텍처 문서 역생성 |
| text-summarize | 텍스트 요약 | text_processing | 구조화 요약 (핵심/세부/결론) |
| text-translate | 텍스트 번역 | text_processing | 다국어 번역 |
| data-transform | 데이터 정제 | data_transform | 비정형 데이터 → 구조화 JSON |

---

## 지식소스 (Knowledge Sources)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/knowledge-sources` | 지식소스 목록 조회 (type 필터) | 🔒 |
| POST | `/knowledge-sources` | 지식소스 생성 | 🔒 |
| GET | `/knowledge-sources/{id}` | 지식소스 상세 조회 | 🔒 |
| PUT | `/knowledge-sources/{id}` | 지식소스 수정 | 🔒 |
| DELETE | `/knowledge-sources/{id}` | 지식소스 삭제 | 🔒 |
| POST | `/knowledge-sources/{id}/sync` | 인제스션 실행 | 🔒 |
| POST | `/knowledge-sources/{id}/upload` | 파일 업로드 (multipart, v3.0 CR-010) | 🔒 |
| POST | `/knowledge-sources/search` | 벡터 검색 | 🔒 |
| GET | `/knowledge-sources/{id}/ingestion-logs` | 인제스션 로그 조회 | 🔒 |

### 지식소스 생성/수정 요청 스키마

`POST /knowledge-sources`, `PUT /knowledge-sources/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 지식소스 ID |
| name | String | ✅ | 지식소스 이름 |
| type | String | ✅ | 소스 유형 (text, url, file, database) |
| config | Map | ✅ | 소스별 설정 (JSONB) |
| chunkingConfig | Map | 선택 | 청킹 설정 |
| embeddingConfig | Map | 선택 | 임베딩 설정 |
| syncConfig | Map | 선택 | 동기화 설정 |

```json
{
  "id": "ks-product-manual",
  "name": "제품 매뉴얼",
  "type": "text",
  "config": { "content": "매뉴얼 내용..." },
  "chunkingConfig": { "strategy": "semantic", "max_chunk_size": 500 },
  "embeddingConfig": { "model": "text-embedding-3-small" }
}
```

### 벡터 검색 요청

`POST /knowledge-sources/search`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| query | String | ✅ | 검색 쿼리 |
| sourceId | String | 선택 | 특정 소스 한정 검색 |
| topK | Integer | 선택 | 반환 결과 수 (기본: 5) |

### 파일 업로드 (v3.0, CR-010)

`POST /knowledge-sources/{id}/upload` — 지식소스에 파일을 업로드하고 자동 인제스션을 트리거한다.

**요청**: `Content-Type: multipart/form-data`
```
file: (binary) — PDF, DOCX, XLSX, PPTX, CSV, TXT, MD, HTML (최대 50MB)
```

**응답**:
```json
{
  "success": true,
  "data": {
    "source_id": "ks-001",
    "file_path": "/data/aimbase/tenant-1/knowledge/uuid_document.pdf",
    "file_size": 2048576,
    "status": "syncing"
  }
}
```

---

## 검색 설정 (Retrieval Config)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/retrieval-config` | 검색 설정 목록 조회 | 🔒 |
| POST | `/retrieval-config` | 검색 설정 생성 | 🔒 |
| GET | `/retrieval-config/{id}` | 검색 설정 상세 조회 | 🔒 |
| PUT | `/retrieval-config/{id}` | 검색 설정 수정 | 🔒 |
| DELETE | `/retrieval-config/{id}` | 검색 설정 삭제 | 🔒 |

### 검색 설정 생성/수정 요청 스키마

`POST /retrieval-config`, `PUT /retrieval-config/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 검색 설정 ID |
| name | String | ✅ | 설정 이름 |
| topK | Integer | 선택 | 상위 K개 결과 반환 (기본: 5) |
| similarityThreshold | BigDecimal | 선택 | 유사도 임계값 (0.0~1.0) |
| maxContextTokens | Integer | 선택 | 최대 컨텍스트 토큰 수 |
| searchType | String | 선택 | 검색 유형 (vector, hybrid, keyword) |
| sourceFilters | Map | 선택 | 소스 필터링 조건 |
| queryProcessing | Map | 선택 | 쿼리 전처리 설정 |
| contextTemplate | String | 선택 | 컨텍스트 템플릿 |

```json
{
  "id": "rc-default",
  "name": "기본 검색 설정",
  "topK": 5,
  "similarityThreshold": 0.7,
  "maxContextTokens": 4000,
  "searchType": "hybrid"
}
```

---

## 관리 (Admin)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/admin/dashboard` | 대시보드 통계 | 🔒 |
| GET | `/admin/action-logs` | 액션 로그 조회 | 🔒 |
| GET | `/admin/audit-logs` | 감사 로그 조회 | 🔒 |
| GET | `/admin/usage` | 사용량 통계 조회 | 🔒 |
| GET | `/admin/approvals` | 승인 대기 목록 | 🔒 |
| POST | `/admin/approvals/{id}/approve` | 승인 처리 | 🔒 |
| POST | `/admin/approvals/{id}/reject` | 거부 처리 | 🔒 |
| GET | `/admin/traces` | LLM 트레이스 조회 (v3.0 CR-010) | 🔒 Admin |
| GET | `/admin/cost-breakdown` | 모델별 비용 분석 (v3.0 CR-010) | 🔒 Admin |
| GET | `/admin/cost-trend` | 일별 비용 추세 (v3.0 CR-010) | 🔒 Admin |

---

## 사용자 (Users)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/users` | 사용자 목록 조회 | 🔒 |
| POST | `/users` | 사용자 생성 | 🔒 |
| GET | `/users/{id}` | 사용자 상세 조회 | 🔒 |
| PUT | `/users/{id}` | 사용자 수정 | 🔒 |
| DELETE | `/users/{id}` | 사용자 비활성화 | 🔒 |
| POST | `/users/{id}/api-key` | API 키 재생성 | 🔒 |

### 사용자 생성/수정 요청 스키마

`POST /users`, `PUT /users/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| email | String | ✅ | 이메일 (유효성 검증, @Email) |
| name | String | 선택 | 사용자 이름 |
| roleId | String | 선택 | 역할 ID |

> ⚠️ **password 필드 없음**: 사용자 생성 시 비밀번호는 설정되지 않음. 테넌트 온보딩 시 초기 비밀번호만 설정됨.

```json
{
  "email": "user@example.com",
  "name": "홍길동",
  "roleId": "operator"
}
```

### API 키 재생성 응답

`POST /users/{id}/api-key`

```json
{
  "success": true,
  "data": {
    "apiKey": "plat-f080adfd035b45728fdf148c4f171d64",
    "note": "이 키는 다시 조회할 수 없습니다. 안전한 곳에 저장하세요."
  }
}
```

---

## 역할 (Roles)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/roles` | 역할 목록 조회 | 🔒 |
| POST | `/roles` | 역할 생성 | 🔒 |
| GET | `/roles/{id}` | 역할 상세 조회 | 🔒 |
| PUT | `/roles/{id}` | 역할 수정 | 🔒 |
| DELETE | `/roles/{id}` | 역할 삭제 | 🔒 |

### 역할 생성/수정 요청 스키마

`POST /roles`, `PUT /roles/{id}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 역할 ID |
| name | String | ✅ | 역할 이름 |
| permissions | Map | ✅ | 권한 맵 (JSONB) |

```json
{
  "id": "operator",
  "name": "운영자",
  "permissions": {
    "chat:read": true,
    "chat:write": true,
    "connections:read": true,
    "connections:write": false,
    "policies:read": true,
    "admin:read": false,
    "admin:write": false
  }
}
```

---

## 모니터링 (Monitoring)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/models` | 모델 목록 조회 | 🔒 |
| GET | `/routing` | 활성 라우팅 설정 조회 | 🔒 |

---

## 인증 (Auth) [v3.0, CR-010]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/auth/login` | JWT 로그인 (email + password) | 🔓 Public |
| POST | `/auth/refresh` | 토큰 갱신 (refreshToken) | 🔓 Public |

> ⚠️ **X-Tenant-Id 헤더 필수**: 로그인 시 `X-Tenant-Id` 헤더로 대상 테넌트를 지정해야 함 (users 테이블이 Tenant DB에 있으므로)

### 로그인

`POST /auth/login`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| email | String | ✅ | 이메일 (@Email 검증) |
| password | String | ✅ | 비밀번호 |

**요청**:
```json
{ "email": "admin@dev.local", "password": "admin1234" }
```

**응답**:
```json
{
  "success": true,
  "data": {
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "token_type": "Bearer",
    "user_id": "admin-tenant_dev",
    "email": "admin@dev.local",
    "role": "super_admin"
  }
}
```

### 토큰 갱신

`POST /auth/refresh`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| refreshToken | String | ✅ | 리프레시 토큰 (**camelCase**, snake_case 아님) |

**요청**:
```json
{ "refreshToken": "eyJ..." }
```

### API Key 인증

`X-API-Key` 헤더로 인증. JWT와 API Key 중 하나로 인증 가능.

---

## 대화 히스토리 (Conversations) [v3.0, CR-010]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/conversations` | 대화 세션 목록 조회 (페이징, 검색) | 🔒 |
| GET | `/conversations/{sessionId}` | 대화 상세 (메시지 포함) | 🔒 |
| DELETE | `/conversations/{sessionId}` | 대화 삭제 (Redis + DB) | 🔒 |

---

## 캐시 관리 (Cache) [v3.2, CR-012]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/cache/stats` | 캐시 히트율/크기/항목 수 통계 | 🔒 |
| DELETE | `/cache` | 전체 캐시 무효화 | 🔒 |
| DELETE | `/cache/{hash}` | 특정 캐시 항목 무효화 | 🔒 |

### 캐시 통계

**GET /api/v1/cache/stats**

**응답**:
```json
{
  "success": true,
  "data": {
    "total_entries": 1250,
    "hit_count": 8420,
    "miss_count": 3180,
    "hit_rate": 0.726,
    "total_tokens_saved": 524000,
    "estimated_cost_saved_usd": 1.57
  }
}
```

---

## 메모리 관리 (Memory) [v3.2, CR-012]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/memories` | 메모리 조회 (session_id, layer 필터) | 🔒 |
| POST | `/memories` | 메모리 항목 추가 | 🔒 |
| DELETE | `/memories/{id}` | 메모리 항목 삭제 | 🔒 |
| DELETE | `/memories` | 세션 메모리 전체 삭제 (query: session_id) | 🔒 |
| GET | `/memories/profile` | 사용자 프로필 메모리 조회 (query: user_id) | 🔒 |

### 메모리 조회

**GET /api/v1/memories?session_id=xxx&layer=SHORT_TERM**

**응답**:
```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "session_id": "sess-001",
      "memory_type": "SHORT_TERM",
      "content": "사용자가 Python 코드 리뷰를 요청함",
      "created_at": "2026-03-29T10:00:00Z"
    }
  ]
}
```

### 메모리 추가

`POST /api/v1/memories`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| sessionId | String | 선택 | 세션 ID |
| userId | String | 선택 | 사용자 ID |
| layer | String | 선택 | 메모리 계층 (SYSTEM_RULES, LONG_TERM, SHORT_TERM, USER_PROFILE) |
| content | String | 선택 | 메모리 내용 |

**요청**:
```json
{
  "sessionId": "sess-001",
  "userId": "user-001",
  "layer": "LONG_TERM",
  "content": "선호 언어: Python, 코드 스타일: PEP 8 준수"
}
```

---

## 플랫폼 관리 (Platform - Super Admin)

### 테넌트 관리

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/platform/tenants` | 테넌트 목록 조회 (status 필터) | 🔒 Super Admin |
| POST | `/platform/tenants` | 테넌트 생성 (자동 프로비저닝) | 🔒 Super Admin |
| GET | `/platform/tenants/{id}` | 테넌트 상세 조회 (구독+사용량 포함) | 🔒 Super Admin |
| PUT | `/platform/tenants/{id}` | 테넌트 수정 | 🔒 Super Admin |
| POST | `/platform/tenants/{id}/suspend` | 테넌트 일시정지 | 🔒 Super Admin |
| POST | `/platform/tenants/{id}/activate` | 테넌트 재활성화 | 🔒 Super Admin |
| DELETE | `/platform/tenants/{id}` | 테넌트 삭제 (디프로비저닝) | 🔒 Super Admin |
| GET | `/platform/subscriptions` | 구독 목록 조회 | 🔒 Super Admin |
| PUT | `/platform/subscriptions/{tenantId}` | 구독 수정 (쿼터 변경) | 🔒 Super Admin |
| GET | `/platform/usage` | 플랫폼 사용량 대시보드 | 🔒 Super Admin |

#### 테넌트 생성 요청 스키마

`POST /platform/tenants`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| tenantId | String | ✅ | 테넌트 ID |
| appId | String | 선택 | 소속 App ID (3계층 구조 시) |
| name | String | ✅ | 테넌트 이름 |
| adminEmail | String | ✅ | 관리자 이메일 |
| initialAdminPassword | String | ✅ | 초기 관리자 비밀번호 |
| plan | String | 선택 | 구독 플랜 (free, starter, pro, enterprise) |

```json
{
  "tenantId": "my-tenant",
  "name": "My Tenant",
  "adminEmail": "admin@test.local",
  "initialAdminPassword": "secure1234",
  "plan": "starter"
}
```

#### 구독 수정 요청 스키마

`PUT /platform/subscriptions/{tenantId}`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| plan | String | 선택 | 구독 플랜 |
| llmMonthlyTokenQuota | Long | 선택 | 월 토큰 쿼터 |
| maxConnections | Integer | 선택 | 최대 연결 수 |
| maxKnowledgeSources | Integer | 선택 | 최대 지식소스 수 |
| maxWorkflows | Integer | 선택 | 최대 워크플로우 수 |
| storageGb | Integer | 선택 | 스토리지 (GB) |

### App 관리 [v3.0, CR-014]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/platform/apps` | App 목록 조회 (status 필터) | 🔒 Super Admin |
| POST | `/platform/apps` | App 생성 (자동 프로비저닝) | 🔒 Super Admin |
| GET | `/platform/apps/{appId}` | App 상세 조회 | 🔒 Super Admin |
| PUT | `/platform/apps/{appId}` | App 수정 | 🔒 Super Admin |
| POST | `/platform/apps/{appId}/suspend` | App 일시정지 (하위 테넌트 전체) | 🔒 Super Admin |
| POST | `/platform/apps/{appId}/activate` | App 활성화 | 🔒 Super Admin |
| DELETE | `/platform/apps/{appId}` | App 삭제 (DB 포함) | 🔒 Super Admin |
| GET | `/platform/apps/{appId}/tenants` | App 하위 테넌트 목록 | 🔒 Super Admin |

#### App 생성 요청 스키마

`POST /platform/apps`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| appId | String | ✅ | App ID |
| name | String | ✅ | App 이름 |
| description | String | 선택 | 설명 |
| ownerEmail | String | ✅ | 소유자 이메일 |
| ownerPassword | String | ✅ | 소유자 비밀번호 |
| maxTenants | Integer | 선택 | 최대 테넌트 수 |

### App Admin 셀프서비스 API [v3.0, CR-014]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/apps/{appId}/tenants` | App 하위 테넌트 목록 | 🔒 App Admin |
| POST | `/apps/{appId}/tenants` | 테넌트 셀프서비스 생성 | 🔒 App Admin |
| GET | `/apps/{appId}/tenants/{tenantId}` | 테넌트 상세 | 🔒 App Admin |
| PUT | `/apps/{appId}/tenants/{tenantId}` | 테넌트 수정 | 🔒 App Admin |
| POST | `/apps/{appId}/tenants/{tenantId}/suspend` | 테넌트 정지 | 🔒 App Admin |
| POST | `/apps/{appId}/tenants/{tenantId}/activate` | 테넌트 활성화 | 🔒 App Admin |
| DELETE | `/apps/{appId}/tenants/{tenantId}` | 테넌트 삭제 | 🔒 App Admin |

### 에이전트 계정 풀 [v3.0, CR-015]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/platform/agent-accounts` | 전체 계정 + 풀 상태 조회 | 🔒 Super Admin |
| POST | `/platform/agent-accounts` | 계정 등록 | 🔒 Super Admin |
| GET | `/platform/agent-accounts/{id}` | 계정 상세 조회 | 🔒 Super Admin |
| PUT | `/platform/agent-accounts/{id}` | 계정 수정 | 🔒 Super Admin |
| DELETE | `/platform/agent-accounts/{id}` | 계정 삭제 | 🔒 Super Admin |
| POST | `/platform/agent-accounts/{id}/test` | 사이드카 연결 테스트 | 🔒 Super Admin |
| POST | `/platform/agent-accounts/{id}/upload-token` | OAuth 토큰 업로드 | 🔒 Super Admin |
| POST | `/platform/agent-accounts/{id}/reset-circuit` | 서킷 브레이커 리셋 | 🔒 Super Admin |
| GET | `/platform/agent-accounts/assignments` | 할당 목록 조회 | 🔒 Super Admin |
| POST | `/platform/agent-accounts/assignments` | 할당 생성 | 🔒 Super Admin |
| DELETE | `/platform/agent-accounts/assignments/{id}` | 할당 삭제 | 🔒 Super Admin |

### 평가 API

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/evaluations/status` | Evaluation MCP 가용 상태 | 🔒 |
| POST | `/evaluations/rag` | RAG 품질 평가 | 🔒 |
| POST | `/evaluations/llm-output` | LLM 출력 평가 | 🔒 |
| POST | `/evaluations/prompt-comparison` | 프롬프트 A/B 비교 | 🔒 |

### 기타 API

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/extensions` | 로드된 Extension 목록 | 🔒 |
| POST | `/actuator/tool-test/{toolName}` | 도구 직접 실행 테스트 | 🔒 |
| GET | `/actuator/tool-test/list` | 등록된 도구 목록 | 🔒 |

---

## Python MCP Server API (v2.0, CR-002)

> MCP 프로토콜 기반 도구(Tool) 정의. Spring MCP Client에서 직접 호출 (LLM 경유 불필요).
> Transport: stdio 또는 SSE (Docker 내부 통신)

### MCP Server 1: RAG Pipeline

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `ingest_document` | 문서 파싱(Unstructured) → 시맨틱 청킹 → 임베딩 → pgvector 저장 | PRD-052, PY-001, PY-002 |
| `search_hybrid` | 하이브리드 검색(BM25 + 벡터) + 리랭킹 | PRD-053, PY-003, PY-004 |
| `embed_texts` | 텍스트 배열을 임베딩 벡터로 변환 | PY-002 |
| `chunk_document` | 문서를 시맨틱 청크로 분할 (인제스션 미수행) | PY-001 |
| `rerank_results` | 검색 결과를 cross-encoder로 리랭킹 | PY-003 |
| `transform_query` | 쿼리 변환 (HyDE, Multi-Query) | PY-005 |
| `finetune_embeddings` | 도메인 특화 임베딩 파인튜닝 | PY-012 |
| `parse_document` | 문서 파싱 (PDF/DOCX/PPTX 등, v3.0 CR-009) | PY-013 |
| `self_rag_search` | Self-RAG 자동 개선 루프 (v3.0 CR-009) | PY-014 |
| `compress_context` | 컨텍스트 압축 (v3.0 CR-009) | PY-015 |
| `embed_multimodal` | 멀티모달 임베딩 CLIP (v3.0 CR-009) | PY-016 |
| `scrape_url` | 웹 스크래핑 강화 (v3.0 CR-009) | PY-017 |

### MCP Server 2: Evaluation

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `evaluate_rag` | RAG 응답 품질 평가 (faithfulness, relevancy, context precision/recall) | PY-006 |
| `evaluate_llm_output` | LLM 출력 평가 (hallucination, toxicity, bias) | PY-007 |
| `compare_prompts` | 프롬프트 A/B 비교 회귀 테스트 | PY-008 |
| `generate_benchmark` | RAG 벤치마크 Q&A 자동 생성 (v3.0 CR-009) | PY-020 |
| `detect_embedding_drift` | 임베딩 드리프트 감지 (v3.0 CR-009) | PY-021 |

### MCP Server 3: Safety

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `detect_pii` | PII 탐지 (한국어 포함 다국어) | PY-009 |
| `mask_pii` | PII 마스킹 처리 | PY-009 |
| `validate_output` | 출력 안전성/포맷 가드레일 검증 | PY-010 |

Safety MCP Server에 PY-018(한국어 NER 강화)은 기존 `detect_pii` / `mask_pii` 도구에 엔티티 타입 확장으로 반영 (별도 도구 불필요).
PY-019(독성 분류 강화)는 기존 `validate_output` 도구 내부 로직 고도화로 반영.

### MCP Server 4: Agent

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `run_reasoning_chain` | 고급 추론 체인 실행 (reflection, plan_and_execute) | PY-011 |

---

## ClaudeCodeTool (v2.3, CR-011)

### ClaudeCodeTool API 확장 [CR-011]

#### 에러 패턴 관리 (Master DB, Platform 전용)

**GET /api/v1/platform/claude-code/error-patterns**
- 설명: 에러 패턴 목록 조회
- 응답: `[{id, pattern, error_type, action, priority, created_at}]`

**POST /api/v1/platform/claude-code/error-patterns**
- 설명: 에러 패턴 등록
- 요청: `{pattern, error_type, action, priority}`
- 응답: `{id, pattern, error_type, action, priority}`

**DELETE /api/v1/platform/claude-code/error-patterns/{id}**
- 설명: 에러 패턴 삭제

#### 서킷 브레이커 상태 조회

**GET /api/v1/platform/claude-code/circuit-status**
- 설명: 서킷 브레이커 현재 상태 조회
- 응답: `{state, consecutive_failures, last_failure_at, last_success_at, open_until}`

**POST /api/v1/platform/claude-code/circuit-reset**
- 설명: 서킷 브레이커 수동 리셋 (OPEN → CLOSED)

---

## FlowGuard Agent 범용 도구 프로토콜 (CR-017)

> FlowGuard Agent(flowguard-agent.jar)의 WebSocket ASSIGN_TASK 프로토콜 확장.
> Playwright(기존)에 더해 범용 도구를 `taskType="tool"`로 호출한다.

### 설정 파일 (agent-config.json)

```json
{
  "allowedPaths": ["~/Documents", "~/projects"],
  "allowedCommands": ["npm", "npx", "gradle", "./gradlew", "mvn", "python", "docker"],
  "shellTimeout": 120,
  "claudeTimeout": 300,
  "claudeConfigDir": "~/.claude"
}
```

### WebSocket 프로토콜 확장

기존 ASSIGN_TASK에 `taskType` 필드 추가:

**Playwright 작업 (기존, 호환 유지)**
```json
{
  "type": "ASSIGN_TASK",
  "taskId": "task-001",
  "payload": {
    "taskType": "playwright",
    "stepSnapshot": { "action": { "url": "...", "steps": [...] } },
    "timeout": 60000
  }
}
```

**범용 도구 작업 (신규)**
```json
{
  "type": "ASSIGN_TASK",
  "taskId": "task-002",
  "payload": {
    "taskType": "tool",
    "toolName": "claude_execute",
    "toolParams": {
      "project_path": "/home/user/projects/my-app",
      "prompt": "이 프로젝트의 아키텍처를 분석해줘"
    }
  }
}
```

### 도구 목록

| 도구 | 설명 | 주요 파라미터 |
|------|------|-------------|
| `claude_execute` | Claude CLI 실행 | project_path, prompt, config_dir, model, max_turns |
| `file_read` | 파일 읽기 | path, offset, limit |
| `file_write` | 파일 쓰기 | path, content |
| `file_list` | 디렉토리 목록 | path, pattern, max_results |
| `file_search` | 파일 내용 검색 | path, pattern, glob, max_results |
| `docker_ps` | 컨테이너 목록 | all, filter |
| `docker_logs` | 컨테이너 로그 | container, tail, since |
| `docker_exec` | 컨테이너 명령 | container, command |
| `git_status` | Git 상태 | repo_path |
| `git_diff` | Git diff | repo_path, ref, path |
| `git_log` | Git 이력 | repo_path, count, path |
| `shell_exec` | 셸 명령 (화이트리스트) | command, args, cwd |

### 응답 형식 (TASK_RESULT)

```json
{
  "type": "TASK_RESULT",
  "agentId": "agent-001",
  "taskId": "task-002",
  "payload": {
    "stdout": "...",
    "stderr": "...",
    "exit_code": 0
  }
}
```

에러 시:
```json
{
  "payload": {
    "error": "PathNotAllowedException",
    "message": "허용되지 않은 경로: /etc/passwd"
  }
}
```

---

## 작성 가이드

- **경로**: kebab-case 사용 (e.g., `/knowledge-sources`, `/mcp-servers`)
- **CRUD 패턴**: GET / → POST / → GET /{id} → PUT /{id} → DELETE /{id}
- **🔒 표시**: 인증 필요 엔드포인트
- **상세 스키마**: 모든 POST/PUT 엔드포인트에 필수/선택 필드, 타입, 예시 JSON을 본 문서에 명시 (v3.0 DEF-001 반영)
- **Swagger**: `/swagger-ui.html`에서 자동 생성된 API 문서 확인 가능 (필드 validation 어노테이션 포함)
