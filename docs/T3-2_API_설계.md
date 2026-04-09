# T3-2. API 설계서

> 설계 버전: 6.3 | 최종 수정: 2026-04-07 | 관련 CR: CR-009, CR-010, CR-011, CR-012, CR-014, CR-015, CR-016, CR-017, CR-018, CR-019, CR-021, CR-025, CR-029, CR-031, CR-032, CR-033, CR-035, CR-036, CR-038
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
| adapter | String | ✅ | 어댑터 유형 (openai, anthropic, ollama, openai_compatible, bedrock, vertex_ai, postgresql, slack 등) |
| type | String | ✅ | 연결 분류 (llm, write, notify, realtime) |
| config | Map | 선택 | 어댑터별 설정 (JSONB) — 아래 예시 참조 |

**adapter별 config 스키마 [v5.0, CR-031/CR-032]:**

```json
// anthropic (기존 + CR-031 Adaptive Thinking)
{
  "id": "conn-anthropic-main",
  "name": "Claude Main",
  "adapter": "anthropic",
  "type": "llm",
  "config": {
    "apiKey": "sk-ant-...",
    "model": "anthropic/claude-sonnet-4-5",
    "max_tokens": 4096,
    "thinking_mode": "ADAPTIVE",
    "thinking_budget_tokens": 10000
  }
}

// openai_compatible (CR-032) — DeepSeek, Groq, Mistral, LM Studio 등
{
  "id": "conn-deepseek",
  "name": "DeepSeek Chat",
  "adapter": "openai_compatible",
  "type": "llm",
  "config": {
    "base_url": "https://api.deepseek.com/v1",
    "apiKey": "sk-...",
    "model": "deepseek-chat"
  }
}

// bedrock (CR-032)
{
  "id": "conn-bedrock-claude",
  "name": "Bedrock Claude",
  "adapter": "bedrock",
  "type": "llm",
  "config": {
    "aws_region": "us-east-1",
    "aws_access_key_id": "AKIA...",
    "aws_secret_access_key": "...",
    "model_id": "anthropic.claude-sonnet-4-5-20250514-v1:0"
  }
}

// vertex_ai (CR-032)
{
  "id": "conn-vertex-gemini",
  "name": "Vertex Gemini",
  "adapter": "vertex_ai",
  "type": "llm",
  "config": {
    "project_id": "my-gcp-project",
    "location": "us-central1",
    "service_account_key": "{...JSON...}",
    "model_id": "gemini-2.0-flash"
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
| POST | `/knowledge-sources/{id}/ingest-text` | 텍스트 직접 인제스션 (v3.0 CR-011) | 🔒 |
| DELETE | `/knowledge-sources/{id}/documents/{documentId}` | 개별 문서 삭제 (v3.0 CR-011) | 🔒 |
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
| PATCH | `/users/{id}/activate` | 사용자 활성화/비활성화 토글 (query: active) | 🔒 |
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
| POST | `/auth/logout` | 로그아웃 (토큰 무효화) | 🔒 |
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
| GET | `/conversations/{sessionId}/meta` | 세션 메타데이터 조회 [v4.0, CR-029] | 🔒 |
| PUT | `/conversations/{sessionId}/meta` | 세션 메타데이터 수정 [v4.0, CR-029] | 🔒 |

### 세션 메타데이터 조회 [v4.0, CR-029]

**GET /api/v1/conversations/{sessionId}/meta**

**응답**:
```json
{
  "success": true,
  "data": {
    "session_id": "sess-001",
    "scope_type": "agent",
    "runtime_kind": "sidecar",
    "workspace_ref": "/home/user/project",
    "persistent_session": true,
    "summary_version": 3,
    "context_recipe_id": "recipe-code-review",
    "app_id": "chatpilot",
    "project_id": "proj-001",
    "parent_session_id": null,
    "last_tool_chain": [
      { "tool": "claude_code", "success": true, "duration_ms": 1200 }
    ]
  }
}
```

### 세션 메타데이터 수정 [v4.0, CR-029]

`PUT /api/v1/conversations/{sessionId}/meta`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| scopeType | String | 선택 | 세션 유형 (chat, agent, workflow) |
| runtimeKind | String | 선택 | 런타임 종류 (direct, sidecar, mcp) |
| workspaceRef | String | 선택 | 작업 디렉토리 경로 |
| persistentSession | Boolean | 선택 | 영속 세션 여부 |
| contextRecipeId | String | 선택 | 컨텍스트 레시피 ID |
| appId | String | 선택 | 도메인 앱 ID |
| projectId | String | 선택 | 프로젝트 ID |
| parentSessionId | String | 선택 | 부모 세션 ID |

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
| POST | `/platform/agent-accounts/{id}/extract-token` | OAuth 토큰 추출 (사이드카) | 🔒 Super Admin |
| POST | `/platform/agent-accounts/{id}/deploy-token` | OAuth 토큰 배포 (사이드카) | 🔒 Super Admin |
| GET | `/platform/agent-accounts/{id}/token` | 인증 토큰 조회 | 🔒 Super Admin |
| POST | `/platform/agent-accounts/{id}/save-token` | 인증 토큰 저장 | 🔒 Super Admin |
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
| POST | `/evaluations/rag-quality` | RAGAS 배치 평가 실행 (v3.0 CR-011) | 🔒 |
| GET | `/evaluations/rag-quality/{id}` | RAGAS 평가 결과 조회 | 🔒 |
| GET | `/evaluations/rag-quality` | 지식소스별 평가 이력 조회 (query: sourceId) | 🔒 |

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

### 플랫폼 설정 관리 [CR-040]

**GET /api/v1/platform/settings**
- 설명: 런타임 설정 전체 조회 (카테고리별 그룹핑)
- 쿼리 파라미터: `category` (선택, orchestrator/session/compaction)
- 응답: `{"orchestrator": [{"key": "...", "value": "...", "description": "...", "default_value": "..."}], "session": [...], "compaction": [...]}`

**PUT /api/v1/platform/settings**
- 설명: 런타임 설정 수정 (단건/다건)
- 요청: `{"settings": [{"key": "orchestrator.max-tool-iterations", "value": "50"}]}`
- 응답: `{"updated": 1, "settings": [...]}`
- 제약: 값 유효성 검증 (숫자 범위, 퍼센트 0~100). 변경 시 감사 로그 + 캐시 무효화.

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

## 문서 생성 (Documents) [v3.6, CR-018/019]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/documents/generate` | LLM 기반 문서 생성 (파일 다운로드) | 🔒 |
| POST | `/documents/generate-json` | LLM 기반 문서 생성 (JSON 응답) | 🔒 |
| GET | `/documents/formats` | 지원 출력 형식 목록 조회 | 🔒 |
| POST | `/documents/templates` | 문서 템플릿 저장 | 🔒 |
| GET | `/documents/templates` | 템플릿 목록 조회 | 🔒 |
| GET | `/documents/templates/{templateId}` | 템플릿 상세 조회 | 🔒 |
| DELETE | `/documents/templates/{templateId}` | 템플릿 삭제 | 🔒 |
| POST | `/documents/templates/{templateId}/render` | 템플릿 렌더링 (파일 다운로드) | 🔒 |
| POST | `/documents/templates/{templateId}/render-json` | 템플릿 렌더링 (JSON 응답) | 🔒 |
| POST | `/documents/templates/upload` | 템플릿 파일 업로드 | 🔒 |
| POST | `/documents/schema/generate` | 스키마 기반 문서 생성 (파일 다운로드) | 🔒 |
| POST | `/documents/schema/generate-json` | 스키마 기반 문서 생성 (JSON 응답) | 🔒 |
| POST | `/documents/schema/validate` | 문서 스키마 유효성 검증 | 🔒 |
| GET | `/documents/themes` | 문서 테마 목록 조회 | 🔒 |

