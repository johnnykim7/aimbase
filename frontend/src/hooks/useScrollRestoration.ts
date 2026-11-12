import { useCallback, useEffect, useRef } from "react";

const scrollParent = (el: HTMLElement | null): HTMLElement | null => {
  let node = el?.parentElement ?? null;
  while (node) {
    const { overflowY } = getComputedStyle(node);
    if (overflowY === "auto" || overflowY === "scroll") return node;
    node = node.parentElement;
  }
  return null;
};

const read = (key: string): number | null => {
  try {
    const raw = sessionStorage.getItem(key);
    return raw === null ? null : Number(raw);
  } catch {
    return null;
  }
};

/**
 * 목록에서 상세로 갔다가 돌아오면 보던 위치로 되돌린다.
 * 본문만 스크롤되는 구조라 window 가 아니라 가장 가까운 스크롤 부모의 위치를 저장한다.
 */
export function useScrollRestoration<T extends HTMLElement>(key: string, ready: boolean) {
  const anchorRef = useRef<T>(null);
  const restoredKey = useRef<string | null>(null);

  useEffect(() => {
    if (!ready || restoredKey.current === key) return;
    restoredKey.current = key;
    const top = read(`scroll:${key}`);
    const parent = scrollParent(anchorRef.current);
    if (top !== null && parent) parent.scrollTop = top;
  }, [key, ready]);

  const save = useCallback(() => {
    const parent = scrollParent(anchorRef.current);
    if (!parent) return;
    try {
      sessionStorage.setItem(`scroll:${key}`, String(parent.scrollTop));
    } catch {
      // 저장소를 막은 브라우저에서는 위치 복원만 빠진다.
    }
  }, [key]);

  return { anchorRef, save };
}
