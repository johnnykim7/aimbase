# Aimbase — 전체 설계서

> **Version**: 2.0  
> **Date**: 2025-02-22  
> **Status**: Architecture Design (Draft)

---

## 1. Executive Summary

### 1.1 프로젝트 개요

본 플랫폼은 다양한 LLM(OpenAI, Claude, LLaMA, Gemini 등)과 MCP(Model Context Protocol)를 범용적으로 연동하고, LLM의 추론 결과를 실제 세계에 반영하는 **종단 액션(End Actions)**까지 통합 관리하는 오케스트레이션 솔루션이다.

### 1.2 핵심 설계 철학

```
"모델은 교체 가능한 부품, Tool은 공유 자산, 액션은 Write + Notify로 수렴한다"
```

- LLM은 추상화된 인터페이스 뒤에 숨긴다 (모델 교체 시 비즈니스 로직 변경 없음)
- MCP를 통해 Tool/Resource를 모델에 무관하게 공유한다
- 종단 액션은 **Write**(데이터 저장)와 **Notify**(이벤트 발행) 두 가지 primitive로 정규화한다
- UI를 포함한 모든 소비자는 데이터/이벤트를 구독해서 반영한다

### 1.3 핵심 원칙

| 원칙 | 설명 |
|------|------|
| **Model Agnostic** | 어떤 LLM이든 동일한 인터페이스로 사용 |
| **Action Simplicity** | 모든 종단 행위는 Write + Notify로 환원 |
| **Extension First** | 코어는 최소화, 도메인 특화는 확장으로 |
| **Safety by Default** | 모든 액션은 Policy를 거쳐 실행 |
| **Event-Driven** | UI 포함 모든 소비자는 이벤트 구독 방식 |

---

## 2. 전체 아키텍처

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Consumers (구독자)                        │
│   ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐  │
│   │ Web UI │  │Mobile  │  │  Bot   │  │ Batch  │  │External│  │
│   │        │  │  App   │  │(Slack) │  │  Job   │  │ System │  │
│   └───┬────┘  └───┬────┘  └───┬────┘  └───┬────┘  └───┬────┘  │
│       └───────────┴──────┬────┴───────────┴───────────┘        │
│                          │ Subscribe (WS/SSE/Poll/Webhook)      │
└──────────────────────────┼──────────────────────────────────────┘
                           │
┌──────────────────────────┼──────────────────────────────────────┐
│                    API Gateway Layer                             │
│   ┌──────────────────────▼─────────────────────────────┐        │
│   │  Unified REST / WebSocket / gRPC API                │        │
│   │  • Authentication & Rate Limiting                   │        │
│   │  • Request Routing                                  │        │
│   │  • API Versioning                                   │        │
│   └──────────────────────┬─────────────────────────────┘        │
└──────────────────────────┼──────────────────────────────────────┘
                           │
┌──────────────────────────┼──────────────────────────────────────┐
│                  Orchestration Layer                             │
│   ┌──────────────────────▼─────────────────────────────┐        │
│   │              Orchestrator Engine                    │        │
│   │  ┌───────────┐ ┌───────────┐ ┌───────────────┐    │        │
│   │  │  Router   │ │ Workflow  │ │    Chain       │    │        │
│   │  │ (모델선택) │ │  Engine   │ │   Manager     │    │        │
│   │  └───────────┘ └───────────┘ └───────────────┘    │        │
│   └──┬──────────────┬──────────────┬──────────────┬────┘        │
└──────┼──────────────┼──────────────┼──────────────┼─────────────┘
       │              │              │              │
 ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼──────┐
 │ RAG Layer │ │ LLM Layer │ │ MCP Layer │ │Action Layer│
 │           │ │           │ │           │ │            │
 │(Section5) │ │(Section3) │ │(Section4) │ │ (Section6) │
 └─────┬─────┘ └───────────┘ └───────────┘ └────────────┘
       │
 ┌─────▼──────────────────────────┐
 │         Vector Store           │
 │  (pgvector / Pinecone / etc.)  │
 └────────────────────────────────┘
```

### 2.2 Layer 구성 요약

| Layer | 역할 | 핵심 컴포넌트 |
|-------|------|--------------|
| **API Gateway** | 외부 진입점, 인증, 라우팅 | Auth, Rate Limiter, Router |
| **Orchestration** | 요청 해석, 모델 선택, 워크플로우 실행 | Router, Workflow Engine, Chain Manager |
| **LLM** | 멀티 모델 추상화, 프롬프트 변환 | Model Adapters, Prompt Transformer |
| **MCP** | Tool/Resource 연동 관리 | MCP Client Manager, Tool Registry |
| **RAG** | 지식 수집, 임베딩, 검색, 컨텍스트 주입 | Ingestor, Chunker, Embedder, Retriever |
| **Action** | 종단 액션 실행 (Write + Notify) | Action Router, Adapters, Policy Engine |
| **Data** | 상태, 세션, 컨텍스트 저장 | Session Store, Context DB, Event Store |

---

## 3. LLM Abstraction Layer

### 3.1 설계 목표

어떤 LLM을 사용하든 상위 레이어는 동일한 인터페이스로 호출하며, 모델별 차이(프롬프트 포맷, Tool Calling 방식, 응답 구조, 스트리밍 방식)를 내부에서 흡수한다.

### 3.2 Unified Model Interface

```typescript
interface LLMRequest {
  model: string;                    // "openai/gpt-4o", "anthropic/claude-sonnet", "meta/llama3"
  messages: UnifiedMessage[];       // 통일된 메시지 포맷
  tools?: UnifiedToolDef[];         // 통일된 Tool 정의
  config?: ModelConfig;             // temperature, max_tokens 등
  stream?: boolean;                 // 스트리밍 여부
}

interface UnifiedMessage {
  role: "system" | "user" | "assistant" | "tool_result";
  content: ContentBlock[];          // text, image, document 등
}

interface ContentBlock {
  type: "text" | "image" | "document" | "tool_use" | "tool_result";
  data: any;
}

interface LLMResponse {
  id: string;
  model: string;
  content: ContentBlock[];
  tool_calls?: ToolCall[];
  usage: { input_tokens: number; output_tokens: number; };
  finish_reason: "end" | "tool_use" | "max_tokens" | "error";
  metadata: ResponseMetadata;       // latency, cost 등
}
```

### 3.3 Model Adapter 구조

```
┌─────────────────────────────────────────┐
│          LLM Adapter Registry           │
│                                         │
│  ┌──────────────┐  ┌──────────────┐    │
│  │ OpenAI       │  │ Anthropic    │    │
│  │ Adapter      │  │ Adapter      │    │
│  │              │  │              │    │
│  │ • GPT-4o     │  │ • Claude 4   │    │
│  │ • GPT-4o-mini│  │ • Sonnet     │    │
│  │ • o1/o3      │  │ • Haiku      │    │
│  └──────────────┘  └──────────────┘    │
│                                         │
│  ┌──────────────┐  ┌──────────────┐    │
│  │ Meta         │  │ Google       │    │
│  │ Adapter      │  │ Adapter      │    │
│  │              │  │              │    │
│  │ • LLaMA 3   │  │ • Gemini Pro │    │
│  │ • LLaMA 4   │  │ • Gemini Flash│   │
│  └──────────────┘  └──────────────┘    │
│                                         │
│  ┌──────────────┐                      │
│  │ Custom       │  ← 자체 모델, vLLM   │
│  │ Adapter      │    호스팅 등 지원     │
│  └──────────────┘                      │
└─────────────────────────────────────────┘
```

각 Adapter가 처리하는 변환:

| 변환 항목 | 설명 |
|----------|------|
| **Prompt Format** | system prompt 위치, 포맷 차이 흡수 |
| **Tool Calling** | OpenAI function_calling ↔ Claude tool_use ↔ LLaMA custom format |
| **Streaming** | SSE, WebSocket 등 스트리밍 프로토콜 통일 |
| **Response Parsing** | 응답 구조를 UnifiedResponse로 정규화 |
| **Error Mapping** | 모델별 에러코드를 통일된 에러 체계로 매핑 |
| **Token Counting** | 모델별 토크나이저 차이 처리 |

### 3.4 Model Router (Smart Routing)

```typescript
interface RoutingPolicy {
  strategy: "fixed" | "cost_optimized" | "quality_first" | "latency_first" | "fallback_chain";

  // 비용 최적화: 작업 난이도에 따라 모델 자동 선택
  cost_rules?: {
    simple_query: string;     // "anthropic/haiku"
    complex_reasoning: string; // "anthropic/claude-opus"
    code_generation: string;   // "anthropic/claude-sonnet"
  };

  // 폴백 체인: 실패 시 다음 모델로
  fallback_chain?: string[];   // ["openai/gpt-4o", "anthropic/claude-sonnet", "meta/llama3"]

  // 로드밸런싱
  load_balance?: {
    models: string[];
    weights: number[];
  };
}
```

### 3.5 Prompt Transformer

모델 간 프롬프트 호환성을 자동 처리한다.

```typescript
interface PromptTransformer {
  // 통일 포맷 → 모델 네이티브 포맷
  toNative(messages: UnifiedMessage[], targetModel: string): NativePrompt;

  // 모델 네이티브 응답 → 통일 포맷
  fromNative(response: NativeResponse, sourceModel: string): LLMResponse;

