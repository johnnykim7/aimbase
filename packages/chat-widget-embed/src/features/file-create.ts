import type { WidgetFeature } from "../frame/context";
import { fileShareSettings } from "./chat-files";

/**
 * 파일 만들기(CR-182 2절, PRD-501). 관리자가 위젯 설정에서 켠 앱만 대화 요청에 file_create 를 싣고,
 * 서버는 그 요청에만 create_file 도구를 연다. 화면에 따로 그리는 것은 없고 만든 파일은 파일 칩으로 온다.
 */
export const fileCreate: WidgetFeature = {
  id: "file-create",
  install(ctx) {
    ctx.chat.addRequestFields(() =>
      fileShareSettings(ctx)?.file_create_enabled === true ? { file_create: true } : {},
    );
  },
};
