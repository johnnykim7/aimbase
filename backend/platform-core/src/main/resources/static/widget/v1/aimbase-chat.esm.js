// src/attachment-client.ts
var AttachmentClient = class {
  constructor(tokens) {
    this.tokens = tokens;
  }
  async upload(args, onProgress) {
    const token = await this.tokens.getToken();
    const form = new FormData();
    form.append("session_id", args.sessionId);
    form.append("file", args.file, args.file.name);
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", `${args.baseUrl}/api/v1/chat/attachments`);
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
      if (onProgress && xhr.upload) {
        xhr.upload.addEventListener("progress", (e) => {
          if (e.lengthComputable) {
            const pct = Math.round(e.loaded / e.total * 100);
            onProgress(pct);
          }
        });
      }
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const body = JSON.parse(xhr.responseText);
            const data = body.data ?? body;
            resolve(data);
          } catch (e) {
            reject(new Error(`invalid response JSON: ${e.message}`));
          }
        } else {
          const msg = extractErrorMessage(xhr.responseText) ?? `status ${xhr.status}`;
          reject(new Error(msg));
        }
      };
      xhr.onerror = () => reject(new Error("network error"));
      xhr.send(form);
    });
  }
  async delete(args) {
    const token = await this.tokens.getToken();
    const url = `${args.baseUrl}/api/v1/chat/attachments/${encodeURIComponent(args.attachmentId)}?session_id=${encodeURIComponent(args.sessionId)}`;
    const res = await fetch(url, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!res.ok && res.status !== 404) {
      const text = await res.text().catch(() => "");
      throw new Error(`delete attachment ${res.status}: ${text.slice(0, 200)}`);
    }
  }
};
function extractErrorMessage(raw) {
  if (!raw) return null;
  try {
    const body = JSON.parse(raw);
    if (typeof body.error === "string") return body.error;
    if (typeof body.message === "string") return body.message;
    return null;
  } catch {
    return raw.slice(0, 200);
  }
}

// src/sse-parser.ts
async function* parseSseStream(response, signal) {
  if (!response.body) throw new Error("SSE response has no body");
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let currentEvent = "message";
  try {
    while (true) {
      if (signal?.aborted) break;
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let boundary;
      while ((boundary = buffer.indexOf("\n\n")) !== -1) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        let dataLine = "";
        for (const raw of block.split("\n")) {
          if (raw.startsWith("event:")) {
            currentEvent = raw.slice(6).trim() || "message";
          } else if (raw.startsWith("data:")) {
            dataLine += raw.slice(5).replace(/^ /, "");
          } else if (raw.startsWith(":")) {
          }
        }
        if (dataLine) {
          yield { name: currentEvent, data: dataLine };
          currentEvent = "message";
        }
      }
    }
  } finally {
    try {
      reader.releaseLock();
    } catch {
    }
  }
}

// src/chat-client.ts
var ChatClient = class {
  constructor(tokens) {
    this.tokens = tokens;
    this.currentAbort = null;
  }
  /**
   * chat stream — fetch 기반 SSE 파싱. Authorization 헤더로 토큰 전송.
   * 이벤트가 나올 때마다 onDelta 콜백 호출.
   */
  async sendMessage(args, onDelta) {
    const prev = this.currentAbort;
    const ac = new AbortController();
    this.currentAbort = ac;
    prev?.abort();
    const token = await this.tokens.getToken();
    const userContent = [];
    for (const att of args.attachments ?? []) {
      userContent.push({
        type: att.mediaType === "application/pdf" ? "document" : "image",
        attachment_id: att.attachmentId
      });
    }
    if (args.text && args.text.length > 0) {
      userContent.push({ type: "text", text: args.text });
    }
    const messages = [];
    if (args.context && Object.keys(args.context).length > 0) {
      messages.push({
        role: "system",
        content: [
          {
            type: "text",
            text: `# \uD604\uC7AC \uD654\uBA74 \uCEE8\uD14D\uC2A4\uD2B8
${JSON.stringify(args.context, null, 2)}`
          }
        ]
      });
    }
    messages.push({ role: "user", content: userContent });
    const body = {
      model: args.model ?? "auto",
      session_id: args.sessionId,
      stream: true,
      messages
    };
    if (args.ragSourceId) body.rag_source_id = args.ragSourceId;
    if (args.connectionId) body.connection_id = args.connectionId;
    if (args.actionsEnabled) body.actions_enabled = true;
    let res;
    try {
      res = await fetch(`${args.baseUrl}/api/v1/chat/completions`, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${token}`,
          "Content-Type": "application/json",
          "Accept": "text/event-stream"
        },
        body: JSON.stringify(body),
        signal: ac.signal
      });
    } catch (e) {
      if (e?.name === "AbortError" || ac.signal.aborted) return;
      throw e;
    }
    if (!res.ok) {
      const errText = await res.text().catch(() => "");
      throw new Error(`chat/completions ${res.status}: ${errText.slice(0, 200)}`);
    }
    try {
      for await (const ev of parseSseStream(res, ac.signal)) {
        try {
          const payload = JSON.parse(ev.data);
          switch (ev.name) {
            case "delta":
              onDelta({ type: "delta", text: payload.delta ?? "" });
              break;
            case "thinking":
              onDelta({ type: "thinking", text: payload.delta ?? "" });
              break;
            case "tool_use_start":
              onDelta({
                type: "tool_use_start",
                tool: { id: payload.id, name: payload.name, input: payload.input }
              });
              break;
            case "tool_result":
              onDelta({
                type: "tool_result",
                toolResult: {
                  tool_use_id: payload.tool_use_id,
                  output: payload.output,
                  is_error: !!payload.is_error
                }
              });
              break;
            case "done":
              onDelta({
                type: "done",
                done: {
                  rag_used: !!payload.rag_used,
                  citations: Array.isArray(payload.citations) ? payload.citations : []
                }
              });
              break;
            default:
              break;
          }
        } catch (e) {
          onDelta({ type: "error", error: `parse error: ${e.message}` });
        }
      }
    } catch (e) {
      if (e?.name === "AbortError" || ac.signal.aborted) return;
      throw e;
    }
  }
  async abort(baseUrl, sessionId) {
    this.currentAbort?.abort();
    this.currentAbort = null;
    const token = await this.tokens.getToken();
    try {
      await fetch(`${baseUrl}/api/v1/chat/${encodeURIComponent(sessionId)}/abort`, {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}` }
      });
    } catch {
    }
  }
};