  // Tool 정의 변환
  transformTools(tools: UnifiedToolDef[], targetModel: string): NativeToolDef[];
}
```

주요 변환 매핑:

| 항목 | OpenAI | Claude | LLaMA |
|------|--------|--------|-------|
| System Prompt | messages[0].role="system" | system 파라미터 | 프롬프트 내 [INST] 태그 |
| Tool 정의 | functions / tools | tools (input_schema) | 프롬프트 내 JSON 또는 커스텀 |
| Tool 호출 | function_call / tool_calls | tool_use content block | 텍스트 파싱 또는 structured output |
| Tool 결과 | role="tool" | role="user" + tool_result | 프롬프트 내 결과 삽입 |
| 이미지 입력 | url 또는 base64 | base64 (source block) | 모델 의존적 |

---

## 4. MCP Integration Layer

### 4.1 설계 목표

MCP(Model Context Protocol) 서버들을 동적으로 관리하고, 어떤 LLM에서든 동일한 Tool/Resource에 접근할 수 있도록 브릿지 역할을 한다.

### 4.2 MCP Manager 구조

```
┌─────────────────────────────────────────────────┐
│                MCP Manager                       │
│                                                  │
│  ┌──────────────────────────────────────┐       │
│  │          Tool Registry               │       │
│  │  • 사용 가능한 Tool 목록 관리          │       │
│  │  • Tool 스키마 캐싱                   │       │
│  │  • Tool 의존성 해석                   │       │
│  └──────────────┬───────────────────────┘       │
│                 │                                 │
│  ┌──────────────▼───────────────────────┐       │
│  │      MCP Client Pool                 │       │
│  │                                      │       │
│  │  ┌──────────┐  ┌──────────┐         │       │
│  │  │ DB MCP   │  │ File MCP │         │       │
│  │  │ Server   │  │ Server   │         │       │
│  │  └──────────┘  └──────────┘         │       │
│  │  ┌──────────┐  ┌──────────┐         │       │
│  │  │ API MCP  │  │ Search   │         │       │
│  │  │ Server   │  │ MCP Svr  │         │       │
│  │  └──────────┘  └──────────┘         │       │
│  │  ┌──────────┐                       │       │
│  │  │ Custom   │  ← 사용자 정의 MCP     │       │
│  │  │ MCP Svr  │                       │       │
│  │  └──────────┘                       │       │
│  └──────────────────────────────────────┘       │
│                                                  │
│  ┌──────────────────────────────────────┐       │
│  │      MCP-to-LLM Bridge              │       │
│  │  • MCP Tool → OpenAI function 변환   │       │
│  │  • MCP Tool → Claude tool 변환       │       │
│  │  • MCP Tool → LLaMA prompt 변환      │       │
│  └──────────────────────────────────────┘       │
└─────────────────────────────────────────────────┘
```

### 4.3 MCP Server 관리

```typescript
interface MCPServerConfig {
  id: string;
  name: string;
  transport: "stdio" | "sse" | "streamable-http";
  command?: string;              // stdio 방식일 때 실행 커맨드
  url?: string;                  // HTTP 방식일 때 엔드포인트
  env?: Record<string, string>;  // 환경 변수
  auto_start: boolean;           // 플랫폼 시작 시 자동 연결
  health_check_interval: number; // 헬스체크 주기 (초)
}

interface MCPManager {
  // 서버 라이프사이클
  registerServer(config: MCPServerConfig): Promise<void>;
  unregisterServer(id: string): Promise<void>;
  getServerStatus(id: string): ServerStatus;

  // Tool 디스커버리
  listAvailableTools(): Promise<ToolDefinition[]>;
  getToolSchema(toolName: string): Promise<ToolSchema>;

  // Tool 실행 (LLM에서 요청 시)
  executeTool(toolName: string, params: any): Promise<ToolResult>;

  // Resource 접근
  listResources(serverId: string): Promise<Resource[]>;
  readResource(uri: string): Promise<ResourceContent>;
}
```

### 4.4 MCP-to-LLM Bridge

MCP Tool 정의를 각 LLM의 네이티브 Tool 포맷으로 자동 변환한다.

```typescript
// MCP Tool 정의 (표준)
{
  name: "query_database",
  description: "Execute SQL query on the database",
  inputSchema: {
    type: "object",
    properties: {
      query: { type: "string", description: "SQL query" },
      database: { type: "string", enum: ["production", "analytics"] }
    },
    required: ["query"]
  }
}

// → OpenAI Function 변환
{
  type: "function",
  function: {
    name: "query_database",
    description: "Execute SQL query on the database",
    parameters: { /* 동일 JSON Schema */ }
  }
}

// → Claude Tool 변환
{
  name: "query_database",
  description: "Execute SQL query on the database",
  input_schema: { /* 동일 JSON Schema */ }
}
```

---

## 5. RAG Layer — 지식 검색 증강 엔진

### 5.1 설계 목표

LLM이 "우리 데이터"를 알고 답변할 수 있도록, 외부 문서/데이터를 수집하고 임베딩하여 질문 시 관련 컨텍스트를 자동으로 주입하는 파이프라인을 제공한다.
**모든 설정은 Admin UI에서 코딩 없이 구성할 수 있어야 한다.**

### 5.2 RAG 전체 구조

```
┌──────────────────────────────────────────────────────────────┐
│                        RAG Layer                             │
│                                                              │
│  ═══ Ingestion Pipeline (수집/가공) ════════════════════════ │
│                                                              │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐ │
│  │ Source    │   │ Document │   │ Chunker  │   │ Embedder │ │
│  │ Connector │──▶│ Parser   │──▶│          │──▶│          │ │
│  │          │   │          │   │          │   │          │ │
│  │ •File    │   │ •PDF     │   │ •Fixed   │   │ •OpenAI  │ │
│  │ •DB      │   │ •DOCX    │   │ •Semantic│   │ •Voyage  │ │
│  │ •API     │   │ •HTML    │   │ •Recursive│  │ •로컬모델│ │
│  │ •S3      │   │ •CSV     │   │ •Custom  │   │ •HuggingF│ │
│  │ •Web     │   │ •Markdown│   │          │   │          │ │
│  │ •MCP     │   │ •Text    │   │          │   │          │ │
│  └──────────┘   └──────────┘   └──────────┘   └────┬─────┘ │
│                                                     │       │
│                                                     ▼       │
│                                              ┌──────────┐   │
│                                              │ Vector   │   │
│                                              │ Store    │   │
│                                              │          │   │
│                                              │ •pgvector│   │
│                                              │ •Pinecone│   │
│                                              │ •Weaviate│   │
│                                              │ •Chroma  │   │
│                                              └─────┬────┘   │
│                                                    │        │
│  ═══ Retrieval Pipeline (검색/주입) ═══════════════╪══════  │
│                                                    │        │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐       │        │
│  │ Query    │   │ Retriever│   │ Context  │       │        │
│  │ Processor│──▶│          │◀──┘ Injector │       │        │
│  │          │   │          │──▶│          │       │        │
│  │ •쿼리변환 │   │ •유사도   │   │ •프롬프트 │       │        │
│  │ •확장    │   │ •하이브리드│   │  삽입    │       │        │
│  │ •HyDE   │   │ •필터링   │   │ •토큰관리 │       │        │
│  │          │   │ •리랭킹   │   │ •출처표시 │       │        │
│  └──────────┘   └──────────┘   └──────────┘       │        │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 5.3 두 개의 파이프라인

RAG는 크게 **두 가지 독립적인 파이프라인**으로 구성된다.

| 파이프라인 | 시점 | 역할 |
|-----------|------|------|
| **Ingestion** (수집) | 사전/배치/실시간 | 문서를 가져와서 → 파싱 → 청킹 → 임베딩 → 벡터DB 저장 |
| **Retrieval** (검색) | 사용자 질문 시 | 질문을 벡터화 → 유사 문서 검색 → 결과를 LLM 프롬프트에 주입 |

두 파이프라인 모두 **설정으로 동작**한다.

### 5.4 Ingestion Pipeline (수집 파이프라인)

#### 5.4.1 Knowledge Source — 데이터 소스

```yaml
# Admin UI에서 설정하는 Knowledge Source 예시
knowledge_sources:

  # 파일 기반
  - id: "product_manual"
    name: "제품 매뉴얼"
    type: "file"
    config:
      path: "/data/manuals/"
      patterns: ["*.pdf", "*.docx", "*.md"]
      recursive: true
    sync:
      schedule: "0 2 * * *"          # 매일 새벽 2시
      mode: "incremental"            # 변경분만 처리

  # DB 기반
  - id: "faq_database"
    name: "FAQ 데이터베이스"
    type: "database"
    config:
      connection: "shop-main-db"     # 기존 연결 재사용
      query: "SELECT question, answer, category FROM faq WHERE active = true"
      id_column: "id"
      content_columns: ["question", "answer"]
      metadata_columns: ["category"]
    sync:
      schedule: "*/30 * * * *"       # 30분마다
      mode: "incremental"
      change_detection: "updated_at"

  # API / 웹 크롤링
  - id: "help_center"
    name: "헬프센터 웹페이지"
    type: "web"
    config:
      urls:
        - "https://help.myshop.com/articles/*"
      depth: 2
      exclude_patterns: ["/admin/*", "/login"]
    sync:
      schedule: "0 4 * * 0"          # 매주 일요일 새벽 4시
      mode: "full"

  # S3 / 클라우드 스토리지
  - id: "internal_docs"
    name: "내부 문서"
    type: "s3"
    config:
      connection: "s3-docs-bucket"
      bucket: "company-docs"
      prefix: "cs-team/"
      patterns: ["*.pdf", "*.pptx"]
    sync:
      schedule: "0 3 * * *"
      mode: "incremental"

  # MCP 리소스 (기존 MCP 서버의 Resource 활용)
  - id: "order_context"
    name: "주문 컨텍스트"
    type: "mcp_resource"
    config:
      mcp_server: "order-service"
      resources: ["order://templates/*", "order://policies/*"]
    sync:
      schedule: "0 * * * *"          # 매시간
      mode: "full"
```

#### 5.4.2 Document Parser — 문서 파싱

