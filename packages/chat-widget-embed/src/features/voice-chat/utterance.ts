import type { WidgetContext } from "../../frame/context";
import { WidgetApiError } from "../../widget-api";
import { CHUNK_MS, CLOSE_FULL, CLOSE_TOO_LONG, TICKET_PATH, VOICE_MIME, type VoiceConfig } from "./config";

export type UtteranceResult =
  | { kind: "text"; text: string }
  | { kind: "empty" }
  /** 말소리 없이 연결 상한에 닿았다. 조용히 새로 연다 */
  | { kind: "silent" }
  | { kind: "fail"; reason: "busy" | "server" }
  | { kind: "cancelled" };

interface TicketResponse {
  ticket: string;
  expires_in_seconds: number;
  stream_path: string;
}

export interface UtteranceHooks {
  onReady(): void;
  onTranscript(text: string, buffer: string): void;
}

/**
 * 말 한 번(CR-186 3.3, 4.3). 입장권을 받아 중계를 열고, 소리를 보내고, 끝을 판정해 마지막 글을 받는다.
 * 중계 하나가 발화 하나라 듣기를 시작할 때마다 새로 만든다.
 */
export class Utterance {
  readonly result: Promise<UtteranceResult>;
  private resolve!: (r: UtteranceResult) => void;
  private ws: WebSocket | null = null;
  private rec: MediaRecorder | null = null;
  private readyAt = 0;
  private lastLoud = 0;
  private heard = false;
  private ending = false;
  private done = false;
  private confirmed = "";

  constructor(
    private readonly ctx: WidgetContext,
    private readonly stream: MediaStream,
    private readonly cfg: VoiceConfig,
    private readonly hooks: UtteranceHooks,
  ) {
    this.result = new Promise((r) => (this.resolve = r));
  }

  /** 소리를 보내는 중(끝을 알리기 전) */
  get recording(): boolean {
    return this.rec !== null && !this.ending && !this.done;
  }

  get hasSpeech(): boolean {
    return this.heard;
  }

  async start(): Promise<void> {
    let ticket: TicketResponse;
    try {
      ticket = await this.ctx.api.request<TicketResponse>("POST", TICKET_PATH, { session_id: this.ctx.sessionId() });
    } catch (e) {
      this.finish({ kind: "fail", reason: e instanceof WidgetApiError && e.status === 429 ? "busy" : "server" });
      return;
    }
    if (this.done) return;
    let ws: WebSocket;
    try {
      ws = new WebSocket(this.ctx.api.wsUrl(`${ticket.stream_path}?ticket=${encodeURIComponent(ticket.ticket)}`));
    } catch {
      this.finish({ kind: "fail", reason: "server" });
      return;
    }
    this.ws = ws;
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") this.onMessage(ev.data);
    };
    ws.onclose = (ev) => this.onClose(ev.code);
  }

  /** 말소리 크기(RMS)로 끝을 판정한다. now 는 performance.now() */
  tick(level: number, now: number): void {
    if (!this.recording) return;
    if (level >= this.cfg.speechLevel) {
      this.heard = true;
      this.lastLoud = now;
    }
    // 서버는 연결부터 발화 상한에 5초를 더해 끊으므로, 말을 시작한 때와 상관없이 연결부터 상한을 잰다.
    if ((this.heard && now - this.lastLoud >= this.cfg.endSilenceMs) || now - this.readyAt >= this.cfg.maxUtteranceMs) {
      this.end();
    }
  }

  cancel(): void {
    this.finish({ kind: "cancelled" });
  }

  private onMessage(raw: string): void {
    let m: { type?: string; text?: string; buffer?: string };
    try {
      m = JSON.parse(raw) as typeof m;
    } catch {
      return;
    }
    if (m.type === "ready") this.begin();
    else if (m.type === "transcript") {
      this.confirmed = m.text ?? "";
      this.hooks.onTranscript(this.confirmed, m.buffer ?? "");
    } else if (m.type === "final") this.settle(m.text ?? "");
  }

  private begin(): void {
    const ws = this.ws;
    if (this.done || this.rec || !ws) return;
    let rec: MediaRecorder;
    try {
      rec = new MediaRecorder(this.stream, { mimeType: VOICE_MIME });
    } catch {
      this.finish({ kind: "fail", reason: "server" });
      return;
    }
    rec.ondataavailable = (e) => {
      if (e.data.size > 0 && ws.readyState === WebSocket.OPEN) ws.send(e.data);
    };
    // stop() 은 남은 소리를 dataavailable 로 먼저 낸 뒤 stop 을 부른다. 마지막 소리가 나간 다음에 끝을 알린다.
    rec.onstop = () => {
      if (this.ending && !this.done && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "end" }));
    };
    this.rec = rec;
    rec.start(CHUNK_MS);
    this.readyAt = performance.now();
    this.lastLoud = this.readyAt;
    this.hooks.onReady();
  }

  private end(): void {
    if (this.ending || this.done) return;
    this.ending = true;
    if (this.rec && this.rec.state !== "inactive") this.rec.stop();
  }

  /** 말소리를 듣지 못한 발화의 글은 잡음을 받아쓴 것이라 버린다. */
  private settle(finalText: string): void {
    if (!this.heard) {
      this.finish({ kind: "silent" });
      return;
    }
    const text = finalText.trim() || this.confirmed.trim();
    this.finish(text ? { kind: "text", text } : { kind: "empty" });
  }

  private onClose(code: number): void {
    if (this.done) return;
    if (this.ending || code === CLOSE_TOO_LONG) {
      // 끝을 알린 뒤 마지막 글이 시간 안에 오지 않았거나 발화가 길어 끊겼다. 받은 확정 글이 있으면 그 글로 묻는다(4.3 오류 4).
      if (!this.heard) this.finish({ kind: "silent" });
      else if (this.confirmed.trim()) this.finish({ kind: "text", text: this.confirmed.trim() });
      else this.finish({ kind: "fail", reason: "server" });
      return;
    }
    this.finish({ kind: "fail", reason: code === CLOSE_FULL ? "busy" : "server" });
  }

  private finish(r: UtteranceResult): void {
    if (this.done) return;
    this.done = true;
    const rec = this.rec;
    if (rec) {
      rec.ondataavailable = null;
      rec.onstop = null;
      if (rec.state !== "inactive") rec.stop();
    }
    const ws = this.ws;
    if (ws) {
      ws.onmessage = null;
      ws.onclose = null;
      if (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN) ws.close(1000);
    }
    this.resolve(r);
  }
}