### 문서 생성 요청

`POST /documents/generate-json`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| prompt | String | ✅ | 생성 지시 프롬프트 |
| format | String | ✅ | 출력 형식 (pdf, docx, md, html) |
| model | String | 선택 | LLM 모델 |
| templateId | String | 선택 | 템플릿 ID (지정 시 템플릿 기반 생성) |
| variables | Map | 선택 | 템플릿 변수 값 |

### 템플릿 저장 요청

`POST /documents/templates`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 템플릿 ID |
| name | String | ✅ | 템플릿명 |
| description | String | 선택 | 설명 |
| format | String | ✅ | 출력 형식 (pdf/docx/md/html) |
| templateType | String | ✅ | 타입 (code/file) |
| codeTemplate | String | 선택 | 코드 기반 템플릿 내용 |
| variables | List\<Map\> | ✅ | 변수 정의 [{name, type, required, default}] |
| tags | List\<String\> | 선택 | 태그 |

---

## 프로젝트 (Projects) [v3.6, CR-021]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/projects` | 프로젝트 목록 조회 | 🔒 |
| POST | `/projects` | 프로젝트 생성 | 🔒 |
| GET | `/projects/{id}` | 프로젝트 상세 조회 (멤버 + 리소스 포함) | 🔒 |
| PUT | `/projects/{id}` | 프로젝트 수정 | 🔒 |
| DELETE | `/projects/{id}` | 프로젝트 삭제 | 🔒 |
| GET | `/projects/{id}/members` | 멤버 목록 조회 | 🔒 |
| POST | `/projects/{id}/members` | 멤버 추가 | 🔒 |
| PUT | `/projects/{id}/members/{userId}` | 멤버 역할 변경 | 🔒 |
| DELETE | `/projects/{id}/members/{userId}` | 멤버 제거 | 🔒 |
| GET | `/projects/{id}/resources` | 리소스 할당 목록 조회 | 🔒 |
| POST | `/projects/{id}/resources` | 리소스 할당 | 🔒 |
| DELETE | `/projects/{id}/resources/{resourceType}/{resourceId}` | 리소스 해제 | 🔒 |

### 프로젝트 생성 요청

