# Aimbase 워크플로우 생성 가이드 (AXOPM 개발자용)

> AXOPM 개발자가 Claude Code에서 Aimbase MCP 서버에 연결하여 워크플로우를 작성하는 절차.

---

## 1. MCP 서버 연결 설정

AXOPM 프로젝트의 `.mcp.json`에 Aimbase Admin MCP 서버를 등록합니다.

```json
{
  "mcpServers": {
    "aimbase-admin": {
      "type": "sse",
      "url": "http://localhost:8080/admin-mcp/sse?tenant_id=axopm_companyA"
    }
  }
}
```

- **URL에 `tenant_id` 쿼리 파라미터 필수** — 테넌트 DB 라우팅에 사용
- Aimbase BE가 `http://localhost:8080`에서 기동 중이어야 합니다
- 테넌트는 Aimbase 관리 화면(`/platform/tenants`) 또는 Platform API로 사전 생성

연결 확인: Claude Code에서 14개 도구 목록이 보이면 성공입니다.

---

## 2. MCP 관리 도구 (14개)

연결되면 Claude Code에 다음 도구가 자동 등록됩니다.

### 조회 도구 (6개)

| 도구명 | 설명 | 주요 파라미터 |
|--------|------|-------------|
| `list_connections` | LLM 연결 목록 조회 | — |
| `list_knowledge_sources` | RAG 지식 소스 목록 | `type` (선택) |
| `list_prompts` | 프롬프트 템플릿 목록 | `domain` (선택) |
| `list_schemas` | JSON 스키마 목록 | — |
| `list_policies` | 정책 목록 | — |
| `list_workflows` | 워크플로우 목록 | `domain`, `project_id` (선택) |

### 워크플로우 CRUD 도구 (8개)

| 도구명 | 설명 | 필수 파라미터 |
|--------|------|-------------|
| `get_workflow` | 워크플로우 상세 조회 | `workflow_id` |
| `create_workflow` | 워크플로우 생성 | `name`, `steps` |
| `update_workflow` | 워크플로우 수정 | `workflow_id` |
| `delete_workflow` | 워크플로우 삭제 | `workflow_id` |
| `run_workflow` | 워크플로우 실행 | `workflow_id` |
| `get_workflow_run` | 실행 결과 조회 | `run_id` |
| `list_workflow_runs` | 실행 이력 조회 | `workflow_id` |
| `approve_workflow_run` | HUMAN_INPUT 승인/거부 | `run_id`, `approved` |

---

## 3. 워크플로우 작성 절차

Claude Code에서 자연어로 요청하면 됩니다. Claude Code가 MCP 도구를 호출하여 처리합니다.

### Step 1 — 사용 가능한 리소스 확인

Claude Code에게:
```
Aimbase에 등록된 연결과 지식 소스를 확인해줘
```

Claude Code가 호출하는 도구:
- `list_connections` → connection_id 획득
- `list_knowledge_sources` → 검색 가능한 소스 확인
- `list_prompts` → 재사용 가능한 프롬프트 확인

### Step 2 — 워크플로우 생성

Claude Code에게:
```
AXOPM 이슈가 들어오면 자동으로 분류하고,
긴급이면 에스컬레이션하는 워크플로우를 만들어줘.
연결은 {connection_id}를 사용해.
```

Claude Code가 `create_workflow`를 호출합니다.

### Step 3 — 테스트 실행

```
방금 만든 워크플로우를 테스트해줘.
이슈 제목: "로그인 500 에러", 내용: "소셜 로그인 시 간헐적 500 에러"
```

Claude Code가 `run_workflow` → `get_workflow_run`을 호출합니다.

---

## 4. 워크플로우 스텝 레퍼런스

`create_workflow`의 `steps` 배열에 사용하는 스텝 타입입니다.

### LLM_CALL — LLM 호출

```json
{
  "id": "classify",
  "type": "LLM_CALL",
  "config": {
    "connection_id": "연결ID",
    "system": "시스템 프롬프트",
    "prompt": "{{input.title}}을 분류하세요",
    "response_schema": { "type": "object", "properties": { ... } },
    "max_tokens": 8192
  }
}
```

> **토큰 초과 자동 처리 (CR-028)**: `response_schema`가 있는 LLM_CALL 스텝은
> 응답이 잘릴 경우 플랫폼이 자동으로 에스컬레이션(8192) → 자동분할(파트별 생성→취합)을
> 수행합니다. 소비앱은 이를 인지할 필요 없이 완성된 JSON을 받습니다.
> `max_tokens`를 명시하면 그 값을 상한선으로 사용합니다.

### TOOL_USE — 도구 호출

```json
{
  "id": "search",
  "type": "TOOL_USE",
  "config": {
    "tool_name": "search_hybrid",
    "arguments": { "query": "{{classify.output}}", "top_k": 5 }
  },
  "depends_on": ["classify"]
}
```

> 사용 가능한 도구는 Aimbase에 등록된 43개 MCP 도구입니다.
> 주요 도구: `search_hybrid`, `translate_text`, `generate_document`, `analyze_image`, `send_notification`, `web_search`, `run_python` 등

### CONDITION — 조건 분기