// src/stt-client.ts
var SttClient = class {
  constructor(tokens) {
    this.tokens = tokens;
  }
  async transcribe(args, onProgress) {
    const token = await this.tokens.getToken();
    const ext = mimeToExt(args.blob.type);
    const filename = args.filename ?? `rec.${ext}`;
    const form = new FormData();
    form.append("session_id", args.sessionId);
    form.append("file", args.blob, filename);
    if (args.language) form.append("language", args.language);
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", `${args.baseUrl}/api/v1/chat/stt`);
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
      if (onProgress && xhr.upload) {
        xhr.upload.addEventListener("progress", (e) => {
          if (e.lengthComputable) onProgress(Math.round(e.loaded / e.total * 100));
        });
      }
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const body = JSON.parse(xhr.responseText);
            resolve(body.data ?? body);
          } catch (e) {
            reject(new SttError("STT_INVALID_RESPONSE", `invalid JSON: ${e.message}`, xhr.status));
          }
        } else {
          const { code, message } = parseErrorBody(xhr.responseText, xhr.status);
          reject(new SttError(code, message, xhr.status));
        }
      };
      xhr.onerror = () => reject(new SttError("STT_NETWORK", "network error", 0));
      xhr.onabort = () => reject(new SttError("STT_ABORTED", "aborted", 0));
      xhr.send(form);
    });
  }
};
var SttError = class extends Error {
  constructor(code, message, status) {
    super(message);
    this.code = code;
    this.status = status;
    this.name = "SttError";
  }
};
function parseErrorBody(raw, status) {
  if (!raw) return { code: "STT_UNKNOWN", message: `status ${status}` };
  try {
    const body = JSON.parse(raw);
    const errText = body.error ?? body.message;
    if (errText) {
      const m = errText.match(/^(STT_[A-Z_]+):\s*(.*)$/);
      if (m) return { code: m[1], message: m[2] };
      return { code: "STT_UNKNOWN", message: errText };
    }
  } catch {
  }
  return { code: "STT_UNKNOWN", message: raw.slice(0, 200) };
}
function mimeToExt(mime) {
  const m = (mime || "").toLowerCase().split(";")[0].trim();
  switch (m) {
    case "audio/webm":
      return "webm";
    case "audio/mp4":
      return "mp4";
    case "audio/mpeg":
      return "mp3";
    case "audio/wav":
      return "wav";
    case "audio/ogg":
      return "ogg";
    default:
      return "bin";
  }
}

