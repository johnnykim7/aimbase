/**
 * 입력칸의 음성 대화(CR-186 4.3, T3-3 화면 컨셉 3 ~ 8, PRD-581, PRD-582).
 */
import type { WidgetFeature } from "../../frame/context";
import { svgIcon } from "../../icons";
import { PHRASES, speechOutput } from "../speech-player";
import { BAR_CSS, VoiceBar } from "./bar";
import { browserSupportsVoice, STRINGS, voiceConfig } from "./config";
import { VoiceSession } from "./session";

/** 음성 대화 단추 그림(소리 물결) */
const WAVE = svgIcon(
  '<path d="M2 10v3"/><path d="M6 6v11"/><path d="M10 3v18"/><path d="M14 8v7"/><path d="M18 5v13"/><path d="M22 10v3"/>',
);

export const voiceChat: WidgetFeature = {
  id: "voice-chat",
  css: BAR_CSS,
  install(ctx) {
    const t = ctx.strings(STRINGS);
    const phrases = ctx.strings(PHRASES);
    const out = speechOutput(ctx);
    let session: VoiceSession | null = null;

    const bar = new VoiceBar(ctx.ui.layer, t, {
      interrupt: () => session?.interrupt(),
      end: () => session?.close(),
    });

    const button = document.createElement("button");
    button.type = "button";
    button.className = "ib";
    button.innerHTML = WAVE;
    button.hidden = true;

    const update = () => {
      button.hidden = !(voiceConfig(ctx) && browserSupportsVoice());
      button.setAttribute("aria-label", t.voiceChat);
      button.setAttribute("data-tip", t.voiceChat);
    };

    button.addEventListener("click", () => {
      if (session || ctx.isRecording()) return;
      const cfg = voiceConfig(ctx);
      if (!cfg || !browserSupportsVoice()) return;
      const s = new VoiceSession(ctx, cfg, bar, out, t, phrases, () => {
        if (session === s) session = null;
      });
      session = s;
      void s.open();
    });

    ctx.ui.composer.addTool({ id: "voice-chat", order: 10, render: () => button });
    ctx.ui.onEscape(() => {
      if (!session) return false;
      session.close();
      return true;
    });
    ctx.on("settingsLoaded", update);
    ctx.on("localeChange", () => {
      update();
      bar.relabel();
    });
    ctx.on("newConversation", () => session?.close());
    ctx.on("destroy", () => session?.close());
    // 창을 닫은 뒤에도 마이크가 켜져 있으면 사람이 모르는 채 듣게 된다.
    ctx.on("panelToggle", ({ open }) => {
      if (!open) session?.close();
    });
    update();
  },
};