```typescript
interface DocumentParser {
  // 파일을 텍스트로 변환
  supportedFormats: string[];
  parse(source: RawDocument): ParsedDocument;
}

interface ParsedDocument {
  id: string;
  content: string;                   // 추출된 텍스트
  metadata: {
    source_id: string;               // Knowledge Source ID
    file_name?: string;
    file_type?: string;
    page_number?: number;
    url?: string;
    created_at?: string;
    custom: Record<string, any>;     // 사용자 정의 메타데이터
  };
}
```

지원 포맷:

| 포맷 | 파서 | 비고 |
|------|------|------|
| PDF | Apache PDFBox / Tika | 테이블, 이미지 내 텍스트(OCR) 포함 |
| DOCX | Apache POI | 표, 목록 구조 보존 |
| HTML | Jsoup | 태그 제거, 본문 추출 |
| CSV / Excel | Apache POI / OpenCSV | 행 단위 또는 셀 결합 |
| Markdown | 자체 파서 | 구조 보존 |
| Plain Text | 없음 (그대로 사용) | |

#### 5.4.3 Chunker — 문서 분할

하나의 문서를 LLM 컨텍스트에 넣기 적합한 크기로 분할한다.

```yaml
# Admin UI에서 설정하는 Chunking 전략
chunking:

  # Knowledge Source별로 다른 전략 가능
  - source_id: "product_manual"
    strategy: "recursive"
    config:
      chunk_size: 1000               # 토큰 수
      chunk_overlap: 200             # 겹침 (문맥 유지용)
      separators:                    # 분할 우선순위
        - "\n## "                    # 마크다운 헤더
        - "\n\n"                     # 빈 줄
        - "\n"                       # 줄바꿈
        - ". "                       # 문장
      preserve_metadata: true

  - source_id: "faq_database"
    strategy: "fixed"
    config:
      chunk_size: 500
      chunk_overlap: 50

  - source_id: "help_center"
    strategy: "semantic"             # 의미 단위 분할 (LLM 기반)
    config:
      model: "anthropic/haiku"
      max_chunk_size: 1500
      min_chunk_size: 200
```

Chunking 전략:

| 전략 | 방식 | 적합한 경우 |
|------|------|------------|
| **fixed** | 고정 크기로 자름 | 균일한 구조의 데이터 (FAQ, 로그) |
| **recursive** | 구분자 기준으로 재귀 분할 | 구조화된 문서 (매뉴얼, 가이드) |
| **semantic** | 의미 단위로 분할 (LLM 활용) | 비정형 텍스트, 긴 문서 |
| **custom** | 사용자 정의 로직 | 특수 포맷 (법률 문서, 논문) |

#### 5.4.4 Embedder — 임베딩

텍스트 청크를 벡터로 변환한다.

```yaml
# Admin UI에서 설정하는 임베딩 모델
embedding:

  # Knowledge Source별로 다른 모델 가능
  - source_id: "product_manual"
    model: "openai/text-embedding-3-small"
    config:
      dimensions: 1536
      batch_size: 100

  - source_id: "faq_database"
    model: "openai/text-embedding-3-small"
    config:
      dimensions: 1536

  # 로컬 임베딩 모델도 가능
  - source_id: "internal_docs"
    model: "ollama/nomic-embed-text"
    config:
      base_url: "http://localhost:11434"
      dimensions: 768
```

임베딩 모델 옵션:

| 모델 | 제공자 | 차원 | 비고 |
|------|--------|------|------|
| text-embedding-3-small | OpenAI | 1536 | 범용, 비용 효율적 |
| text-embedding-3-large | OpenAI | 3072 | 고정밀도 |
| voyage-3 | Voyage AI | 1024 | 코드/기술 문서에 강함 |
| nomic-embed-text | Ollama (로컬) | 768 | 무료, 프라이버시 보장 |
| multilingual-e5 | HuggingFace (로컬) | 768 | 다국어 (한국어 포함) |

#### 5.4.5 Vector Store — 벡터 저장소

```yaml
# Admin UI에서 설정하는 Vector Store
vector_store:
  provider: "pgvector"               # PostgreSQL 확장 (기본)
  config:
    connection: "shop-main-db"       # 기존 DB 연결 재사용
    table: "embeddings"
    dimensions: 1536
    distance_metric: "cosine"        # cosine, l2, inner_product
    index_type: "hnsw"              # hnsw, ivfflat
    hnsw_config:
      m: 16
      ef_construction: 200
```

### 5.5 Retrieval Pipeline (검색 파이프라인)

사용자 질문이 들어오면 Orchestrator가 RAG Retriever를 호출한다.

#### 5.5.1 전체 검색 흐름

```
사용자 질문: "운동화 교환은 몇일 이내에 가능한가요?"
     │
     ▼
┌─ Query Processor ──────────────────────────┐
│ 1. 쿼리 분석: 의도 파악                      │
│ 2. 쿼리 확장: "운동화 교환 기간 반품 정책"    │
│ 3. (선택) HyDE: LLM이 가상 답변 생성 →       │
│    "운동화 교환은 배송 후 14일 이내..."        │
│    → 이걸 검색 쿼리로 사용                    │
└──────────────┬─────────────────────────────┘
               │
               ▼
┌─ Retriever ────────────────────────────────┐
│ 1. 임베딩: 쿼리를 벡터로 변환                 │
│ 2. 유사도 검색: Vector Store에서 top-K 조회   │
│ 3. (선택) 키워드 검색: BM25 병행              │
│ 4. (선택) 메타데이터 필터:                    │
│    source_id = "product_manual"              │
│    category = "교환/반품"                     │
│ 5. (선택) 리랭킹: Cross-encoder로 재정렬     │
└──────────────┬─────────────────────────────┘
               │
               ▼ 검색 결과 (top-K 청크)
┌─ Context Injector ─────────────────────────┐
│ 1. 토큰 계산: 결과가 컨텍스트 윈도우에       │
│    들어가는지 확인                            │
│ 2. 프롬프트 조립:                            │
│    [System Prompt]                           │
│    [검색된 컨텍스트]  ← 여기에 삽입           │
│    [사용자 질문]                              │
│ 3. 출처 정보 첨부 (citation용)               │
└──────────────┬─────────────────────────────┘
               │
               ▼
         LLM 호출 (컨텍스트가 포함된 프롬프트)
```

#### 5.5.2 Retrieval 설정

```yaml
# Admin UI에서 설정하는 Retrieval 전략
retrieval:

  # 기본 설정
  default:
    top_k: 5                         # 상위 5개 청크 검색
    similarity_threshold: 0.7        # 유사도 0.7 미만은 제외
    max_context_tokens: 4000         # 컨텍스트에 최대 4000 토큰
    search_type: "hybrid"            # similarity, keyword, hybrid

  # Knowledge Source별 필터링 (어떤 소스에서 검색할지)
  source_filters:
    - intent: "refund"
      sources: ["product_manual", "faq_database"]
      metadata_filter:
        category: ["교환/반품", "환불"]

    - intent: "product_info"
      sources: ["product_manual"]

    - intent: "*"                    # 기본값
      sources: ["faq_database", "help_center"]

  # 쿼리 전처리
  query_processing:
    expansion: true                  # 쿼리 확장 (동의어, 관련어)
    hyde: false                      # HyDE (고급, 비용 추가)
    reranking:
      enabled: true
      model: "cross-encoder"         # 리랭킹 모델
      top_k_rerank: 3                # 리랭킹 후 최종 3개 선택

  # 컨텍스트 주입 템플릿
  context_template: |
    아래는 질문과 관련된 참고 자료입니다. 이 정보를 기반으로 답변하세요.
    정보가 부족하면 "확인이 필요합니다"라고 안내하세요.

    ---참고 자료---
    {{#each chunks}}
    [출처: {{this.source_name}} | {{this.metadata.file_name}}]
    {{this.content}}

    {{/each}}
    ---참고 자료 끝---
```

#### 5.5.3 검색 전략

| 전략 | 방식 | 장점 | 단점 |
|------|------|------|------|
| **Similarity** | 벡터 유사도만 사용 | 의미적 검색 가능 | 키워드 정확 매칭 약함 |
| **Keyword** | BM25 (키워드 매칭) | 정확한 용어 검색 | 유사어/동의어 못 잡음 |
| **Hybrid** | Similarity + Keyword 결합 | 두 장점 결합 | 약간 느림 |
| **HyDE** | LLM이 가상 답변 생성 후 검색 | 검색 정확도 높음 | LLM 호출 비용 추가 |

### 5.6 Orchestrator에서의 RAG 흐름

```
사용자 요청
     │
     ▼
Orchestrator
     │
     ├─ 1. 의도 분류 (Intent Detection)
     │      "이 질문에 RAG가 필요한가?"
     │
     ├─ 2. [RAG 필요 시] Retrieval Pipeline 호출
     │      ├─ 소스 필터링 (어디서 찾을지)
     │      ├─ 벡터 검색 (top-K)
     │      ├─ 리랭킹 (정확도 향상)
     │      └─ 컨텍스트 주입 (프롬프트에 삽입)
     │
     ├─ 3. LLM 호출 (검색 결과 + 원래 질문)
     │
     ├─ 4. [Tool 필요 시] MCP Tool 호출
     │
     └─ 5. Action 실행 (Write + Notify)
```

### 5.7 Knowledge Source 관리 인터페이스