`POST /projects`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | ✅ | 프로젝트 ID |
| name | String | ✅ | 프로젝트명 |
| description | String | 선택 | 설명 |

### 멤버 추가 요청

`POST /projects/{id}/members`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| userId | String | ✅ | 사용자 ID |
| role | String | ✅ | 역할 (owner/admin/member/viewer) |

### 리소스 할당 요청

`POST /projects/{id}/resources`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| resourceType | String | ✅ | 리소스 유형 (workflow/knowledge_source/prompt/schema) |
| resourceId | String | ✅ | 리소스 ID |

---

## 가이드 (Guides)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/guides` | 가이드 목록 조회 | 🔒 |
| GET | `/guides/{slug}` | 가이드 상세 조회 (슬러그별) | 🔒 |

---

## 음성 (Speech) [v3.0, CR-011]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/speech/tts` | Text-to-Speech 변환 | 🔒 |
| POST | `/speech/stt` | Speech-to-Text 변환 | 🔒 |

---

## 도구 (Tools) [v4.0, CR-029 확장]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/tools` | 등록된 도구 목록 조회 (MCP + Built-in, contract 포함, capability 필터) | 🔒 |
| GET | `/tools/{toolName}/contract` | 도구 계약(입출력 스키마, 부수효과 선언) 조회 | 🔒 |
| POST | `/tools/{toolName}/execute` | 도구 직접 실행 | 🔒 |
| POST | `/tools/{toolName}/validate` | 도구 입력 유효성 검증 (dry-run) | 🔒 |

### 도구 목록 조회 [v4.0, CR-029]

**GET /api/v1/tools?capability=file_write&runtime_kind=sidecar**

**쿼리 파라미터**:

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| capability | String | 선택 | 기능 필터 (file_read, file_write, code_exec 등) |
| runtime_kind | String | 선택 | 런타임 필터 (direct, sidecar, mcp) |
| include_contract | Boolean | 선택 | contract 포함 여부 (기본 false) |

### 도구 계약 조회 [v4.0, CR-029]

**GET /api/v1/tools/{toolName}/contract**

**응답**:
```json
{
  "success": true,
  "data": {
    "tool_name": "claude_code",
    "input_schema": { "type": "object", "properties": { "command": { "type": "string" } } },
    "output_schema": { "type": "object", "properties": { "result": { "type": "string" } } },
    "side_effects": ["file_write", "process_exec"],
    "requires_approval": true,
    "timeout_ms": 300000
  }
}
```

### 도구 실행 [v4.0, CR-029]

`POST /api/v1/tools/{toolName}/execute`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| input | Map | ✅ | 도구 입력 파라미터 |
| sessionId | String | 선택 | 세션 ID (실행 이력 연결) |
| dryRun | Boolean | 선택 | true이면 실행하지 않고 계획만 반환 |

### 도구 유효성 검증 [v4.0, CR-029]

`POST /api/v1/tools/{toolName}/validate`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| input | Map | ✅ | 검증할 입력 파라미터 |

**응답**:
```json
{
  "success": true,
  "data": {
    "valid": true,
    "errors": [],
    "warnings": ["input.path is relative — consider using absolute path"]
  }
}
```

---

## 플랫폼 API 키 (Platform API Keys) [v3.5, CR-025]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/platform/api-keys` | API 키 목록 조회 | 🔒 Super Admin |
| POST | `/platform/api-keys` | API 키 생성 | 🔒 Super Admin |
| DELETE | `/platform/api-keys/{id}` | API 키 폐기 | 🔒 Super Admin |
| POST | `/platform/api-keys/{id}/regenerate` | API 키 재생성 | 🔒 Super Admin |

### API 키 생성 요청

`POST /platform/api-keys`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| name | String | ✅ | 키 이름 |
| tenantId | String | 선택 | 테넌트 ID (테넌트 한정 키) |
| domainApp | String | ✅ | 도메인 앱 구분 |
| scope | Map | 선택 | 권한 범위 |
| expiresAt | String | 선택 | 만료일시 (ISO 8601) |

---

## 도구 실행 이력 (Tool Executions) [v4.0, CR-029]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/tool-executions` | 도구 실행 이력 조회 (필터링, 페이징) | 🔒 |
| GET | `/tool-executions/{id}` | 도구 실행 이력 상세 조회 | 🔒 |

### 도구 실행 이력 조회 [v4.0, CR-029]

**GET /api/v1/tool-executions?session_id=sess-001&tool_name=claude_code**

**쿼리 파라미터**:

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| session_id | String | 선택 | 세션 ID 필터 |
| workflow_run_id | String | 선택 | 워크플로우 실행 ID 필터 |
| tool_name | String | 선택 | 도구 이름 필터 |
| success | Boolean | 선택 | 성공/실패 필터 |
| page | Integer | 선택 | 페이지 번호 (기본 0) |
| size | Integer | 선택 | 페이지 크기 (기본 20) |