// src/styles.ts
var WIDGET_CSS = `
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

/* \uBC84\uBE14 \uBAA8\uB4DC */
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

/* CR-061: \uCCA8\uBD80 UI */
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

/* CR-060: \uB9C8\uC774\uD06C \uBC84\uD2BC & \uB179\uC74C \uC624\uBC84\uB808\uC774 */
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

// src/token-store.ts
var TokenStore = class {
  constructor(resolver, onExpiring) {
    this.resolver = resolver;
    this.token = null;
    this.expiresAtMs = 0;
    this.refreshTimer = null;
    this.onExpiring = onExpiring;
  }
  async getToken() {
    if (!this.token || Date.now() >= this.expiresAtMs - 3e4) {
      await this.refresh();
    }
    return this.token.token;
  }
  /** 현재 토큰이 특정 scope 를 포함하는지 (지연 초기화됨 — 미초기화면 false) */
  hasScope(scope) {
    return !!this.token?.scopes?.includes(scope);
  }
  /** 토큰 메타 미리 로드 (getToken 호출로 초기화만 수행) */
  async ensureLoaded() {
    if (!this.token) await this.refresh();
  }
  async refresh() {
    const res = await this.resolver();
    this.token = res;
    this.expiresAtMs = typeof res.expires_at === "number" ? res.expires_at : new Date(res.expires_at).getTime();
    this.scheduleRefresh(res.refresh_after);
  }
  scheduleRefresh(refreshAfterSec) {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    const delayMs = Math.max(1e4, refreshAfterSec * 1e3);
    this.refreshTimer = setTimeout(() => {
      try {
        this.onExpiring?.();
      } catch {
      }
      this.refresh().catch(() => {
      });
    }, delayMs);
  }
  destroy() {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = null;
    this.token = null;
  }
};

// src/workflow-client.ts
var WorkflowClient = class {
  constructor(tokens) {
    this.tokens = tokens;
  }
  async subscribe(baseUrl, runId, handlers) {
    const token = await this.tokens.getToken();
    const url = `${baseUrl}/api/v1/workflows/runs/${encodeURIComponent(runId)}/subscribe?access_token=${encodeURIComponent(token)}`;
    const es = new EventSource(url);
    es.addEventListener("workflow.snapshot", (e) => {
      try {
        handlers.onSnapshot?.(JSON.parse(e.data));
      } catch (err) {
        handlers.onError?.(err);
      }
    });
    es.addEventListener("workflow.step", (e) => {
      try {
        handlers.onStep?.(JSON.parse(e.data));
      } catch (err) {
        handlers.onError?.(err);
      }
    });
    es.addEventListener("workflow.approval", (e) => {
      try {
        handlers.onApproval?.(JSON.parse(e.data));
      } catch (err) {
        handlers.onError?.(err);
      }
    });
    es.addEventListener("workflow.done", (e) => {
      try {
        handlers.onDone?.(JSON.parse(e.data));
      } catch (err) {
        handlers.onError?.(err);
      } finally {
        es.close();
      }
    });
    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED) {
        handlers.onError?.(new Error("workflow SSE closed"));
      }
    };
    return () => es.close();
  }
  async fetchChunk(baseUrl, sourceId, chunkId) {
    const token = await this.tokens.getToken();
    const res = await fetch(
      `${baseUrl}/api/v1/knowledge-sources/${encodeURIComponent(sourceId)}/chunks/${encodeURIComponent(chunkId)}`,
      { headers: { "Authorization": `Bearer ${token}` } }
    );
    if (!res.ok) throw new Error(`chunks ${res.status}`);
    const json = await res.json();
    return json.data ?? {};
  }
};

// src/widget.ts
var ACCEPT_MIME = "image/png,image/jpeg,image/gif,image/webp,application/pdf";
var MAX_IMAGE_BYTES = 10 * 1024 * 1024;
var MAX_PDF_BYTES = 32 * 1024 * 1024;
function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}
function genId() {
  try {
    return self.crypto.randomUUID();
  } catch {
    return `sess-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  }
}
function createWidget(options) {
  const display = options.display ?? "bubble";
  const theme = options.theme?.mode ?? "auto";
  const host = document.createElement("div");
  host.setAttribute("data-aimbase-widget", "true");
  if (theme === "dark") host.setAttribute("data-theme", "dark");
  if (theme === "light") host.setAttribute("data-theme", "light");
  const mountTarget = typeof options.target === "string" ? document.querySelector(options.target) : options.target ?? null;
  if ((display === "inline" || display === "panel") && !mountTarget) {
    throw new Error(`display='${display}' requires options.target`);
  }
  (mountTarget ?? document.body).appendChild(host);
  const shadow = host.attachShadow({ mode: "open" });
  const style = document.createElement("style");
  style.textContent = WIDGET_CSS;
  shadow.appendChild(style);
  if (options.theme?.cssVars) {
    const overrides = Object.entries(options.theme.cssVars).map(([k, v]) => `${k}: ${v};`).join(" ");
    const overrideStyle = document.createElement("style");
    overrideStyle.textContent = `:host { ${overrides} }`;
    shadow.appendChild(overrideStyle);
  }
  const root = el("div", "root");
  shadow.appendChild(root);
  const state = {
    open: display !== "bubble",
    // bubble 은 기본 닫힘
    sessionId: options.sessionId ?? genId(),
    ragSourceId: options.ragSourceId,
    currentAssistantDiv: null,
    currentAssistantText: "",
    workflowRuns: /* @__PURE__ */ new Map(),
    attachments: []
  };
  let bubbleBtn = null;
  if (display === "bubble") {
    bubbleBtn = el("button", "bubble-btn", "\u{1F4AC}");
    bubbleBtn.setAttribute("aria-label", "Open Aimbase chat");
    bubbleBtn.addEventListener("click", () => setOpen(true));
    root.appendChild(bubbleBtn);
  }
  const panel = el("div", `panel ${displayClass(display)} ${state.open ? "" : "hidden"}`);
  root.appendChild(panel);
  const header = el("div", "header");
  header.appendChild(el("div", "title", "Aimbase Chat"));
  if (display === "bubble" || display === "panel") {
    const closeBtn = el("button", "close", "\xD7");
    closeBtn.addEventListener("click", () => setOpen(false));
    header.appendChild(closeBtn);
  }
  panel.appendChild(header);
  const messagesEl = el("div", "messages");
  panel.appendChild(messagesEl);
  const attachmentsEl = el("div", "attachments");
  panel.appendChild(attachmentsEl);
  const composer = el("div", "composer");
  const attachBtn = el("button", "attach-btn", "\u{1F4CE}");
  attachBtn.setAttribute("aria-label", "\uD30C\uC77C \uCCA8\uBD80 (\uC774\uBBF8\uC9C0/PDF)");
  attachBtn.type = "button";
  const fileInput = document.createElement("input");
  fileInput.type = "file";
  fileInput.accept = ACCEPT_MIME;
  fileInput.multiple = true;
  fileInput.style.display = "none";
  attachBtn.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    const files = fileInput.files ? Array.from(fileInput.files) : [];
    for (const f of files) void addAttachment(f);
    fileInput.value = "";
  });
  const textarea = document.createElement("textarea");
  textarea.placeholder = "\uBA54\uC2DC\uC9C0\uB97C \uC785\uB825\uD558\uC138\uC694\u2026 (Enter \uC804\uC1A1, Shift+Enter \uC904\uBC14\uAFC8)";
  textarea.rows = 1;
  const micBtn = el("button", "mic-btn", "\u{1F3A4}");
  micBtn.type = "button";
  micBtn.setAttribute("aria-label", "\uC74C\uC131 \uC785\uB825 \uC2DC\uC791");
  micBtn.setAttribute("aria-pressed", "false");
  micBtn.hidden = true;
  const sendBtn = el("button", "send-btn", "\uC804\uC1A1");
  composer.appendChild(attachBtn);
  composer.appendChild(fileInput);
  composer.appendChild(textarea);
  composer.appendChild(micBtn);
  composer.appendChild(sendBtn);
  panel.appendChild(composer);
  const recordingOverlay = el("div", "recording-overlay hidden");
  recordingOverlay.setAttribute("role", "status");
  recordingOverlay.setAttribute("aria-live", "polite");
  const wave = el("span", "rec-wave", "\u2581\u2583\u2585\u2587\u2585\u2583\u2581");
  const timer = el("span", "rec-timer", "0:00");
  const cancelRec = el("button", "rec-cancel", "\uCDE8\uC18C");
  cancelRec.type = "button";
  cancelRec.setAttribute("aria-label", "\uB179\uC74C \uCDE8\uC18C");
  const stopRec = el("button", "rec-stop", "\u25A0 \uC815\uC9C0");
  stopRec.type = "button";
  stopRec.setAttribute("aria-label", "\uB179\uC74C \uC815\uC9C0 \uD6C4 \uBCC0\uD658");
  recordingOverlay.appendChild(wave);
  recordingOverlay.appendChild(timer);
  recordingOverlay.appendChild(cancelRec);
  recordingOverlay.appendChild(stopRec);
  panel.appendChild(recordingOverlay);
  let dragCounter = 0;
  let dropOverlay = null;
  panel.addEventListener("dragenter", (e) => {
    if (!hasFile(e)) return;
    e.preventDefault();
    dragCounter += 1;
    if (!dropOverlay) {
      dropOverlay = el("div", "drop-overlay", "\uD30C\uC77C\uC744 \uB193\uC544 \uCCA8\uBD80\uD558\uC138\uC694");
      panel.appendChild(dropOverlay);
    }
  });
  panel.addEventListener("dragover", (e) => {
    if (hasFile(e)) e.preventDefault();
  });
  panel.addEventListener("dragleave", (e) => {
    if (!hasFile(e)) return;
    dragCounter -= 1;
    if (dragCounter <= 0) {
      dragCounter = 0;
      dropOverlay?.remove();
      dropOverlay = null;
    }
  });
  panel.addEventListener("drop", (e) => {
    if (!hasFile(e)) return;
    e.preventDefault();
    dragCounter = 0;
    dropOverlay?.remove();
    dropOverlay = null;
    const files = e.dataTransfer ? Array.from(e.dataTransfer.files) : [];
    for (const f of files) void addAttachment(f);
  });
  function hasFile(e) {
    return !!e.dataTransfer && Array.from(e.dataTransfer.types ?? []).includes("Files");
  }
  function setOpen(v) {
    state.open = v;
    if (v) panel.classList.remove("hidden");
    else panel.classList.add("hidden");
  }
  function appendMessage(kind, text) {
    const div = el("div", `msg ${kind}`, text);
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return div;
  }
  function renderCitations(citations) {
    if (!citations?.length) return;
    const wrap = el("div", "citations");
    citations.forEach((c, idx) => {
      const badge = el("button", "citation", `[${c.index ?? idx + 1}] ${c.document_name ?? c.source_id}`);
      badge.title = c.content_preview;
      badge.addEventListener("click", () => openCitationPreview(c));
      wrap.appendChild(badge);
    });
    messagesEl.appendChild(wrap);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }
  async function openCitationPreview(c) {
    const existing = panel.querySelector(".citation-preview");
    if (existing) existing.remove();
    const preview = el("div", "citation-preview");
    const closeBtn = el("button", "close", "\xD7");
    closeBtn.addEventListener("click", () => preview.remove());
    preview.appendChild(closeBtn);
    preview.appendChild(el("h4", "", c.document_name ?? c.source_id));
    preview.appendChild(
      el(
        "div",
        "meta",
        `score ${c.score.toFixed(2)}${c.page_number ? ` \xB7 page ${c.page_number}` : ""}`
      )
    );
    const contentDiv = el("div", "content", c.content_preview);
    preview.appendChild(contentDiv);
    panel.appendChild(preview);
    if (c.chunk_id) {
      try {
        const detail = await workflow.fetchChunk(options.baseUrl, c.source_id, c.chunk_id);
        if (typeof detail.content === "string") {
          contentDiv.textContent = detail.content;
        }
      } catch {
      }
    }
  }
  function ensureAssistantBlock() {
    if (!state.currentAssistantDiv) {
      state.currentAssistantDiv = appendMessage("assistant", "");
      state.currentAssistantText = "";
    }
    return state.currentAssistantDiv;
  }
  function resetAssistantBlock() {
    state.currentAssistantDiv = null;
    state.currentAssistantText = "";
  }
  const tokens = new TokenStore(options.authResolver, options.on?.onTokenExpiring);
  const chat = new ChatClient(tokens);
  const workflow = new WorkflowClient(tokens);
  const attachments = new AttachmentClient(tokens);
  function genLocalId() {
    return `att-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  }
  function validateFile(file) {
    const isImage = file.type.startsWith("image/");
    const isPdf = file.type === "application/pdf";
    if (!isImage && !isPdf) return "\uC774\uBBF8\uC9C0(PNG/JPEG/GIF/WEBP) \uB610\uB294 PDF \uB9CC \uCCA8\uBD80 \uAC00\uB2A5\uD569\uB2C8\uB2E4";
    const limit = isPdf ? MAX_PDF_BYTES : MAX_IMAGE_BYTES;
    if (file.size > limit) {
      const mb = Math.round(limit / 1024 / 1024);
      return `\uD30C\uC77C\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4 (\uCD5C\uB300 ${mb}MB)`;
    }
    return null;
  }
  function renderChips() {
    attachmentsEl.innerHTML = "";
    for (const draft of state.attachments) {
      const chip = el("div", `chip ${draft.status}`);
      if (draft.previewDataUrl) {
        const img = document.createElement("img");
        img.className = "chip-thumb";
        img.src = draft.previewDataUrl;
        img.alt = draft.file.name;
        chip.appendChild(img);
      } else {
        const icon = el("span", "chip-thumb pdf", "PDF");
        chip.appendChild(icon);
      }
      const label = el("span", "chip-label");
      const pages = draft.serverMeta?.pages;
      const pageSuffix = pages ? ` \xB7 ${pages}p` : "";
      label.textContent = draft.status === "error" ? `${draft.file.name} \u2014 ${draft.error ?? "\uC2E4\uD328"}` : `${draft.file.name}${pageSuffix}`;
      label.title = label.textContent ?? "";
      chip.appendChild(label);
      if (draft.status === "uploading") {
        chip.appendChild(el("span", "chip-spinner"));
      } else {
        const rm = el("button", "chip-remove", "\xD7");
        rm.type = "button";
        rm.setAttribute("aria-label", "\uCCA8\uBD80 \uC81C\uAC70");
        rm.addEventListener("click", () => void removeAttachment(draft.localId));
        chip.appendChild(rm);
      }
      attachmentsEl.appendChild(chip);
    }
    updateSendDisabled();
  }
  async function readThumb(file) {
    if (!file.type.startsWith("image/")) return void 0;
    return new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : void 0);
      reader.onerror = () => resolve(void 0);
      reader.readAsDataURL(file);
    });
  }
  async function addAttachment(file) {
    const err = validateFile(file);
    if (err) {
      options.on?.onError?.(new Error(err));
      appendMessage("error", err);
      return;
    }
    const draft = {
      localId: genLocalId(),
      file,
      status: "uploading",
      previewDataUrl: await readThumb(file)
    };
    state.attachments.push(draft);
    renderChips();
    try {
      const meta = await attachments.upload({
        baseUrl: options.baseUrl,
        sessionId: state.sessionId,
        file
      });
      draft.serverMeta = meta;
      draft.status = "ready";
    } catch (e) {
      draft.status = "error";
      draft.error = e.message;
      options.on?.onError?.(e);
    } finally {
      renderChips();
    }
  }
  async function removeAttachment(localId) {
    const idx = state.attachments.findIndex((a) => a.localId === localId);
    if (idx < 0) return;
    const draft = state.attachments[idx];
    state.attachments.splice(idx, 1);
    renderChips();
    if (draft.serverMeta) {
      try {
        await attachments.delete({
          baseUrl: options.baseUrl,
          sessionId: state.sessionId,
          attachmentId: draft.serverMeta.attachment_id
        });
      } catch {
      }
    }
  }
  const stt = new SttClient(tokens);
  let recorder = null;
  let recordedChunks = [];
  let micStream = null;
  let recStartedAt = 0;
  let recTickTimer = null;
  let recAutoStopTimer = null;
  let recState = "idle";
  const MAX_RECORD_MS = 6e4;
  function sttSupported() {
    return typeof window !== "undefined" && !!window.isSecureContext && !!navigator.mediaDevices?.getUserMedia && typeof MediaRecorder !== "undefined";
  }
  function pickMimeType() {
    const candidates = [
      "audio/webm;codecs=opus",
      "audio/webm",
      "audio/mp4"
    ];
    for (const m of candidates) {
      try {
        if (MediaRecorder.isTypeSupported(m)) return m;
      } catch {
      }
    }
    return void 0;
  }
  function setRecState(next) {
    recState = next;
    micBtn.setAttribute("aria-pressed", next === "recording" ? "true" : "false");
    micBtn.setAttribute(
      "aria-label",
      next === "recording" ? "\uB179\uC74C \uC815\uC9C0" : "\uC74C\uC131 \uC785\uB825 \uC2DC\uC791"
    );
    micBtn.textContent = next === "recording" ? "\u23F9" : "\u{1F3A4}";
    if (next === "recording") {
      recordingOverlay.classList.remove("hidden");
    } else {
      recordingOverlay.classList.add("hidden");
    }
  }
  function formatTimer(ms) {
    const sec = Math.floor(ms / 1e3);
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${s.toString().padStart(2, "0")}`;
  }
  function clearRecTimers() {
    if (recTickTimer) {
      clearInterval(recTickTimer);
      recTickTimer = null;
    }
    if (recAutoStopTimer) {
      clearTimeout(recAutoStopTimer);
      recAutoStopTimer = null;
    }
  }
  function releaseMic() {
    micStream?.getTracks().forEach((t) => t.stop());
    micStream = null;
  }
  async function startRecording() {
    if (recState !== "idle") return;
    if (!sttSupported()) {
      appendMessage("error", "\uC774 \uBE0C\uB77C\uC6B0\uC800\xB7\uD658\uACBD\uC5D0\uC11C\uB294 \uC74C\uC131 \uC785\uB825\uC744 \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4 (HTTPS \uD544\uC218)");
      return;
    }
    setRecState("requesting-permission");
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      setRecState("error");
      appendMessage("error", "\uB9C8\uC774\uD06C \uAD8C\uD55C\uC774 \uD544\uC694\uD569\uB2C8\uB2E4. \uBE0C\uB77C\uC6B0\uC800 \uC8FC\uC18C\uCC3D\uC5D0\uC11C \u{1F512} \uC544\uC774\uCF58\uC744 \uB20C\uB7EC \uD5C8\uC6A9\uD574\uC8FC\uC138\uC694");
      options.on?.onError?.(e);
      setRecState("idle");
      return;
    }
    const mimeType = pickMimeType();
    try {
      recorder = new MediaRecorder(micStream, mimeType ? { mimeType } : void 0);
    } catch (e) {
      releaseMic();
      setRecState("idle");
      appendMessage("error", "\uC774 \uBE0C\uB77C\uC6B0\uC800\uC758 MediaRecorder \uD3EC\uB9F7\uC774 \uC11C\uBC84\uC640 \uD638\uD658\uB418\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4");
      options.on?.onError?.(e);
      return;
    }
    recordedChunks = [];
    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) recordedChunks.push(e.data);
    };
    recorder.onstop = () => void finalizeRecording();
    recorder.onerror = (ev) => {
      options.on?.onError?.(new Error("recorder error: " + ev.type));
    };
    recorder.start();
    recStartedAt = Date.now();
    setRecState("recording");
    timer.textContent = "0:00";
    recTickTimer = setInterval(() => {
      const elapsed = Date.now() - recStartedAt;
      timer.textContent = formatTimer(elapsed);
    }, 250);
    recAutoStopTimer = setTimeout(() => {
      if (recState === "recording") stopRecordingAndSend();
    }, MAX_RECORD_MS);
  }
  function stopRecordingAndSend() {
    if (recState !== "recording") return;
    clearRecTimers();
    try {
      recorder?.stop();
    } catch {
    }
    setRecState("uploading");
  }
  function cancelRecording() {
    clearRecTimers();
    if (recorder && recorder.state !== "inactive") {
      recorder.onstop = null;
      try {
        recorder.stop();
      } catch {
      }
    }
    releaseMic();
    recorder = null;
    recordedChunks = [];
    setRecState("idle");
  }
  async function finalizeRecording() {
    const chunks = recordedChunks;
    recordedChunks = [];
    releaseMic();
    if (!chunks.length) {
      setRecState("idle");
      return;
    }
    const type = recorder?.mimeType || "audio/webm";
    recorder = null;
    const blob = new Blob(chunks, { type });
    try {
      const result = await stt.transcribe(
        { baseUrl: options.baseUrl, sessionId: state.sessionId, blob }
      );
      insertTextAtCursor(textarea, result.text);
      textarea.focus();
      updateSendDisabled();
    } catch (e) {
      const err = e instanceof SttError ? e : new Error(String(e));
      appendMessage("error", sttErrorMessage(err));
      options.on?.onError?.(err);
    } finally {
      setRecState("idle");
    }
  }
  function sttErrorMessage(err) {
    if (err instanceof SttError) {
      switch (err.code) {
        case "STT_RATE_LIMITED":
          return "\uC74C\uC131 \uC785\uB825\uC744 \uB108\uBB34 \uC790\uC8FC \uC0AC\uC6A9\uD588\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574\uC8FC\uC138\uC694";
        case "STT_FILE_TOO_LARGE":
          return "\uB179\uC74C \uD30C\uC77C\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4";
        case "STT_FILE_TOO_LONG":
          return "\uB179\uC74C \uC2DC\uAC04\uC774 \uB108\uBB34 \uAE41\uB2C8\uB2E4";
        case "STT_INVALID_MIME":
          return "\uC774 \uC624\uB514\uC624 \uD3EC\uB9F7\uC740 \uC11C\uBC84\uC5D0\uC11C \uC9C0\uC6D0\uD558\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4";
        case "STT_PROVIDER_UNAVAILABLE":
          return "\uC74C\uC131 \uC778\uC2DD \uC11C\uBE44\uC2A4\uB97C \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4 (OpenAI \uC5F0\uACB0 \uD655\uC778 \uD544\uC694)";
        case "STT_TIMEOUT":
          return "\uC74C\uC131 \uC778\uC2DD \uC751\uB2F5\uC774 \uC9C0\uC5F0\uB429\uB2C8\uB2E4. \uB2E4\uC2DC \uC2DC\uB3C4\uD574\uC8FC\uC138\uC694";
        case "STT_NETWORK":
          return "\uB124\uD2B8\uC6CC\uD06C \uC624\uB958\uB85C \uC74C\uC131\uC744 \uC804\uC1A1\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4";
      }
    }
    return "\uC74C\uC131 \uC778\uC2DD\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4: " + err.message;
  }
  function insertTextAtCursor(el2, text) {
    const start = el2.selectionStart ?? el2.value.length;
    const end = el2.selectionEnd ?? el2.value.length;
    const before = el2.value.slice(0, start);
    const after = el2.value.slice(end);
    const sep = before.length > 0 && !/\s$/.test(before) ? " " : "";
    const insert = sep + text;
    el2.value = before + insert + after;
    const caret = (before + insert).length;
    el2.setSelectionRange(caret, caret);
  }
  micBtn.addEventListener("click", () => {
    if (recState === "idle") void startRecording();
    else if (recState === "recording") stopRecordingAndSend();
  });
  stopRec.addEventListener("click", () => stopRecordingAndSend());
  cancelRec.addEventListener("click", () => cancelRecording());
  panel.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && recState === "recording") {
      e.stopPropagation();
      cancelRecording();
    }
  });
  (async () => {
    try {
      await tokens.ensureLoaded();
      if (sttSupported() && tokens.hasScope("chat:stt")) {
        micBtn.hidden = false;
      }
    } catch {
    }
  })();
  function updateSendDisabled() {
    const hasUploading = state.attachments.some((a) => a.status === "uploading");
    const hasText = textarea.value.trim().length > 0;
    const hasReady = state.attachments.some((a) => a.status === "ready");
    if (hasUploading || !hasText && !hasReady) {
      sendBtn.setAttribute("disabled", "true");
    } else {
      sendBtn.removeAttribute("disabled");
    }
  }
  async function sendMessage(text) {
    const trimmed = text.trim();
    const readyAtts = state.attachments.filter((a) => a.status === "ready" && a.serverMeta);
    if (!trimmed && readyAtts.length === 0) return;
    const userDisplay = [
      trimmed,
      ...readyAtts.map((a) => `\u{1F4CE} ${a.file.name}`)
    ].filter((s) => s.length > 0).join("\n");
    appendMessage("user", userDisplay);
    resetAssistantBlock();
    sendBtn.setAttribute("disabled", "true");
    const attachmentPayload = readyAtts.map((a) => ({
      attachmentId: a.serverMeta.attachment_id,
      mediaType: a.serverMeta.media_type
    }));
    state.attachments = [];
    renderChips();
    try {
      await chat.sendMessage(
        {
          baseUrl: options.baseUrl,
          sessionId: state.sessionId,
          text: trimmed,
          model: options.model,
          ragSourceId: state.ragSourceId,
          connectionId: options.connectionId,
          actionsEnabled: options.actionsEnabled,
          context: options.contextProvider?.(),
          attachments: attachmentPayload
        },
        (d) => {
          options.on?.onMessage?.(d);
          switch (d.type) {
            case "delta": {
              const div = ensureAssistantBlock();
              state.currentAssistantText += d.text ?? "";
              div.textContent = state.currentAssistantText;
              messagesEl.scrollTop = messagesEl.scrollHeight;
              break;
            }
            case "thinking":
              appendMessage("thinking", `\u{1F4AD} ${d.text ?? ""}`);
              break;
            case "tool_use_start":
              appendMessage("tool", `\u{1F6E0} ${d.tool?.name ?? "tool"} \uC2E4\uD589\u2026`);
              break;
            case "tool_result":
              if (d.toolResult?.is_error) {
                appendMessage("error", `\uB3C4\uAD6C \uC5D0\uB7EC: ${d.toolResult.output.slice(0, 200)}`);
              }
              break;
            case "done":
              if (d.done?.citations?.length) renderCitations(d.done.citations);
              resetAssistantBlock();
              break;
            case "error":
              appendMessage("error", d.error ?? "unknown error");
              break;
          }
        }
      );
    } catch (e) {
      const err = e;
      if (err?.name !== "AbortError") {
        appendMessage("error", err.message);
        options.on?.onError?.(err);
      }
    } finally {
      sendBtn.removeAttribute("disabled");
    }
  }
  sendBtn.addEventListener("click", () => {
    void sendMessage(textarea.value);
    textarea.value = "";
    updateSendDisabled();
  });
  textarea.addEventListener("keydown", (e) => {
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void sendMessage(textarea.value);
      textarea.value = "";
      updateSendDisabled();
    }
  });
  textarea.addEventListener("input", updateSendDisabled);
  textarea.addEventListener("paste", (e) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (const item of items) {
      if (item.kind === "file") {
        const f = item.getAsFile();
        if (f) void addAttachment(f);
      }
    }
  });
  updateSendDisabled();
  async function subscribeWorkflow(runId) {
    const wfPanel = el("div", "workflow-panel");
    const wfTitle = el("div", "title", `\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${runId.slice(0, 8)}\u2026`);
    wfPanel.appendChild(wfTitle);
    const stepsList = el("div", "steps");
    wfPanel.appendChild(stepsList);
    messagesEl.appendChild(wfPanel);
    const steps = /* @__PURE__ */ new Map();
    state.workflowRuns.set(runId, { stepEl: wfPanel, steps });
    const unsubscribe = await workflow.subscribe(options.baseUrl, runId, {
      onSnapshot: (snap) => {
        wfTitle.textContent = `\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${snap.workflow_id ?? runId.slice(0, 8)} \u2014 ${snap.status}`;
      },
      onStep: (ev) => {
        options.on?.onWorkflowStep?.(ev);
        let stepEl = steps.get(ev.step_id);
        if (!stepEl) {
          stepEl = el("div", "workflow-step");
          steps.set(ev.step_id, stepEl);
          stepsList.appendChild(stepEl);
        }
        const dotClass = ev.status === "running" ? "running" : ev.status === "completed" ? "completed" : "failed";
        stepEl.innerHTML = "";
        const dot = el("span", `dot ${dotClass}`);
        const label = el("span", "label", `${ev.step_id}${ev.duration_ms ? ` \xB7 ${ev.duration_ms}ms` : ""}`);
        stepEl.appendChild(dot);
        stepEl.appendChild(label);
        if (ev.sub_workflow_id) {
          const sub = el("span", "sub", ` \u2192 ${ev.sub_workflow_id}`);
          sub.style.color = "var(--aimbase-muted)";
          stepEl.appendChild(sub);
        }
      },
      onApproval: (ev) => {
        options.on?.onApprovalRequired?.(ev);
        const note = el("div", "workflow-step");
        note.innerHTML = `\u23F8 \uC2B9\uC778 \uB300\uAE30: <b>${ev.step_id}</b> \u2014 ${ev.reason ?? ev.policy_id}`;
        note.style.color = "#b45309";
        stepsList.appendChild(note);
      },
      onDone: (ev) => {
        wfTitle.textContent = `\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${runId.slice(0, 8)}\u2026 \u2014 ${ev.status} (${ev.duration_ms}ms)`;
        state.workflowRuns.delete(runId);
      },
      onError: (e) => {
        options.on?.onError?.(e);
      }
    });
    return unsubscribe;
  }
  return {
    open: () => setOpen(true),
    close: () => setOpen(false),
    sendMessage,
    abort: () => chat.abort(options.baseUrl, state.sessionId),
    subscribeWorkflow: (runId) => {
      let unsub = null;
      void subscribeWorkflow(runId).then((u) => unsub = u);
      return () => unsub?.();
    },
    destroy: () => {
      cancelRecording();
      tokens.destroy();
      host.remove();
    }
  };
}
function displayClass(d) {
  if (d === "bubble") return "floating";
  if (d === "panel") return "side";
  return "inline";
}

