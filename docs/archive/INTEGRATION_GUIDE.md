# Aimbase — 외부 서비스 연동 가이드

> 이 문서는 Aimbase을 외부 서비스에서 연동하거나, MCP 서버를 구현해 플랫폼에 등록하려는 개발자를 대상으로 합니다.

---

## 목차

1. [플랫폼 개요](#1-플랫폼-개요)
2. [사전 준비](#2-사전-준비)
3. [API 연동 가이드](#3-api-연동-가이드)
   - 3.1 Chat API (단순 LLM 호출)
   - 3.2 Workflow API (다단계 자동화)
   - 3.3 Knowledge Search API (RAG)
4. [MCP 서버 구현 가이드](#4-mcp-서버-구현-가이드)
   - 4.1 MCP 프로토콜 개요
   - 4.2 엔드포인트 구현 (JSON-RPC 2.0)
   - 4.3 플랫폼에 등록
5. [공통 응답 형식](#5-공통-응답-형식)
6. [에러 처리](#6-에러-처리)

---

## 1. 플랫폼 개요

Aimbase은 여러 LLM 제공자(Claude, GPT 등)를 단일 API로 통합 관리하는 **AI 미들웨어**입니다.

```
외부 서비스
    │
    ▼
Aimbase (http://aimbase:8080)
    ├── LLM 호출 관리 (Claude, OpenAI, Ollama)
    ├── 워크플로우 실행
    ├── 정책/비용 관리
    └── MCP 도구 중계
         │
         ▼
    외부 MCP 서버 (각 서비스에서 구현)
```

**Base URL**: `http://{aimbase-host}:8080`

---

## 2. 사전 준비

### 2.1 Connection 등록 (어드민 1회 설정)

어드민 UI 또는 API로 LLM 연결을 등록합니다.

```http
POST /api/v1/connections
Content-Type: application/json

{
  "id": "claude-main",
  "name": "Claude (Anthropic)",
  "adapter": "anthropic",
  "type": "llm",
  "config": {
    "apiKey": "sk-ant-...",
    "model": "anthropic/claude-sonnet-4-5"
  }
}
```

| adapter 값 | 제공자 |
|---|---|
| `anthropic` | Anthropic Claude |
| `openai` | OpenAI GPT |
| `ollama` | Ollama (로컬) |

---

## 3. API 연동 가이드

### 3.1 Chat API — 단순 LLM 호출

가장 기본적인 사용 방식입니다. 메시지를 보내고 LLM 응답을 받습니다.

**엔드포인트**
```
POST /api/v1/chat/completions
```

**요청**
```json
{
  "connection_id": "claude-main",
  "model": "anthropic/claude-sonnet-4-5",
  "session_id": "optional-세션-id",
  "messages": [
    {
      "role": "system",
      "content": "당신은 전문 문서 작성 도우미입니다."
    },
    {
      "role": "user",
      "content": "다음 내용을 요약해주세요: ..."
    }
  ],
  "stream": false,
  "actions_enabled": false
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `connection_id` | 선택 | 등록된 Connection ID. 없으면 기본값 사용 |
| `model` | 선택 | 모델 ID. `connection_id`가 있으면 해당 Connection의 기본 모델 사용 |
| `session_id` | 선택 | 대화 세션 유지용 ID. 같은 ID로 호출하면 이전 대화 컨텍스트 유지 |
| `messages` | **필수** | 메시지 배열. `role`: `user` / `assistant` / `system` |
| `stream` | 선택 | `true`이면 SSE 스트리밍 응답 |
| `actions_enabled` | 선택 | `true`이면 Tool Use(MCP 도구 호출) 활성화 |

**응답 (stream: false)**
```json
{
  "success": true,
  "data": {
    "id": "msg_01abc...",
    "model": "claude-sonnet-4-5",
    "session_id": "my-session-123",
    "content": [
      { "type": "text", "text": "요약 내용입니다..." }
    ],
    "actions_executed": [],
    "usage": {
      "input_tokens": 150,
      "output_tokens": 80,
      "cost_usd": 0.000345
    }
  }
}
```

**응답 (stream: true)** — SSE 이벤트 스트림
```
event: delta
data: {"delta": "요약"}

event: delta
data: {"delta": " 내용입니다"}

event: done
data: {"done": true}
```

**코드 예시 (Java)**
```java
RestTemplate restTemplate = new RestTemplate();

Map<String, Object> request = Map.of(
    "connection_id", "claude-main",
    "messages", List.of(
        Map.of("role", "user", "content", "안녕하세요")
    ),
    "stream", false,
    "actions_enabled", false
);

Map response = restTemplate.postForObject(
    "http://aimbase:8080/api/v1/chat/completions",
    request,
    Map.class
);
```

**코드 예시 (Python)**
```python
import requests

response = requests.post(
    "http://aimbase:8080/api/v1/chat/completions",
    json={
        "connection_id": "claude-main",
        "messages": [{"role": "user", "content": "안녕하세요"}],
        "stream": False,
        "actions_enabled": False
    }
)
result = response.json()["data"]["content"][0]["text"]
```

**코드 예시 (Node.js)**
```javascript
const response = await fetch("http://aimbase:8080/api/v1/chat/completions", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    connection_id: "claude-main",
    messages: [{ role: "user", content: "안녕하세요" }],
    stream: false,
    actions_enabled: false
  })
});
const { data } = await response.json();
const text = data.content[0].text;
```

---

### 3.2 Workflow API — 다단계 자동화

사전에 정의된 시나리오를 실행합니다. 문서 생성, 데이터 처리 등 복잡한 작업에 적합합니다.

#### 워크플로우 등록 (어드민 1회 설정)

```http
POST /api/v1/workflows
Content-Type: application/json

{
  "id": "document-generator",
  "name": "문서 자동 생성",
  "domain": "my-service",
  "triggerConfig": { "type": "manual" },
  "steps": [
    {
      "id": "analyze",
      "type": "LLM",
      "connection_id": "claude-main",
      "prompt": "다음 정보를 분석하여 핵심 요구사항을 추출하세요:\n\n{{input}}"
    },
    {
      "id": "write",
      "type": "LLM",
      "connection_id": "claude-main",
      "prompt": "분석 결과: {{analyze.output}}\n\n위 내용을 바탕으로 {{documentType}} 문서를 작성하세요."
    }
  ]
}
```

#### 워크플로우 실행

```http
POST /api/v1/workflows/{workflowId}/run
Content-Type: application/json

{
  "input": "처리할 내용...",
  "documentType": "제안서"
}
```

**응답** (202 Accepted — 비동기 실행)
```json
{
  "success": true,
  "data": {
    "id": "run-uuid-here",
    "workflowId": "document-generator",
    "status": "RUNNING",
    "startedAt": "2026-02-24T10:00:00Z"
  }
}
```

#### 실행 결과 조회 (Polling)

```http
GET /api/v1/workflows/{workflowId}/runs/{runId}
```

```json
{
  "success": true,
  "data": {
    "id": "run-uuid-here",
    "status": "COMPLETED",
    "output": { "write": "생성된 문서 내용..." },
    "completedAt": "2026-02-24T10:00:15Z"
  }
}
```

**status 값**

| 값 | 의미 |
|---|---|
| `RUNNING` | 실행 중 (계속 polling) |
| `COMPLETED` | 완료 |
| `FAILED` | 실패 |
| `WAITING_APPROVAL` | 사람 승인 대기 |

#### Polling 예시 (Java)

```java
String runId = startWorkflow(input);

// 최대 60초 polling
for (int i = 0; i < 30; i++) {
    Thread.sleep(2000);
    Map run = getWorkflowRun(workflowId, runId);
    String status = (String) run.get("status");

    if ("COMPLETED".equals(status)) {
        return (Map) run.get("output");
    } else if ("FAILED".equals(status)) {
        throw new RuntimeException("Workflow failed");
    }
}
throw new TimeoutException("Workflow timed out");
```

---

### 3.3 Knowledge Search API — RAG 벡터 검색

등록된 지식 소스에서 관련 내용을 검색합니다.

```http
POST /api/v1/knowledge-sources/search
Content-Type: application/json

{
  "query": "검색할 내용",
  "sourceId": "my-knowledge-base",
  "topK": 5
}
```

**응답**
```json
{
  "success": true,
  "data": {
    "query": "검색할 내용",
    "results": [
      {
        "content": "관련 문서 청크...",
        "score": 0.92,
        "metadata": { "source": "doc1.pdf", "page": 3 }
      }
    ]
  }
}
```

---

## 4. MCP 서버 구현 가이드

MCP(Model Context Protocol) 서버를 구현하면 LLM이 워크플로우 실행 중에 여러분의 서비스 데이터에 직접 접근할 수 있습니다.

### 4.1 MCP 프로토콜 개요

MCP는 **JSON-RPC 2.0** over HTTP를 사용합니다. 구현해야 할 메서드는 3가지입니다.

| JSON-RPC 메서드 | 역할 |
|---|---|
| `initialize` | 서버 정보 및 지원 기능 반환 |
| `tools/list` | 사용 가능한 도구 목록 반환 |
| `tools/call` | 도구 실행 |

**단일 엔드포인트**: `POST /mcp`

### 4.2 엔드포인트 구현

#### Spring Boot (Java) 예시

```java
@RestController
@RequestMapping("/mcp")
public class McpServerController {

    // tools/list에서 반환할 도구 정의
    private static final List<Map<String, Object>> TOOLS = List.of(
        Map.of(
            "name", "search_data",
            "description", "키워드로 데이터를 검색합니다",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "keyword", Map.of("type", "string", "description", "검색 키워드"),
                    "limit",   Map.of("type", "integer", "description", "최대 결과 수", "default", 10)
                ),
                "required", List.of("keyword")
            )
        ),
        Map.of(
            "name", "save_result",
            "description", "처리 결과를 저장합니다",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "id",      Map.of("type", "string"),
                    "content", Map.of("type", "string")
                ),
                "required", List.of("id", "content")
            )
        )
    );

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Map<String, Object> handle(@RequestBody Map<String, Object> body) {
        String method = (String) body.get("method");
        Object id = body.get("id");

        return switch (method) {
            case "initialize"  -> success(id, Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities",    Map.of("tools", Map.of()),
                "serverInfo",      Map.of("name", "my-service-mcp", "version", "1.0.0")
            ));
            case "tools/list"  -> success(id, Map.of("tools", TOOLS));
            case "tools/call"  -> handleToolCall(id, body);
            default            -> error(id, -32601, "Method not found: " + method);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolCall(Object id, Map<String, Object> body) {
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        String toolName = (String) params.get("name");
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        try {
            Object result = switch (toolName) {
                case "search_data"  -> searchData(args);
                case "save_result"  -> saveResult(args);
                default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
            };

            return success(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", result.toString()))
            ));
        } catch (Exception e) {
            return success(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())),
                "isError", true
            ));
        }
    }

    // ─── 실제 비즈니스 로직 ───────────────────────────────────────────────

    private Object searchData(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        int limit = args.containsKey("limit") ? (Integer) args.get("limit") : 10;
        // TODO: 실제 DB 조회 로직
        return "검색 결과: " + keyword;
    }

    private Object saveResult(Map<String, Object> args) {
        String id = (String) args.get("id");
        String content = (String) args.get("content");
        // TODO: 실제 저장 로직
        return "저장 완료: " + id;
    }

    // ─── JSON-RPC 응답 헬퍼 ──────────────────────────────────────────────

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        return Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "error", Map.of("code", code, "message", message)
        );
    }
}
```

#### FastAPI (Python) 예시

```python
from fastapi import FastAPI
from pydantic import BaseModel
from typing import Any

