import type { WidgetContext } from "../../frame/context";
import { SpeechQueue, ttsFetcher, type SpeechEnd, type SpeechOutput } from "../speech-player";
import { StreamingSpeech, type SpeechPhrases } from "../speech-text";
import type { Phase, VoiceBar } from "./bar";
import { LEVEL_POLL_MS, type VoiceConfig, type VoiceStrings } from "./config";
import { Utterance, type UtteranceResult } from "./utterance";

/**
 * 음성 대화 한 번(CR-186 4.3, BIZ-254). 듣는 중, 답을 만드는 중, 읽는 중을 돌다가 끝내기, Esc, 오류로 끝난다.
 * 질문은 입력칸에서 보낸 것과 같은 길로 보내 대화 칸에 글로 쌓인다.
 */
export class VoiceSession {
  private closed = false;
  private current: Phase = "connecting";
  private readonly audioCtx: AudioContext;
  private stream: MediaStream | null = null;
  private analyser: AnalyserNode | null = null;
  private samples: Float32Array<ArrayBuffer> | null = null;
  private timer = 0;
  private utter: Utterance | null = null;
  private idleSince = 0;
  private queue: SpeechQueue | null = null;
  private speech: StreamingSpeech | null = null;
  /** 보낸 질문의 응답이 시작되기를 기다림 */
  private awaitingAnswer = false;
  /** 그 응답이 오는 중 */
  private watching = false;
  /** 읽기가 먼저 끝났거나 실패해 응답이 끝나면 다시 듣는다 */
  private listenAfterAnswer = false;
  private readonly offs: Array<() => void> = [];

  constructor(
    private readonly ctx: WidgetContext,
    private readonly cfg: VoiceConfig,
    private readonly bar: VoiceBar,
    private readonly out: SpeechOutput,
    private readonly t: VoiceStrings,
    private readonly phrases: SpeechPhrases,
    private readonly onClosed: () => void,
  ) {
    // 사람이 누른 처리 안에서 만들어야 브라우저가 소리 분석과 재생을 막지 않는다.
    this.audioCtx = new AudioContext();
    out.silence();
    out.unlock();
  }

  get phase(): Phase {
    return this.current;
  }

