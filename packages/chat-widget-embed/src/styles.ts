/** Shadow DOM 내부 스타일. CSS 변수로 테마 커스터마이징 가능. */
export const WIDGET_CSS = `
:host {
  --aimbase-primary: #18181b;
  --aimbase-primary-hover: #3f3f46;
  --aimbase-on-primary: #ffffff;
  --aimbase-bg: #ffffff;
  --aimbase-fg: #18181b;
  --aimbase-muted: #71717a;
  --aimbase-border: #e4e4e7;
  --aimbase-border-strong: #d4d4d8;
  --aimbase-user-bg: #f4f4f5;
  --aimbase-assistant-bg: #fafafa;
  --aimbase-chip-bg: #f4f4f5;
  --aimbase-chip-hover: #e4e4e7;
  --aimbase-danger: #dc2626;
  --aimbase-radius: 10px;
  --aimbase-font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif;
  font-family: var(--aimbase-font);
  color: var(--aimbase-fg);
  font-size: 14px;
}
:host([data-theme="dark"]) {
  --aimbase-primary: #fafafa;
  --aimbase-primary-hover: #d4d4d8;
  --aimbase-on-primary: #18181b;
  --aimbase-bg: #18181b;
  --aimbase-fg: #f4f4f5;
  --aimbase-muted: #a1a1aa;
  --aimbase-border: #3f3f46;
  --aimbase-border-strong: #52525b;
  --aimbase-user-bg: #27272a;
  --aimbase-assistant-bg: #1f1f23;
  --aimbase-chip-bg: #27272a;
  --aimbase-chip-hover: #3f3f46;
  --aimbase-danger: #f87171;
}

.root { box-sizing: border-box; }
*, *::before, *::after { box-sizing: border-box; }

/* 글자 크기 세 단계. 붙이는 앱은 theme.cssVars 로 값을 바꾼다(BIZ-152). */
:host { --aimbase-font-size-small: 13px; --aimbase-font-size-medium: 14px; --aimbase-font-size-large: 16px; }
.panel { font-size: var(--aimbase-font-size-medium); }
:host([data-font-size="small"]) .panel { font-size: var(--aimbase-font-size-small); }
:host([data-font-size="large"]) .panel { font-size: var(--aimbase-font-size-large); }
button { font: inherit; color: inherit; }

/* 버블 모드 */
.bubble-btn {
  position: fixed; bottom: 20px; right: 20px;
  width: 56px; height: 56px; border-radius: 50%;
  background: var(--aimbase-primary); color: var(--aimbase-on-primary);
  border: none; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.15);
  display: inline-flex; align-items: center; justify-content: center;
  z-index: 9998;
}
.bubble-btn:hover { background: var(--aimbase-primary-hover); }

.panel {
  position: relative;
  background: var(--aimbase-bg);
  border: 1px solid var(--aimbase-border);
  border-radius: var(--aimbase-radius);
  display: flex; flex-direction: row;
  overflow: hidden;
}
.panel.floating {
  position: fixed; bottom: 90px; right: 20px;
  width: 400px; height: 600px; max-height: 80vh;
  box-shadow: 0 10px 30px rgba(0,0,0,.18);
  z-index: 9999;
}
/* 칸 안에 넣을 때는 호스트와 root 가 칸 높이를 이어받아야 panel 의 100% 가 칸 높이가 되고, 대화가 칸 안에서 스크롤된다 */
:host([data-display="inline"]) { display: block; height: 100%; }
:host([data-display="inline"]) .root { height: 100%; }
.panel.inline { width: 100%; height: 100%; min-height: 400px; }
.panel.side { position: fixed; top: 0; right: 0; bottom: 0; width: 420px; max-width: 100vw; z-index: 9999; }
.panel.hidden { display: none; }
.panel.expanded {
  position: fixed; inset: 28px; width: auto; height: auto; max-height: none;
  z-index: 10000; box-shadow: 0 24px 60px rgba(0,0,0,.22);
}
.backdrop { display: none; position: fixed; inset: 0; background: rgba(24,24,27,.32); z-index: 9999; }
.backdrop.on { display: block; }

/* 위젯 틀(CR-177): 판은 [왼쪽 칸][대화][옆 창] 가로 배치다 */
[hidden] { display: none !important; }
.main { position: relative; flex: 1; min-width: 0; display: flex; flex-direction: column; }
.lp { width: 228px; flex-shrink: 0; display: flex; flex-direction: column; min-height: 0; border-right: 1px solid var(--aimbase-border); background: var(--aimbase-assistant-bg); }
.lp-body { flex: 1; min-height: 0; overflow-y: auto; }
.lp.overlay { position: absolute; top: 0; bottom: 0; left: 0; width: 280px; max-width: 85%; z-index: 50; background: var(--aimbase-bg); box-shadow: 4px 0 16px rgba(0,0,0,.12); }
.lp-scrim { display: none; position: absolute; inset: 0; background: rgba(24,24,27,.24); z-index: 45; }
.lp-scrim.on { display: block; }
.spane { width: 56%; flex-shrink: 0; display: flex; flex-direction: column; min-height: 0; border-left: 1px solid var(--aimbase-border); background: var(--aimbase-bg); }
.spane-hd { height: 44px; flex-shrink: 0; display: flex; align-items: center; gap: 2px; padding: 0 8px 0 14px; border-bottom: 1px solid var(--aimbase-border); }
.spane-ic { display: inline-flex; color: var(--aimbase-muted); margin-right: 6px; }
.spane-ttl { flex: 1; min-width: 0; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.spane-body { flex: 1; min-height: 0; overflow: auto; }
.spane-ft { flex-shrink: 0; padding: 8px 10px; border-top: 1px solid var(--aimbase-border); }
/* 좁은 화면에서는 옆 창이 대화를 덮어 판 전체를 쓴다 */
.panel.narrow.has-side .main { display: none; }
.panel.narrow .spane { width: 100%; border-left: 0; }

/* 아이콘 단추와 말풍선 도움말 */
.ib {
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border: 0; background: transparent; border-radius: 8px;
  color: var(--aimbase-muted); cursor: pointer; flex-shrink: 0; padding: 0;
}
.ib:hover { background: var(--aimbase-chip-bg); color: var(--aimbase-fg); }
.ib.on, .ib[aria-pressed="true"] { color: var(--aimbase-fg); background: var(--aimbase-chip-bg); }
.ib.sm { width: 26px; height: 26px; border-radius: 6px; }
.ib:disabled { opacity: .4; cursor: default; background: transparent; }
.ib[hidden] { display: none; }
[data-tip] { position: relative; }
[data-tip]:not([data-tip=""]):hover::after {
  content: attr(data-tip); position: absolute; bottom: calc(100% + 6px); left: 50%; transform: translateX(-50%);
  background: #18181b; color: #fff; font-size: 11px; padding: 3px 7px; border-radius: 6px;
  white-space: nowrap; pointer-events: none; z-index: 30;
}
.btn {
  height: 28px; padding: 0 12px; border-radius: 8px; border: 1px solid var(--aimbase-border);
  background: var(--aimbase-bg); cursor: pointer; font-size: .9em; font-weight: 500;
  display: inline-flex; align-items: center; gap: 6px;
}
.btn:hover { background: var(--aimbase-chip-bg); }
.btn.pri { background: var(--aimbase-primary); color: var(--aimbase-on-primary); border-color: var(--aimbase-primary); }
.btn.pri:hover { background: var(--aimbase-primary-hover); }
.btn:disabled { opacity: .45; cursor: default; }

/* 머리줄 */
.hd {
  height: 44px; flex-shrink: 0; display: flex; align-items: center; gap: 2px;
  padding: 0 8px 0 14px; border-bottom: 1px solid var(--aimbase-border);
}
.ttlwrap { flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; }
.ttl { flex: 0 1 auto; min-width: 0; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ttag {
  display: inline-flex; align-items: center; gap: 4px; flex-shrink: 1; min-width: 0; max-width: 45%; height: 22px; padding: 0 8px;
  border: 1px solid var(--aimbase-border); border-radius: 999px; background: var(--aimbase-chip-bg);
  color: var(--aimbase-muted); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer;
}
.ttag:disabled { cursor: default; color: var(--aimbase-muted); }
.ttag-ic { display: inline-flex; }

/* 대화 칸 */
.scroll { flex: 1; overflow-y: auto; position: relative; }
.thread { max-width: 760px; margin: 0 auto; padding: 16px 16px 8px; display: flex; flex-direction: column; gap: 18px; }
.empty { max-width: 620px; margin: 0 auto; padding: 28px 4px 6px; text-align: center; }
.empty h3 { margin: 0 0 4px; font-size: 1.25em; font-weight: 700; letter-spacing: -.01em; }
.empty p { margin: 0 0 16px; color: var(--aimbase-muted); font-size: .92em; }
.sugs { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; text-align: left; }
.sug {
  border: 1px solid var(--aimbase-border); background: var(--aimbase-bg); border-radius: 10px;
  padding: 9px 11px; cursor: pointer; font-size: .92em; line-height: 1.4; text-align: left;
}
.sug:hover { background: var(--aimbase-assistant-bg); border-color: var(--aimbase-border-strong); }

.u { align-self: flex-end; max-width: 82%; display: flex; flex-direction: column; align-items: flex-end; }
.u .bub { background: var(--aimbase-user-bg); border-radius: 14px; padding: 8px 12px; white-space: pre-wrap; line-height: 1.55; word-break: break-word; }
.u .acts { display: flex; justify-content: flex-end; gap: 2px; margin-top: 2px; align-items: center; }
.u .acts button { opacity: 0; transition: opacity .12s; }
.u:hover .acts button, .u:focus-within .acts button { opacity: 1; }
.time { font-size: 11px; color: var(--aimbase-muted); padding: 0 4px; white-space: nowrap; }
.u .edit { border: 1px solid var(--aimbase-border-strong); border-radius: 12px; padding: 8px; background: var(--aimbase-bg); width: min(520px, 72vw); }
.u .edit textarea { width: 100%; border: 0; outline: none; resize: none; font: inherit; min-height: 48px; background: transparent; color: var(--aimbase-fg); }
.u .edit .warn { font-size: .85em; color: var(--aimbase-muted); margin: 2px 2px 6px; }
.u .edit .eb { display: flex; justify-content: flex-end; gap: 6px; }

.a { display: flex; flex-direction: column; align-items: stretch; min-width: 0; }
.fold { display: inline-flex; align-items: center; gap: 6px; font-size: .86em; color: var(--aimbase-muted); cursor: pointer; border: 0; background: none; padding: 2px 0; align-self: flex-start; }
.fold:hover { color: var(--aimbase-fg); }
.fold .cv { display: inline-flex; transition: transform .15s; }
.fold.open .cv { transform: rotate(90deg); }
.fold.live .lbl {
  background: linear-gradient(90deg, var(--aimbase-muted) 0%, var(--aimbase-fg) 50%, var(--aimbase-muted) 100%);
  background-size: 200% 100%; -webkit-background-clip: text; background-clip: text; color: transparent;
  animation: shim 1.4s linear infinite;
}
@keyframes shim { to { background-position: -200% 0; } }
.foldbody { display: none; margin: 4px 0 6px 6px; padding: 6px 10px; border-left: 2px solid var(--aimbase-border); color: var(--aimbase-muted); font-size: .9em; line-height: 1.55; white-space: pre-wrap; }
.foldbody.open { display: block; }
.trow { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 8px; padding: 2px 0; font-size: .86em; white-space: normal; }
.trow .tn { color: var(--aimbase-fg); font-weight: 500; white-space: nowrap; }
.trow .ta { font-family: ui-monospace, Menlo, monospace; font-size: .8em; color: var(--aimbase-muted); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trow .to { flex-basis: 100%; padding-left: 14px; font-size: .9em; color: var(--aimbase-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--aimbase-border-strong); flex-shrink: 0; }
.dot.run { background: var(--aimbase-fg); animation: pulse 1s infinite; }
.dot.fail { background: var(--aimbase-danger); }
@keyframes pulse { 50% { opacity: .3; } }
.md { line-height: 1.65; word-break: break-word; }
.md.raw { white-space: pre-wrap; }
.caret::after { content: ""; display: inline-block; width: 7px; height: 1em; background: var(--aimbase-fg); margin-left: 2px; vertical-align: -2px; animation: pulse .8s infinite; }
.note { font-size: .86em; color: var(--aimbase-muted); margin-top: 4px; }
.note.error { color: var(--aimbase-danger); }
.notice { align-self: stretch; font-size: .88em; color: var(--aimbase-danger); border: 1px solid var(--aimbase-border); border-radius: 10px; padding: 8px 10px; white-space: pre-wrap; }
.notice.info { color: var(--aimbase-muted); }

.aacts { display: flex; align-items: center; flex-wrap: wrap; gap: 0; margin-top: 4px; }
.aacts .vn { font-size: .8em; color: var(--aimbase-muted); display: inline-flex; align-items: center; }
.aacts .mdl { font-size: 11px; color: var(--aimbase-muted); padding: 0 4px; white-space: nowrap; }
.panel.busy .needs-idle { display: none; }
.fb { margin-top: 6px; border: 1px solid var(--aimbase-border); border-radius: 10px; padding: 8px 10px; max-width: 520px; }
.fb .fq { font-size: .86em; color: var(--aimbase-muted); margin-bottom: 6px; }
.fb .fc { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 6px; }
.chipb { height: 26px; padding: 0 10px; font-size: .84em; border: 1px solid var(--aimbase-border); background: var(--aimbase-bg); border-radius: 999px; cursor: pointer; color: var(--aimbase-muted); }
.chipb:hover { color: var(--aimbase-fg); background: var(--aimbase-chip-bg); }
.chipb.on { background: var(--aimbase-fg); color: var(--aimbase-bg); border-color: var(--aimbase-fg); }
.fb input { width: 100%; border: 1px solid var(--aimbase-border); border-radius: 8px; padding: 6px 8px; font: inherit; font-size: .92em; outline: none; margin-bottom: 6px; background: var(--aimbase-bg); color: var(--aimbase-fg); }
.fb .eb { display: flex; justify-content: flex-end; gap: 6px; }
.fb .warn { font-size: .85em; color: var(--aimbase-danger); margin: -2px 2px 6px; }
.fups { display: flex; flex-direction: column; gap: 2px; margin-top: 8px; align-items: flex-start; }
.fups:empty { display: none; }
.fup { border: 0; background: transparent; color: var(--aimbase-fg); opacity: .8; font-size: .92em; cursor: pointer; padding: 3px 0; display: inline-flex; gap: 6px; align-items: center; text-align: left; }
.fup:hover { opacity: 1; text-decoration: underline; text-underline-offset: 3px; }
.fup .ar { color: var(--aimbase-muted); display: inline-flex; }
.tobottom {
  position: absolute; left: 50%; transform: translateX(-50%); bottom: 12px; display: none; align-items: center; gap: 6px;
  height: 30px; padding: 0 12px; border-radius: 999px; border: 1px solid var(--aimbase-border);
  background: var(--aimbase-bg); box-shadow: 0 4px 14px rgba(0,0,0,.1); cursor: pointer; font-size: 12px; z-index: 5;
}
.tobottom.on { display: inline-flex; }
.stack { position: relative; flex: 1; min-height: 0; display: flex; flex-direction: column; }

.citations { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.citation {
  background: var(--aimbase-chip-bg); border: 1px solid var(--aimbase-border); color: var(--aimbase-fg);
  padding: 4px 10px; border-radius: 12px; font-size: 12px; cursor: pointer;
}
.citation:hover { background: var(--aimbase-chip-hover); }
.artifacts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.artifact-item { display: inline-flex; align-items: center; gap: 2px; }
.workflow-approval:empty { display: none; }
.artifact {
  background: var(--aimbase-chip-bg); border: 1px solid var(--aimbase-border); color: var(--aimbase-fg);
  padding: 6px 12px; border-radius: 12px; font-size: 12px; font-weight: 500; cursor: pointer;
  display: inline-flex; align-items: center; gap: 6px;
}
.artifact:hover { background: var(--aimbase-chip-hover); }
.workflow-panel { padding: 10px; border: 1px solid var(--aimbase-border); border-radius: 8px; font-size: 12px; background: var(--aimbase-assistant-bg); }
.workflow-step { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
.workflow-step .dot { width: 8px; height: 8px; }
.workflow-step .dot.running { background: var(--aimbase-muted); animation: pulse 1.2s infinite; }
.workflow-step .dot.completed { background: var(--aimbase-fg); }
.workflow-step .dot.failed { background: var(--aimbase-danger); }
.citation-preview {
  position: absolute; right: 0; top: 0; bottom: 0; width: 60%;
  background: var(--aimbase-bg); border-left: 1px solid var(--aimbase-border);
  padding: 16px; overflow-y: auto; box-shadow: -4px 0 12px rgba(0,0,0,.1); z-index: 20;
}
.citation-preview h4 { margin: 0 0 8px; font-size: 1em; }
.citation-preview .meta { font-size: 12px; color: var(--aimbase-muted); margin-bottom: 10px; }
.citation-preview .content { font-size: .92em; line-height: 1.6; white-space: pre-wrap; }
.citation-preview .close { position: absolute; top: 8px; right: 8px; }

/* 입력칸 */
.cwrap { padding: 8px 12px 10px; flex-shrink: 0; }
.comp { max-width: 760px; margin: 0 auto; border: 1px solid var(--aimbase-border-strong); border-radius: 14px; background: var(--aimbase-bg); box-shadow: 0 1px 3px rgba(0,0,0,.04); }
.comp:focus-within { border-color: var(--aimbase-muted); }
.comp textarea {
  display: block; width: 100%; border: 0; outline: none; resize: none; font: inherit;
  padding: 10px 12px 2px; min-height: 36px; max-height: 180px; line-height: 1.5;
  background: transparent; color: var(--aimbase-fg);
}
.crow { display: flex; align-items: center; gap: 2px; padding: 4px 6px 6px; }
.crow .sp { flex: 1; }
.ctools { display: inline-flex; align-items: center; gap: 2px; }
.ctools:empty { display: none; }
.cmode { padding: 8px 10px 0; }
.cmode-pill { display: inline-flex; align-items: center; gap: 6px; height: 26px; padding: 0 2px 0 10px; border-radius: 999px; background: var(--aimbase-chip-bg); font-size: .86em; font-weight: 500; }
.cmode-ic { display: inline-flex; }
.model { display: inline-flex; align-items: center; gap: 4px; height: 28px; padding: 0 8px; border: 0; background: transparent; border-radius: 8px; cursor: pointer; color: var(--aimbase-muted); font-size: .9em; max-width: 160px; }
.model span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model:hover { background: var(--aimbase-chip-bg); color: var(--aimbase-fg); }
.model[hidden] { display: none; }
.send {
  width: 30px; height: 30px; border-radius: 999px; border: 0; background: var(--aimbase-primary); color: var(--aimbase-on-primary);
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer; margin-left: 2px; flex-shrink: 0; padding: 0;
}
.send:hover:not(:disabled) { background: var(--aimbase-primary-hover); }
.send:disabled { background: var(--aimbase-border-strong); cursor: default; }

.pop { position: absolute; z-index: 40; min-width: 200px; background: var(--aimbase-bg); border: 1px solid var(--aimbase-border); border-radius: 10px; box-shadow: 0 10px 30px rgba(0,0,0,.12); padding: 4px; display: none; }
.pop.on { display: block; }
.pop .mi { display: flex; align-items: center; gap: 9px; width: 100%; padding: 7px 9px; border: 0; background: transparent; border-radius: 7px; cursor: pointer; text-align: left; font-size: .9em; }
.pop .mi:hover { background: var(--aimbase-chip-bg); }
.pop .mi .mk { margin-left: auto; display: inline-flex; }
.pop .row { display: flex; align-items: center; gap: 8px; padding: 6px 9px; font-size: .9em; }
.pop .row .fsz { margin-left: auto; display: inline-flex; gap: 4px; }
.pop.menu { width: 248px; }
.pop.menu .mi { min-height: 36px; }
.pop .mic { display: inline-flex; justify-content: center; width: 18px; color: var(--aimbase-muted); flex-shrink: 0; }
.pop .ml { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pop .mh { color: var(--aimbase-muted); font-size: .88em; white-space: nowrap; }
.pop .sep { height: 1px; margin: 4px 6px; background: var(--aimbase-border); }

/* 첨부 */
.attachments { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 10px 0; }
.attachments:empty { display: none; }
.chip {
  display: inline-flex; align-items: center; gap: 6px; padding: 4px 8px 4px 4px;
  background: var(--aimbase-assistant-bg); border: 1px solid var(--aimbase-border); border-radius: 8px;
  max-width: 220px; font-size: 12px;
}
.chip.uploading { opacity: .75; }
.chip.error { border-color: var(--aimbase-danger); color: var(--aimbase-danger); }
.chip-thumb { width: 28px; height: 28px; border-radius: 4px; object-fit: cover; background: var(--aimbase-border); flex-shrink: 0; }
.chip-thumb.pdf {
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--aimbase-muted); font-weight: 700; font-size: 10px;
  background: var(--aimbase-assistant-bg); border: 1px solid var(--aimbase-border);
}
.chip-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
.chip-size { color: var(--aimbase-muted); flex-shrink: 0; font-variant-numeric: tabular-nums; }
.u .bub .q-att { display: flex; align-items: center; gap: 4px; font-size: 12px; opacity: .8; white-space: nowrap; min-width: 0; }
.u .bub .q-text + .q-att { margin-top: 4px; }
.q-att-icon { display: inline-flex; flex-shrink: 0; }
.q-att-name { overflow: hidden; text-overflow: ellipsis; }
.chip-remove { background: none; border: none; cursor: pointer; color: var(--aimbase-muted); font-size: 14px; padding: 0 2px; line-height: 1; }
.chip-remove:hover { color: var(--aimbase-danger); }
.chip-spinner {
  width: 10px; height: 10px; border: 2px solid var(--aimbase-border); border-top-color: var(--aimbase-primary);
  border-radius: 50%; animation: chip-spin .8s linear infinite; flex-shrink: 0;
}
@keyframes chip-spin { to { transform: rotate(360deg); } }
.drop-overlay {
  position: absolute; inset: 0; border: 2px dashed var(--aimbase-border-strong);
  background: rgba(127,127,127,.08); border-radius: var(--aimbase-radius);
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; color: var(--aimbase-fg); font-weight: 500; pointer-events: none; z-index: 10;
}

/* 마이크와 녹음 */
.ib.rec-on { background: var(--aimbase-danger); color: #fff; }
.recording-overlay {
  position: absolute; left: 12px; right: 12px; bottom: 14px;
  background: var(--aimbase-bg); border: 1px solid var(--aimbase-border-strong); border-radius: 14px;
  padding: 10px 12px; display: flex; align-items: center; gap: 10px; z-index: 11; box-shadow: 0 4px 12px rgba(0,0,0,.08);
}
.recording-overlay.hidden { display: none; }
.rec-wave { flex: 1; font-family: monospace; color: var(--aimbase-danger); font-size: 14px; letter-spacing: 1px; animation: rec-pulse .9s ease-in-out infinite; }
.rec-timer { font-variant-numeric: tabular-nums; color: var(--aimbase-fg); font-size: 13px; font-weight: 600; }
@keyframes rec-pulse { 0%, 100% { opacity: .55; } 50% { opacity: 1; } }

.toast {
  position: absolute; left: 50%; bottom: 96px; transform: translateX(-50%) translateY(8px);
  background: #18181b; color: #fff; font-size: 12px; padding: 7px 12px; border-radius: 8px;
  opacity: 0; transition: all .18s; pointer-events: none; z-index: 50; white-space: nowrap;
}
.toast.on { opacity: 1; transform: translateX(-50%) translateY(0); }
/* ─────────────────────────────────────────────
   CR-131: 리치 렌더링 (마크다운/표/코드/mermaid)
   답 본문(.md.rich) 안에서만 적용.
   ───────────────────────────────────────────── */
.md.rich { white-space: normal; }
.rich > *:first-child { margin-top: 0; }
.rich > *:last-child { margin-bottom: 0; }

.rich p { margin: 0 0 8px; line-height: 1.6; }
.rich h1, .rich h2, .rich h3, .rich h4, .rich h5, .rich h6 {
  margin: 14px 0 6px; line-height: 1.3; font-weight: 700;
}
.rich h1 { font-size: 1.35em; border-bottom: 1px solid var(--aimbase-border); padding-bottom: 4px; }
.rich h2 { font-size: 1.2em; border-bottom: 1px solid var(--aimbase-border); padding-bottom: 3px; }
.rich h3 { font-size: 1.1em; }
.rich h4, .rich h5, .rich h6 { font-size: 1em; }

.rich ul, .rich ol { margin: 0 0 8px; padding-left: 1.4em; }
.rich li { margin: 2px 0; line-height: 1.55; }
.rich li > ul, .rich li > ol { margin: 2px 0; }

.rich blockquote {
  margin: 0 0 8px; padding: 4px 12px;
  border-left: 3px solid var(--aimbase-border-strong);
  color: var(--aimbase-muted); background: rgba(127,127,127,.06);
  border-radius: 0 6px 6px 0;
}
.rich a { color: var(--aimbase-primary); text-decoration: underline; text-underline-offset: 2px; }
.rich a:hover { text-decoration: none; }
.rich hr { border: none; border-top: 1px solid var(--aimbase-border); margin: 12px 0; }
.rich img { max-width: 100%; border-radius: 6px; }

/* 인라인 코드 */
.rich :not(pre) > code {
  background: rgba(127,127,127,.14);
  border-radius: 4px; padding: 1px 5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: .88em; word-break: break-word;
}

/* 코드블록 */
.rich pre.rich-code {
  position: relative; margin: 0 0 10px;
  background: #0d1117; color: #c9d1d9;
  border: 1px solid var(--aimbase-border);
  border-radius: 8px; padding: 12px 12px 10px;
  overflow-x: auto; font-size: 12.5px; line-height: 1.5;
}
.rich pre.rich-code code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: none; padding: 0; white-space: pre;
}
.rich-code-copy {
  position: absolute; top: 6px; right: 6px;
  background: rgba(255,255,255,.08); color: #c9d1d9;
  border: 1px solid rgba(255,255,255,.15);
  border-radius: 5px; padding: 2px 8px; font-size: 11px; cursor: pointer;
  opacity: 0; transition: opacity 120ms ease;
}
.rich pre.rich-code:hover .rich-code-copy { opacity: 1; }
.rich-code-copy:hover { background: rgba(255,255,255,.18); }

/* highlight.js — Shadow DOM 격리라 테마 CSS 를 인라인(github/github-dark 요약본) */
.rich .hljs-comment, .rich .hljs-quote { color: #8b949e; font-style: italic; }
.rich .hljs-keyword, .rich .hljs-selector-tag, .rich .hljs-built_in,
.rich .hljs-name, .rich .hljs-tag { color: #ff7b72; }
.rich .hljs-string, .rich .hljs-title, .rich .hljs-section,
.rich .hljs-attribute, .rich .hljs-literal, .rich .hljs-template-tag,
.rich .hljs-template-variable, .rich .hljs-type, .rich .hljs-addition { color: #a5d6ff; }
.rich .hljs-number, .rich .hljs-symbol, .rich .hljs-bullet,
.rich .hljs-link, .rich .hljs-meta, .rich .hljs-selector-id,
.rich .hljs-selector-class { color: #79c0ff; }
.rich .hljs-function .hljs-title, .rich .hljs-title.function_ { color: #d2a8ff; }
.rich .hljs-attr, .rich .hljs-variable, .rich .hljs-property { color: #ffa657; }
.rich .hljs-emphasis { font-style: italic; }
.rich .hljs-strong { font-weight: 700; }
.rich .hljs-deletion { color: #ffdcd7; background: #67060c; }

/* 표 + 다운로드 툴바 */
.rich-table-wrap { margin: 0 0 12px; }
.rich-table-toolbar {
  display: flex; gap: 6px; margin-bottom: 4px;
}
.rich-tbl-btn {
  background: transparent; color: var(--aimbase-muted);
  border: 1px solid var(--aimbase-border); border-radius: 6px;
  padding: 3px 10px; font-size: 11px; cursor: pointer;
  font-family: inherit; line-height: 1.4;
}
.rich-tbl-btn:hover { color: var(--aimbase-fg); background: var(--aimbase-chip-bg); }
.rich-tbl-btn:disabled { opacity: .55; cursor: default; }
.rich-table-scroll { overflow-x: auto; border-radius: 8px; }
.rich table {
  border-collapse: collapse; width: 100%; font-size: 12.5px;
  border: 1px solid var(--aimbase-border);
}
.rich th, .rich td {
  border: 1px solid var(--aimbase-border);
  padding: 6px 10px; text-align: left; vertical-align: top;
  white-space: nowrap;
}
.rich thead th {
  background: var(--aimbase-assistant-bg); font-weight: 700;
  position: sticky; top: 0;
}
.rich tbody tr:nth-child(even) { background: rgba(127,127,127,.05); }

/* mermaid */
.rich-mermaid {
  margin: 0 0 12px; padding: 10px;
  background: var(--aimbase-bg); border: 1px solid var(--aimbase-border);
  border-radius: 8px; overflow-x: auto; text-align: center;
}
.rich-mermaid svg { max-width: 100%; height: auto; }
.rich-mermaid-error { border-color: var(--aimbase-danger); }

`;
