/**
 * 위젯 기능 등록표(CR-177). 기능을 더하는 건은 import 한 줄과 목록 한 줄을 더한다.
 * 목록 순서는 화면 순서와 상관없다. 쓰는 법은 docs/guides/chat-widget-frame.md 다.
 */
import type { WidgetFeature } from "../frame/context";
import { fontSize } from "./font-size";
import { language } from "./language";

export const FEATURES: WidgetFeature[] = [fontSize, language];
