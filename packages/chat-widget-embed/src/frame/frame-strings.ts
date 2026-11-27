/**
 * CR-203: 채팅창 전용 페이지의 안내 문구. 위젯 본체 문구표(strings.ts)와 따로 둔다.
 * 안내만 보일 때는 위젯 본체를 띄우지 않으므로 이 표만 읽는다.
 */
import { defineStrings } from "./i18n";

export const FRAME_TEXT = defineStrings({
  ko: {
    standalone: "이 화면은 연결된 서비스 화면 안에서만 열 수 있습니다",
    refused: "이 화면에서는 채팅창을 열 수 없습니다",
  },
  en: {
    standalone: "This page can only be opened inside a connected service page.",
    refused: "The chat cannot be opened on this page.",
  },
});
