/** Shadow DOM 내부 스타일. CSS 변수로 테마 커스터마이징 가능. */
export const WIDGET_CSS = `
:host {
  --aimbase-primary: #4f46e5;
  --aimbase-primary-hover: #4338ca;
  --aimbase-bg: #ffffff;
  --aimbase-fg: #111827;
  --aimbase-muted: #6b7280;
  --aimbase-border: #e5e7eb;
  --aimbase-user-bg: #eef2ff;
  --aimbase-assistant-bg: #f9fafb;
  --aimbase-citation-bg: #fef3c7;
  --aimbase-radius: 10px;
  --aimbase-font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif;
  font-family: var(--aimbase-font);
  color: var(--aimbase-fg);
  font-size: 14px;
}
:host([data-theme="dark"]) {
  --aimbase-bg: #1f2937;
  --aimbase-fg: #f3f4f6;
  --aimbase-muted: #9ca3af;
  --aimbase-border: #374151;
  --aimbase-user-bg: #312e81;
  --aimbase-assistant-bg: #111827;
}

.root { box-sizing: border-box; }
*, *::before, *::after { box-sizing: border-box; }

/* 버블 모드 */
.bubble-btn {
  position: fixed; bottom: 20px; right: 20px;
  width: 56px; height: 56px; border-radius: 50%;
  background: var(--aimbase-primary); color: white;
  border: none; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.15);
  font-size: 24px; z-index: 9998;
}
.bubble-btn:hover { background: var(--aimbase-primary-hover); }

.panel {
  background: var(--aimbase-bg);
  border: 1px solid var(--aimbase-border);
  border-radius: var(--aimbase-radius);
  display: flex; flex-direction: column;
  overflow: hidden;
}
.panel.floating {
  position: fixed; bottom: 90px; right: 20px;
  width: 380px; height: 560px; max-height: 80vh;
  box-shadow: 0 10px 30px rgba(0,0,0,.18);
  z-index: 9999;
}
.panel.inline { width: 100%; height: 100%; min-height: 400px; }
.panel.side { position: fixed; top: 0; right: 0; bottom: 0; width: 400px; max-width: 100vw; z-index: 9999; }
.panel.hidden { display: none; }

.header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px; border-bottom: 1px solid var(--aimbase-border);
  font-weight: 600;
}
.header .close { background: none; border: none; font-size: 20px; cursor: pointer; color: var(--aimbase-muted); }

.messages { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 10px; }

.msg {
  padding: 10px 12px; border-radius: 8px; max-width: 85%;
  white-space: pre-wrap; word-break: break-word; line-height: 1.5;
}
.msg.user { align-self: flex-end; background: var(--aimbase-user-bg); }
.msg.assistant { align-self: flex-start; background: var(--aimbase-assistant-bg); border: 1px solid var(--aimbase-border); }
.msg.thinking { align-self: flex-start; background: transparent; color: var(--aimbase-muted); font-style: italic; font-size: 12px; }
.msg.tool { align-self: flex-start; background: transparent; color: var(--aimbase-muted); font-size: 12px; border-left: 3px solid var(--aimbase-primary); padding-left: 8px; }
.msg.error { align-self: center; background: #fee2e2; color: #991b1b; font-size: 12px; }

.citations {
  align-self: flex-start; max-width: 100%;
  display: flex; flex-wrap: wrap; gap: 6px;
  margin-top: -4px;
}
.citation {
  background: var(--aimbase-citation-bg);
  border: 1px solid #f59e0b;
  color: #78350f;
  padding: 4px 10px; border-radius: 12px;
  font-size: 12px; cursor: pointer;
}
.citation:hover { background: #fde68a; }

.workflow-panel {
  margin: 8px 12px; padding: 10px;
  border: 1px solid var(--aimbase-border);
  border-radius: 8px; font-size: 12px;
  background: var(--aimbase-assistant-bg);
}
.workflow-step {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 0;
}
.workflow-step .dot { width: 8px; height: 8px; border-radius: 50%; }
.workflow-step .dot.running { background: #3b82f6; animation: pulse 1.2s infinite; }
.workflow-step .dot.completed { background: #10b981; }
.workflow-step .dot.failed { background: #ef4444; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: .5; } }

.composer {
  display: flex; gap: 8px; padding: 10px;
  border-top: 1px solid var(--aimbase-border);
}
.composer textarea {
  flex: 1; resize: none; padding: 8px 10px;
  border: 1px solid var(--aimbase-border);
  border-radius: 6px; font-family: inherit; font-size: 13px;
  background: var(--aimbase-bg); color: var(--aimbase-fg);
  min-height: 36px; max-height: 120px;
}
.composer button {
  background: var(--aimbase-primary); color: white; border: none;
  border-radius: 6px; padding: 0 14px; cursor: pointer; font-size: 13px;
}
.composer button:disabled { opacity: .5; cursor: not-allowed; }

.citation-preview {
  position: absolute; right: 0; top: 0; bottom: 0; width: 60%;
  background: var(--aimbase-bg);
  border-left: 1px solid var(--aimbase-border);
  padding: 16px; overflow-y: auto;
  box-shadow: -4px 0 12px rgba(0,0,0,.1);
}
.citation-preview h4 { margin: 0 0 8px; font-size: 14px; }
.citation-preview .meta { font-size: 12px; color: var(--aimbase-muted); margin-bottom: 10px; }
.citation-preview .content { font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.citation-preview .close { position: absolute; top: 8px; right: 8px; background: none; border: none; font-size: 18px; cursor: pointer; }

/* CR-061: 첨부 UI */
.attach-btn {
  background: transparent; color: var(--aimbase-muted);
  border: 1px solid var(--aimbase-border); border-radius: 6px;
  width: 34px; height: 34px; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer; font-size: 16px; line-height: 1; flex-shrink: 0;
}
.attach-btn:hover { color: var(--aimbase-primary); border-color: var(--aimbase-primary); }
.attach-btn:disabled { opacity: .5; cursor: not-allowed; }

.attachments {
  display: flex; flex-wrap: wrap; gap: 6px;
  padding: 6px 10px 0;
}
.attachments:empty { padding: 0; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 8px 4px 4px;
  background: var(--aimbase-assistant-bg);
  border: 1px solid var(--aimbase-border);
  border-radius: 6px;
  max-width: 220px;
  font-size: 12px;
}
.chip.uploading { opacity: .75; }
.chip.error { border-color: #ef4444; color: #ef4444; }
.chip-thumb {
  width: 28px; height: 28px; border-radius: 4px;
  object-fit: cover;
  background: var(--aimbase-border);
  flex-shrink: 0;
}
.chip-thumb.pdf {
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--aimbase-primary); font-weight: 700; font-size: 10px;
  background: var(--aimbase-assistant-bg);
  border: 1px solid var(--aimbase-border);
}
.chip-label {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  flex: 1; min-width: 0;
}
.chip-remove {
  background: none; border: none; cursor: pointer;
  color: var(--aimbase-muted); font-size: 14px; padding: 0 2px; line-height: 1;
}
.chip-remove:hover { color: #ef4444; }
.chip-spinner {
  width: 10px; height: 10px; border: 2px solid var(--aimbase-border);
  border-top-color: var(--aimbase-primary); border-radius: 50%;
  animation: chip-spin 0.8s linear infinite; flex-shrink: 0;
}
@keyframes chip-spin { to { transform: rotate(360deg); } }

.drop-overlay {
  position: absolute; inset: 0;
  border: 2px dashed var(--aimbase-primary);
  background: rgba(79, 70, 229, 0.08);
  border-radius: var(--aimbase-radius);
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; color: var(--aimbase-primary); font-weight: 500;
  pointer-events: none; z-index: 10;
}

/* CR-060: 마이크 버튼 & 녹음 오버레이 */
.mic-btn {
  background: none; border: 1px solid var(--aimbase-border);
  border-radius: 999px; width: 32px; height: 32px; cursor: pointer;
  font-size: 14px; line-height: 1;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0; color: var(--aimbase-muted);
  transition: background 120ms ease, color 120ms ease, border-color 120ms ease;
}
.mic-btn:hover { color: var(--aimbase-primary); border-color: var(--aimbase-primary); }
.mic-btn[aria-pressed="true"] {
  background: #ef4444; color: #fff; border-color: #ef4444;
}
.mic-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.recording-overlay {
  position: absolute; left: 12px; right: 12px; bottom: 64px;
  background: var(--aimbase-surface); border: 1px solid #ef4444;
  border-radius: var(--aimbase-radius);
  padding: 10px 12px; display: flex; align-items: center; gap: 10px;
  z-index: 11; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}
.recording-overlay.hidden { display: none; }
.rec-wave {
  flex: 1; font-family: monospace; color: #ef4444; font-size: 14px;
  letter-spacing: 1px;
  animation: rec-pulse 0.9s ease-in-out infinite;
}
.rec-timer {
  font-variant-numeric: tabular-nums; color: var(--aimbase-text);
  font-size: 13px; font-weight: 600;
}
.rec-cancel, .rec-stop {
  background: none; border: 1px solid var(--aimbase-border);
  border-radius: var(--aimbase-radius); padding: 4px 10px;
  font-size: 12px; cursor: pointer; color: var(--aimbase-text);
}
.rec-cancel:hover { color: var(--aimbase-muted); }
.rec-stop {
  border-color: #ef4444; color: #ef4444; font-weight: 600;
}
.rec-stop:hover { background: #ef4444; color: #fff; }
@keyframes rec-pulse {
  0%, 100% { opacity: 0.55; }
  50%      { opacity: 1; }
}
`;
