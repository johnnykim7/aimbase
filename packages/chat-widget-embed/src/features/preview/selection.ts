/*
 * 위젯은 그림자 영역 안에 있어 document.getSelection 만으로는 브라우저마다 다르게 보인다.
 * Chrome 은 shadowRoot.getSelection, Safari 는 getComposedRanges, Firefox 는 문서 선택이 그림자 안 노드를 그대로 준다.
 */
type ShadowWithSelection = ShadowRoot & { getSelection?: () => Selection | null };
type SelectionWithComposed = Selection & {
  getComposedRanges?: (...args: unknown[]) => StaticRange[];
};

function textOfRange(startContainer: Node, startOffset: number, endContainer: Node, endOffset: number): string {
  const range = document.createRange();
  range.setStart(startContainer, startOffset);
  range.setEnd(endContainer, endOffset);
  return range.toString();
}

function composedRanges(sel: SelectionWithComposed, root: ShadowRoot): StaticRange[] | null {
  if (typeof sel.getComposedRanges !== "function") return null;
  try {
    return sel.getComposedRanges({ shadowRoots: [root] });
  } catch {
    try {
      return sel.getComposedRanges(root);
    } catch {
      return null;
    }
  }
}

export function selectedTextWithin(body: HTMLElement): string {
  const root = body.getRootNode();
  const inBody = (a: Node, b: Node) => body.contains(a) && body.contains(b);

  if (root instanceof ShadowRoot) {
    const own = (root as ShadowWithSelection).getSelection?.();
    if (own) {
      if (own.rangeCount === 0) return "";
      const r = own.getRangeAt(0);
      return inBody(r.startContainer, r.endContainer) ? own.toString() : "";
    }
    const doc = document.getSelection() as SelectionWithComposed | null;
    if (!doc) return "";
    const ranges = composedRanges(doc, root);
    if (ranges) {
      const r = ranges[0];
      if (!r || r.collapsed || !inBody(r.startContainer, r.endContainer)) return "";
      return textOfRange(r.startContainer, r.startOffset, r.endContainer, r.endOffset);
    }
  }

  const sel = document.getSelection();
  if (!sel || sel.rangeCount === 0) return "";
  const r = sel.getRangeAt(0);
  return inBody(r.startContainer, r.endContainer) ? sel.toString() : "";
}

/** 선택을 마친 때(마우스 뗌, 키 뗌)만 알린다. 키는 선택이 바뀐 경우만 알려 같은 글이 거듭 가지 않게 한다. */
export function watchSelection(body: HTMLElement, notify: (text: string) => void): () => void {
  let last = "";
  const onMouseUp = () => {
    const text = selectedTextWithin(body);
    last = text.trim() ? text : "";
    if (last) notify(last);
  };
  const onKeyUp = () => {
    const text = selectedTextWithin(body);
    if (!text.trim()) {
      last = "";
      return;
    }
    if (text !== last) {
      last = text;
      notify(text);
    }
  };
  body.addEventListener("mouseup", onMouseUp);
  body.addEventListener("keyup", onKeyUp);
  return () => {
    body.removeEventListener("mouseup", onMouseUp);
    body.removeEventListener("keyup", onKeyUp);
  };
}
