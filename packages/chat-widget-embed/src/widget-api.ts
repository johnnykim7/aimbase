import type { TokenStore } from "./token-store";
import type { FeedbackRating, WidgetSettings } from "./types";

/** 서버가 ApiResponse.error(code) 로 돌려준 거절 코드를 담는다. */
export class WidgetApiError extends Error {
  constructor(public readonly status: number, public readonly code: string) {
    super(code);
    this.name = "WidgetApiError";
  }
}

export async function readError(res: Response): Promise<WidgetApiError> {
  const raw = await res.text().catch(() => "");
  let code = raw.slice(0, 200);
  try {
    const body = JSON.parse(raw) as { error?: string; message?: string };
    code = body.error ?? body.message ?? code;
  } catch {
    // 본문이 JSON 이 아니면 앞부분을 그대로 코드로 쓴다.
  }
  return new WidgetApiError(res.status, code || `HTTP ${res.status}`);
}

/** CR-175: 위젯 설정, 응답 평가, 후속 질문 호출. */
export class WidgetApi {
  constructor(private readonly tokens: TokenStore, private readonly baseUrl: string) {}

  /** CR-177: 기능이 위젯 토큰으로 서버를 부른다. 응답 본문의 data 를 돌려준다. */
  async request<R>(method: string, path: string, body?: unknown): Promise<R> {
    const token = await this.tokens.getToken();
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) throw await readError(res);
    const json = (await res.json().catch(() => ({}))) as { data?: R };
    return json.data as R;
  }

  /** CR-177: 파일처럼 본문을 그대로 받는다. body 가 있으면 JSON 으로 싣는다. */
  async requestBlob(method: string, path: string, body?: unknown, opts?: { signal?: AbortSignal }): Promise<Blob> {
    const token = await this.tokens.getToken();
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: opts?.signal,
    });
    if (!res.ok) throw await readError(res);
    return res.blob();
  }

  /** CR-177: 위젯 서버 주소로 만든 절대 웹소켓 주소. */
  wsUrl(path: string): string {
    const url = new URL(`${this.baseUrl}${path}`, location.href);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    return url.toString();
  }

  /** CR-177: locale 을 주면 평가 이유 이름이 그 언어로 온다. */
  settings(connectionId: string | undefined, locale: string): Promise<WidgetSettings> {
    const q = new URLSearchParams({ locale });
    if (connectionId) q.set("connection_id", connectionId);
    return this.request("GET", `/api/v1/chat/widget-settings?${q.toString()}`);
  }

  saveFeedback(args: {
    sessionId: string;
    turnId: string;
    rating: FeedbackRating;
    reasonCodes?: string[];
    comment?: string;
  }): Promise<unknown> {
    return this.request("PUT", "/api/v1/chat/feedback", {
      session_id: args.sessionId,
      turn_id: args.turnId,
      rating: args.rating,
      reason_codes: args.reasonCodes ?? [],
      comment: args.comment ?? null,
    });
  }

  deleteFeedback(turnId: string): Promise<unknown> {
    return this.request("DELETE", `/api/v1/chat/feedback?turn_id=${encodeURIComponent(turnId)}`);
  }

  followUps(sessionId: string, turnId: string, connectionId?: string): Promise<{ follow_ups: string[]; title?: string }> {
    return this.request("POST", `/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/follow-ups`, {
      turn_id: turnId,
      connection_id: connectionId ?? null,
    });
  }
}
