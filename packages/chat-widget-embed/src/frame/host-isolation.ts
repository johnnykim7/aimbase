/**
 * CR-194: 붙이는 앱의 모달(건 창)은 화면 나머지 누름을 끄고(body 의 pointer-events: none), 문서로 올라온 누름, 초점, 휠을 보고
 * 창을 닫거나 초점을 되찾거나 스크롤을 막는다. 그래서 모달이 열린 동안 위젯 단추가 눌리지 않았다.
 * 위젯 자리는 누름을 받게 하고, 위젯 안에서 일어난 이 일들은 붙이는 앱 문서로 올리지 않는다.
 * 문서 앞단(capture)에서 받는 것(모달의 Esc)과 모달 안 요소에서 나는 일(초점을 떠날 때의 focusout)은 위젯이 막을 수 없다.
 */
export const HOST_CONTAINED_EVENTS = [
  "pointerdown",
  "mousedown",
  "touchstart",
  "touchmove",
  "wheel",
  "focusin",
  "focusout",
] as const;

export function isolateHost(host: HTMLElement): void {
  host.style.pointerEvents = "auto";
  containEvents(host);
}

export function containEvents(target: EventTarget): void {
  const stop = (e: Event) => e.stopPropagation();
  for (const type of HOST_CONTAINED_EVENTS) target.addEventListener(type, stop, { passive: true });
}