**응답**:
```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "session_id": "sess-001",
      "tool_name": "claude_code",
      "input_summary": "git status 실행",
      "output_summary": "3 files changed",
      "success": true,
      "duration_ms": 850,
      "runtime_kind": "sidecar",
      "created_at": "2026-04-06T10:00:00Z"
    }
  ],
  "pagination": { "page": 0, "size": 20, "totalElements": 45, "totalPages": 3 }
}
```

### 도구 실행 이력 상세 [v4.0, CR-029]

**GET /api/v1/tool-executions/{id}**

**응답**:
```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "session_id": "sess-001",
    "workflow_run_id": null,
    "step_id": null,
    "turn_number": 3,
    "sequence_in_turn": 1,
    "tool_id": "builtin-claude-code",
    "tool_name": "claude_code",
    "input_summary": "git status 실행",
    "input_full": { "command": "git status" },
    "output_summary": "3 files changed",
    "output_full": "On branch dev\n...",
    "success": true,
    "duration_ms": 850,
    "artifacts": [],
    "side_effects": [{ "type": "file_read", "path": ".git/HEAD" }],
    "context_snapshot": { "model": "claude-sonnet-4-5", "turn": 3 },
    "runtime_kind": "sidecar",
    "created_at": "2026-04-06T10:00:00Z"
  }
}
```

---

## Context Recipe [v4.0, CR-029]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/context-recipes` | 컨텍스트 레시피 목록 조회 | 🔒 |
| GET | `/context-recipes/{id}` | 컨텍스트 레시피 상세 조회 | 🔒 |
| POST | `/context-recipes` | 컨텍스트 레시피 생성 | 🔒 |
| PUT | `/context-recipes/{id}` | 컨텍스트 레시피 수정 | 🔒 |
| DELETE | `/context-recipes/{id}` | 컨텍스트 레시피 삭제 | 🔒 |
| POST | `/context-recipes/{id}/preview` | 레시피 적용 미리보기 | 🔒 |

### 컨텍스트 레시피 생성 [v4.0, CR-029]

`POST /api/v1/context-recipes`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| name | String | ✅ | 레시피 이름 |
| description | String | 선택 | 설명 |
| recipe | Map | ✅ | 레시피 정의 (컨텍스트 조합 규칙) |
| domainApp | String | 선택 | 도메인 앱 구분 |
| scopeType | String | 선택 | 적용 세션 유형 (chat, agent, workflow) |
| priority | Integer | 선택 | 우선순위 (기본 0) |
| active | Boolean | 선택 | 활성 여부 (기본 true) |

**요청 예시**:
```json
{
  "name": "코드 리뷰 레시피",
  "description": "코드 리뷰 에이전트용 컨텍스트 조합",
  "recipe": {
    "system_prompt_ref": "prompt-code-review-v2",
    "memory_layers": ["LONG_TERM", "SHORT_TERM"],
    "rag_sources": ["knowledge-src-001"],
    "tool_allowlist": ["claude_code", "file_read"],
    "max_context_tokens": 100000
  },
  "domainApp": "chatpilot",
  "scopeType": "agent",
  "priority": 10,
  "active": true
}
```

### 컨텍스트 레시피 수정 [v4.0, CR-029]

`PUT /api/v1/context-recipes/{id}`

요청 필드는 생성과 동일 (부분 업데이트 지원).

### 레시피 적용 미리보기 [v4.0, CR-029]

`POST /api/v1/context-recipes/{id}/preview`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| sessionId | String | 선택 | 미리보기 대상 세션 |
| sampleMessage | String | 선택 | 테스트 메시지 |

**응답**:
```json
{
  "success": true,
  "data": {
    "resolved_system_prompt": "당신은 코드 리뷰 전문가입니다...",
    "included_memories": 5,
    "included_rag_chunks": 3,
    "available_tools": ["claude_code", "file_read"],
    "estimated_context_tokens": 45000
  }
}
```

---

## Domain Config [v4.0, CR-029]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/domain-configs` | 도메인 앱 설정 목록 조회 | 🔒 |
| GET | `/domain-configs/{domainApp}` | 도메인 앱 설정 상세 조회 | 🔒 |
| POST | `/domain-configs` | 도메인 앱 설정 생성 | 🔒 |
| PUT | `/domain-configs/{domainApp}` | 도메인 앱 설정 수정 | 🔒 |
| DELETE | `/domain-configs/{domainApp}` | 도메인 앱 설정 삭제 | 🔒 |

### 도메인 앱 설정 생성 [v4.0, CR-029]

`POST /api/v1/domain-configs`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| domainApp | String | ✅ | 도메인 앱 식별자 (UNIQUE) |
| defaultContextRecipeId | String | 선택 | 기본 컨텍스트 레시피 ID |
| defaultToolAllowlist | List | 선택 | 기본 도구 허용 목록 |
| defaultToolDenylist | List | 선택 | 기본 도구 거부 목록 |
| defaultPolicyPreset | Map | 선택 | 기본 정책 프리셋 |
| defaultSessionScope | String | 선택 | 기본 세션 유형 (chat, agent, workflow) |
| defaultRuntime | String | 선택 | 기본 런타임 (direct, sidecar, mcp) |
| mcpServerIds | List | 선택 | 연결된 MCP 서버 ID 목록 |
| config | Map | 선택 | 추가 설정 |