```kotlin
interface KnowledgeSource {
    val id: String
    val name: String
    val type: SourceType                     // FILE, DATABASE, WEB, S3, API, MCP_RESOURCE

    suspend fun fetch(): List<RawDocument>   // 원본 문서 수집
    suspend fun fetchIncremental(since: Instant): List<RawDocument>  // 증분 수집
    fun getStatus(): SourceStatus            // 마지막 동기화 시간, 문서 수 등
}

interface IngestionPipeline {
    // 전체 파이프라인 실행
    suspend fun ingest(sourceId: String, mode: SyncMode): IngestionResult

    // 개별 단계
    suspend fun parse(documents: List<RawDocument>): List<ParsedDocument>
    suspend fun chunk(documents: List<ParsedDocument>, strategy: ChunkStrategy): List<Chunk>
    suspend fun embed(chunks: List<Chunk>, model: EmbeddingModel): List<EmbeddedChunk>
    suspend fun store(chunks: List<EmbeddedChunk>, vectorStore: VectorStoreConfig): StoreResult
}

interface Retriever {
    suspend fun retrieve(
        query: String,
        config: RetrievalConfig
    ): RetrievalResult
}

data class RetrievalResult(
    val chunks: List<RetrievedChunk>,
    val query: String,
    val expandedQuery: String?,
    val searchType: String,
    val totalCandidates: Int,
    val latencyMs: Long
)

data class RetrievedChunk(
    val content: String,
    val score: Double,                       // 유사도 점수
    val metadata: Map<String, Any>,          // 출처 정보
    val sourceId: String,
    val sourceName: String
)

data class IngestionResult(
    val sourceId: String,
    val documentsProcessed: Int,
    val chunksCreated: Int,
    val embeddingsStored: Int,
    val errors: List<String>,
    val durationMs: Long
)
```

### 5.8 Admin UI — Knowledge 관리 화면

```
┌──────────────────────────────────────────────────────────┐
│ 📚 Knowledge 관리                        [+ 새 소스 추가] │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  ── Knowledge Sources ───────────────────────────────── │
│                                                          │
│  🟢 제품 매뉴얼                              File (PDF) │
│     /data/manuals/ · 47개 문서 · 1,234 청크             │
│     마지막 동기화: 2시간 전 ✅ · 매일 02:00              │
│     [동기화] [검색 테스트] [설정] [삭제]                  │
│                                                          │
│  🟢 FAQ 데이터베이스                         Database    │
│     shop-main-db → faq 테이블 · 523건 · 523 청크        │
│     마지막 동기화: 28분 전 ✅ · 30분마다                  │
│     [동기화] [검색 테스트] [설정] [삭제]                  │
│                                                          │
│  🟡 헬프센터 웹페이지                        Web Crawl   │
│     help.myshop.com · 89 페이지 · 412 청크              │
│     마지막 동기화: 3일 전 ⚠️ · 매주 일요일              │
│     [동기화] [검색 테스트] [설정] [삭제]                  │
│                                                          │
│  ── 통계 ────────────────────────────────────────────── │
│                                                          │
│  총 문서: 659 · 총 청크: 2,169 · 벡터DB 크기: 124MB     │
│  임베딩 모델: text-embedding-3-small                     │
│  벡터DB: pgvector (shop-main-db)                         │
│                                                          │
│  ── 검색 테스트 ─────────────────────────────────────── │
│  ┌────────────────────────────────────────────────┐     │
│  │ 운동화 교환은 몇일 이내에 가능한가요?            │     │
│  └────────────────────────────────────────────────┘     │
│  [검색]                                                  │
│                                                          │
│  결과 (3건, 0.23초):                                     │
│  ┌────────────────────────────────────────────────┐     │
│  │ 📄 0.94  제품 매뉴얼 > 교환반품정책.pdf p.3     │     │
│  │ "배송 완료일로부터 14일 이내 교환 가능하며,      │     │
│  │  착용 흔적이 없는 상태여야 합니다..."            │     │
│  ├────────────────────────────────────────────────┤     │
│  │ 📄 0.89  FAQ > #127 교환 기간 안내              │     │
│  │ "Q: 교환 기간이 어떻게 되나요?                   │     │
│  │  A: 배송 완료 후 14일 이내 교환 가능합니다..."   │     │
│  ├────────────────────────────────────────────────┤     │
│  │ 📄 0.82  헬프센터 > /articles/exchange-policy   │     │
│  │ "교환/반품 정책: 수령 후 2주 이내..."            │     │
│  └────────────────────────────────────────────────┘     │
│                                                          │
└──────────────────────────────────────────────────────────┘

[+ 새 소스 추가] 클릭 시:
┌──────────────────────────────────────────────────────────┐
│ Knowledge Source 추가                                    │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  소스 유형:                                              │
│  [📁 파일] [🗄 DB] [🌐 웹크롤] [☁ S3] [🔌 MCP] [📡 API]│
│                                                          │
│  ── 📁 파일 선택 시 ──                                   │
│  소스 이름:     [제품 매뉴얼          ]                   │
│  경로:          [/data/manuals/       ]                   │
│  파일 패턴:     [*.pdf, *.docx        ]                   │
│  하위 폴더 포함: [✅]                                    │
│                                                          │
│  ── Chunking 설정 ──                                    │
│  전략:   ● Recursive  ○ Fixed  ○ Semantic               │
│  크기:   [1000] 토큰                                     │
│  겹침:   [200] 토큰                                      │
│                                                          │
│  ── 임베딩 설정 ──                                       │
│  모델:   [text-embedding-3-small ▾]                      │
│                                                          │
│  ── 동기화 설정 ──                                       │
│  주기:   [매일 ▾]  시간: [02:00]                         │
│  모드:   ● 증분  ○ 전체                                  │
│                                                          │
│         [테스트 수집 (5건만)] [저장] [취소]               │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 5.9 RAG와 기존 레이어의 관계

```
┌──────────────────────────────────────────────────────────┐
│                      Orchestrator                        │
│                                                          │
│  요청 들어옴                                              │
│       │                                                  │
│       ├─ RAG 필요 판단                                    │
│       │    │                                             │
│       │    ▼                                             │
│       │  ┌─────────────────────┐                         │
│       │  │ RAG Layer           │                         │
│       │  │ • Retriever 호출    │                         │
│       │  │ • 관련 문서 검색     │                         │
│       │  │ • 컨텍스트에 주입    │                         │
│       │  └─────────┬───────────┘                         │
│       │            │ 검색 결과 포함된 컨텍스트             │
│       │            ▼                                     │
│       ├─ LLM 호출 (풍부한 컨텍스트로)                     │
│       │            │                                     │
│       │            ▼                                     │
│       ├─ [필요 시] MCP Tool 호출                          │
│       │            │                                     │
│       │            ▼                                     │
│       └─ Action 실행 (Write + Notify)                    │
│                                                          │
│  ── 관계 정리 ──                                         │
│  • Connection: 기존 연결 재사용 (DB, S3 등)              │
│  • MCP: MCP Resource를 Knowledge Source로 활용 가능       │
│  • Schema: 검색 결과 구조도 Schema로 정의 가능            │
│  • Policy: Ingestion에도 접근 제어 적용                   │
│  • Action: Ingestion 결과를 Write(벡터DB)로 처리          │
│  • Event: 동기화 완료/실패를 Event로 발행                 │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 5.10 RAG도 Write + Notify 패턴을 따른다

수집(Ingestion)의 결과는 결국 **벡터DB에 Write**하는 것이고,
동기화 상태를 Admin UI에 **Notify**하는 것이다.

```
Ingestion Pipeline
     │
     ├─ Write: 임베딩 결과 → Vector Store (pgvector) 저장
     ├─ Write: 수집 이력 → PostgreSQL (ingestion_logs) 기록
     ├─ Notify: WebSocket → Admin 대시보드 갱신
     └─ Notify: Slack → 동기화 실패 시 알림
```

즉, RAG Layer는 독립적인 별개 시스템이 아니라 기존 Write + Notify 구조 위에서 동작하는 **특화된 파이프라인**이다.

---

## 6. Action Layer — 종단 액션 엔진

### 6.1 핵심 개념

LLM의 추론 결과가 실제 세계에 반영되는 마지막 단계.
모든 종단 액션은 두 가지 primitive로 환원된다:

```
┌──────────────────────────────────────────────┐
│               Action Primitives              │
│                                              │
│   ┌─────────────────┐  ┌──────────────────┐ │
│   │     WRITE       │  │     NOTIFY       │ │
│   │                 │  │                  │ │
│   │ 데이터를 어딘가에 │  │ 이벤트를 어딘가에 │ │
│   │ 저장한다         │  │ 발행한다          │ │
│   │                 │  │                  │ │
│   │ • DB INSERT     │  │ • WebSocket push │ │
│   │ • File Create   │  │ • Webhook call   │ │
│   │ • Cache Set     │  │ • MQ publish     │ │
│   │ • Storage Upload│  │ • SSE event      │ │
│   │ • API POST/PUT  │  │ • Email/SMS      │ │
│   └─────────────────┘  └──────────────────┘ │
└──────────────────────────────────────────────┘
```

**UI 동작도 이 패턴에 포함된다:**

```
LLM → Write(DB에 결과 저장) → Notify(이벤트 발행) → UI가 구독해서 반영
```

UI는 직접 조작 대상이 아니라 이벤트 소비자(Consumer)이다.

### 6.2 Action Request 정규화

```typescript
interface ActionRequest {
  // 1. Intent: 무엇을 할 것인가
  intent: string;                  // "save_data", "generate_file", "send_notification" 등

  // 2. Type: Write인가 Notify인가
  type: "write" | "notify" | "write_and_notify";

  // 3. Target: 어디에 할 것인가
  target: {
    adapter: string;               // "postgresql", "s3", "slack", "websocket" 등
    connection: string;            // 사전 등록된 연결 ID
    destination: string;           // 테이블명, 버킷, 채널 등
  };

  // 4. Payload: 무엇을 쓰거나 보낼 것인가
  payload: {
    schema: string;                // 사전 정의된 스키마 참조
    data: Record<string, any>;     // 실제 데이터
    format?: string;               // "json", "csv", "pdf" 등
  };

  // 5. Policy: 어떤 규칙을 적용할 것인가
  policy: {
    requires_approval: boolean;    // Human-in-the-loop 필요 여부
    approval_channel?: string;     // 승인 요청을 보낼 채널
    retry: RetryPolicy;            // 실패 시 재시도 정책
    rollback?: RollbackPolicy;     // 롤백 정책
    validation_rules?: string[];   // 유효성 검증 규칙 참조
    audit_log: boolean;            // 감사 로그 기록 여부
  };
}
```

