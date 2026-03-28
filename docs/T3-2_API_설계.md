# T3-2. API 설계서

> 설계 버전: 2.9 | 최종 수정: 2026-03-28 | 관련 CR: CR-006, CR-007, CR-009, CR-010, CR-011, CR-015, CR-016, CR-017, CR-019, CR-020, CR-021, CR-025

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

### 도구 선택 제어 요청 (v2.4, CR-006)

`POST /chat/completions`의 `tool_choice` 및 `tool_filter` 파라미터로 LLM에 노출할 도구를 제어한다.

**요청 확장 필드**:
```json
{
  "model": "claude-sonnet-4-5",
  "messages": [{ "role": "user", "content": "주문 조회해줘" }],
  "tool_choice": "auto",
  "tool_filter": {
    "allowed_tools": ["get_order", "search_orders"],
    "exclude_tools": ["delete_order"],
    "tags": ["order", "read-only"]
  }
}
```

**tool_choice 옵션**:
| 값 | 설명 |
|----|------|
| `"auto"` | LLM이 자유롭게 선택 (기본값) |
| `"none"` | 도구 호출 금지 |
| `"required"` | 반드시 도구 호출 |
| `{"type": "tool", "name": "get_order"}` | 특정 도구 강제 선택 |

**tool_filter (ToolFilterContext)**:
- `allowed_tools`: 허용할 도구 이름 목록 (화이트리스트)
- `exclude_tools`: 제외할 도구 이름 목록 (블랙리스트)
- `tags`: 태그 기반 필터 (도구에 부여된 태그와 매칭)
- `isToolAllowed(toolName)`: 내부 메서드 — allowed/exclude 기반 허용 여부 판단
- `filterTools(allTools)`: 내부 메서드 — 전체 도구 목록에서 필터링된 결과 반환

**프로바이더별 매핑**: Anthropic(`tool_choice`), OpenAI(`tool_choice`), Ollama(미지원 — `none` 시 tools 제외)

---

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

---

## 스키마 (Schemas)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/schemas` | 스키마 목록 조회 | 🔒 |
| POST | `/schemas` | 스키마 생성 | 🔒 |
| GET | `/schemas/{id}/{version}` | 스키마 버전별 조회 | 🔒 |
| DELETE | `/schemas/{id}/{version}` | 스키마 삭제 | 🔒 |
| POST | `/schemas/{id}/{version}/validate` | 데이터 유효성 검증 | 🔒 |

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

### 정책 규칙 타입별 JSON 스키마 (v3.3, CR-015)

`POST /policies` 및 `PUT /policies/{id}` 요청의 `rules` JSONB 배열에 타입별 구조화된 규칙을 정의한다.

**규칙 타입별 config 예시**:
```json
{
  "rules": [
    { "type": "content_filter", "keywords": ["비속어", "욕설"], "patterns": ["\\d{6}-\\d{7}"], "action": "DENY" },
    { "type": "cost_limit", "daily_limit": 10.0, "monthly_limit": 200.0, "currency": "USD" },
    { "type": "token_limit", "max_input_tokens": 4096, "max_output_tokens": 2048, "max_total_tokens": 8192 },
    { "type": "rate_limit", "max_requests_per_hour": 100, "max_requests_per_minute": 10 },
    { "type": "model_filter", "allowed_models": ["gpt-4o", "claude-sonnet-4-5"], "blocked_models": [] },
    { "type": "time_restriction", "allowed_hours": {"start": 9, "end": 18}, "allowed_days": ["MON","TUE","WED","THU","FRI"], "timezone": "Asia/Seoul" }
  ]
}
```

서버 측에서 각 규칙의 `type` 필드를 기반으로 JSON Schema Validator(networknt)로 구조 검증을 수행한다.

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

### 워크플로우 스텝 구조 (v3.5, CR-017)

`POST /workflows` 및 `PUT /workflows/{id}` 요청의 `steps` 배열에 WorkflowStep 구조를 정의한다.