**요청 예시**:
```json
{
  "domainApp": "chatpilot",
  "defaultContextRecipeId": "recipe-chatpilot-default",
  "defaultToolAllowlist": ["web_search", "file_read"],
  "defaultToolDenylist": ["claude_code"],
  "defaultPolicyPreset": {
    "max_tokens_per_request": 4096,
    "require_approval_for": ["file_write"]
  },
  "defaultSessionScope": "chat",
  "defaultRuntime": "direct",
  "mcpServerIds": ["mcp-001", "mcp-002"],
  "config": {
    "welcome_message": "안녕하세요! 무엇을 도와드릴까요?"
  }
}
```

### 도메인 앱 설정 수정 [v4.0, CR-029]

`PUT /api/v1/domain-configs/{domainApp}`

요청 필드는 생성과 동일 (domainApp 제외, 부분 업데이트 지원).

---

## 에이전트 구조적 사고 — Tool 스키마 [v6.1, CR-033]

> Plan/Todo/Task는 REST API가 아닌 **LLM Tool**로 제공된다. ToolRegistry에 등록되어 LLM이 직접 호출한다.
> 조회용 REST API는 FE 대시보드(FE-015)를 위해 별도 제공한다.

### Tool: enter_plan_mode [PRD-222]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE | readOnly: false | destructive: false

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| title | String | ✅ | 계획 제목 |
| goals | String[] | ✅ | 달성 목표 리스트 |
| constraints | String[] | 선택 | 제약 조건 리스트 |

**반환**: `{ plan_id, status: "PLANNING", message }` → ToolResult.ok()

**부작용**: SessionStore에 `planModeActive: true` 설정. ToolFilterContext.readOnlyMode() 활성화.

### Tool: exit_plan_mode [PRD-223]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| plan_id | String | ✅ | 대상 Plan ID |
| steps | Object[] | ✅ | 실행 계획 스텝 배열 `[{ id, description, tools? }]` |
| summary | String | 선택 | 계획 요약 |

**반환**: `{ plan_id, status: "EXECUTING", total_steps }` → ToolResult.ok()

**부작용**: PlanEntity 상태 PLANNING→EXECUTING 전이. planModeActive=false 복원. 쓰기 도구 재활성화.

### Tool: verify_plan_execution [PRD-224]

**EnhancedToolExecutor 구현** | PermissionLevel: READ_ONLY

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| plan_id | String | ✅ | 대상 Plan ID |
| step_results | Object[] | 선택 | 각 step 결과 `[{ step_id, result, status: "done"|"skipped"|"failed" }]` |

**반환**: `{ plan_id, completion_rate, verified_steps, total_steps, gaps: [{ step_id, issue }], status }` → ToolResult.ok()

**부작용**: completion_rate 100%이면 상태 COMPLETED로 전이. gaps 있으면 EXECUTING 유지.

### Tool: todo_write [PRD-225]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| todos | Object[] | ✅ | 전체 Todo 배열 `[{ content, status, activeForm }]` |

**반환**: `{ todos: [...], summary: "3/5 completed" }` → ToolResult.ok()

**부작용**: 기존 todos 전체 교체. Redis 캐시 갱신 + DB 영속.

### Tool: task_create [PRD-226]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| description | String | ✅ | 태스크 설명 |
| prompt | String | ✅ | 서브에이전트에 전달할 프롬프트 |
| model | String | 선택 | 사용할 LLM 모델 |
| isolation | String | 선택 | "worktree" 또는 "none" (기본: none) |

**반환**: `{ task_id, status: "running" }` → ToolResult.ok()

**내부**: SubagentRunner.runInBackground() 호출. SubagentRunEntity에 task_description 저장.

### Tool: task_get [PRD-226]

**EnhancedToolExecutor 구현** | PermissionLevel: READ_ONLY

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| task_id | String | ✅ | 조회할 Task ID |

**반환**: `{ task_id, status, output, token_usage, duration_ms, created_at }`

### Tool: task_list [PRD-226]

**EnhancedToolExecutor 구현** | PermissionLevel: READ_ONLY

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| status_filter | String | 선택 | "running", "completed", "failed" 필터 |

**반환**: `{ tasks: [...], total }`

### Tool: task_update [PRD-227]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| task_id | String | ✅ | 대상 Task ID |
| description | String | 선택 | 변경할 설명 |
| priority | String | 선택 | "low", "medium", "high" |

### Tool: task_output [PRD-227]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| task_id | String | ✅ | 대상 Task ID |
| output | String | ✅ | 저장할 대용량 출력 |

**부작용**: large_output JSONB 컬럼에 저장. 컨텍스트 윈도우에서 제거.

### Tool: task_stop [PRD-227]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| task_id | String | ✅ | 중지할 Task ID |
| reason | String | 선택 | 중지 사유 |

**반환**: `{ task_id, status: "cancelled" }` | **제약**: RUNNING 상태에서만 가능.

