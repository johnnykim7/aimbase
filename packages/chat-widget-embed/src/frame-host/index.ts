/**
 * CR-203: 연결 스크립트 진입점(aimbase-chat-frame.js). <script> 로 넣으면 <aimbase-chat-frame> 을 등록한다.
 * 채팅창 코드는 들어 있지 않다. 채팅창은 Aimbase 출처의 전용 페이지가 띄운다.
 */
import { defineAimbaseChatFrame } from "./element";

defineAimbaseChatFrame();

export { AimbaseChatFrameElement, defineAimbaseChatFrame } from "./element";
