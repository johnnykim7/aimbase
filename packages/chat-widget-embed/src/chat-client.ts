import { parseSseStream } from "./sse-parser";
import type { TokenStore } from "./token-store";
import type { ChatDelta } from "./types";
import { readError } from "./widget-api";

export interface SendMessageArgs {
  baseUrl: string;
  sessionId: string;
  text: string;
  model?: string;
  ragSourceId?: string;
  connectionId?: string;
  /** MCP 도구 자동 호출 활성화 — true 면 body.actions_enabled=true 송신. */
  actionsEnabled?: boolean;
  context?: Record<string, unknown>;
  /** CR-061: 사전 업로드된 첨부들. `{media_type, attachment_id}` 로 BE 에 전달. */
  attachments?: Array<{ attachmentId: string; mediaType: string }>;
  /** CR-175: 이 순번(앞 답의 turn_seq)부터 지우고 이번 질문으로 다시 답하게 한다. */
  rewindFromSeq?: number;
  /** CR-175: 깊게 생각하기를 켜면 "on". 보내지 않으면 연결 설정을 따른다. */
  thinking?: "on" | "off";
  /** CR-177: 기능이 요청 본문에 더한 칸. 위젯 본체 칸(CORE_KEYS)과 겹치면 본체 값을 쓴다. */
  extra?: Record<string, unknown>;
  /** CR-177: 기능이 그릴 수 있다고 알린 응답 형식 이름 */
  renderFeatures?: string[];
}

/** 기능이 덮어쓸 수 없는 요청 본문 칸 */
export const CORE_KEYS: readonly string[] = [
  "model", "session_id", "stream", "messages", "connection_id", "rag_source_id",
  "actions_enabled", "rewind_from_seq", "thinking", "render_features",
];

/** 끝 이벤트(done)에서 위젯 본체가 읽는 칸. 나머지는 extra 로 기능에 보낸다. */
const DONE_KEYS = new Set(["rag_used", "citations", "artifacts", "turn_id", "turn_seq", "model"]);

const KNOWN_EVENTS = new Set(["delta", "thinking", "tool_use_start", "tool_result", "done", "error"]);

/** 멈춤을 서버에 알린 뒤 저장 완료(done)가 이 시간 안에 오지 않으면 연결을 끊는다. */
const STOP_GRACE_MS = 8000;

export class ChatClient {
  private currentAbort: AbortController | null = null;
  private stopTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly tokens: TokenStore) {}

  /**
   * chat stream — fetch 기반 SSE 파싱. Authorization 헤더로 토큰 전송.
   * 이벤트가 나올 때마다 onDelta 콜백 호출.
   */
  async sendMessage(args: SendMessageArgs, onDelta: (d: ChatDelta) => void): Promise<void> {
    // 직전 호출이 진행 중이면 silent abort (사용자가 새 메시지 보낸 housekeeping).
    // 그 abort 의 결과로 발생하는 AbortError 는 직전 호출의 sendMessage 가 catch 해서 silent 처리해야 한다.
    const prev = this.currentAbort;
    const ac = new AbortController();
    this.currentAbort = ac;
    prev?.abort();

    const token = await this.tokens.getToken();

    // CR-061: 첨부 블록 먼저, 텍스트 블록 뒤 — LLM 이 첨부 맥락을 먼저 본다.
    const userContent: Array<Record<string, unknown>> = [];
    for (const att of args.attachments ?? []) {
      userContent.push({
        type: att.mediaType.startsWith("image/") ? "image" : "document",
        attachment_id: att.attachmentId,
      });
    }
    if (args.text && args.text.length > 0) {
      userContent.push({ type: "text", text: args.text });
    }
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

    const body: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(args.extra ?? {})) {
      if (!CORE_KEYS.includes(k)) body[k] = v;
    }
    Object.assign(body, {
      model: args.model ?? "auto",
      session_id: args.sessionId,
      stream: true,
      messages,
    });
    if (args.renderFeatures?.length) body.render_features = args.renderFeatures;
    if (args.ragSourceId) body.rag_source_id = args.ragSourceId;
    if (args.connectionId) body.connection_id = args.connectionId;
    if (args.actionsEnabled) body.actions_enabled = true;
    if (args.rewindFromSeq !== undefined) body.rewind_from_seq = args.rewindFromSeq;
    if (args.thinking) body.thinking = args.thinking;

    let res: Response;
    try {
      res = await fetch(`${args.baseUrl}/api/v1/chat/completions`, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${token}`,
          "Content-Type": "application/json",
          "Accept": "text/event-stream",
        },
        body: JSON.stringify(body),
        signal: ac.signal,
      });
    } catch (e) {
      // 새 sendMessage 에 의해 abort 된 경우 — UI 에 노출하지 않는다 (housekeeping).
      if ((e as Error)?.name === "AbortError" || ac.signal.aborted) return;
      throw e;
    }

    if (!res.ok) throw await readError(res);

    try {
      for await (const ev of parseSseStream(res, ac.signal)) {
        if (!KNOWN_EVENTS.has(ev.name)) {
          onDelta({ type: "event", event: { name: ev.name, data: parseData(ev.data) } });
          continue;
        }
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
                  // CR-132: CLI 산출물 다운로드 메타
                  artifacts: Array.isArray(payload.artifacts) ? payload.artifacts : [],
                  turn_id: typeof payload.turn_id === "string" ? payload.turn_id : undefined,
                  turn_seq: typeof payload.turn_seq === "number" ? payload.turn_seq : undefined,
                  model: typeof payload.model === "string" ? payload.model : undefined,
                  extra: Object.fromEntries(Object.entries(payload ?? {}).filter(([k]) => !DONE_KEYS.has(k))),
                },
              });
              break;
            case "error": {
              // CR-177: 응답 도중 서버가 멈춘 이유. 받은 글은 그대로 두고 오류로 끝낸다.
              const code = typeof payload?.code === "string" ? payload.code : undefined;
              const message = typeof payload?.message === "string" ? payload.message : undefined;
              onDelta({ type: "error", error: message ?? code ?? "error", errorCode: code });
              break;
            }
          }
        } catch (e) {
          onDelta({ type: "error", error: `parse error: ${(e as Error).message}` });
        }
      }
    } catch (e) {
      // SSE 스트림 도중 abort 발생 — silent (사용자 housekeeping). 그 외 네트워크 오류는 throw.
      if ((e as Error)?.name === "AbortError" || ac.signal.aborted) return;
      throw e;
    } finally {
      if (this.currentAbort === ac) {
        this.currentAbort = null;
        if (this.stopTimer) clearTimeout(this.stopTimer);
        this.stopTimer = null;
      }
    }
  }

  /**
   * CR-175: 응답 중지. 서버에 멈춤을 알리고 연결은 그대로 둔다.
   * 서버가 받은 데까지 저장한 뒤 보내는 done 에 turn_seq 가 있어야 멈춘 답도 다시 생성할 수 있다.
   * done 이 오지 않으면 잠시 뒤 연결을 끊는다.
   */
  async stop(baseUrl: string, sessionId: string): Promise<void> {
    const ac = this.currentAbort;
    if (!ac) return;
    if (this.stopTimer) clearTimeout(this.stopTimer);
    this.stopTimer = setTimeout(() => {
      if (this.currentAbort === ac) ac.abort();
    }, STOP_GRACE_MS);
    const token = await this.tokens.getToken();
    try {
      await fetch(`${baseUrl}/api/v1/chat/${encodeURIComponent(sessionId)}/abort`, {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}` },
      });
    } catch {
      ac.abort();
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

function parseData(raw: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}
