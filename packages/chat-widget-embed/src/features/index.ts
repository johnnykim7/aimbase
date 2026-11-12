/**
 * 위젯 기능 등록표(CR-177). 기능을 더하는 건은 import 한 줄과 목록 한 줄을 더한다.
 * 목록 순서는 화면 순서와 상관없다. 쓰는 법은 docs/guides/chat-widget-frame.md 다.
 */
import type { WidgetFeature } from "../frame/context";
import { dataChart } from "./data-chart";
import { documentEdit } from "./document-edit";
import { fileCreate } from "./file-create";
import { fontSize } from "./font-size";
import { history } from "./history";
import { imageGen } from "./image-gen";
import { language } from "./language";
import { math } from "./math";
import { memory } from "./memory";
import { preview } from "./preview";
import { readAloud } from "./read-aloud";
import { saveExport } from "./save-export";
import { screenCapture } from "./screen-capture";
import { shareLink } from "./share-link";
import { voiceChat } from "./voice-chat";

export const FEATURES: WidgetFeature[] = [
  fontSize, language, preview, imageGen, fileCreate, saveExport, shareLink, memory, documentEdit, history, readAloud, voiceChat,
  dataChart, math, screenCapture,
];
