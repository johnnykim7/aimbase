import { defineAimbaseChat } from "./web-component";
import { createWidget } from "./widget";

export { createWidget } from "./widget";
export { defineAimbaseChat, AimbaseChatElement } from "./web-component";
export type {
  AuthResolver,
  ApprovalEvent,
  ChatDelta,
  Citation,
  DisplayMode,
  TokenResponse,
  WidgetHandle,
  WidgetOptions,
  WorkflowStepEvent,
} from "./types";

/** UMD/IIFE 번들 로드 시 자동으로 <aimbase-chat> 커스텀 엘리먼트 등록. */
if (typeof window !== "undefined") {
  try {
    defineAimbaseChat();
  } catch {
    // CE 지원 안 하는 구형 브라우저는 ignore — initAimbaseChat() 로 수동 사용 가능
  }
}

/** 전역 네임스페이스 편의 API — <script> 로드 시 `window.AimbaseChat.init(...)` 로 호출 가능. */
export function init(options: import("./types").WidgetOptions): import("./types").WidgetHandle {
  return createWidget(options);
}
