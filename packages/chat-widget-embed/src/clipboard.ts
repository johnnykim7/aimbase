/**
 * 클립보드에 글을 넣는다. 넣었으면 true.
 * 브라우저 클립보드 기능은 https 와 localhost 에서만 열려 있다. 붙이는 앱이 http 로 뜨면
 * 숨긴 입력칸에 글을 담아 복사 명령으로 넣는다(CR-175, 개발기 백오피스가 http).
 */
export async function copyText(text: string): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 권한을 막은 브라우저도 복사 명령은 받는 경우가 있어 한 번 더 해 본다
    }
  }
  return copyWithCommand(text);
}

function copyWithCommand(text: string): boolean {
  const focused = deepActiveElement();
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.style.cssText = "position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;pointer-events:none;";
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand("copy");
  } catch {
    ok = false;
  }
  ta.remove();
  focused?.focus({ preventScroll: true });
  return ok;
}

// 위젯 단추는 그림자 영역 안에 있어 document.activeElement 로는 위젯 바깥 틀만 잡힌다
function deepActiveElement(): HTMLElement | null {
  let el: Element | null = document.activeElement;
  while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
  return el instanceof HTMLElement ? el : null;
}