**WorkflowStep record**:
```json
{
  "steps": [
    {
      "id": "step-1",
      "name": "직원 정보 추출",
      "type": "LLM_CALL",
      "config": {
        "connection_id": "conn-openai-001",
        "model": "gpt-4o",
        "system_prompt": "직원 정보를 추출하세요.",
        "response_schema": { "type": "object", "properties": { "name": { "type": "string" } } }
      },
      "dependsOn": ["step-0"],
      "onSuccess": "step-2",
      "onFailure": "step-error",
      "timeoutMs": 30000
    },
    {
      "id": "step-2",
      "name": "Slack 알림 발송",
      "type": "ACTION",
      "config": {
        "type": "NOTIFY",
        "adapter": "slack",
        "destination": "#hr-channel",
        "payload": { "text": "신규 직원: {{step-1.output.name}}" }
      },
      "dependsOn": ["step-1"]
    }
  ]
}
```

**StepType enum**: `LLM_CALL`, `TOOL_CALL`, `ACTION`, `CONDITION`, `PARALLEL`, `HUMAN_INPUT`

**변수 치환**: ACTION config의 payload에서 `{{stepId.output}}` 또는 `{{stepId.output.field}}` 패턴을 이전 스텝 실행 결과로 치환한다.

**@JsonAlias**: `dependsOn` 필드는 하위 호환을 위해 `dependencies` alias를 지원한다.

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

---

## 역할 (Roles)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/roles` | 역할 목록 조회 | 🔒 |
| POST | `/roles` | 역할 생성 | 🔒 |
| GET | `/roles/{id}` | 역할 상세 조회 | 🔒 |
| PUT | `/roles/{id}` | 역할 수정 | 🔒 |
| DELETE | `/roles/{id}` | 역할 삭제 | 🔒 |

---

## 모니터링 (Monitoring)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/models` | 모델 목록 조회 | 🔒 |
| GET | `/routing` | 활성 라우팅 설정 조회 | 🔒 |

---

## 인증 (Auth) [v3.0, CR-010, CR-027]

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/auth/login` | JWT 로그인 (email + password) | 🔓 Public |
| POST | `/auth/refresh` | 토큰 갱신 (refreshToken) | 🔓 Public |

### 로그인

**요청**:
```json
{ "email": "admin@example.com", "password": "..." }
```

> **[CR-027]** `X-Tenant-Id` 헤더 선택적. 미전달 시 Master DB `user_tenant_map`에서 email로 tenant_id 자동 resolve. 전달 시 기존 동작 유지 (하위 호환).

**응답**:
```json
{
  "success": true,
  "data": {
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "expires_in": 1800,
    "token_type": "Bearer",
    "tenant_id": "bidding_system",
    "user_id": "admin-bidding_system",
    "email": "admin@bidding.local",
    "role": "admin"
  }
}
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

## 플랫폼 관리 (Platform - Super Admin)

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
| `contextual_chunk` | 청크별 LLM 맥락 프리픽스 생성 (v3.1 CR-011) | PY-023 |
| `parent_child_search` | Child 매칭 → Parent 반환 계층 검색 (v3.1 CR-011) | PY-024 |
| `transform_query_v2` | HyDE/Multi-Query/Step-Back 실제 LLM 연동 (v3.1 CR-011) | PY-025 |
| `advanced_parse` | 테이블/OCR/레이아웃 고급 파싱 (v3.1 CR-011) | PY-027 |
| `semantic_cache` | 질문 임베딩 캐시 비교 (v3.1 CR-011) | PY-028 |

### MCP Server 2: Evaluation

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `evaluate_rag` | RAG 응답 품질 평가 (faithfulness, relevancy, context precision/recall). **mode 파라미터**: `"fast"` (RAGAS only) / `"accurate"` (RAGAS + LLM Judge, CR-016) | PY-006 |
| `evaluate_llm_output` | LLM 출력 평가 (hallucination, toxicity, bias) | PY-007 |
| `compare_prompts` | 프롬프트 A/B 비교 회귀 테스트 | PY-008 |
| `generate_benchmark` | RAG 벤치마크 Q&A 자동 생성 (v3.0 CR-009) | PY-020 |
| `detect_embedding_drift` | 임베딩 드리프트 감지 (v3.0 CR-009) | PY-021 |
| `evaluate_rag_quality` | RAGAS 5개 메트릭 자동 산출 (v3.1 CR-011) | PY-026 |

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