### FE 조회 REST API [FE-015]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/api/v1/sessions/{sessionId}/plan` | 현재 세션 Plan 조회 | 🔒 |
| GET | `/api/v1/sessions/{sessionId}/todos` | 현재 세션 Todo 목록 | 🔒 |
| GET | `/api/v1/sessions/{sessionId}/tasks` | 현재 세션 Task 목록 | 🔒 |
| GET | `/api/v1/sessions/{sessionId}/tasks/{taskId}` | Task 상세 (large_output 포함) | 🔒 |

---

## 에이전트 자율성 강화 — Tool 스키마 [v6.4, CR-038]

> MCP 리소스 탐색·읽기, 이벤트 트리거, 세션 브리핑은 **LLM Tool**로 제공된다. ToolRegistry에 등록되어 LLM이 직접 호출한다.
> 브리핑 조회 REST API는 FE 패널(FE-019)을 위해 별도 제공한다.

### Tool: list_mcp_resources [PRD-245]

**EnhancedToolExecutor 구현** | PermissionLevel: READ_ONLY | readOnly: true | destructive: false

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| server_id | String | 선택 | 특정 MCP 서버 필터 (미지정 시 전체 연결 서버) |
| cursor | String | 선택 | 페이지네이션 커서 |

**반환**: `{ resources: [{ uri, name, description, mimeType, serverId }], nextCursor? }` → ToolResult.ok()

**내부**: MCPServerManager.getConnectedServerIds() → 각 서버의 MCPServerClient.listResources() 호출 → 통합 반환.

### Tool: read_mcp_resource [PRD-246]

**EnhancedToolExecutor 구현** | PermissionLevel: READ_ONLY | readOnly: true | destructive: false

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| uri | String | ✅ | 읽을 리소스 URI |
| server_id | String | 선택 | MCP 서버 ID (미지정 시 URI에서 자동 매칭) |

**반환**: `{ uri, mimeType, content, truncated }` → ToolResult.ok()

**제약**: BIZ-066 텍스트 32KB 트렁케이션. blob은 base64 인코딩 반환.

### Tool: remote_trigger [PRD-247]

**EnhancedToolExecutor 구현** | PermissionLevel: RESTRICTED_WRITE | readOnly: false | destructive: false

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| target_type | String | ✅ | "WORKFLOW" 또는 "TOOL" |
| target_id | String | ✅ | 실행 대상 (워크플로우 ID 또는 도구 이름) |
| input | Object | 선택 | 입력 파라미터 |
| trigger_reason | String | 선택 | 트리거 사유 (감사 로그 기록) |

**반환**: `{ triggered: true, target_type, target_id, result_summary, duration_ms }` → ToolResult.ok()

**제약**: BIZ-067 분당 10회 제한. 타임아웃 120초. 감사 로그에 trigger_reason 기록.

**내부**: CronScheduleManager의 실행 로직(WorkflowEngine.execute / ToolRegistry.execute) 재사용. 스케줄 등록 없이 즉시 1회 실행.

### Tool: brief [PRD-248]

**EnhancedToolExecutor 구현** | PermissionLevel: READ_ONLY | readOnly: true (LLM 호출은 있으나 외부 부작용 없음)

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| session_id | String | 선택 | 대상 세션 (미지정 시 현재 세션) |
| force_refresh | Boolean | 선택 | 캐시 무시하고 재생성 (기본: false) |

**반환**: `{ session_id, summary, key_decisions, pending_items, created_at }` → ToolResult.ok()

**제약**: BIZ-068 캐시 TTL 1시간. BIZ-069 최근 50개 메시지. Haiku급 모델로 요약 비용 최소화.

**내부**: SessionStore → 최근 50개 메시지 → LLM 요약 호출 → session_briefs DB 캐시.

### FE 조회 REST API [FE-019]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/api/v1/sessions/{sessionId}/brief` | 세션 브리핑 조회 (캐시 우선) | 🔒 |
| POST | `/api/v1/sessions/{sessionId}/brief` | 세션 브리핑 수동 생성/갱신 | 🔒 |

---

## 프롬프트 템플릿 API [CR-036]

