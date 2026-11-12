/**
 * CR-186: 답 읽어 주기와 음성 대화가 같이 쓰는 소리 재생(PRD-580, BIZ-251).
 * 위젯 하나에 소리 자리 하나를 두고, 한 번에 한 주인만 읽게 한다.
 */
import type { WidgetContext } from "../frame/context";
import { defineStrings } from "../frame/i18n";
import type { SpeechPhrases } from "./speech-text";

export const TTS_PATH = "/api/v1/chat/tts";
export const SCOPE_TTS = "chat:tts";

/** 위젯 설정 응답 중 읽기와 음성 대화 칸(CR-186 3.4). 칸이 없는 서버면 그 기능을 보이지 않는다. */
export interface SpeechSettings {
  tts_enabled?: boolean;
  tts_max_chars?: number;
  voice_chat_enabled?: boolean;
  voice_end_silence_ms?: number;
  voice_speech_level?: number;
  voice_max_utterance_seconds?: number;
  voice_idle_end_seconds?: number;
}

export function speechSettings(ctx: WidgetContext): SpeechSettings {
  return (ctx.widgetSettings() ?? {}) as SpeechSettings;
}

/** 읽기 조각 상한. 읽기를 쓸 수 없으면 null. */
export function readableMaxChars(ctx: WidgetContext): number | null {
  const s = speechSettings(ctx);
  if (s.tts_enabled !== true || !isPositive(s.tts_max_chars) || !ctx.api.hasScope(SCOPE_TTS)) return null;
  return s.tts_max_chars;
}

export function isPositive(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v) && v > 0;
}

export const PHRASES = defineStrings<SpeechPhrases>({
  ko: { code: "코드는 화면에서 확인해 주세요", table: "표는 화면에서 확인해 주세요" },
  en: { code: "Please check the code on screen", table: "Please check the table on screen" },
});

export type FetchAudio = (text: string, signal: AbortSignal) => Promise<Blob>;

export function ttsFetcher(ctx: WidgetContext, sessionId: string): FetchAudio {
  return (text, signal) => ctx.api.requestBlob("POST", TTS_PATH, { session_id: sessionId, text }, { signal });
}

interface Owner {
  stop(): void;
}

/** 위젯 하나가 같이 쓰는 재생 요소와 지금 읽는 주인. */
export class SpeechOutput {
  readonly audio = new Audio();
  private owner: Owner | null = null;
  private silentUrl: string | null = null;

  /**
   * 사람이 누른 처리 안에서 부른다. 브라우저는 사람이 누른 때가 아니면 재생을 막으므로(특히 Safari)
   * 그때 이 요소로 무음을 한 번 재생해 두면 뒤에 받아 온 소리를 같은 요소로 틀 수 있다.
   */
  unlock(): void {
    const url = this.silent();
    this.audio.src = url;
    this.audio.play().then(
      () => {
        if (this.audio.src === url) this.audio.pause();
      },
      () => undefined,
    );
  }

  claim(owner: Owner): void {
    const prev = this.owner;
    if (prev && prev !== owner) prev.stop();
    this.owner = owner;
  }

  release(owner: Owner): void {
    if (this.owner !== owner) return;
    this.owner = null;
    this.audio.pause();
    this.audio.onended = null;
    this.audio.onerror = null;
  }

  isOwner(owner: Owner): boolean {
    return this.owner === owner;
  }

  /** 지금 읽고 있는 것을 멈춘다. */
  silence(): void {
    this.owner?.stop();
  }

  dispose(): void {
    this.silence();
    if (this.silentUrl) URL.revokeObjectURL(this.silentUrl);
    this.silentUrl = null;
  }

  private silent(): string {
    if (!this.silentUrl) this.silentUrl = URL.createObjectURL(silentWav());
    return this.silentUrl;
  }
}

const outputs = new WeakMap<WidgetContext, SpeechOutput>();

export function speechOutput(ctx: WidgetContext): SpeechOutput {
  let out = outputs.get(ctx);
  if (!out) {
    const created = new SpeechOutput();
    outputs.set(ctx, created);
    ctx.on("destroy", () => created.dispose());
    out = created;
  }
  return out;
}