## TTS/STT API [v3.1, CR-011]

### TTS (Text-to-Speech)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/tts` | 텍스트→음성 변환 (OpenAI TTS 프록시) | 🔒 |

**POST /tts**
```json
// Request
{
  "text": "광합성은 식물이 빛 에너지를 화학 에너지로 변환하는 과정입니다.",
  "model": "tts-1",
  "voice": "alloy",
  "response_format": "mp3",
  "speed": 1.0,
  "connection_id": "conn-openai-001"
}

// Response: Content-Type: audio/mpeg (바이너리 스트리밍)
```

### STT (Speech-to-Text)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/stt` | 음성→텍스트 변환 (OpenAI Whisper 프록시) | 🔒 |

**POST /stt**
```json
// Request: multipart/form-data
// - file: 오디오 파일 (mp3/wav/webm/m4a, 최대 25MB)
// - language: "ko" (선택)
// - response_format: "verbose_json" (선택)
// - connection_id: "conn-openai-001" (선택)

// Response
{
  "text": "광합성이란 무엇인가요?",
  "language": "ko",
  "duration": 3.2,
  "segments": [
    {"start": 0.0, "end": 3.2, "text": "광합성이란 무엇인가요?"}
  ]
}
```

---

## RAG A++ 고도화 API [v3.1, CR-011]

### RAG 평가 (Evaluation)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/evaluations/rag-quality` | RAG 품질 평가 (RAGAS 5개 메트릭) | 🔒 |
| GET | `/evaluations/rag-quality` | RAG 평가 결과 조회 (source_id, 기간 필터) | 🔒 |

**POST /evaluations/rag-quality**
```json
// Request
{
  "query": "광합성이란 무엇인가?",
  "answer": "광합성은 식물이 빛 에너지를...",
  "contexts": ["광합성(光合成)은 식물이...", "엽록체에서 빛 에너지를..."],
  "ground_truth": "광합성은 빛 에너지를 화학 에너지로 변환하는 과정",
  "source_id": "ks-001",
  "config": {
    "mode": "accurate"
  }
}

// config.mode (v3.4, CR-016):
//   "fast" (기본값): RAGAS 통계 기반 메트릭만 산출 (무비용, 빠름)
//   "accurate": RAGAS + LLM Judge(Claude Haiku) 의미론적 평가 병행 (유료, 정확)
//   LLM Judge 내부: _call_llm_for_score() → POST https://api.anthropic.com/v1/messages
//     model: claude-haiku-4-5-20251001

// Response
{
  "faithfulness": 0.92,
  "context_relevancy": 0.88,
  "answer_relevancy": 0.95,
  "context_precision": 0.85,
  "context_recall": 0.90,
  "overall_score": 0.90,
  "mode": "accurate",
  "llm_judge_scores": {
    "faithfulness": 0.94,
    "relevancy": 0.91
  },
  "evaluated_at": "2026-03-21T10:30:00Z"
}
```

### Citation 응답 확장

ChatResponse에 `citations` 필드 추가 (PRD-125):
```json
{
  "content": [
    {"type": "text", "text": "광합성[1]은 식물이 빛 에너지를 화학 에너지로 변환하는 과정입니다[2]."}
  ],
  "citations": [
    {"number": 1, "source_name": "고등생물학 교재", "document_id": "doc-001", "chunk_index": 3, "page": 42, "similarity": 0.94},
    {"number": 2, "source_name": "과학백과", "document_id": "doc-002", "chunk_index": 7, "similarity": 0.89}
  ]
}
```

---

## 도구 관리 API (CR-019)

### GET /api/v1/tools
- **설명**: 등록된 MCP Tool 전체 목록 조회
- **인증**: JWT Bearer Token
- **Query Parameters**:
  | 파라미터 | 타입 | 필수 | 설명 |
  |---------|------|------|------|
  | source | string | N | 필터: builtin, mcp, extension |
  | category | string | N | 필터: rag, document, analysis, web, notification |