// src/web-component.ts
var AimbaseChatElement = class extends HTMLElement {
  constructor() {
    super(...arguments);
    this.handle = null;
    /** 사용자가 JS 로 직접 설정할 수 있는 프로퍼티 */
    this.authResolver = null;
    this.contextProvider = null;
    this.ragSourceId = null;
  }
  static get observedAttributes() {
    return [
      "base-url",
      "token-endpoint",
      "display",
      "theme-mode",
      "rag-source-id",
      "session-id",
      "connection-id",
      "actions-enabled",
      "model"
    ];
  }
  connectedCallback() {
    const baseUrl = this.getAttribute("base-url");
    if (!baseUrl) {
      console.error("[aimbase-chat] base-url attribute is required");
      return;
    }
    const tokenEndpoint = this.getAttribute("token-endpoint");
    const resolver = this.authResolver ?? (tokenEndpoint ? async () => {
      const res = await fetch(tokenEndpoint, { method: "POST", credentials: "include" });
      if (!res.ok) throw new Error(`token endpoint ${res.status}`);
      const data = await res.json();
      const payload = data.data ?? data;
      return payload;
    } : null);
    if (!resolver) {
      console.error("[aimbase-chat] token-endpoint attribute or authResolver property is required");
      return;
    }
    const displayAttr = this.getAttribute("display") ?? "inline";
    const themeAttr = this.getAttribute("theme-mode");
    const actionsAttr = this.getAttribute("actions-enabled");
    const options = {
      baseUrl,
      authResolver: resolver,
      display: displayAttr,
      sessionId: this.getAttribute("session-id") ?? void 0,
      ragSourceId: this.ragSourceId ?? this.getAttribute("rag-source-id") ?? void 0,
      connectionId: this.getAttribute("connection-id") ?? void 0,
      actionsEnabled: actionsAttr === "true" || actionsAttr === "" ? true : void 0,
      model: this.getAttribute("model") ?? void 0,
      target: displayAttr === "inline" ? this : void 0,
      theme: themeAttr ? { mode: themeAttr } : void 0,
      contextProvider: this.contextProvider ?? void 0
    };
    this.handle = createWidget(options);
  }
  disconnectedCallback() {
    this.handle?.destroy();
    this.handle = null;
  }
  /** JS 에서 프로그램적으로 워크플로우 구독 */
  subscribeWorkflow(runId) {
    return this.handle?.subscribeWorkflow(runId) ?? (() => {
    });
  }
  open() {
    this.handle?.open();
  }
  close() {
    this.handle?.close();
  }
};
function defineAimbaseChat() {
  if (typeof customElements === "undefined") return;
  if (!customElements.get("aimbase-chat")) {
    customElements.define("aimbase-chat", AimbaseChatElement);
  }
}

// src/index.ts
if (typeof window !== "undefined") {
  try {
    defineAimbaseChat();
  } catch {
  }
}
function init(options) {
  return createWidget(options);
}
export {
  AimbaseChatElement,
  SttClient,
  SttError,
  createWidget,
  defineAimbaseChat,
  init
};
//# sourceMappingURL=aimbase-chat.esm.js.map