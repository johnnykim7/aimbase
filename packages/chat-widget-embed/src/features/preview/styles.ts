/** 옆 창 미리보기와 응답 안 코드 블록 단추의 스타일. 문서 쪽 그림과 그림 바탕은 문서 색 그대로 흰색이다. */
export const PREVIEW_CSS = `
.pv-root { position: relative; display: flex; flex-direction: column; height: 100%; min-height: 0; }
.pv-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 24px; color: var(--aimbase-muted); text-align: center; }
.pv-state p { margin: 0; }
.pv-spin { width: 18px; height: 18px; border: 2px solid var(--aimbase-border); border-top-color: var(--aimbase-primary); border-radius: 50%; animation: pv-spin .8s linear infinite; }
@keyframes pv-spin { to { transform: rotate(360deg); } }
.pv-btn { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border: 1px solid var(--aimbase-border-strong); border-radius: 8px; background: var(--aimbase-bg); color: var(--aimbase-fg); cursor: pointer; font: inherit; }
.pv-btn:hover { background: var(--aimbase-chip-bg); }

.pv-htools { display: inline-flex; align-items: center; gap: 2px; flex-shrink: 0; }
.pv-hcount { font-size: .85em; color: var(--aimbase-muted); padding: 0 6px; white-space: nowrap; font-variant-numeric: tabular-nums; }

.pv-loading { position: relative; }
.pv-loading::after { content: ""; position: absolute; left: 50%; top: 50%; width: 18px; height: 18px; margin: -9px 0 0 -9px; border: 2px solid var(--aimbase-border); border-top-color: var(--aimbase-primary); border-radius: 50%; animation: pv-spin .8s linear infinite; }
.pv-err { position: relative; }
.pv-err::after { content: attr(data-msg); position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--aimbase-muted); font-size: .85em; }

.pv-slides { flex: 1; min-height: 0; display: flex; flex-direction: column; outline: none; }
.pv-stage { flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center; padding: 12px; }
.pv-stage img { max-width: 100%; max-height: 100%; object-fit: contain; background: #fff; box-shadow: 0 1px 4px rgba(0,0,0,.18); }
.pv-stage.pv-loading img, .pv-stage.pv-err img { visibility: hidden; }
.pv-nav { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 4px 0 10px; }
.pv-navbtn { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 1px solid var(--aimbase-border); border-radius: 8px; background: var(--aimbase-bg); color: var(--aimbase-fg); cursor: pointer; padding: 0; }
.pv-navbtn:hover:not(:disabled) { background: var(--aimbase-chip-bg); }
.pv-navbtn:disabled { opacity: .4; cursor: default; }
.pv-count { min-width: 56px; text-align: center; font-size: .9em; color: var(--aimbase-muted); font-variant-numeric: tabular-nums; }

.pv-pages { position: relative; flex: 1; min-height: 0; overflow: auto; padding: 12px; display: flex; flex-direction: column; gap: 12px; background: var(--aimbase-chip-bg); }
.pv-page { position: relative; width: 100%; flex-shrink: 0; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.14); }
.pv-page img { display: block; width: 100%; height: 100%; }

.pv-sheet { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.pv-tabs { display: flex; gap: 2px; padding: 6px 10px 0; overflow-x: auto; border-bottom: 1px solid var(--aimbase-border); flex-shrink: 0; }
.pv-tabs[hidden] { display: none; }
.pv-tab { border: 0; border-bottom: 2px solid transparent; background: transparent; padding: 6px 10px; color: var(--aimbase-muted); cursor: pointer; font: inherit; white-space: nowrap; }
.pv-tab.on { color: var(--aimbase-fg); border-bottom-color: var(--aimbase-primary); font-weight: 600; }
.pv-notice { padding: 6px 12px; font-size: .85em; color: var(--aimbase-muted); background: var(--aimbase-chip-bg); flex-shrink: 0; }
.pv-notice[hidden] { display: none; }
.pv-sheet-scroll { flex: 1; min-height: 0; overflow: auto; outline: none; }
.pv-table { border-collapse: separate; border-spacing: 0; font-size: .85em; }
.pv-table th, .pv-table td { border-right: 1px solid var(--aimbase-border); border-bottom: 1px solid var(--aimbase-border); padding: 4px 8px; text-align: left; vertical-align: top; white-space: pre-wrap; min-width: 48px; max-width: 320px; }
.pv-table thead th { position: sticky; top: 0; z-index: 1; background: var(--aimbase-chip-bg); font-weight: 600; }
.pv-table .pv-rn { position: sticky; left: 0; z-index: 1; min-width: 0; background: var(--aimbase-chip-bg); color: var(--aimbase-muted); font-weight: 400; text-align: right; }
.pv-table thead .pv-rn { z-index: 2; }

.pv-codebar { display: flex; align-items: center; gap: 6px; margin: 6px 0 4px; }
.pv-subbar { margin: 0; padding: 6px 10px; border-bottom: 1px solid var(--aimbase-border); flex-shrink: 0; }
.pv-barbtn { display: inline-flex; align-items: center; gap: 4px; padding: 3px 9px; border: 1px solid var(--aimbase-border); border-radius: 6px; background: var(--aimbase-bg); color: var(--aimbase-fg); font: inherit; font-size: .85em; cursor: pointer; }
.pv-barbtn:hover { background: var(--aimbase-chip-bg); }
.pv-barbtn.on { background: var(--aimbase-chip-bg); font-weight: 600; }
.pv-seg { display: inline-flex; }
.pv-seg .pv-barbtn { border-radius: 0; }
.pv-seg .pv-barbtn:first-child { border-radius: 6px 0 0 6px; }
.pv-seg .pv-barbtn:last-child { border-radius: 0 6px 6px 0; margin-left: -1px; }
.pv-svgblock { margin: 8px 0; }
.pv-svgimg { display: block; max-width: 100%; max-height: 420px; padding: 8px; background: #fff; border: 1px solid var(--aimbase-border); border-radius: 6px; box-sizing: border-box; }
.pv-svgimg[hidden], .pv-svgblock pre[hidden] { display: none; }

.pv-htmlbody { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.pv-frame { flex: 1; width: 100%; min-height: 0; border: 0; background: #fff; }

.pv-imgwrap { flex: 1; min-height: 0; overflow: auto; display: flex; align-items: center; justify-content: center; padding: 12px; cursor: zoom-in; }
.pv-imgwrap img { max-width: 100%; max-height: 100%; object-fit: contain; background: #fff; }
.pv-imgwrap.pv-vector img { width: 100%; height: 100%; }
.pv-imgwrap.pv-actual { cursor: zoom-out; align-items: flex-start; justify-content: flex-start; }
.pv-imgwrap.pv-actual img { max-width: none; max-height: none; width: auto; height: auto; }

.pv-md { flex: 1; min-height: 0; overflow: auto; padding: 12px 16px; }
.pv-text { flex: 1; min-height: 0; margin: 0; overflow: auto; padding: 12px 16px; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .85em; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

.pv-busy { position: absolute; inset: 0; z-index: 3; display: flex; align-items: center; justify-content: center; background: rgba(255,255,255,.6); }

.pv-present { position: fixed; inset: 0; z-index: 2147483647; display: flex; align-items: center; justify-content: center; background: #000; outline: none; cursor: pointer; user-select: none; }
.pv-present img { max-width: 100%; max-height: 100%; object-fit: contain; }
.pv-present-count { position: absolute; right: 16px; bottom: 12px; color: rgba(255,255,255,.7); font-size: 14px; font-variant-numeric: tabular-nums; }

.pv-chipdl { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; padding: 0; border: 0; border-radius: 5px; background: transparent; color: var(--aimbase-muted); cursor: pointer; }
.pv-chipdl:hover { background: var(--aimbase-chip-hover); color: var(--aimbase-fg); }
`;
