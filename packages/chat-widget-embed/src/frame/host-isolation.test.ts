import { describe, expect, it } from "vitest";
import { containEvents, HOST_CONTAINED_EVENTS } from "./host-isolation";

describe("TC-CHT-155 모달 위 위젯 누름", () => {
  it("위젯 안의 누름, 초점, 휠은 붙이는 앱 문서로 올라가지 않는다", () => {
    const host = new EventTarget();
    containEvents(host);
    for (const type of HOST_CONTAINED_EVENTS) {
      const e = new Event(type, { bubbles: true });
      host.dispatchEvent(e);
      expect(e.cancelBubble, type).toBe(true);
    }
  });

  it("키 입력과 클릭은 지금처럼 올라간다(모달의 Esc 는 붙이는 앱이 거른다)", () => {
    const host = new EventTarget();
    containEvents(host);
    for (const type of ["keydown", "click"]) {
      const e = new Event(type, { bubbles: true });
      host.dispatchEvent(e);
      expect(e.cancelBubble, type).toBe(false);
    }
  });
});
