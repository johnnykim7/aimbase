/**
 * 응답 아래 줄의 읽어 주기(CR-186 4.2, T3-3 화면 컨셉 1, 2, PRD-580). 한 번에 한 응답만 읽는다.
 */
import type { AnswerView, WidgetFeature } from "../frame/context";
import { defineStrings } from "../frame/i18n";
import { icon, svgIcon } from "../icons";
import { chunkSpeech, toSpeechText } from "./speech-text";
import { PHRASES, readableMaxChars, SpeechQueue, speechOutput, ttsFetcher } from "./speech-player";

const S = defineStrings({
  ko: { read: "읽어 주기", stop: "읽기 멈춤", empty: "읽을 글이 없습니다", failed: "읽어 드리지 못했습니다" },
  en: { read: "Read aloud", stop: "Stop reading", empty: "Nothing to read aloud", failed: "Couldn't read this aloud" },
});

const SPEAKER = svgIcon(
  '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><path d="M16 9a5 5 0 0 1 0 6"/><path d="M19.364 18.364a9 9 0 0 0 0-12.728"/>',
);

const CSS = `
.ra-wrap { display: inline-flex; align-items: center; gap: 4px; }
.ra-note { font-size: 12px; color: var(--aimbase-muted); white-space: nowrap; animation: ra-fade 3s ease-in forwards; }
.ra-note[hidden] { display: none; }
@keyframes ra-fade { 0%, 70% { opacity: 1; } 100% { opacity: 0; } }
`;

/** 같은 응답을 다시 그려도(버전, 언어 바꿈) 읽는 중 표시가 이어지도록 응답을 가리키는 열쇠. */
function answerKey(a: AnswerView): string {
  return `${a.sessionId}|${a.turnId ?? ""}|${a.turnSeq ?? ""}|${a.at.getTime()}`;
}

export const readAloud: WidgetFeature = {
  id: "read-aloud",
  css: CSS,
  install(ctx) {
    const t = ctx.strings(S);
    const phrases = ctx.strings(PHRASES);
    const out = speechOutput(ctx);
    const buttons = new Map<string, Set<HTMLButtonElement>>();
    let current: { key: string; queue: SpeechQueue } | null = null;

    const paint = (b: HTMLButtonElement, reading: boolean) => {
      const label = reading ? t.stop : t.read;
      b.innerHTML = reading ? icon("stop") : SPEAKER;
      b.setAttribute("aria-label", label);
      b.setAttribute("data-tip", label);
      b.setAttribute("aria-pressed", String(reading));
    };

    const refresh = (key?: string) => {
      for (const [k, set] of buttons) {
        if (key !== undefined && k !== key) continue;
        for (const b of set) {
          if (!b.isConnected) set.delete(b);
          else paint(b, current?.key === k);
        }
        if (!set.size) buttons.delete(k);
      }
    };

    const flash = (key: string, text: string) => {
      for (const b of buttons.get(key) ?? []) {
        const note = b.parentElement?.querySelector<HTMLElement>(".ra-note");
        if (!note) continue;
        note.hidden = false;
        note.textContent = text;
        // 같은 알림을 다시 띄울 때 사라지는 움직임을 처음부터 다시 돌린다.
        note.style.animation = "none";
        void note.offsetWidth;
        note.style.animation = "";
      }
    };

    const stop = () => {
      current?.queue.stop();
    };

    const start = (a: AnswerView, key: string) => {
      const maxChars = readableMaxChars(ctx);
      if (maxChars === null) return;
      const chunks = chunkSpeech(toSpeechText(a.text, phrases), maxChars);
      if (!chunks.length) {
        flash(key, t.empty);
        return;
      }
      out.silence();
      out.unlock();
      const queue = new SpeechQueue(out, ttsFetcher(ctx, a.sessionId), {
        onEnd: (how) => {
          if (current?.queue === queue) current = null;
          refresh(key);
          if (how === "failed") flash(key, t.failed);
        },
      });
      current = { key, queue };
      refresh(key);
      for (const c of chunks) queue.push(c);
      queue.close();
    };

    ctx.ui.answer.addAction({
      id: "read-aloud",
      order: 10,
      render: (a) => {
        if (a.error || readableMaxChars(ctx) === null) return null;
        const key = answerKey(a);
        const wrap = document.createElement("span");
        wrap.className = "ra-wrap";
        const b = document.createElement("button");
        b.type = "button";
        b.className = "ib sm";
        const note = document.createElement("span");
        note.className = "ra-note";
        note.hidden = true;
        note.setAttribute("role", "status");
        note.addEventListener("animationend", () => {
          note.hidden = true;
        });
        b.addEventListener("click", () => {
          if (current?.key === key) stop();
          else start(a, key);
        });
        let set = buttons.get(key);
        if (!set) buttons.set(key, (set = new Set()));
        set.add(b);
        paint(b, current?.key === key);
        wrap.append(b, note);
        return wrap;
      },
    });

    ctx.ui.onEscape(() => {
      if (!current) return false;
      stop();
      return true;
    });
    ctx.on("answerStart", stop);
    ctx.on("newConversation", stop);
    ctx.on("destroy", stop);
    ctx.on("panelToggle", ({ open }) => {
      if (!open) stop();
    });
    ctx.on("localeChange", () => refresh());
  },
};