app = FastAPI()

TOOLS = [
    {
        "name": "search_data",
        "description": "키워드로 데이터를 검색합니다",
        "inputSchema": {
            "type": "object",
            "properties": {
                "keyword": {"type": "string"},
                "limit":   {"type": "integer", "default": 10}
            },
            "required": ["keyword"]
        }
    }
]

@app.post("/mcp")
async def mcp_handler(body: dict):
    method = body.get("method")
    rpc_id = body.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0", "id": rpc_id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "my-service-mcp", "version": "1.0.0"}
            }
        }

    elif method == "tools/list":
        return {"jsonrpc": "2.0", "id": rpc_id, "result": {"tools": TOOLS}}

    elif method == "tools/call":
        params = body.get("params", {})
        tool_name = params.get("name")
        args = params.get("arguments", {})

        try:
            if tool_name == "search_data":
                result = search_data(args["keyword"], args.get("limit", 10))
            else:
                raise ValueError(f"Unknown tool: {tool_name}")

            return {
                "jsonrpc": "2.0", "id": rpc_id,
                "result": {"content": [{"type": "text", "text": str(result)}]}
            }
        except Exception as e:
            return {
                "jsonrpc": "2.0", "id": rpc_id,
                "result": {"content": [{"type": "text", "text": f"Error: {e}"}], "isError": True}
            }