### 6.3 Action Adapter Registry

```
┌───────────────────────────────────────────────────┐
│              Action Adapter Registry               │
│                                                    │
│  ── Write Adapters ──────────────────────────────  │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │ RDB        │ │ NoSQL      │ │ File       │    │
│  │ Adapter    │ │ Adapter    │ │ Adapter    │    │
│  │            │ │            │ │            │    │
│  │ •Postgres  │ │ •MongoDB   │ │ •Local FS  │    │
│  │ •MySQL     │ │ •DynamoDB  │ │ •S3        │    │
│  │ •SQLite    │ │ •Redis     │ │ •GCS       │    │
│  └────────────┘ └────────────┘ └────────────┘    │
│  ┌────────────┐ ┌────────────┐                    │
│  │ API        │ │ Vector DB  │                    │
│  │ Adapter    │ │ Adapter    │                    │
│  │            │ │            │                    │
│  │ •REST POST │ │ •Pinecone  │                    │
│  │ •GraphQL   │ │ •Weaviate  │                    │
│  │ •gRPC      │ │ •Chroma    │                    │
│  └────────────┘ └────────────┘                    │
│                                                    │
│  ── Notify Adapters ─────────────────────────────  │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │ Realtime   │ │ Message    │ │ Push       │    │
│  │ Adapter    │ │ Adapter    │ │ Adapter    │    │
│  │            │ │            │ │            │    │
│  │ •WebSocket │ │ •Slack     │ │ •Email     │    │
│  │ •SSE       │ │ •Teams     │ │ •SMS       │    │
│  │ •Socket.io │ │ •Discord   │ │ •FCM/APNs  │    │
│  └────────────┘ └────────────┘ └────────────┘    │
│  ┌────────────┐ ┌────────────┐                    │
│  │ Queue      │ │ Webhook    │                    │
│  │ Adapter    │ │ Adapter    │                    │
│  │            │ │            │                    │
│  │ •Kafka     │ │ •HTTP POST │                    │
│  │ •RabbitMQ  │ │ •Custom    │                    │
│  │ •SQS       │ │            │                    │
│  └────────────┘ └────────────┘                    │
│                                                    │
│  ── Custom Adapters ─────────────────────────────  │
│  ┌────────────────────────────────────────┐       │
│  │ Adapter SDK를 통해 누구나 제작 가능      │       │
│  │ • npm/pip 패키지로 배포                  │       │
│  │ • Marketplace 등록                      │       │
│  └────────────────────────────────────────┘       │
└───────────────────────────────────────────────────┘
```

### 6.4 Adapter Interface

모든 Adapter는 동일한 인터페이스를 구현한다.

```typescript
interface WriteAdapter {
  id: string;
  type: "write";
  supportedFormats: string[];

  // 연결 관리
  connect(config: ConnectionConfig): Promise<void>;
  disconnect(): Promise<void>;
  healthCheck(): Promise<HealthStatus>;

  // 핵심 오퍼레이션
  write(destination: string, data: any, options?: WriteOptions): Promise<WriteResult>;
  read(destination: string, query: any): Promise<ReadResult>;
  update(destination: string, query: any, data: any): Promise<WriteResult>;
  delete(destination: string, query: any): Promise<WriteResult>;
}

interface NotifyAdapter {
  id: string;
  type: "notify";
  supportedChannels: string[];

  connect(config: ConnectionConfig): Promise<void>;
  disconnect(): Promise<void>;
  healthCheck(): Promise<HealthStatus>;

  // 핵심 오퍼레이션
  publish(channel: string, event: Event, options?: NotifyOptions): Promise<NotifyResult>;
  subscribe(channel: string, handler: EventHandler): Promise<Subscription>;
}

// Adapter 등록
interface AdapterRegistry {
  register(adapter: WriteAdapter | NotifyAdapter): void;
  get(adapterId: string): Adapter;
  list(type?: "write" | "notify"): Adapter[];
  unregister(adapterId: string): void;
}
```

### 6.5 실제 액션 흐름 예시

**예시 1: "분석 결과를 DB에 저장하고 Slack으로 알림"**

```
LLM 추론 완료
     │
     ▼
ActionRequest {
  type: "write_and_notify",
  target: [
    { adapter: "postgresql", destination: "analysis_results" },
    { adapter: "slack", destination: "#data-team" }
  ],
  payload: {
    data: { report_id: "R001", summary: "매출 15% 증가...", ... }
  },
  policy: { requires_approval: false, audit_log: true }
}
     │
     ▼
┌─ Policy Engine ──────────────────────┐
│ ✓ 권한 확인 (해당 DB, Slack 접근 가능) │
│ ✓ 스키마 유효성 검증 통과              │
│ ✓ 승인 불필요                         │
└──────────────┬───────────────────────┘
               │
     ┌─────────┴─────────┐
     ▼                   ▼
  [Write]            [Notify]
  PostgreSQL         Slack
  INSERT INTO        POST message
  analysis_results   to #data-team
     │                   │
     ▼                   ▼
  WriteResult        NotifyResult
  { success: true }  { delivered: true }
```

**예시 2: "보고서 PDF 생성 → S3 업로드 → UI에 다운로드 링크 표시"**

```
LLM 추론 완료 (보고서 내용 생성)
     │
     ▼
Action 1: Write (File)
  → PDF 파일 생성 → /tmp/report_001.pdf

Action 2: Write (S3)
  → S3 업로드 → s3://reports/report_001.pdf
  → presigned URL 생성

Action 3: Notify (WebSocket)
  → UI에 이벤트 발행: { type: "file_ready", url: "https://..." }

UI가 구독 중 → 다운로드 버튼 표시
```

**예시 3: "위험한 작업 → 승인 후 실행"**

```
LLM: "프로덕션 DB 고객 테이블에서 비활성 계정 삭제"
     │
     ▼
ActionRequest {
  target: { adapter: "postgresql", connection: "prod", destination: "customers" },
  payload: { data: { query: "DELETE WHERE active = false" } },
  policy: { requires_approval: true, approval_channel: "slack:#db-admins" }
}
     │
     ▼
┌─ Policy Engine ─────────────────┐
│ ⚠ 프로덕션 DB 감지              │
│ ⚠ DELETE 작업 감지              │
│ → requires_approval: true       │
└──────────────┬──────────────────┘
               │
               ▼
         [승인 대기]
         Slack #db-admins로 승인 요청 발송
         "비활성 계정 237건 삭제 요청. 승인하시겠습니까?"
               │
          관리자 승인 ✓
               │
               ▼
         [Write 실행]
         DELETE FROM customers WHERE active = false
               │
               ▼
         [Notify]
         → Slack: "삭제 완료 (237건)"
         → Audit Log: 기록 저장
```

---

## 7. Schema Layer — 데이터 구조 관리

### 7.1 역할

LLM이 생성한 데이터가 올바른 구조를 가지는지 검증하고, Adapter가 해석할 수 있는 형태로 정규화한다.

### 7.2 Schema Registry

```typescript
interface SchemaRegistry {
  // 스키마 관리
  register(schema: DataSchema): void;
  get(schemaId: string): DataSchema;
  validate(schemaId: string, data: any): ValidationResult;
  list(domain?: string): DataSchema[];

  // 스키마 버전 관리
  getVersion(schemaId: string, version: number): DataSchema;
  migrate(schemaId: string, fromVersion: number, toVersion: number, data: any): any;
}

interface DataSchema {
  id: string;
  version: number;
  domain?: string;                // "medical", "legal", "finance" 등
  description: string;
  jsonSchema: JSONSchema;         // JSON Schema 표준 사용
  transforms?: {                  // Adapter별 변환 규칙
    [adapterId: string]: TransformRule;
  };
  validators?: ValidatorRule[];   // 커스텀 유효성 검증
}
```

### 7.3 스키마 정의 예시

```json
{
  "id": "analysis_report",
  "version": 1,
  "domain": "general",
  "description": "LLM 분석 결과 보고서",
  "jsonSchema": {
    "type": "object",
    "properties": {
      "report_id": { "type": "string", "format": "uuid" },
      "title": { "type": "string", "maxLength": 200 },
      "summary": { "type": "string" },
      "data_points": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "metric": { "type": "string" },
            "value": { "type": "number" },
            "unit": { "type": "string" }
          }
        }
      },
      "created_at": { "type": "string", "format": "date-time" },
      "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
    },
    "required": ["report_id", "title", "summary"]
  },
  "transforms": {
    "postgresql": {
      "table": "analysis_reports",
      "column_mapping": {
        "report_id": "id",
        "data_points": "data_points::jsonb"
      }
    },
    "elasticsearch": {
      "index": "reports",
      "mapping_overrides": {
        "summary": { "type": "text", "analyzer": "korean" }
      }
    }
  }
}
```

---

## 8. Policy Layer — 안전 및 규칙 엔진

### 8.1 역할

모든 Action이 실행되기 전에 반드시 거치는 관문. 권한 확인, 유효성 검증, 승인 프로세스, 감사 로깅을 관리한다.

### 8.2 Policy Engine 구조

