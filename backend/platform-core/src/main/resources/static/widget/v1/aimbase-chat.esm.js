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
    this.currentAbort?.abort();
    const ac = new AbortController();
    this.currentAbort = ac;
    const token = await this.tokens.getToken();
    const userContent = [{ type: "text", text: args.text }];
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
    const res = await fetch(`${args.baseUrl}/api/v1/chat/completions`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "Accept": "text/event-stream"
      },
      body: JSON.stringify(body),
      signal: ac.signal
    });
    if (!res.ok) {
      const errText = await res.text().catch(() => "");
      throw new Error(`chat/completions ${res.status}: ${errText.slice(0, 200)}`);
    }
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
    workflowRuns: /* @__PURE__ */ new Map()
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
  const composer = el("div", "composer");
  const textarea = document.createElement("textarea");
  textarea.placeholder = "\uBA54\uC2DC\uC9C0\uB97C \uC785\uB825\uD558\uC138\uC694\u2026 (Enter \uC804\uC1A1, Shift+Enter \uC904\uBC14\uAFC8)";
  textarea.rows = 1;
  const sendBtn = el("button", "send-btn", "\uC804\uC1A1");
  composer.appendChild(textarea);
  composer.appendChild(sendBtn);
  panel.appendChild(composer);
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
  async function sendMessage(text) {
    const trimmed = text.trim();
    if (!trimmed) return;
    appendMessage("user", trimmed);
    resetAssistantBlock();
    sendBtn.setAttribute("disabled", "true");
    try {
      await chat.sendMessage(
        {
          baseUrl: options.baseUrl,
          sessionId: state.sessionId,
          text: trimmed,
          ragSourceId: state.ragSourceId,
          context: options.contextProvider?.()
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
      appendMessage("error", e.message);
      options.on?.onError?.(e);
    } finally {
      sendBtn.removeAttribute("disabled");
    }
  }
  sendBtn.addEventListener("click", () => {
    void sendMessage(textarea.value);
    textarea.value = "";
  });
  textarea.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void sendMessage(textarea.value);
      textarea.value = "";
    }
  });
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
    return ["base-url", "token-endpoint", "display", "theme-mode", "rag-source-id", "session-id"];
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
    const options = {
      baseUrl,
      authResolver: resolver,
      display: displayAttr,
      sessionId: this.getAttribute("session-id") ?? void 0,
      ragSourceId: this.ragSourceId ?? this.getAttribute("rag-source-id") ?? void 0,
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
  createWidget,
  defineAimbaseChat,
  init
};
//# sourceMappingURL=aimbase-chat.esm.js.map