def search_data(keyword: str, limit: int) -> str:
    # TODO: 실제 DB 조회
    return f"검색 결과: {keyword}"
```

#### Express (Node.js) 예시

```javascript
const express = require('express');
const app = express();
app.use(express.json());

const TOOLS = [
  {
    name: 'search_data',
    description: '키워드로 데이터를 검색합니다',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string' },
        limit: { type: 'integer', default: 10 }
      },
      required: ['keyword']
    }
  }
];

app.post('/mcp', async (req, res) => {
  const { method, id, params } = req.body;

  if (method === 'initialize') {
    return res.json({ jsonrpc: '2.0', id, result: {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'my-service-mcp', version: '1.0.0' }
    }});
  }

  if (method === 'tools/list') {
    return res.json({ jsonrpc: '2.0', id, result: { tools: TOOLS } });
  }

  if (method === 'tools/call') {
    const { name, arguments: args } = params;
    try {
      let result;
      if (name === 'search_data') {
        result = await searchData(args.keyword, args.limit ?? 10);
      } else {
        throw new Error(`Unknown tool: ${name}`);
      }
      return res.json({ jsonrpc: '2.0', id,
        result: { content: [{ type: 'text', text: String(result) }] }
      });
    } catch (e) {
      return res.json({ jsonrpc: '2.0', id,
        result: { content: [{ type: 'text', text: `Error: ${e.message}` }], isError: true }
      });
    }
  }

  res.json({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found' } });
});

async function searchData(keyword, limit) {
  // TODO: 실제 DB 조회
  return `검색 결과: ${keyword}`;
}

app.listen(8081);
```

---

### 4.3 플랫폼에 MCP 서버 등록

MCP 서버를 구현하고 실행한 후 Aimbase에 등록합니다.

```http
POST /api/v1/mcp-servers
Content-Type: application/json

{
  "id": "my-service-mcp",
  "name": "My Service MCP",
  "url": "http://my-service:8081/mcp",
  "type": "HTTP"
}
```

도구 목록 확인 (Discover):
```http
POST /api/v1/mcp-servers/my-service-mcp/discover
```

응답:
```json
{
  "success": true,
  "data": {
    "tools": ["search_data", "save_result"]
  }
}
```

이제 워크플로우에서 `actions_enabled: true`로 실행하면 LLM이 자동으로 이 도구들을 사용합니다.

---

### 4.4 도구 설계 원칙

**좋은 도구 설계**
```
✅ 하나의 도구 = 하나의 명확한 작업
✅ description에 언제 사용해야 하는지 명확히 기술
✅ 필수 파라미터만 required로 지정
✅ 에러 발생 시 isError: true로 반환 (예외를 던지지 않음)
✅ 결과는 텍스트로 직렬화해서 반환
```

**나쁜 도구 설계**
```
❌ 너무 범용적인 도구 ("do_everything")
❌ 파라미터가 10개 이상
❌ 예외를 외부로 던짐 (서버 500 에러)
❌ 바이너리 데이터를 직접 반환
```

---

## 5. 공통 응답 형식

모든 API 응답은 다음 형식을 따릅니다.

**성공**
```json
{
  "success": true,
  "data": { ... }
}
```

**페이지네이션**
```json
{
  "success": true,
  "data": {
    "content": [ ... ],
    "totalElements": 100,
    "totalPages": 5,
    "number": 0,
    "size": 20
  }
}
```

**실패**
```json
{
  "success": false,
  "message": "Connection not found: invalid-id"
}
```

---

## 6. 에러 처리

| HTTP 상태 | 의미 | 대응 |
|---|---|---|
| `400` | 잘못된 요청 (파라미터 오류) | 요청 필드 확인 |
| `404` | 리소스 없음 (잘못된 ID) | ID 확인 |
| `500` | 서버 내부 오류 | 관리자에게 문의 |
| `202` | 비동기 작업 수락됨 | runId로 polling |

**재시도 권장 상황**: `500`, 네트워크 타임아웃
**재시도 금지**: `400`, `404` (요청 자체가 잘못됨)

---

## 빠른 시작 체크리스트

```
□ 1. Aimbase 서버 URL 확인
□ 2. Connection 등록 (어드민 UI 또는 POST /api/v1/connections)
□ 3. Chat API 테스트 호출
□ 4. (필요시) 워크플로우 정의 및 등록
□ 5. (필요시) MCP 서버 구현 → POST /mcp 엔드포인트 노출
□ 6. MCP 서버 등록 → POST /api/v1/mcp-servers
□ 7. Discover로 도구 목록 확인
□ 8. actions_enabled: true 로 워크플로우 테스트
```
