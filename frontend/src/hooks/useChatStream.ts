import { useCallback, useRef, useState } from "react";

/**
 * CR-045 Phase 4-A: SSE 스트림 수신 훅.
 *
 * `POST /api/v1/chat/completions` with stream=true → SSE 응답을
 * fetch + ReadableStream으로 직접 파싱 (EventSource는 POST 미지원).
 *
 * 지원 이벤트 (Phase 2-A):
 *  - delta    {delta: string} — 텍스트 청크
 *  - thinking {delta: string} — thinking 델타
 *  - done     {done: true}
 *
 * Phase 4-B에서 tool_use_start / tool_result 추가 예정.
 */

export type StreamBlock =
  | { kind: "text"; text: string }
  | { kind: "thinking"; text: string };

export interface StreamMessage {
  id: string;
  role: "user" | "assistant";
  blocks: StreamBlock[];
}

export interface SendParams {
  sessionId: string;
  connectionId: string;
  model: string;
  workingDirectory?: string;
  userText: string;
}

interface StreamState {
  messages: StreamMessage[];
  isStreaming: boolean;
  error: string | null;
}

function parseSseChunk(
  buffer: string,
): { events: { event: string; data: string }[]; rest: string } {
  const events: { event: string; data: string }[] = [];
  const parts = buffer.split("\n\n");
  const rest = parts.pop() ?? "";
  for (const raw of parts) {
    if (!raw.trim()) continue;
    let event = "message";
    const dataLines: string[] = [];
    for (const line of raw.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
    }
    events.push({ event, data: dataLines.join("\n") });
  }
  return { events, rest };
}

export const useChatStream = (
  initialMessages: StreamMessage[] = [],
): StreamState & {
  send: (p: SendParams) => Promise<void>;
  reset: (msgs?: StreamMessage[]) => void;
} => {
  const [messages, setMessages] = useState<StreamMessage[]>(initialMessages);
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const reset = useCallback((msgs: StreamMessage[] = []) => {
    abortRef.current?.abort();
    setMessages(msgs);
    setIsStreaming(false);
    setError(null);
  }, []);

  const send = useCallback(async (p: SendParams) => {
    setError(null);
    setIsStreaming(true);

    const userMsg: StreamMessage = {
      id: `u-${Date.now()}`,
      role: "user",
      blocks: [{ kind: "text", text: p.userText }],
    };
    const assistantId = `a-${Date.now()}`;
    const assistantMsg: StreamMessage = {
      id: assistantId,
      role: "assistant",
      blocks: [],
    };
    setMessages((prev) => [...prev, userMsg, assistantMsg]);

    const appendBlock = (kind: "text" | "thinking", delta: string) => {
      if (!delta) return;
      setMessages((prev) => {
        const copy = [...prev];
        const idx = copy.findIndex((m) => m.id === assistantId);
        if (idx < 0) return prev;
        const msg = { ...copy[idx], blocks: [...copy[idx].blocks] };
        const last = msg.blocks[msg.blocks.length - 1];
        if (last && last.kind === kind) {
          msg.blocks[msg.blocks.length - 1] = { kind, text: last.text + delta };
        } else {
          msg.blocks.push({ kind, text: delta });
        }
        copy[idx] = msg;
        return copy;
      });
    };

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const token = localStorage.getItem("access_token") ?? "";
      const tenantId = localStorage.getItem("tenant_id") ?? "";
      const res = await fetch("/api/v1/chat/completions", {
        method: "POST",
        signal: controller.signal,
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream",
          Authorization: `Bearer ${token}`,
          "X-Tenant-Id": tenantId,
        },
        body: JSON.stringify({
          model: p.model,
          session_id: p.sessionId,
          connection_id: p.connectionId,
          working_directory: p.workingDirectory,
          stream: true,
          actions_enabled: true,
          messages: [{ role: "user", content: p.userText }],
        }),
      });

      if (!res.ok || !res.body) {
        throw new Error(`HTTP ${res.status}`);
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSseChunk(buffer);
        buffer = parsed.rest;
        for (const ev of parsed.events) {
          if (ev.event === "done") {
            // 종료 이벤트
          } else if (ev.event === "delta" || ev.event === "thinking") {
            try {
              const payload = JSON.parse(ev.data);
              const delta = (payload.delta ?? "") as string;
              appendBlock(ev.event === "thinking" ? "thinking" : "text", delta);
            } catch {
              // JSON parse fail — skip
            }
          }
        }
      }
    } catch (e) {
      if ((e as Error).name !== "AbortError") {
        setError((e as Error).message);
      }
    } finally {
      setIsStreaming(false);
      abortRef.current = null;
    }
  }, []);

  return { messages, isStreaming, error, send, reset };
};