```
┌─────────────────────────────────────────────────┐
│                 Policy Engine                    │
│                                                  │
│  ┌───────────────────────────────────────┐      │
│  │ 1. Authentication & Authorization     │      │
│  │    • 사용자 신원 확인                   │      │
│  │    • RBAC / ABAC 권한 검사             │      │
│  │    • Adapter별 접근 권한               │      │
│  └───────────────┬───────────────────────┘      │
│                  │ PASS                          │
│  ┌───────────────▼───────────────────────┐      │
│  │ 2. Schema Validation                  │      │
│  │    • 데이터 구조 검증                   │      │
│  │    • 필수 필드 확인                     │      │
│  │    • 포맷/범위 검증                     │      │
│  └───────────────┬───────────────────────┘      │
│                  │ PASS                          │
│  ┌───────────────▼───────────────────────┐      │
│  │ 3. Domain Rules                       │      │
│  │    • 도메인별 비즈니스 규칙 적용         │      │
│  │    • Guardrails (금지 행위 차단)        │      │
│  │    • 위험도 평가                        │      │
│  └───────────────┬───────────────────────┘      │
│                  │ PASS                          │
│  ┌───────────────▼───────────────────────┐      │
│  │ 4. Approval Gate                      │      │
│  │    • 위험도 높은 액션 → 승인 대기        │      │
│  │    • 승인 채널로 요청 발송               │      │
│  │    • 타임아웃 / 자동 거부 처리           │      │
│  └───────────────┬───────────────────────┘      │
│                  │ APPROVED                      │
│  ┌───────────────▼───────────────────────┐      │
│  │ 5. Audit Logging                      │      │
│  │    • 모든 액션 실행 기록                 │      │
│  │    • who, what, when, result 기록      │      │
│  │    • 규제 준수 증빙                     │      │
│  └───────────────────────────────────────┘      │
└─────────────────────────────────────────────────┘
```

### 8.3 Policy 정의

```typescript
interface Policy {
  id: string;
  name: string;
  domain?: string;
  priority: number;              // 높을수록 먼저 평가

  // 이 Policy가 적용되는 조건
  match: {
    actions?: string[];          // ["write", "notify"]
    adapters?: string[];         // ["postgresql", "s3"]
    destinations?: string[];     // ["prod.*", "customers"]
    users?: string[];
    roles?: string[];
  };

  // 규칙
  rules: PolicyRule[];
}

interface PolicyRule {
  type: "require_approval" | "deny" | "allow" | "transform" | "rate_limit" | "log";

  condition?: string;            // 조건 표현식 (예: "payload.size > 1000")
  config: Record<string, any>;   // 규칙별 설정

  // 예시
  // { type: "deny", condition: "target.connection == 'prod' && payload.operation == 'DELETE'" }
  // { type: "require_approval", config: { channel: "slack:#admins", timeout: "30m" } }
  // { type: "rate_limit", config: { max: 100, window: "1h" } }
  // { type: "transform", config: { mask_fields: ["ssn", "credit_card"] } }
}
```

### 8.4 내장 Policy 예시

```yaml
# 프로덕션 DB 보호 정책
- id: protect_production_db
  name: "프로덕션 DB 보호"
  priority: 100
  match:
    adapters: ["postgresql", "mysql"]
    destinations: ["prod.*"]
  rules:
    - type: deny
      condition: "payload.operation == 'DROP'"
      message: "프로덕션 DB에서 DROP은 허용되지 않습니다"
    - type: require_approval
      condition: "payload.operation in ['DELETE', 'UPDATE'] && payload.affected_rows > 100"
      config:
        channel: "slack:#db-admins"
        timeout: "1h"
    - type: log
      config:
        level: "audit"
        include_payload: true

# 개인정보 보호 정책
- id: pii_protection
  name: "개인정보 마스킹"
  priority: 200
  match:
    actions: ["notify"]
  rules:
    - type: transform
      config:
        mask_fields: ["email", "phone", "ssn", "credit_card"]
        mask_pattern: "***"

# API 호출 제한
- id: api_rate_limit
  name: "외부 API 호출 제한"
  match:
    adapters: ["rest_api", "webhook"]
  rules:
    - type: rate_limit
      config:
        max: 1000
        window: "1h"
        per: "user"
```

---

## 9. Orchestration Layer — 오케스트레이션 엔진

### 9.1 역할

사용자 요청을 해석하고, LLM 호출 → MCP Tool 사용 → Action 실행까지의 전체 흐름을 관리한다.

### 9.2 핵심 컴포넌트

```
┌───────────────────────────────────────────────────┐
│              Orchestration Engine                   │
│                                                    │
│  ┌──────────────┐                                  │
│  │ Request      │  요청 해석 및 라우팅               │
│  │ Interpreter  │  • 의도 분류                       │
│  └──────┬───────┘  • 복잡도 판단                     │
│         │          • 모델 선택                       │
│  ┌──────▼───────┐                                  │
│  │ Workflow     │  워크플로우 실행                    │
│  │ Engine       │  • DAG 기반 작업 흐름               │
│  │              │  • 분기, 루프, 병렬 지원             │
│  └──────┬───────┘  • 에러 핸들링 & 재시도             │
│         │                                          │
│  ┌──────▼───────┐                                  │
│  │ Chain        │  LLM 체이닝                       │
│  │ Manager      │  • 순차 체인 (A → B → C)           │
│  │              │  • 분기 체인 (조건부)               │
│  │              │  • 재귀 체인 (자기 참조)             │
│  └──────┬───────┘                                  │
│         │                                          │
│  ┌──────▼───────┐                                  │
│  │ Context      │  컨텍스트 관리                     │
│  │ Manager      │  • 대화 히스토리                    │
│  │              │  • Tool 실행 결과                   │
│  │              │  • 중간 상태 저장                    │
│  └──────────────┘                                  │
└───────────────────────────────────────────────────┘
```

### 9.3 Workflow 정의 (DAG)

```typescript
interface Workflow {
  id: string;
  name: string;
  domain?: string;
  trigger: WorkflowTrigger;
  steps: WorkflowStep[];
  error_handling: ErrorPolicy;
}

interface WorkflowStep {
  id: string;
  type: "llm_call" | "tool_call" | "action" | "condition" | "parallel" | "human_input";
  config: StepConfig;
  depends_on?: string[];           // 선행 스텝 ID
  on_success?: string;             // 다음 스텝 ID
  on_failure?: string;             // 실패 시 스텝 ID
  timeout?: number;
}
```

**Workflow 예시: "데이터 분석 → 보고서 → 알림"**

```yaml
workflow:
  id: data_analysis_report
  name: "데이터 분석 보고서 자동 생성"
  steps:
    - id: fetch_data
      type: tool_call
      config:
        tool: "query_database"
        params: { query: "SELECT * FROM sales WHERE date >= '2025-01-01'" }

    - id: analyze
      type: llm_call
      depends_on: [fetch_data]
      config:
        model: "anthropic/claude-sonnet"
        prompt_template: "analyze_sales_data"
        input: "{{fetch_data.result}}"

    - id: generate_report
      type: action
      depends_on: [analyze]
      config:
        type: write
        adapter: file
        format: pdf
        data: "{{analyze.result}}"

    - id: save_to_db
      type: action
      depends_on: [analyze]
      config:
        type: write
        adapter: postgresql
        destination: analysis_reports
        data: "{{analyze.result}}"

    - id: upload_report
      type: action
      depends_on: [generate_report]
      config:
        type: write
        adapter: s3
        destination: reports/
        data: "{{generate_report.file_path}}"

    - id: notify_team
      type: action
      depends_on: [upload_report, save_to_db]
      config:
        type: notify
        adapter: slack
        channel: "#analytics"
        message: "새 분석 보고서: {{upload_report.url}}"

    - id: update_ui
      type: action
      depends_on: [save_to_db]
      config:
        type: notify
        adapter: websocket
        channel: "user/{{user_id}}/reports"
        event: { type: "report_ready", data: "{{save_to_db.record_id}}" }
```

실행 흐름 (DAG):

```
  fetch_data
      │
      ▼
   analyze
    ┌──┴──┐
    ▼     ▼
generate  save_to_db ──→ update_ui
 _report       │
    │          │
    ▼          │
  upload       │
  _report      │
    │          │
    └────┬─────┘
         ▼
    notify_team
```

---

## 10. Session & Context Layer

### 10.1 역할

대화 상태, 사용자 컨텍스트, 실행 결과를 모델/세션에 무관하게 관리한다.

### 10.2 데이터 모델

```typescript
interface Session {
  id: string;
  user_id: string;
  created_at: Date;
  expires_at: Date;
  status: "active" | "paused" | "completed" | "expired";

  // 대화 히스토리 (모든 모델 공통 포맷)
  messages: UnifiedMessage[];

  // 현재 워크플로우 상태
  workflow_state?: {
    workflow_id: string;
    current_step: string;
    step_results: Record<string, any>;
    variables: Record<string, any>;
  };

  // Tool 실행 결과 캐시
  tool_cache: {
    [toolCallId: string]: {
      tool: string;
      params: any;
      result: any;
      timestamp: Date;
      ttl: number;
    };
  };

  // 메타데이터
  metadata: {
    models_used: string[];          // 사용된 모델 목록
    total_tokens: number;
    total_cost: number;
    actions_executed: ActionLog[];
  };
}
```

### 10.3 Context Window 관리

```typescript
interface ContextManager {
  // 컨텍스트 윈도우 최적화
  // 모델별 토큰 한도에 맞춰 히스토리를 자동 압축
  buildContext(session: Session, model: string): UnifiedMessage[];

  // 전략
  strategies: {
    truncate: "oldest_first" | "summarize" | "sliding_window";
    preserve: string[];       // 항상 유지할 메시지 (system prompt 등)
    max_tokens: number;       // 모델별 한도
  };

  // 장기 메모리 (세션 간 유지)
  longTermMemory: {
    store(userId: string, key: string, value: any): Promise<void>;
    retrieve(userId: string, key: string): Promise<any>;
    search(userId: string, query: string): Promise<any[]>;  // semantic search
  };
}
```

---

## 11. Data & Storage Layer

### 11.1 내부 저장소 구조