  async open(): Promise<void> {
    this.bar.show();
    this.setPhase("connecting");
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    } catch {
      this.close(this.t.micDenied);
      return;
    }
    if (this.closed) {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }
    this.stream = stream;
    const analyser = this.audioCtx.createAnalyser();
    this.audioCtx.createMediaStreamSource(stream).connect(analyser);
    this.analyser = analyser;
    this.samples = new Float32Array(analyser.fftSize);
    void this.audioCtx.resume().catch(() => undefined);
    this.timer = window.setInterval(() => this.tick(), LEVEL_POLL_MS);
    this.offs.push(
      this.ctx.on("answerStart", () => this.onAnswerStart()),
      this.ctx.on("answerDelta", ({ answer }) => this.onAnswerDelta(answer.text)),
      this.ctx.on("answerDone", (a) => this.onAnswerDone(a.text)),
    );
    this.listen(true);
  }

  /** 말 끊기: 응답과 읽기를 멈추고 다시 듣는다. */
  interrupt(): void {
    if (this.closed || (this.current !== "thinking" && this.current !== "speaking")) return;
    const asked = this.awaitingAnswer || this.watching;
    this.awaitingAnswer = false;
    this.watching = false;
    this.listenAfterAnswer = false;
    const q = this.queue;
    this.queue = null;
    q?.stop();
    if (asked) void this.ctx.chat.abort();
    this.listen(true);
  }

  /** 끝내기. 오고 있는 응답은 끊지 않아 글로 끝까지 받는다. */
  close(notice?: string): void {
    if (this.closed) return;
    this.closed = true;
    this.utter?.cancel();
    this.utter = null;
    const q = this.queue;
    this.queue = null;
    q?.stop();
    window.clearInterval(this.timer);
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    void this.audioCtx.close().catch(() => undefined);
    this.offs.splice(0).forEach((off) => off());
    this.bar.hide();
    if (notice) this.ctx.ui.notice(notice);
    this.onClosed();
  }

  private setPhase(p: Phase): void {
    this.current = p;
    this.bar.phase(p);
  }

  private tick(): void {
    const u = this.utter;
    if (this.closed || !this.analyser || !this.samples || !u?.recording) return;
    this.analyser.getFloatTimeDomainData(this.samples);
    let sum = 0;
    for (const s of this.samples) sum += s * s;
    const level = Math.sqrt(sum / this.samples.length);
    const now = performance.now();
    this.bar.level(Math.min(1, level / this.cfg.speechLevel));
    u.tick(level, now);
    if (!u.hasSpeech && now - this.idleSince >= this.cfg.idleEndMs) this.close(this.t.idleEnded);
  }

  /** fresh 가 거짓이면 말 없이 다시 연 것이라 말 없는 시간을 이어 잰다. */
  private listen(fresh: boolean): void {
    const stream = this.stream;
    if (this.closed || !stream) return;
    if (fresh) this.idleSince = performance.now();
    this.setPhase("connecting");
    this.bar.transcript("", "");
    const u = new Utterance(this.ctx, stream, this.cfg, {
      onReady: () => {
        if (this.utter === u) this.setPhase("listening");
      },
      onTranscript: (text, buffer) => {
        if (this.utter === u) this.bar.transcript(text, buffer);
      },
    });
    this.utter = u;
    void u.result.then((r) => this.onUtterance(u, r));
    void u.start();
  }

  private onUtterance(u: Utterance, r: UtteranceResult): void {
    if (this.utter !== u || this.closed) return;
    this.utter = null;
    switch (r.kind) {
      case "silent":
        this.listen(false);
        break;
      case "empty":
        this.ctx.ui.toast(this.t.notHeard);
        this.listen(true);
        break;
      case "text":
        this.ask(r.text);
        break;
      case "fail":
        this.close(r.reason === "busy" ? this.t.busy : this.t.serverFail);
        break;
      case "cancelled":
        break;
    }
  }

  private ask(text: string): void {
    this.setPhase("thinking");
    this.bar.transcript("", "");
    this.speech = new StreamingSpeech(this.cfg.maxChars, this.phrases);
    const queue = new SpeechQueue(this.out, ttsFetcher(this.ctx, this.ctx.sessionId()), {
      onPlay: () => {
        if (this.queue === queue) this.setPhase("speaking");
      },
      onEnd: (how) => this.onSpoken(queue, how),
    });
    this.queue = queue;
    this.awaitingAnswer = true;
    this.listenAfterAnswer = false;
    // 보내기에 실패하면(세션이 바쁨 등) 오류 글은 위젯 본체가 대화 칸에 보이고, 여기서는 다시 듣는다(4.3 오류 6).
    const giveUp = () => {
      if (this.closed || this.queue !== queue || !this.awaitingAnswer) return;
      this.awaitingAnswer = false;
      this.queue = null;
      queue.stop();
      this.listen(true);
    };
    this.ctx.chat.send(text).then(giveUp, giveUp);
  }

  private onAnswerStart(): void {
    if (!this.awaitingAnswer) return;
    this.awaitingAnswer = false;
    this.watching = true;
  }

  private onAnswerDelta(markdownSoFar: string): void {
    if (!this.watching || !this.speech) return;
    for (const c of this.speech.push(markdownSoFar)) this.queue?.push(c);
  }

  private onAnswerDone(markdown: string): void {
    if (!this.watching) return;
    this.watching = false;
    if (this.speech) for (const c of this.speech.finish(markdown)) this.queue?.push(c);
    this.speech = null;
    this.queue?.close();
    if (this.listenAfterAnswer) {
      this.listenAfterAnswer = false;
      this.listen(true);
    }
  }

  private onSpoken(queue: SpeechQueue, how: SpeechEnd): void {
    if (this.closed || this.queue !== queue) return;
    this.queue = null;
    if (how === "failed") this.ctx.ui.toast(this.t.readFailed);
    if (this.watching || this.awaitingAnswer) {
      // 읽기가 멈췄어도 응답은 글로 끝까지 받고, 끝나면 다시 듣는다(4.3 오류 5).
      this.listenAfterAnswer = true;
      this.setPhase("thinking");
      return;
    }
    this.listen(true);
  }
}