- **응답 (200)**:
```json
{
  "success": true,
  "data": {
    "tools": [
      {
        "name": "read_pptx",
        "description": "PPTX 파일을 구조화된 JSON/HTML로 변환",
        "parameters": {
          "type": "object",
          "properties": {
            "file_base64": {"type": "string", "description": "base64 인코딩된 PPTX"},
            "output_mode": {"type": "string", "enum": ["json", "html"], "default": "json"}
          },
          "required": ["file_base64"]
        },
        "source": "mcp",
        "server_id": "rag-pipeline"
      }
    ],
    "count": 39
  }
}
```
- **비고**: ToolRegistry.getToolDefs()에서 데이터 제공. 워크플로우 TOOL_CALL 스텝 설정 시 사용

---

---

## 프로젝트 관리 API [CR-021]

> 회사(Tenant) 내 프로젝트 CRUD, 멤버 관리, 리소스 할당. `X-Project-Id` 헤더로 프로젝트 스코핑.

### 🔒 GET /api/v1/projects — 프로젝트 목록 조회

**응답 200**:
```json
[{"id": "axopm", "name": "AXOPM", "description": "프로젝트 관리 AI", "memberCount": 3, "createdAt": "..."}]
```

### 🔒 POST /api/v1/projects — 프로젝트 생성

**요청**:
```json
{"name": "AXOPM", "description": "프로젝트 관리 AI"}
```
**응답 201**: `{id, name, description, createdAt}`
- 생성자 자동으로 admin 역할 등록

### 🔒 GET /api/v1/projects/{id} — 프로젝트 상세 조회

**응답 200**: `{id, name, description, members[{userId, name, role}], resourceSummary{knowledgeCount, promptCount, schemaCount, workflowCount}}`

### 🔒 PUT /api/v1/projects/{id} — 프로젝트 수정

**요청**: `{name, description}`
**응답 200**: `{id, name, description, updatedAt}`

### 🔒 DELETE /api/v1/projects/{id} — 프로젝트 삭제

**응답 204**: 소속 Workflow 함께 삭제, 할당 리소스는 해제만

### 🔒 POST /api/v1/projects/{id}/members — 멤버 추가

**요청**: `{userId: "uuid", role: "editor"}`
**응답 201**: `{projectId, userId, role}`

### 🔒 DELETE /api/v1/projects/{id}/members/{userId} — 멤버 제거

**응답 204**

### 🔒 PUT /api/v1/projects/{id}/members/{userId} — 멤버 역할 변경

**요청**: `{role: "admin"}`
**응답 200**: `{projectId, userId, role}`

### 🔒 POST /api/v1/projects/{id}/resources — 리소스 할당

**요청**: `{resourceType: "knowledge_source", resourceId: "ks-001"}`
**응답 201**: `{projectId, resourceType, resourceId, assignedAt}`
- resourceType: `knowledge_source` | `prompt` | `schema`

### 🔒 DELETE /api/v1/projects/{id}/resources/{resourceType}/{resourceId} — 리소스 해제

**응답 204**: 리소스 자체는 삭제되지 않음

### 🔒 GET /api/v1/projects/{id}/resources — 프로젝트 할당 리소스 목록

**요청 파라미터**: `resource_type` (선택, 필터)
**응답 200**: `[{resourceType, resourceId, name, assignedAt}]`

### 프로젝트 스코핑 헤더

모든 리소스 API에 `X-Project-Id` 헤더 지원:
- **지정 시**: 해당 프로젝트에 할당된 Knowledge/Prompt/Schema + 해당 프로젝트 소속 Workflow만 반환
- **미지정 시**: 회사 전체 리소스 반환 (하위 호환)
- **Workflow 생성 시**: `X-Project-Id` 지정하면 자동으로 project_id 설정

---

## MCP 관리 도구 [CR-020]

> Spring AI MCP Server로 노출. 14개 도구. 기존 REST API 서비스 재사용.

### 조회 도구 (6개)