```json
{
  "id": "check",
  "type": "CONDITION",
  "config": {
    "expression": "{{classify.priority}} == 'critical'",
    "true_step": "escalate",
    "false_step": "auto_assign"
  },
  "depends_on": ["classify"]
}
```

### HUMAN_INPUT — 사람 승인 대기

```json
{
  "id": "review",
  "type": "HUMAN_INPUT",
  "config": {
    "message": "이슈 {{input.issue_id}} 에스컬레이션 검토",
    "timeout_hours": 24
  },
  "depends_on": ["escalate"]
}
```

### SUB_WORKFLOW — 하위 워크플로우 호출

```json
{
  "id": "run_report",
  "type": "SUB_WORKFLOW",
  "config": { "workflow_id": "report-generator" },
  "depends_on": ["classify"]
}
```

---

## 5. 변수 치환

| 패턴 | 설명 | 예시 |
|------|------|------|
| `{{input.필드}}` | 실행 시 전달한 입력값 | `{{input.issue_title}}` |
| `{{스텝ID.output}}` | 이전 스텝의 전체 출력 | `{{classify.output}}` |
| `{{스텝ID.필드}}` | 이전 스텝 출력의 특정 필드 | `{{classify.priority}}` |

---

## 6. DAG 의존성

- `depends_on`: 선행 스텝 ID 배열 (빈 배열이면 시작 스텝)
- 순환 의존 금지
- Kahn 알고리즘으로 위상 정렬 후 실행

```
[classify] → [search]     → [summarize] → [notify]
           → [check] → [escalate] → [review]
                     → [auto_assign]
```

---

## 7. 전체 예시: 이슈 자동 분류 워크플로우

Claude Code에 다음과 같이 요청합니다:

```
AXOPM용 이슈 자동 분류 워크플로우를 만들어줘.

요구사항:
1. 이슈 제목과 내용을 받아서 LLM으로 분류 (category, priority)
2. 유사 이슈를 지식 소스에서 검색
3. critical이면 슬랙 긴급 알림, 아니면 일반 알림
4. 연결: {connection_id} 사용
5. 도메인: axopm, 프로젝트: axopm-main
```

Claude Code가 생성하는 워크플로우:

```json
{
  "name": "이슈 자동 분류 및 할당",
  "domain": "axopm",
  "project_id": "axopm-main",
  "trigger_config": { "type": "webhook", "path": "/hooks/issue-classify" },
  "steps": [
    {
      "id": "classify",
      "type": "LLM_CALL",
      "config": {
        "connection_id": "{연결ID}",
        "system": "프로젝트 이슈를 분류하는 전문가입니다.",
        "prompt": "이슈: {{input.title}}\n내용: {{input.description}}\n\n카테고리(bug/feature/improvement/question)와 우선순위(critical/high/medium/low)를 JSON으로 반환하세요.",
        "response_schema": {
          "type": "object",
          "properties": {
            "category": { "type": "string" },
            "priority": { "type": "string" },
            "summary": { "type": "string" }
          }
        }
      }
    },
    {
      "id": "find_similar",
      "type": "TOOL_USE",
      "config": {
        "tool_name": "search_hybrid",
        "arguments": { "query": "{{classify.summary}}", "top_k": 3 }
      },
      "depends_on": ["classify"]
    },
    {
      "id": "check_critical",
      "type": "CONDITION",
      "config": {
        "expression": "{{classify.priority}} == 'critical'",
        "true_step": "urgent_notify",
        "false_step": "normal_notify"
      },
      "depends_on": ["classify"]
    },
    {
      "id": "urgent_notify",
      "type": "TOOL_USE",
      "config": {
        "tool_name": "send_notification",
        "arguments": {
          "channel": "slack",
          "message": "[긴급] {{input.title}} — {{classify.category}} / {{classify.priority}}"
        }
      },
      "depends_on": ["check_critical"]
    },
    {
      "id": "normal_notify",
      "type": "TOOL_USE",
      "config": {
        "tool_name": "send_notification",
        "arguments": {
          "channel": "slack",
          "message": "분류 완료: {{input.title}} → {{classify.category}} ({{classify.priority}})"
        }
      },
      "depends_on": ["check_critical"]
    }
  ],
  "error_handling": { "strategy": "retry", "max_retries": 2 }
}
```

테스트:
```
방금 만든 워크플로우를 실행해줘.
입력: { "title": "로그인 500 에러", "description": "소셜 로그인 시 간헐적 500 에러 발생" }
```

---

## 8. 비즈니스 규칙

| 규칙 | 설명 |
|------|------|
| BIZ-001 | 도구 호출 루프 최대 5회 |
| BIZ-009 | DAG 실행: Kahn 알고리즘 위상 정렬 |
| BIZ-005 | 정책 평가: priority 내림차순, 첫 DENY에서 중단 |
| BIZ-020 | 모든 주요 이벤트 감사 로깅 |

---

## 부록: 워크플로우 실행 상태

| status | 설명 |
|--------|------|
| `running` | 실행 중 |
| `completed` | 정상 완료 |
| `failed` | 스텝 실행 실패 |
| `pending_approval` | HUMAN_INPUT 대기 중 |

HUMAN_INPUT 대기 시 `approve_workflow_run` 도구로 승인/거부합니다:
```
run_id: {실행ID}, approved: true, reason: "확인 완료"
```
