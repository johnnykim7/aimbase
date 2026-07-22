# Aimbase Chat 히스토리 — 저장 & 불러오기 전체 흐름

```mermaid
flowchart TB
    subgraph FE["🖥️ Chat 화면 (FE)"]
        NewMsg["사용자 메시지 전송"]
        SideList["왼쪽 대화 목록"]
        BodyView["본문(지난 대화)"]
    end

    subgraph API["🔌 Controller 계층"]
        ChatCtl["ChatController<br/>POST /api/v1/chat/completions"]
        ConvCtl["ConversationController<br/>GET /api/v1/conversations"]
    end

    subgraph ORC["🧠 OrchestratorEngine.chat()"]
        Assemble["ContextAssemblyEngine.assemble(sessionId)<br/>① DB 히스토리 getMessages<br/>② history + 새 메시지 합침"]
        Adapter{"어댑터 선택<br/>(LLM 종류는 여기서만 갈림)"}
        Call["adapter.chat(전체 messages)<br/>← 종류 무관 공통 호출"]
        Save["세션 저장<br/>appendMessage(유저 + 어시스턴트)"]
    end

    subgraph LLMS["🤖 LLM 어댑터들 (LLMAdapter 인터페이스)"]
        CLI["Claude CLI"]
        ANT["Anthropic API"]
        OAI["OpenAI"]
        OLL["Ollama / Bedrock / Vertex …"]
    end

    subgraph STORE["💾 SessionStore"]
        Redis[("Redis<br/>캐시 (TTL 24h)")]
        DB[("PostgreSQL<br/>ConversationSession / Message<br/>★ 유일한 원장")]
    end

    %% ─── 저장 + 대화 흐름 ───
    NewMsg -->|"이번 메시지만"| ChatCtl
    ChatCtl --> Assemble
    Assemble -->|"불러온 히스토리 + 새 메시지"| Adapter
    Adapter --> Call
    Call --> CLI & ANT & OAI & OLL
    CLI & ANT & OAI & OLL -->|"응답"| Save
    Save --> Redis
    Save --> DB

    %% ─── 불러오기(assemble이 DB에서) ───
    DB -.->|"getMessages<br/>(매 턴 히스토리 재조립)"| Assemble

    %% ─── 화면 목록/본문 불러오기 ───
    SideList --> ConvCtl
    BodyView --> ConvCtl
    ConvCtl -.->|"읽기"| DB

    classDef store fill:#1e3a5f,stroke:#4a90d9,color:#fff
    classDef orc fill:#3d2c5f,stroke:#a06cd5,color:#fff
    classDef llm fill:#1f4a3d,stroke:#4dc999,color:#fff
    class Redis,DB store
    class Assemble,Adapter,Call,Save orc
    class CLI,ANT,OAI,OLL llm
```

## 핵심 3가지

1. **저장** — 오케스트레이터가 매 대화마다 `appendMessage` → Redis + **PostgreSQL(원장)**. LLM 종류 무관.
2. **불러오기(2종류)**
   - 화면 목록/본문: `ConversationController` → DB 직접 읽기
   - 대화 맥락: `assemble()`이 매 턴 `getMessages`로 DB 히스토리를 **다시 조립**해 어댑터에 넘김
3. **LLM 종류는 "어댑터 선택" 한 지점에서만 갈림.** 그 위(assemble/저장)와 아래(DB)는 종류를 모름 → **CLI도 불러온 히스토리를 다시 내보냄.**

## 실측 근거 (파일:라인)

| 단계 | 위치 |
|---|---|
| 히스토리 불러와서 붙임 | `ContextAssemblyEngine.java:271, 277, 279` |
| 어댑터 선택 (종류 갈림) | `OrchestratorEngine.java:271~285` |
| 공통 호출 | `OrchestratorEngine.java:355~364` (CLI도 `adapter.chat()`) |
| 저장 (분기 없음) | `OrchestratorEngine.java:425~427` |
| Redis + DB 저장 | `SessionStore.java:53~56` |
| 화면 목록/본문 읽기 | `ConversationController.java` (GET /conversations) |
