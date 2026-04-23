import { ChatClient } from "./chat-client";
import { WIDGET_CSS } from "./styles";
import { TokenStore } from "./token-store";
import type {
  ApprovalEvent,
  Citation,
  DisplayMode,
  WidgetHandle,
  WidgetOptions,
  WorkflowStepEvent,
} from "./types";
import { WorkflowClient } from "./workflow-client";

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function genId(): string {
  try {
    return (self.crypto as Crypto).randomUUID();
  } catch {
    return `sess-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  }
}

interface WidgetState {
  open: boolean;
  sessionId: string;
  ragSourceId?: string;
  currentAssistantDiv: HTMLDivElement | null;
  currentAssistantText: string;
  workflowRuns: Map<string, { stepEl: HTMLDivElement; steps: Map<string, HTMLElement> }>;
}

export function createWidget(options: WidgetOptions): WidgetHandle {
  const display: DisplayMode = options.display ?? "bubble";
  const theme = options.theme?.mode ?? "auto";

  // 호스트 element — 사용자가 target 지정하지 않으면 document.body 에 추가
  const host = document.createElement("div");
  host.setAttribute("data-aimbase-widget", "true");
  if (theme === "dark") host.setAttribute("data-theme", "dark");
  if (theme === "light") host.setAttribute("data-theme", "light");

  const mountTarget =
    typeof options.target === "string"
      ? (document.querySelector(options.target) as HTMLElement | null)
      : options.target ?? null;

  if ((display === "inline" || display === "panel") && !mountTarget) {
    throw new Error(`display='${display}' requires options.target`);
  }
  (mountTarget ?? document.body).appendChild(host);

  const shadow = host.attachShadow({ mode: "open" });
  const style = document.createElement("style");
  style.textContent = WIDGET_CSS;
  shadow.appendChild(style);
  // 사용자 CSS 변수 오버라이드
  if (options.theme?.cssVars) {
    const overrides = Object.entries(options.theme.cssVars)
      .map(([k, v]) => `${k}: ${v};`)
      .join(" ");
    const overrideStyle = document.createElement("style");
    overrideStyle.textContent = `:host { ${overrides} }`;
    shadow.appendChild(overrideStyle);
  }
  const root = el("div", "root");
  shadow.appendChild(root);

  const state: WidgetState = {
    open: display !== "bubble", // bubble 은 기본 닫힘
    sessionId: options.sessionId ?? genId(),
    ragSourceId: options.ragSourceId,
    currentAssistantDiv: null,
    currentAssistantText: "",
    workflowRuns: new Map(),
  };

  // ── elements ─────────────────────────
  let bubbleBtn: HTMLButtonElement | null = null;
  if (display === "bubble") {
    bubbleBtn = el("button", "bubble-btn", "💬");
    bubbleBtn.setAttribute("aria-label", "Open Aimbase chat");
    bubbleBtn.addEventListener("click", () => setOpen(true));
    root.appendChild(bubbleBtn);
  }

  const panel = el("div", `panel ${displayClass(display)} ${state.open ? "" : "hidden"}`);
  root.appendChild(panel);

  const header = el("div", "header");
  header.appendChild(el("div", "title", "Aimbase Chat"));
  if (display === "bubble" || display === "panel") {
    const closeBtn = el("button", "close", "×");
    closeBtn.addEventListener("click", () => setOpen(false));
    header.appendChild(closeBtn);
  }
  panel.appendChild(header);

  const messagesEl = el("div", "messages");
  panel.appendChild(messagesEl);

  const composer = el("div", "composer");
  const textarea = document.createElement("textarea");
  textarea.placeholder = "메시지를 입력하세요… (Enter 전송, Shift+Enter 줄바꿈)";
  textarea.rows = 1;
  const sendBtn = el("button", "send-btn", "전송");
  composer.appendChild(textarea);
  composer.appendChild(sendBtn);
  panel.appendChild(composer);

  // ── behavior ─────────────────────────
  function setOpen(v: boolean): void {
    state.open = v;
    if (v) panel.classList.remove("hidden");
    else panel.classList.add("hidden");
  }

  function appendMessage(kind: "user" | "assistant" | "thinking" | "tool" | "error", text: string): HTMLDivElement {
    const div = el("div", `msg ${kind}`, text);
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return div;
  }

  function renderCitations(citations: Citation[]): void {
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

  async function openCitationPreview(c: Citation): Promise<void> {
    const existing = panel.querySelector(".citation-preview");
    if (existing) existing.remove();

    const preview = el("div", "citation-preview");
    const closeBtn = el("button", "close", "×");
    closeBtn.addEventListener("click", () => preview.remove());
    preview.appendChild(closeBtn);
    preview.appendChild(el("h4", "", c.document_name ?? c.source_id));
    preview.appendChild(
      el(
        "div",
        "meta",
        `score ${c.score.toFixed(2)}${c.page_number ? ` · page ${c.page_number}` : ""}`,
      ),
    );
    const contentDiv = el("div", "content", c.content_preview);
    preview.appendChild(contentDiv);
    panel.appendChild(preview);

    // 원문 상세가 필요한 경우 서버 조회
    if (c.chunk_id) {
      try {
        const detail = await workflow.fetchChunk(options.baseUrl, c.source_id, c.chunk_id);
        if (typeof detail.content === "string") {
          contentDiv.textContent = detail.content;
        }
      } catch {
        // 미리보기는 이미 표시됨
      }
    }
  }

  function ensureAssistantBlock(): HTMLDivElement {
    if (!state.currentAssistantDiv) {
      state.currentAssistantDiv = appendMessage("assistant", "");
      state.currentAssistantText = "";
    }
    return state.currentAssistantDiv;
  }

  function resetAssistantBlock(): void {
    state.currentAssistantDiv = null;
    state.currentAssistantText = "";
  }

  // ── token + clients ─────────────────────────
  const tokens = new TokenStore(options.authResolver, options.on?.onTokenExpiring);
  const chat = new ChatClient(tokens);
  const workflow = new WorkflowClient(tokens);

  async function sendMessage(text: string): Promise<void> {
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
          context: options.contextProvider?.(),
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
              appendMessage("thinking", `💭 ${d.text ?? ""}`);
              break;
            case "tool_use_start":
              appendMessage("tool", `🛠 ${d.tool?.name ?? "tool"} 실행…`);
              break;
            case "tool_result":
              // 일반 실행 결과는 UI 에 노이즈, 에러만 표시
              if (d.toolResult?.is_error) {
                appendMessage("error", `도구 에러: ${d.toolResult.output.slice(0, 200)}`);
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
        },
      );
    } catch (e) {
      appendMessage("error", (e as Error).message);
      options.on?.onError?.(e as Error);
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

  // ── workflow ─────────────────────────
  async function subscribeWorkflow(runId: string): Promise<() => void> {
    const wfPanel = el("div", "workflow-panel");
    const wfTitle = el("div", "title", `워크플로우 ${runId.slice(0, 8)}…`);
    wfPanel.appendChild(wfTitle);
    const stepsList = el("div", "steps");
    wfPanel.appendChild(stepsList);
    messagesEl.appendChild(wfPanel);
    const steps = new Map<string, HTMLElement>();
    state.workflowRuns.set(runId, { stepEl: wfPanel, steps });

    const unsubscribe = await workflow.subscribe(options.baseUrl, runId, {
      onSnapshot: (snap) => {
        wfTitle.textContent = `워크플로우 ${snap.workflow_id ?? runId.slice(0, 8)} — ${snap.status}`;
      },
      onStep: (ev: WorkflowStepEvent) => {
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
        const label = el("span", "label", `${ev.step_id}${ev.duration_ms ? ` · ${ev.duration_ms}ms` : ""}`);
        stepEl.appendChild(dot);
        stepEl.appendChild(label);
        if (ev.sub_workflow_id) {
          const sub = el("span", "sub", ` → ${ev.sub_workflow_id}`);
          sub.style.color = "var(--aimbase-muted)";
          stepEl.appendChild(sub);
        }
      },
      onApproval: (ev: ApprovalEvent) => {
        options.on?.onApprovalRequired?.(ev);
        const note = el("div", "workflow-step");
        note.innerHTML = `⏸ 승인 대기: <b>${ev.step_id}</b> — ${ev.reason ?? ev.policy_id}`;
        note.style.color = "#b45309";
        stepsList.appendChild(note);
      },
      onDone: (ev) => {
        wfTitle.textContent = `워크플로우 ${runId.slice(0, 8)}… — ${ev.status} (${ev.duration_ms}ms)`;
        state.workflowRuns.delete(runId);
      },
      onError: (e) => {
        options.on?.onError?.(e);
      },
    });

    return unsubscribe;
  }

  return {
    open: () => setOpen(true),
    close: () => setOpen(false),
    sendMessage,
    abort: () => chat.abort(options.baseUrl, state.sessionId),
    subscribeWorkflow: (runId: string) => {
      let unsub: (() => void) | null = null;
      void subscribeWorkflow(runId).then((u) => (unsub = u));
      return () => unsub?.();
    },
    destroy: () => {
      tokens.destroy();
      host.remove();
    },
  };
}

function displayClass(d: DisplayMode): string {
  if (d === "bubble") return "floating";
  if (d === "panel") return "side";
  return "inline";
}