```
┌──────────────────────────────────────────┐
│           Internal Data Stores           │
│                                          │
│  ┌────────────────┐  ┌────────────────┐  │
│  │ Session Store  │  │ Config Store   │  │
│  │ (Redis)        │  │ (PostgreSQL)   │  │
│  │                │  │                │  │
│  │ •대화 히스토리   │  │ •모델 설정      │  │
│  │ •워크플로우 상태 │  │ •어댑터 연결정보 │  │
│  │ •Tool 캐시     │  │ •Policy 규칙   │  │
│  │ •실시간 상태    │  │ •스키마 정의    │  │
│  └────────────────┘  └────────────────┘  │
│                                          │
│  ┌────────────────┐  ┌────────────────┐  │
│  │ Event Store    │  │ Audit Store    │  │
│  │ (Kafka/Redis   │  │ (PostgreSQL/   │  │
│  │  Streams)      │  │  Elasticsearch)│  │
│  │                │  │                │  │
│  │ •액션 이벤트    │  │ •실행 로그      │  │
│  │ •상태 변경      │  │ •감사 추적      │  │
│  │ •알림 큐       │  │ •비용 추적      │  │
│  └────────────────┘  └────────────────┘  │
│                                          │
│  ┌────────────────┐                      │
│  │ Vector Store   │                      │
│  │ (Pgvector/     │                      │
│  │  Pinecone)     │                      │
│  │                │                      │
│  │ •장기 메모리    │                      │
│  │ •RAG 인덱스    │                      │
│  └────────────────┘                      │
└──────────────────────────────────────────┘
```

---

## 12. Domain Extension Layer

### 12.1 확장 포인트(Extension Points)

범용 코어 위에 도메인 특화 로직을 얹을 수 있는 표준 확장 인터페이스.

```typescript
interface DomainExtension {
  id: string;
  name: string;
  domain: string;                  // "medical", "legal", "finance", "manufacturing"

  // 확장 요소들
  promptTemplates: PromptTemplate[];       // 도메인 특화 프롬프트
  workflows: Workflow[];                    // 도메인 특화 워크플로우
  schemas: DataSchema[];                    // 도메인 특화 데이터 스키마
  policies: Policy[];                       // 도메인 특화 규칙
  guardrails: Guardrail[];                  // 도메인 특화 안전 장치
  evaluators: Evaluator[];                  // 도메인 특화 품질 평가
  adapters?: (WriteAdapter | NotifyAdapter)[];  // 도메인 특화 어댑터

  // 라이프사이클 훅
  onInstall(): Promise<void>;
  onUninstall(): Promise<void>;
  onRequest?(context: RequestContext): Promise<RequestContext>;  // 요청 전처리
  onResponse?(context: ResponseContext): Promise<ResponseContext>; // 응답 후처리
}
```

### 12.2 확장 패키지 구조

```
my-medical-extension/
├── manifest.json           # 메타데이터, 의존성
├── prompts/
│   ├── diagnosis.yaml      # 감별진단 프롬프트
│   ├── prescription.yaml   # 처방 검토 프롬프트
│   └── patient_summary.yaml
├── workflows/
│   ├── diagnosis_flow.yaml # 진단 워크플로우 DAG
│   └── referral_flow.yaml
├── schemas/
│   ├── patient_record.json
│   └── diagnosis_result.json
├── policies/
│   ├── hipaa_compliance.yaml
│   └── prescription_guardrail.yaml
├── evaluators/
│   └── diagnosis_accuracy.ts
├── adapters/
│   └── ehr_adapter.ts      # 전자의무기록 시스템 연동
└── tests/
    └── ...
```

### 12.3 Extension Marketplace 모델

```
┌────────────────────────────────────────────┐
│          Extension Marketplace             │
│                                            │
│  ┌──────────────────────────────────┐     │
│  │  Official Extensions (검증됨)     │     │
│  │  • Medical Suite                 │     │
│  │  • Legal Assistant               │     │
│  │  • Financial Analyst             │     │
│  │  • Customer Support              │     │
│  └──────────────────────────────────┘     │
│                                            │
│  ┌──────────────────────────────────┐     │
│  │  Community Extensions            │     │
│  │  • E-commerce Tools              │     │
│  │  • HR/Recruiting                 │     │
│  │  • Education/Tutoring            │     │
│  │  • IoT/Manufacturing             │     │
│  └──────────────────────────────────┘     │
│                                            │
│  ┌──────────────────────────────────┐     │
│  │  Custom (Private)                │     │
│  │  • 기업 내부용 확장               │     │
│  │  • 비공개 도메인 로직             │     │
│  └──────────────────────────────────┘     │
└────────────────────────────────────────────┘
```

---

## 13. 이벤트 시스템 — 전체 연결고리

### 13.1 Event Bus

플랫폼 내부의 모든 컴포넌트와 외부 소비자를 연결하는 이벤트 백본.

```typescript
interface EventBus {
  // 발행
  publish(topic: string, event: PlatformEvent): Promise<void>;

  // 구독
  subscribe(topic: string, handler: EventHandler, filter?: EventFilter): Subscription;

  // 토픽 패턴
  // "llm.request.completed"
  // "action.write.success"
  // "action.notify.failed"
  // "workflow.step.completed"
  // "policy.approval.requested"
  // "session.created"
}

interface PlatformEvent {
  id: string;
  type: string;
  source: string;                // 발생 컴포넌트
  timestamp: Date;
  data: any;
  metadata: {
    session_id?: string;
    user_id?: string;
    workflow_id?: string;
    trace_id: string;            // 분산 추적용
  };
}
```

### 13.2 이벤트 흐름 전체도

```
사용자 요청
    │
    ▼
[session.request.received]
    │
    ▼
Orchestrator → Router
    │
    ▼
[llm.request.started]
    │
    ▼
LLM Adapter → 모델 호출
    │
    ├─ tool_use 응답 시 ──→ [llm.tool_use.requested]
    │                           │
    │                     MCP Manager → Tool 실행
    │                           │
    │                     [mcp.tool.completed]
    │                           │
    │                     결과를 LLM에 전달 (루프)
    │
    ▼
[llm.request.completed]
    │
    ▼
Action Router
    │
    ├─ Policy Engine ──→ [policy.check.passed] 또는 [policy.approval.requested]
    │
    ├─ Write ──→ [action.write.started] → [action.write.completed]
    │
    └─ Notify ──→ [action.notify.started] → [action.notify.completed]
    │
    ▼
[session.request.completed]
    │
    ▼
소비자들이 각자 관심 있는 이벤트 수신
├── UI: action.write.completed → 화면 갱신
├── 로깅: *.completed → 기록
├── 모니터링: *.failed → 알림
└── 다른 워크플로우: 트리거 이벤트 → 연쇄 실행
```

---

## 14. API 설계

### 14.1 Core API Endpoints

```yaml
# === Chat / Conversation ===
POST   /api/v1/chat/completions          # LLM 호출 (OpenAI 호환)
POST   /api/v1/chat/sessions             # 세션 생성
GET    /api/v1/chat/sessions/{id}        # 세션 조회
DELETE /api/v1/chat/sessions/{id}        # 세션 종료
WS     /api/v1/chat/stream               # 스트리밍 (WebSocket)

# === Models ===
GET    /api/v1/models                     # 사용 가능한 모델 목록
GET    /api/v1/models/{id}               # 모델 상세 정보
POST   /api/v1/models/{id}/test          # 모델 테스트

# === MCP / Tools ===
GET    /api/v1/tools                      # 사용 가능한 Tool 목록
POST   /api/v1/tools/{name}/execute      # Tool 직접 실행
GET    /api/v1/mcp/servers               # MCP 서버 목록
POST   /api/v1/mcp/servers               # MCP 서버 등록
DELETE /api/v1/mcp/servers/{id}          # MCP 서버 제거

# === Actions ===
POST   /api/v1/actions/execute           # 액션 직접 실행
GET    /api/v1/actions/history           # 액션 실행 이력
GET    /api/v1/actions/pending           # 승인 대기 액션 목록
POST   /api/v1/actions/{id}/approve      # 액션 승인
POST   /api/v1/actions/{id}/reject       # 액션 거부

# === Workflows ===
GET    /api/v1/workflows                  # 워크플로우 목록
POST   /api/v1/workflows                  # 워크플로우 생성
POST   /api/v1/workflows/{id}/run        # 워크플로우 실행
GET    /api/v1/workflows/{id}/status     # 워크플로우 실행 상태

# === Adapters ===
GET    /api/v1/adapters                   # 등록된 어댑터 목록
POST   /api/v1/adapters                   # 어댑터 등록
GET    /api/v1/adapters/{id}/health      # 어댑터 헬스체크

# === Schema ===
GET    /api/v1/schemas                    # 스키마 목록
POST   /api/v1/schemas                    # 스키마 등록
POST   /api/v1/schemas/{id}/validate     # 데이터 유효성 검증

# === Policy ===
GET    /api/v1/policies                   # 정책 목록
POST   /api/v1/policies                   # 정책 등록/수정
POST   /api/v1/policies/evaluate         # 정책 시뮬레이션

# === Extensions ===
GET    /api/v1/extensions                 # 설치된 확장 목록
POST   /api/v1/extensions/install        # 확장 설치
DELETE /api/v1/extensions/{id}           # 확장 제거

# === Events (구독) ===
WS     /api/v1/events/stream             # 이벤트 스트림 (WebSocket)
POST   /api/v1/events/webhooks           # 웹훅 등록
GET    /api/v1/events/history            # 이벤트 이력

# === Admin / Monitoring ===
GET    /api/v1/admin/health              # 플랫폼 헬스체크
GET    /api/v1/admin/metrics             # 메트릭 (Prometheus 호환)
GET    /api/v1/admin/audit               # 감사 로그
GET    /api/v1/admin/usage               # 사용량/비용 대시보드
```

