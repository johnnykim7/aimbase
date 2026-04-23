import { parseSseStream } from "./sse-parser";
import type { TokenStore } from "./token-store";
import type { ChatDelta } from "./types";

export interface SendMessageArgs {
  baseUrl: string;
  sessionId: string;
  text: string;
  model?: string;
  ragSourceId?: string;
  connectionId?: string;
  context?: Record<string, unknown>;
}

export class ChatClient {
  private currentAbort: AbortController | null = null;

  constructor(private readonly tokens: TokenStore) {}

  /**
   * chat stream — fetch 기반 SSE 파싱. Authorization 헤더로 토큰 전송.
   * 이벤트가 나올 때마다 onDelta 콜백 호출.
   */
  async sendMessage(args: SendMessageArgs, onDelta: (d: ChatDelta) => void): Promise<void> {
    this.currentAbort?.abort();
    const ac = new AbortController();
    this.currentAbort = ac;

    const token = await this.tokens.getToken();

    const userContent = [{ type: "text", text: args.text }];
    const messages: unknown[] = [];
    if (args.context && Object.keys(args.context).length > 0) {
      // 컨텍스트는 system 메시지로 선행 주입 (간단·가시적)
      messages.push({
        role: "system",
        content: [
          {
            type: "text",
            text: `# 현재 화면 컨텍스트\n${JSON.stringify(args.context, null, 2)}`,
          },
        ],
      });
    }
    messages.push({ role: "user", content: userContent });

    const body: Record<string, unknown> = {
      model: args.model ?? "auto",
      session_id: args.sessionId,
      stream: true,
      messages,
    };
    if (args.ragSourceId) body.rag_source_id = args.ragSourceId;
    if (args.connectionId) body.connection_id = args.connectionId;

    const res = await fetch(`${args.baseUrl}/api/v1/chat/completions`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
      },
      body: JSON.stringify(body),
      signal: ac.signal,
    });

    if (!res.ok) {
      const errText = await res.text().catch(() => "");
      throw new Error(`chat/completions ${res.status}: ${errText.slice(0, 200)}`);
    }

    for await (const ev of parseSseStream(res, ac.signal)) {
      try {
        const payload = JSON.parse(ev.data);
        switch (ev.name) {
          case "delta":
            onDelta({ type: "delta", text: payload.delta ?? "" });
            break;
          case "thinking":
            onDelta({ type: "thinking", text: payload.delta ?? "" });
            break;
          case "tool_use_start":
            onDelta({
              type: "tool_use_start",
              tool: { id: payload.id, name: payload.name, input: payload.input },
            });
            break;
          case "tool_result":
            onDelta({
              type: "tool_result",
              toolResult: {
                tool_use_id: payload.tool_use_id,
                output: payload.output,
                is_error: !!payload.is_error,
              },
            });
            break;
          case "done":
            onDelta({
              type: "done",
              done: {
                rag_used: !!payload.rag_used,
                citations: Array.isArray(payload.citations) ? payload.citations : [],
              },
            });
            break;
          default:
            // 알 수 없는 이벤트는 무시
            break;
        }
      } catch (e) {
        onDelta({ type: "error", error: `parse error: ${(e as Error).message}` });
      }
    }
  }

  async abort(baseUrl: string, sessionId: string): Promise<void> {
    this.currentAbort?.abort();
    this.currentAbort = null;
    const token = await this.tokens.getToken();
    try {
      await fetch(`${baseUrl}/api/v1/chat/${encodeURIComponent(sessionId)}/abort`, {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}` },
      });
    } catch {
      // 서버에 abort 도달 실패해도 클라이언트는 이미 끊어놓음
    }
  }
}
