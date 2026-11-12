/** CR-181 옆 창 고치기. 색과 둥근 정도는 CR-175 위젯 값만 쓴다. 보내기 단추와 문서 카드는 본체의 .send, .artifact 를 그대로 쓴다. */
export const DOCUMENT_EDIT_CSS = `
.de-ver { display: inline-flex; align-items: center; gap: 2px; }
.de-ver button {
  width: 28px; height: 28px; border: 0; padding: 0; border-radius: 8px; background: transparent;
  color: var(--aimbase-muted); display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.de-ver button:hover:not(:disabled) { background: var(--aimbase-chip-bg); color: var(--aimbase-fg); }
.de-ver button:disabled { opacity: .35; cursor: default; }
.de-ver .de-n { min-width: 36px; text-align: center; font-size: 12px; color: var(--aimbase-muted); font-variant-numeric: tabular-nums; }
.de-foot { border-top: 1px solid var(--aimbase-border); background: var(--aimbase-bg); }
.de-foot[hidden], .de-old[hidden], .de-chip[hidden] { display: none; }
.de-old {
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 8px 12px;
  background: var(--aimbase-chip-bg); border-bottom: 1px solid var(--aimbase-border); font-size: 12px; color: var(--aimbase-fg);
}
.de-old .de-msg { flex: 1 1 180px; min-width: 0; }
.de-link {
  border: 0; padding: 2px 6px; border-radius: 6px; background: transparent; font: inherit; font-weight: 600;
  color: var(--aimbase-fg); cursor: pointer;
}
.de-link:hover:not(:disabled) { background: var(--aimbase-chip-hover); }
.de-link:disabled { opacity: .4; cursor: default; }
.de-in { display: flex; flex-direction: column; gap: 6px; padding: 8px 10px 10px; }
.de-chip {
  display: flex; align-items: center; gap: 6px; align-self: flex-start; max-width: 100%; box-sizing: border-box;
  padding: 3px 4px 3px 10px; border: 1px solid var(--aimbase-border); border-radius: 999px;
  background: var(--aimbase-chip-bg); font-size: 12px; color: var(--aimbase-fg);
}
.de-chip > svg { flex-shrink: 0; color: var(--aimbase-muted); }
.de-chip .de-q { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.de-chip button {
  width: 20px; height: 20px; flex-shrink: 0; border: 0; padding: 0; border-radius: 999px; background: transparent;
  color: var(--aimbase-muted); display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.de-chip button:hover { background: var(--aimbase-chip-hover); color: var(--aimbase-fg); }
.de-comp {
  display: flex; align-items: flex-end; gap: 4px; padding: 3px 4px 3px 0;
  border: 1px solid var(--aimbase-border-strong); border-radius: 14px; background: var(--aimbase-bg);
}
.de-comp:focus-within { border-color: var(--aimbase-muted); }
.de-comp textarea {
  flex: 1; min-width: 0; display: block; border: 0; outline: none; resize: none; font: inherit;
  padding: 6px 12px; min-height: 32px; max-height: 180px; line-height: 1.5; box-sizing: border-box;
  background: transparent; color: var(--aimbase-fg);
}
.de-comp .send { margin: 0 0 1px; }
.de-result { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; }
.de-result:empty { display: none; }
.de-note { font-size: 12px; color: var(--aimbase-muted); }
`;