### 14.2 OpenAI 호환 API

기존 OpenAI 기반 앱의 마이그레이션을 위해 OpenAI Chat Completions API와 호환되는 엔드포인트를 제공한다.

```yaml
POST /v1/chat/completions    # OpenAI 호환 (model 필드로 내부 라우팅)
```

---

## 15. 기술 스택 (권장)

| 영역 | 기술 | 사유 |
|------|------|------|
| **API Server** | Node.js (Fastify) 또는 Go | 고성능 비동기 처리, 스트리밍 지원 |
| **Orchestration** | TypeScript + Temporal.io | 워크플로우 내구성, 재시도, 상태 관리 |
| **Event Bus** | Redis Streams 또는 Kafka | 규모에 따라 선택 (소규모: Redis, 대규모: Kafka) |
| **Session Store** | Redis | 빠른 세션 읽기/쓰기 |
| **Config/Metadata DB** | PostgreSQL | 스키마, 정책, 어댑터 설정 저장 |
| **Audit Log** | PostgreSQL + (선택) Elasticsearch | 검색 가능한 감사 로그 |
| **Vector Store** | pgvector 또는 Pinecone | 장기 메모리, RAG |
| **Queue** | BullMQ (Redis 기반) | 비동기 액션 실행 큐 |
| **Monitoring** | Prometheus + Grafana | 메트릭, 대시보드 |
| **Tracing** | OpenTelemetry + Jaeger | 분산 추적 |
| **Container** | Docker + K8s | 배포, 스케일링 |

---

## 16. 구현 로드맵

### Phase 1 — Core Foundation (4~6주)

**목표**: LLM 멀티 모델 호출 + 기본 액션 실행

```
[x] API Gateway (Spring Boot)
[x] LLM Adapter: OpenAI, Claude, Ollama(로컬)
[x] Unified Message Format
[x] 기본 Router (fixed model)
[x] Session Store (Redis)
[x] Action Layer: 기본 Write (파일, PostgreSQL)
[x] Action Layer: 기본 Notify (WebSocket)
[x] 기본 Policy (인증, 로깅)
[x] OpenAI 호환 API
```

**결과물**: 모델 선택 가능한 챗 API + DB 저장 + 파일 생성 + 실시간 알림

### Phase 2 — MCP & Tools (3~4주)

**목표**: MCP 연동 + Tool Calling 통합

```
[x] MCP Client Manager
[x] MCP-to-LLM Bridge (Tool 포맷 변환)
[x] Tool Registry
[x] MCP 서버 동적 등록/해제
[x] Tool 실행 결과 → Action 체이닝
```

**결과물**: 어떤 LLM에서든 MCP Tool 호출 가능

### Phase 3 — RAG & Knowledge (3~4주)

**목표**: 지식 기반 검색 증강 파이프라인

```
[x] Knowledge Source 관리 (파일, DB, 웹, S3, MCP Resource)
[x] Document Parser (PDF, DOCX, HTML, CSV, Markdown)
[x] Chunker (Fixed, Recursive, Semantic)
[x] Embedder (OpenAI, Ollama 로컬 모델)
[x] Vector Store 연동 (pgvector)
[x] Retrieval Pipeline (유사도 검색, 하이브리드, 리랭킹)
[x] Context Injector (검색 결과 → 프롬프트 주입)
[x] Ingestion 스케줄러 (증분/전체 동기화)
[x] Admin UI: Knowledge Source 관리 + 검색 테스트
```

**결과물**: "우리 데이터"를 기반으로 답변하는 RAG 파이프라인

### Phase 4 — Workflow & Orchestration (4~5주)

**목표**: 복합 워크플로우 실행

```
[x] Workflow Engine (DAG 기반)
[x] Chain Manager
[x] Context Manager (컨텍스트 윈도우 최적화)
[x] Smart Router (비용/성능 기반)
[x] RAG Step 통합 (워크플로우에서 RAG 검색 단계 사용)
[x] 에러 핸들링 & 재시도
```

**결과물**: 다단계 LLM + RAG + Tool + Action 워크플로우 실행 가능

### Phase 5 — Policy & Safety (3~4주)

**목표**: 안전하고 통제 가능한 액션 실행

```
[x] Policy Engine
[x] Schema Registry + Validation
[x] Approval Flow (Human-in-the-loop)
[x] Audit Logging
[x] Rate Limiting
[x] PII 마스킹
[x] Knowledge Source 접근 제어 (RAG 소스별 권한)
```

**결과물**: 정책 기반 안전 장치가 적용된 프로덕션 레디 시스템

### Phase 6 — Extension & Ecosystem (4~6주)

**목표**: 도메인 확장 생태계 기반

```
[x] Extension Interface 정의
[x] Extension 로더 / 라이프사이클 관리
[x] Adapter SDK (커스텀 어댑터 개발 도구)
[x] Extension 패키징 & 배포 시스템
[x] 기본 도메인 확장 1~2개 제작 (데모용)
```

**결과물**: 누구나 도메인 확장을 만들고 배포할 수 있는 플랫폼

### Phase 7 — Production & Scale (지속)

```
[x] 모니터링 대시보드
[x] 비용 추적 & 최적화
[x] A/B 테스트 (모델 성능 비교)
[x] Multi-tenant 지원
[x] Marketplace UI
[x] 문서화 & 개발자 포털
```

---

## 17. 핵심 설계 결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| 종단 액션 모델 | Write + Notify | UI 포함 모든 소비자를 이벤트 구독으로 통일 |
| LLM 추상화 방식 | Adapter Pattern | 모델 추가/교체가 독립적 |
| MCP 통합 방식 | Bridge Pattern | MCP를 비-Claude 모델에서도 활용 |
| RAG 파이프라인 | Ingestion + Retrieval 분리 | 수집은 배치, 검색은 실시간. 독립적 스케일링 |
| RAG 저장소 | pgvector (기본) | 기존 PostgreSQL 위에서 동작. 별도 인프라 불요 |
| RAG 설정 방식 | Config-driven (Admin UI) | 코딩 없이 소스 추가, 청킹 전략, 검색 설정 변경 |
| 워크플로우 엔진 | DAG 기반 | 복잡한 분기/병렬/루프 지원 |
| 도메인 확장 | Plugin/Extension | 코어 최소화, 확장으로 도메인 대응 |
| 이벤트 아키텍처 | Event-Driven | 컴포넌트 간 느슨한 결합 |
| API 호환성 | OpenAI 호환 | 기존 생태계 마이그레이션 용이 |
| Policy 적용 시점 | Action 실행 전 (Gateway) | 안전한 기본값 보장 |

---

## 부록 A. 용어 정의

| 용어 | 정의 |
|------|------|
| **Adapter** | 외부 시스템과의 연결을 추상화하는 컴포넌트 |
| **Action** | LLM 추론 결과를 실제 세계에 반영하는 종단 행위 |
| **Write** | 데이터를 영속적으로 저장하는 Action primitive |
| **Notify** | 이벤트를 발행/전달하는 Action primitive |
| **Policy** | Action 실행 전 적용되는 규칙/제약 조건 |
| **Schema** | Action 데이터의 구조 정의 |
| **Extension** | 도메인 특화 로직을 패키징한 확장 모듈 |
| **Workflow** | 여러 Step(LLM 호출, Tool, Action)을 DAG로 연결한 실행 흐름 |
| **MCP** | Model Context Protocol, LLM과 외부 Tool/Resource 연동 표준 |
| **Consumer** | Write/Notify 결과를 구독하여 활용하는 외부 시스템 (UI 포함) |
| **RAG** | Retrieval-Augmented Generation, 외부 지식을 검색하여 LLM 컨텍스트에 주입하는 기법 |
| **Knowledge Source** | RAG에서 지식을 수집하는 원천 (파일, DB, 웹, S3, MCP Resource 등) |
| **Ingestion** | 문서를 파싱 → 청킹 → 임베딩 → 벡터DB 저장하는 수집 파이프라인 |
| **Chunking** | 문서를 LLM 컨텍스트에 적합한 크기로 분할하는 과정 |
| **Embedding** | 텍스트를 고차원 벡터로 변환하여 의미적 유사도 검색을 가능하게 하는 과정 |
| **Vector Store** | 임베딩 벡터를 저장하고 유사도 검색을 제공하는 저장소 (pgvector, Pinecone 등) |
| **Retrieval** | 사용자 질문과 유사한 문서 청크를 벡터DB에서 검색하는 과정 |
| **Context Injection** | 검색된 문서 청크를 LLM 프롬프트에 삽입하는 과정 |
| **HyDE** | Hypothetical Document Embeddings, LLM이 가상 답변을 생성하고 이를 검색 쿼리로 사용하는 고급 기법 |
| **Reranking** | 초기 검색 결과를 Cross-encoder 등으로 재정렬하여 정확도를 높이는 과정 |

---

## 부록 B. Write + Notify 조합 패턴

| 시나리오 | Write Target | Notify Target | 소비자 |
|----------|-------------|---------------|--------|
| UI 자동 입력 | DB (결과 저장) | WebSocket (이벤트) | 웹 프론트엔드 |
| 보고서 생성 | S3 (파일 저장) | Slack (알림) | 사용자 |
| DB에만 저장 | PostgreSQL | - (없음) | 배치 잡 |
| 실시간 알림만 | - (없음) | FCM/APNs | 모바일 앱 |
| 분석 + 대시보드 | PostgreSQL + Redis | WebSocket | 대시보드 UI |
| 승인 워크플로우 | DB (pending 상태) | Slack (승인 요청) | 관리자 |
| IoT 제어 | TimescaleDB (로그) | MQTT (커맨드) | IoT 디바이스 |
| 다른 LLM 트리거 | DB (중간 결과) | Event Bus (트리거) | Orchestrator |

---

*끝*