### CRUD 엔드포인트

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/api/v1/prompt-templates` | 프롬프트 템플릿 목록 (category 필터) | 🔒 |
| POST | `/api/v1/prompt-templates` | 프롬프트 템플릿 생성 | 🔒 |
| GET | `/api/v1/prompt-templates/{key}/{version}` | 버전별 조회 | 🔒 |
| GET | `/api/v1/prompt-templates/{key}/versions` | 특정 키의 전체 버전 목록 조회 | 🔒 |
| PUT | `/api/v1/prompt-templates/{key}/{version}` | 수정 (is_system=true면 template만 수정 가능) | 🔒 |
| DELETE | `/api/v1/prompt-templates/{key}/{version}` | 삭제 (is_system=true면 거부) | 🔒 |

#### GET `/api/v1/prompt-templates`

**쿼리 파라미터**:

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| category | String | 선택 | 카테고리 필터 (core/tool/agent/rag/workflow/evaluation/service/config) |
| is_active | Boolean | 선택 | 활성 여부 필터 (기본: true) |
| language | String | 선택 | 언어 코드 필터 (기본: en) |

**반환**: `ApiResponse<List<PromptTemplateEntity>>`

#### POST `/api/v1/prompt-templates`

**요청 바디**:
```json
{
  "key": "core.system.identity",          // 필수, VARCHAR(100)
  "version": 1,                            // 선택, 기본 1
  "category": "core",                      // 필수
  "name": "System Identity Prompt",        // 필수
  "description": "Defines AI identity",    // 선택
  "template": "You are {{agent_name}}...", // 필수
  "variables": [                           // 선택
    {"name": "agent_name", "type": "string", "required": true, "default": "Aimbase", "description": "Agent display name"}
  ],
  "language": "en",                        // 선택, 기본 'en'
  "is_active": true                        // 선택, 기본 true
}
```

**반환**: `ApiResponse<PromptTemplateEntity>` (201 Created)

#### PUT `/api/v1/prompt-templates/{key}/{version}`

**제약**: is_system=true인 경우 template 필드만 수정 가능. key, version, is_system, created_by 변경 불가.

**반환**: `ApiResponse<PromptTemplateEntity>` (200 OK)

#### DELETE `/api/v1/prompt-templates/{key}/{version}`

**제약**: is_system=true인 경우 409 Conflict 반환.

**반환**: `ApiResponse<Void>` (204 No Content)

### 특수 엔드포인트

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/api/v1/prompt-templates/{key}/{version}/render` | 변수 치환 렌더링 + 토큰 추정 | 🔒 |
| GET | `/api/v1/prompt-templates/bulk` | 벌크 로드 (category 필터, Python 사이드카용) | 🔒 |
| DELETE | `/api/v1/prompt-templates/cache` | 캐시 무효화 | 🔒 |

#### POST `/api/v1/prompt-templates/{key}/{version}/render`

**요청 바디**:
```json
{
  "variables": {
    "agent_name": "MyAgent",
    "tool_list": "read, write, bash"
  }
}
```

**반환**:
```json
{
  "rendered": "You are MyAgent. Available tools: read, write, bash...",
  "estimated_tokens": 1250,
  "unresolved_variables": []
}
```

#### GET `/api/v1/prompt-templates/bulk`

**쿼리 파라미터**:

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| category | String | 선택 | 카테고리 필터 (쉼표 구분 다중 지정 가능) |

**반환**: `ApiResponse<List<PromptTemplateEntity>>` (활성 프롬프트 전체, 최신 버전만)

#### DELETE `/api/v1/prompt-templates/cache`

**반환**: `ApiResponse<Void>` (204 No Content) — Caffeine 캐시 전체 무효화

---

## Cron 스케줄 관리 (Scheduled Jobs) [v6.3, CR-035]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/scheduled-jobs` | 현재 테넌트의 스케줄 작업 목록 조회 | 🔒 |
| POST | `/scheduled-jobs` | 스케줄 작업 생성 | 🔒 |
| DELETE | `/scheduled-jobs/{jobId}` | 스케줄 작업 삭제 | 🔒 |
| PATCH | `/scheduled-jobs/{jobId}/toggle` | 스케줄 작업 활성/비활성 토글 | 🔒 |

### 스케줄 작업 생성 요청

`POST /scheduled-jobs`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| name | String | ✅ | 작업 이름 |
| cron_expression | String | ✅ | Cron 표현식 (최소 간격 1분, BIZ-058) |
| target_type | String | ✅ | 실행 대상 타입 ("WORKFLOW" 또는 "TOOL") |
| target_id | String | ✅ | 실행 대상 ID (워크플로우 ID 또는 도구 이름) |
| input | Map | 선택 | 실행 시 전달할 입력 파라미터 |

```json
{
  "name": "일일 RAG 인덱스 갱신",
  "cron_expression": "0 0 2 * * ?",
  "target_type": "WORKFLOW",
  "target_id": "wf-rag-reindex",
  "input": { "source_id": "ks-001", "mode": "incremental" }
}
```

**응답**: `ApiResponse<ScheduledJobEntity>` — job_id, name, cron_expression, target_type, target_id, input, is_active, next_run_at 포함.

**제약**: 테넌트당 최대 50개 (BIZ-057). Cron 최소 간격 1분 (BIZ-058). 위반 시 에러 반환.

### 스케줄 작업 토글

`PATCH /scheduled-jobs/{jobId}/toggle`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| is_active | Boolean | ✅ | 활성화 여부 |

**응답**: `ApiResponse<ScheduledJobEntity>` — 업데이트된 작업 정보.

---

## 스킬 (Skills) [v6.3, CR-035]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/skills` | 활성 스킬 목록 조회 (name ASC 정렬) | 🔒 |
| GET | `/skills/{id}` | 스킬 상세 조회 | 🔒 |
| POST | `/skills` | 스킬 생성 | 🔒 |
| PUT | `/skills/{id}` | 스킬 수정 (부분 업데이트 지원) | 🔒 |
| DELETE | `/skills/{id}` | 스킬 삭제 | 🔒 |

