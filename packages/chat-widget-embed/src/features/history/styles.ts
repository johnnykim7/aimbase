/** 대화 목록 기능 화면 모양. 색은 위젯 본체의 --aimbase-* 값을 따른다. */
export const CSS = `
.hist { position: relative; height: 100%; display: flex; flex-direction: column; min-height: 0; font-size: .92em; }
.hist [hidden], .hist-dlg [hidden] { display: none !important; }
.hist-top { padding: 10px 8px 6px; display: flex; flex-direction: column; gap: 6px; }
.hist-new { display: flex; align-items: center; gap: 8px; width: 100%; padding: 7px 9px; border: 0; background: transparent; border-radius: 8px; cursor: pointer; color: var(--aimbase-fg); font: inherit; text-align: left; }
.hist-new:hover { background: var(--aimbase-chip-bg); }
.hist-search { display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 3px 0 9px; border: 1px solid var(--aimbase-border); border-radius: 8px; background: var(--aimbase-bg); color: var(--aimbase-muted); cursor: text; }
.hist-search:focus-within { border-color: var(--aimbase-border-strong); }
.hist-search input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent; color: var(--aimbase-fg); font: inherit; }
.hist-sicon { display: inline-flex; }
.hist-list { flex: 1; min-height: 0; overflow-y: auto; padding: 0 6px 8px; }
.hist-foot { padding: 6px 8px 10px; border-top: 1px solid var(--aimbase-border); }
.hist-sec { padding: 12px 8px 4px; font-size: .8em; font-weight: 600; color: var(--aimbase-muted); }
.hist-grp { padding: 8px 8px 2px; font-size: .78em; color: var(--aimbase-muted); }
.hist-row { display: flex; align-items: center; gap: 6px; min-height: 32px; padding: 3px 3px 3px 9px; border-radius: 8px; cursor: pointer; color: var(--aimbase-fg); outline: none; }
.hist-row:hover, .hist-row:focus-visible { background: var(--aimbase-chip-bg); }
.hist-row.on { background: var(--aimbase-chip-hover); font-weight: 600; }
.hist-prow { padding-left: 4px; }
.hist-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-rmore { flex-shrink: 0; opacity: 0; }
.hist-row:hover .hist-rmore, .hist-row:focus-within .hist-rmore, .hist-row.on .hist-rmore { opacity: 1; }
@media (hover: none) { .hist-rmore { opacity: 1; } }
.hist-chev, .hist-fic { display: inline-flex; flex-shrink: 0; color: var(--aimbase-muted); }
.hist-pmain { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.hist-meta { font-size: .8em; font-weight: 400; color: var(--aimbase-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-psess { padding-left: 18px; }
.hist-rename { flex: 1; min-width: 0; height: 26px; padding: 0 6px; border: 1px solid var(--aimbase-border-strong); border-radius: 6px; background: var(--aimbase-bg); color: var(--aimbase-fg); font: inherit; font-weight: 400; outline: none; }
.hist-err { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 6px 9px; font-size: .86em; color: var(--aimbase-danger); }
.hist-empty { padding: 8px 9px; font-size: .86em; color: var(--aimbase-muted); }
.hist-morebtn { display: block; margin: 2px 0 4px 9px; padding: 3px 0; border: 0; background: transparent; color: var(--aimbase-muted); font: inherit; font-size: .86em; cursor: pointer; }
.hist-morebtn:hover { color: var(--aimbase-fg); text-decoration: underline; }
.hist-sentinel { height: 1px; }
.hist-spin { display: block; width: 16px; height: 16px; margin: 10px auto; box-sizing: border-box; border: 2px solid var(--aimbase-border); border-top-color: var(--aimbase-muted); border-radius: 50%; animation: hist-rot .8s linear infinite; }
.hist-spin.sm { display: inline-block; width: 12px; height: 12px; margin: 0; flex-shrink: 0; }
@keyframes hist-rot { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .hist-spin { animation-duration: 2.4s; } }
.hist-menu { min-width: 180px; }
.hist-danger-text, .pop .mi.hist-danger-text { color: var(--aimbase-danger); }
.hist-ov { position: absolute; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center; padding: 16px; background: rgba(24,24,27,.32); }
.hist-dlg { width: 100%; max-width: 440px; max-height: 100%; display: flex; flex-direction: column; box-sizing: border-box; background: var(--aimbase-bg); color: var(--aimbase-fg); border: 1px solid var(--aimbase-border); border-radius: 12px; box-shadow: 0 16px 40px rgba(0,0,0,.18); }
.hist-dhead { display: flex; align-items: center; gap: 8px; padding: 12px 12px 4px 16px; }
.hist-dhead h3 { flex: 1; margin: 0; font-size: 1em; font-weight: 600; }
.hist-dbody { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 16px; }
.hist-dfoot { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding: 8px 16px 14px; }
.hist-dfoot:empty { display: none; }
.hist-dleft { margin-right: auto; }
.hist-upload, .hist-dleft { display: inline-flex; align-items: center; gap: 6px; }
.hist-confirm { margin: 8px 0 4px; line-height: 1.5; }
.btn.hist-danger { background: var(--aimbase-danger); border-color: var(--aimbase-danger); color: var(--aimbase-bg); }
.btn.hist-danger:hover { opacity: .9; }
.hist-field { margin-bottom: 12px; }
.hist-label { margin-bottom: 4px; font-size: .86em; font-weight: 600; }
.hist-input { width: 100%; box-sizing: border-box; padding: 7px 9px; border: 1px solid var(--aimbase-border); border-radius: 8px; background: var(--aimbase-bg); color: var(--aimbase-fg); font: inherit; outline: none; }
.hist-input:focus { border-color: var(--aimbase-border-strong); }
.hist-ta { resize: vertical; min-height: 96px; line-height: 1.5; }
.hist-hintrow { display: flex; align-items: flex-start; gap: 8px; }
.hist-hintrow .note { flex: 1; }
.hist-count { margin-top: 4px; font-size: .8em; color: var(--aimbase-muted); white-space: nowrap; }
.hist-count.over { color: var(--aimbase-danger); }
.hist-form .note.error { white-space: pre-line; }
.hist-form .note.error:empty { display: none; }
.hist-files { display: flex; flex-direction: column; gap: 2px; margin-bottom: 6px; }
.hist-file { display: flex; align-items: center; gap: 8px; padding: 4px 4px 4px 6px; border-radius: 8px; }
.hist-file:hover { background: var(--aimbase-chip-bg); }
.hist-picks { display: flex; flex-direction: column; gap: 2px; }
.hist-pick { display: flex; align-items: center; gap: 9px; width: 100%; padding: 8px 9px; border: 0; background: transparent; border-radius: 8px; cursor: pointer; text-align: left; color: var(--aimbase-fg); font: inherit; }
.hist-pick:hover:not(:disabled) { background: var(--aimbase-chip-bg); }
.hist-pick:disabled { cursor: default; }
.hist-pick.on { font-weight: 600; }
.hist-pick.off { opacity: .55; }
.hist-pick > svg { flex-shrink: 0; color: var(--aimbase-muted); }
.hist-mk { margin-left: auto; display: inline-flex; }
`;