export type SpeechEnd = "done" | "stopped" | "failed";

/**
 * 읽을 조각을 차례로 소리로 받아 재생한다. 재생하는 동안 다음 조각 하나만 미리 받는다.
 * 조각은 나중에 더해도 된다(음성 대화가 응답이 오는 동안 더한다). close 뒤 다 읽으면 끝난다.
 */
export class SpeechQueue {
  private readonly chunks: string[] = [];
  private readonly fetches = new Map<number, Promise<Blob>>();
  private readonly ctrl = new AbortController();
  private next = 0;
  private closed = false;
  private busy = false;
  private sounding = false;
  private played = false;
  private ended = false;
  private settle: (() => void) | null = null;
  private url: string | null = null;

  constructor(
    private readonly out: SpeechOutput,
    private readonly fetchAudio: FetchAudio,
    private readonly hooks: { onPlay?: () => void; onEnd: (how: SpeechEnd) => void },
  ) {
    out.claim(this);
  }

  get active(): boolean {
    return !this.ended;
  }

  push(text: string): void {
    if (this.ended || !text.trim()) return;
    this.chunks.push(text);
    if (this.sounding) this.prefetchNext();
    void this.pump();
  }

  close(): void {
    this.closed = true;
    void this.pump();
  }

  stop(): void {
    this.end("stopped");
  }

  private fetchAt(i: number): Promise<Blob> {
    let p = this.fetches.get(i);
    if (!p) {
      p = this.fetchAudio(this.chunks[i], this.ctrl.signal);
      p.catch(() => undefined);
      this.fetches.set(i, p);
    }
    return p;
  }

  private prefetchNext(): void {
    if (this.next < this.chunks.length) this.fetchAt(this.next);
  }

  private async pump(): Promise<void> {
    if (this.busy || this.ended) return;
    if (this.next >= this.chunks.length) {
      if (this.closed) this.end("done");
      return;
    }
    this.busy = true;
    const i = this.next++;
    try {
      const blob = await this.fetchAt(i);
      this.fetches.delete(i);
      if (this.ended) return;
      await this.play(blob);
    } catch {
      this.end("failed");
      return;
    }
    this.busy = false;
    void this.pump();
  }

  private play(blob: Blob): Promise<void> {
    return new Promise((resolve, reject) => {
      const audio = this.out.audio;
      this.revoke();
      this.url = URL.createObjectURL(blob);
      this.settle = resolve;
      audio.onended = () => {
        this.sounding = false;
        resolve();
      };
      audio.onerror = () => reject(new Error("audio"));
      audio.src = this.url;
      audio.play().then(() => {
        if (this.ended) return;
        this.sounding = true;
        this.prefetchNext();
        if (!this.played) {
          this.played = true;
          this.hooks.onPlay?.();
        }
      }, reject);
    });
  }

  private end(how: SpeechEnd): void {
    if (this.ended) return;
    this.ended = true;
    this.sounding = false;
    this.ctrl.abort();
    this.out.release(this);
    this.settle?.();
    this.settle = null;
    this.revoke();
    this.hooks.onEnd(how);
  }

  private revoke(): void {
    if (this.url) URL.revokeObjectURL(this.url);
    this.url = null;
  }
}

/** 표본 하나짜리 무음 WAV. 머리 44바이트는 WAV 형식이 정한 칸이다(8비트 모노 8000Hz, 무음 값 128). */
function silentWav(): Blob {
  const bytes = new Uint8Array(45);
  const v = new DataView(bytes.buffer);
  const ascii = (at: number, s: string) => [...s].forEach((c, i) => v.setUint8(at + i, c.charCodeAt(0)));
  ascii(0, "RIFF");
  v.setUint32(4, 37, true);
  ascii(8, "WAVE");
  ascii(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, 1, true);
  v.setUint32(24, 8000, true);
  v.setUint32(28, 8000, true);
  v.setUint16(32, 1, true);
  v.setUint16(34, 8, true);
  ascii(36, "data");
  v.setUint32(40, 1, true);
  v.setUint8(44, 128);
  return new Blob([bytes], { type: "audio/wav" });
}