### 스킬 생성 요청

`POST /skills`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| name | String | ✅ | 스킬 이름 |
| description | String | 선택 | 스킬 설명 |
| system_prompt | String | 선택 | 스킬 전용 시스템 프롬프트 |
| tools | List\<String\> | 선택 | 사용 가능한 도구 목록 |
| output_schema | Map | 선택 | 출력 스키마 (JSON Schema) |
| tags | List\<String\> | 선택 | 태그 목록 |

```json
{
  "name": "코드 리뷰 스킬",
  "description": "소스 코드를 분석하고 개선 사항을 제안한다",
  "system_prompt": "You are a senior code reviewer...",
  "tools": ["bash", "glob", "grep", "read_file"],
  "output_schema": {
    "type": "object",
    "properties": {
      "issues": { "type": "array" },
      "summary": { "type": "string" }
    }
  },
  "tags": ["dev", "review"]
}
```

**응답**: `ApiResponse<SkillEntity>` — id (sk-{uuid8}), name, description, system_prompt, tools, output_schema, tags, is_active 포함.

**ID 생성 규칙**: `sk-` 접두사 + UUID 앞 8자리 (예: `sk-a1b2c3d4`).

### 스킬 수정 요청

`PUT /skills/{id}`

요청 바디에 포함된 필드만 업데이트 (부분 업데이트). name, description, system_prompt, tools, output_schema, tags 필드 지원.

**제약**: 삭제 시 참조 중인 워크플로우 확인 필수 (BIZ-064). SkillInvokeTool은 활성화된 스킬만 호출 가능 (BIZ-059).

---

## 세션 플랜 (Session Plans) [v6.3, CR-033/CR-038]

> Plan, Todo, Task, Brief 데이터를 FE 대시보드에 제공하는 조회 전용 REST API.

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/sessions/{sessionId}/plan` | 현재 세션 Plan 조회 | 🔒 |
| GET | `/sessions/{sessionId}/todos` | 현재 세션 Todo 목록 | 🔒 |
| GET | `/sessions/{sessionId}/tasks` | 현재 세션 Task 목록 (최신순) | 🔒 |
| GET | `/sessions/{sessionId}/tasks/{taskId}` | Task 상세 (output, error, prompt 포함) | 🔒 |
| GET | `/sessions/{sessionId}/brief` | 세션 브리핑 조회 (캐시 우선, BIZ-068) | 🔒 |
| POST | `/sessions/{sessionId}/brief` | 세션 브리핑 수동 생성/갱신 | 🔒 |

### GET `/sessions/{sessionId}/plan`

**응답**:
```json
{
  "plan_id": "uuid",
  "title": "리팩토링 계획",
  "status": "EXECUTING",
  "goals": ["목표1", "목표2"],
  "steps": [{ "id": "s1", "description": "...", "status": "done" }],
  "constraints": ["제약1"],
  "verification_result": null,
  "created_at": "2026-04-07T10:00:00Z",
  "updated_at": "2026-04-07T10:30:00Z"
}
```

Plan이 없는 세션은 `data: null` 반환.

### GET `/sessions/{sessionId}/todos`

**응답**: `ApiResponse<List<TodoEntity>>` — todo_id, content, status(pending/in_progress/completed), priority, created_at 포함.

### GET `/sessions/{sessionId}/tasks`

**응답**: 태스크 목록 (최신순 정렬).

```json
[
  {
    "task_id": "uuid",
    "status": "completed",
    "description": "파일 분석 태스크",
    "priority": 1,
    "duration_ms": 12500,
    "token_usage": { "input_tokens": 1200, "output_tokens": 800 },
    "created_at": "2026-04-07T10:00:00Z",
    "completed_at": "2026-04-07T10:00:12Z"
  }
]
```

### GET `/sessions/{sessionId}/tasks/{taskId}`

Task 상세 조회. 목록 응답에 추가로 `output`, `large_output`, `error`, `prompt` 필드 포함.

### GET/POST `/sessions/{sessionId}/brief`

GET은 캐시 우선 조회 (BIZ-068 TTL 1시간). POST는 캐시 무시하고 재생성.

**응답**:
```json
{
  "session_id": "uuid",
  "summary": "세션 요약...",
  "key_decisions": ["결정1", "결정2"],
  "pending_items": ["미결1"],
  "created_at": "2026-04-07T11:00:00Z"
}
```

---

## 작성 가이드

- **경로**: kebab-case 사용 (e.g., `/knowledge-sources`, `/mcp-servers`)
- **CRUD 패턴**: GET / → POST / → GET /{id} → PUT /{id} → DELETE /{id}
- **🔒 표시**: 인증 필요 엔드포인트
- **상세 스키마**: 모든 POST/PUT 엔드포인트에 필수/선택 필드, 타입, 예시 JSON을 본 문서에 명시 (v3.0 DEF-001 반영)
- **Swagger**: `/swagger-ui.html`에서 자동 생성된 API 문서 확인 가능 (필드 validation 어노테이션 포함)