| MCP Tool | 설명 | 내부 호출 |
|----------|------|----------|
| `aimbase_list_step_types` | StepType 목록 + config 스펙 + DAG 규칙 (description에 포함) | 정적 메타데이터 |
| `aimbase_list_tools` | 등록 도구 목록 (project_id 선택) | ToolRegistry.getToolDefs() |
| `aimbase_list_connections` | LLM 연결 목록 | ConnectionRepository.findAll() |
| `aimbase_list_prompts` | 프롬프트 템플릿 목록 (project_id 선택) | PromptRepository + project_resources 필터 |
| `aimbase_list_knowledge_sources` | 지식소스 목록 (project_id 선택) | KnowledgeSourceRepository + project_resources 필터 |
| `aimbase_list_schemas` | 출력 스키마 목록 (project_id 선택) | SchemaRepository + project_resources 필터 |

### 워크플로우 도구 (8개)

| MCP Tool | 설명 | 내부 호출 |
|----------|------|----------|
| `aimbase_workflow_create` | 워크플로우 생성 (project_id 선택) | WorkflowController.create 재사용 |
| `aimbase_workflow_list` | 목록 조회 (project_id/domain 필터) | WorkflowController.list 재사용 |
| `aimbase_workflow_get` | 상세 조회 | WorkflowController.get 재사용 |
| `aimbase_workflow_update` | 수정 | WorkflowController.update 재사용 |
| `aimbase_workflow_delete` | 삭제 | WorkflowController.delete 재사용 |
| `aimbase_workflow_validate` | DAG 유효성 검증 (순환참조, config 필수필드) | WorkflowEngine.validate (신규) |
| `aimbase_workflow_run` | 비동기 실행 | WorkflowEngine.execute 재사용 |
| `aimbase_workflow_run_status` | 실행 상태/결과 조회 | WorkflowRunRepository.findById |

---

## 시스템 API Key 관리 [CR-025]

> 시스템 연동용 API Key를 독립 엔티티로 관리. ADMIN 이상 권한 필요.

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/admin/api-keys` | API Key 생성 (name, scope, expiresAt) | 🔒 Admin |
| GET | `/admin/api-keys` | API Key 목록 조회 (마스킹된 키, 사용일, 만료일) | 🔒 Admin |
| DELETE | `/admin/api-keys/{id}` | API Key 폐기 (soft delete) | 🔒 Admin |
| POST | `/admin/api-keys/{id}/regenerate` | API Key 재발급 (기존 폐기 + 신규 발급) | 🔒 Admin |

### 생성 요청/응답

**Request**: `POST /api/v1/admin/api-keys`
```json
{
  "name": "ChatPilot 연동용",
  "scope": {"resources": ["connections", "knowledge"], "actions": ["read"]},
  "expiresAt": "2027-03-28T00:00:00Z"
}
```

**Response**:
```json
{
  "data": {
    "id": "ak-uuid",
    "name": "ChatPilot 연동용",
    "apiKey": "plat-550e8400e29b41d4a716446655440000",
    "keyPrefix": "plat-550e",
    "scope": {"resources": ["connections", "knowledge"], "actions": ["read"]},
    "expiresAt": "2027-03-28T00:00:00Z",
    "createdAt": "2026-03-28T10:00:00Z",
    "note": "이 키는 다시 조회할 수 없습니다. 안전한 곳에 저장하세요."
  }
}
```

### 목록 조회 응답

**Response**: `GET /api/v1/admin/api-keys`
```json
{
  "data": [
    {
      "id": "ak-uuid",
      "name": "ChatPilot 연동용",
      "keyPrefix": "plat-550e",
      "scope": {"resources": ["connections", "knowledge"], "actions": ["read"]},
      "lastUsedAt": "2026-03-28T09:30:00Z",
      "expiresAt": "2027-03-28T00:00:00Z",
      "isActive": true,
      "createdBy": "admin@companyA.com",
      "createdAt": "2026-03-28T10:00:00Z"
    }
  ]
}
```

---

## 작성 가이드

- **경로**: kebab-case 사용 (e.g., `/knowledge-sources`, `/mcp-servers`)
- **CRUD 패턴**: GET / → POST / → GET /{id} → PUT /{id} → DELETE /{id}
- **🔒 표시**: 인증 필요 엔드포인트
- **상세 스키마**: 데이터 모델(T3-1)에서 자동 도출, 별도 OpenAPI 스키마 미정의
- **Swagger**: `/swagger-ui.html`에서 자동 생성된 API 문서 확인 가능
