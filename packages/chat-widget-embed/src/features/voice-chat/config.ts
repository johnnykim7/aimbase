import type { WidgetContext } from "../../frame/context";
import { defineStrings } from "../../frame/i18n";
import { isPositive, readableMaxChars, speechSettings } from "../speech-player";

export const TICKET_PATH = "/api/v1/chat/voice/tickets";
export const SCOPE_VOICE = "chat:voice";

/** 받아쓰기 서버가 받는 소리 형식이다(CR-186 3.3). 이 형식으로 녹음하지 못하는 브라우저에는 단추를 보이지 않는다. */
export const VOICE_MIME = "audio/webm;codecs=opus";

/** 중계는 받는 대로 받아쓰기 서버로 넘기므로 조각이 작을수록 받아쓴 글이 빨리 보인다. */
export const CHUNK_MS = 250;

/** 말소리 크기를 재는 간격. 끝 판정(침묵 시간)의 오차가 이만큼이다. */
export const LEVEL_POLL_MS = 50;

/** 중계 닫는 번호(CR-186 3.3) */
export const CLOSE_TOO_LONG = 4408;
export const CLOSE_FULL = 4429;

export interface VoiceConfig {
  maxChars: number;
  endSilenceMs: number;
  speechLevel: number;
  maxUtteranceMs: number;
  idleEndMs: number;
}

/** 위젯 설정과 토큰으로 음성 대화를 쓸 수 있으면 그 값, 아니면 null. */
export function voiceConfig(ctx: WidgetContext): VoiceConfig | null {
  const s = speechSettings(ctx);
  const maxChars = readableMaxChars(ctx);
  if (maxChars === null || s.voice_chat_enabled !== true || !ctx.api.hasScope(SCOPE_VOICE)) return null;
  if (
    !isPositive(s.voice_end_silence_ms) ||
    !isPositive(s.voice_speech_level) ||
    !isPositive(s.voice_max_utterance_seconds) ||
    !isPositive(s.voice_idle_end_seconds)
  ) {
    return null;
  }
  return {
    maxChars,
    endSilenceMs: s.voice_end_silence_ms,
    speechLevel: s.voice_speech_level,
    maxUtteranceMs: s.voice_max_utterance_seconds * 1000,
    idleEndMs: s.voice_idle_end_seconds * 1000,
  };
}

export function browserSupportsVoice(): boolean {
  return (
    typeof WebSocket !== "undefined" &&
    typeof AudioContext !== "undefined" &&
    typeof MediaRecorder !== "undefined" &&
    MediaRecorder.isTypeSupported(VOICE_MIME) &&
    typeof navigator !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia
  );
}

export const STRINGS = defineStrings({
  ko: {
    voiceChat: "음성 대화",
    connecting: "연결하는 중",
    listening: "듣는 중",
    thinking: "답을 만드는 중",
    speaking: "읽는 중",
    interrupt: "말 끊기",
    end: "끝내기",
    micDenied: "마이크 권한이 필요합니다",
    busy: "지금은 음성 대화 자리가 없습니다. 잠시 뒤 다시 시도해 주세요",
    serverFail: "음성 인식 서버에 연결하지 못했습니다",
    notHeard: "잘 듣지 못했습니다",
    idleEnded: "음성 대화를 마쳤습니다",
    readFailed: "읽어 드리지 못했습니다",
  },
  en: {
    voiceChat: "Voice chat",
    connecting: "Connecting",
    listening: "Listening",
    thinking: "Thinking",
    speaking: "Speaking",
    interrupt: "Interrupt",
    end: "End",
    micDenied: "Microphone permission is required",
    busy: "No voice chat slots are free right now. Please try again shortly",
    serverFail: "Couldn't connect to the speech recognition server",
    notHeard: "Sorry, I didn't catch that",
    idleEnded: "Voice chat ended",
    readFailed: "Couldn't read this aloud",
  },
});

export type VoiceStrings = typeof STRINGS.ko;
