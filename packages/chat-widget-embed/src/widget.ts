import { AttachmentClient } from "./attachment-client";
import { ChatClient } from "./chat-client";
import { icon } from "./icons";
import { renderRichInto } from "./rich-render";
import { T } from "./strings";
import { SttClient, SttError } from "./stt-client";
import { WIDGET_CSS } from "./styles";
import { TokenStore } from "./token-store";
import type {
  ApprovalEvent,
  AttachmentDraft,
  ChatArtifact,
  ChatDelta,
  Citation,
  DisplayMode,
  FeedbackRating,
  FontSize,
  SttRecordingState,
  WidgetHandle,
  WidgetOptions,
  WidgetSettings,
  WorkflowStepEvent,
} from "./types";
import { WidgetApi, WidgetApiError } from "./widget-api";
import { WorkflowClient } from "./workflow-client";

const ACCEPT_MIME = "image/png,image/jpeg,image/gif,image/webp,application/pdf";
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_PDF_BYTES = 32 * 1024 * 1024;
const MAX_SUGGESTIONS = 4;
const TEXTAREA_MAX_PX = 180;
// 맨 아래에서 이만큼 안쪽이면 새 글을 따라 내려간다.
const PIN_THRESHOLD_PX = 48;
const TITLE_MAX_CHARS = 60;
const TOOL_OUTPUT_EXCERPT = 120;
const DEFAULT_COMMENT_MAX = 500;
const FONT_STORAGE_KEY = "aimbase-chat:font-size";
const FONT_SIZES: FontSize[] = ["small", "medium", "large"];

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

function iconButton(name: Parameters<typeof icon>[0], label: string, extra = ""): HTMLButtonElement {
  const b = el("button", `ib ${extra}`.trim());
  b.type = "button";
  b.innerHTML = icon(name);
  b.setAttribute("aria-label", label);
  b.setAttribute("data-tip", label);
  return b;
}

function genId(): string {
  try {
    return (self.crypto as Crypto).randomUUID();
  } catch {
    return `sess-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  }
}

function formatTime(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const now = new Date();
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  return sameDay ? hm : `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

function readStoredFontSize(): FontSize | null {
  try {
    const v = localStorage.getItem(FONT_STORAGE_KEY);
    return FONT_SIZES.includes(v as FontSize) ? (v as FontSize) : null;
  } catch {
    return null;
  }
}

function storeFontSize(size: FontSize): void {
  try {
    localStorage.setItem(FONT_STORAGE_KEY, size);
  } catch {
    // 저장소를 막은 브라우저에서는 이번 화면에서만 적용된다.
  }
}

interface ToolStep {
  id?: string;
  name: string;
  arg: string;
  status: "running" | "done" | "failed";
  output?: string;
}

interface AnswerVersion {
  text: string;
  thinkingText: string;
  thinkingStartedAt: number | null;
  thinkingMs: number | null;
  tools: ToolStep[];
  citations: Citation[];
  artifacts: ChatArtifact[];
  turnId?: string;
  turnSeq?: number;
  model?: string;
  at: Date;
  live: boolean;
  stopped: boolean;
  error?: string;
  /** 서버가 요청을 받기 전에 거절했다. 서버 대화 기록은 바뀌지 않았다. */
  rejected: boolean;
  rating?: FeedbackRating;
  feedbackOpen: boolean;
  followUps: string[];
}

interface TurnView {
  folds: HTMLDivElement;
  body: HTMLDivElement;
  extras: HTMLDivElement;
  acts: HTMLDivElement;
  feedback: HTMLDivElement;
  followUps: HTMLDivElement;
  thinkingFold?: { btn: HTMLButtonElement; label: HTMLSpanElement; body: HTMLDivElement };
  toolsFold?: { btn: HTMLButtonElement; label: HTMLSpanElement; body: HTMLDivElement };
}

interface Turn {
  question: string;
  attachmentNames: string[];
  attachments: Array<{ attachmentId: string; mediaType: string }>;
  askedAt: Date;
  versions: AnswerVersion[];
  shown: number;
  el: HTMLDivElement;
  userEl: HTMLDivElement;
  answerEl: HTMLDivElement;
  view: TurnView;
}

function newVersion(): AnswerVersion {
  return {
    text: "",
    thinkingText: "",
    thinkingStartedAt: null,
    thinkingMs: null,
    tools: [],
    citations: [],
    artifacts: [],
    at: new Date(),
    live: true,
    stopped: false,
    rejected: false,
    feedbackOpen: false,
    followUps: [],
  };
}

export function createWidget(options: WidgetOptions): WidgetHandle {
  const display: DisplayMode = options.display ?? "bubble";
  const theme = options.theme?.mode ?? "auto";

  const host = document.createElement("div");
  host.setAttribute("data-aimbase-widget", "true");
  host.setAttribute("data-display", display);
  if (theme === "dark") host.setAttribute("data-theme", "dark");
  if (theme === "light") host.setAttribute("data-theme", "light");

  // CR-131: 리치 렌더(코드 하이라이트, mermaid) 테마 판별. auto 는 OS 설정 추종.
  function resolvedTheme(): "light" | "dark" {
    if (theme === "dark") return "dark";
    if (theme === "light") return "light";
    return typeof window !== "undefined"
      && window.matchMedia?.("(prefers-color-scheme: dark)").matches
      ? "dark"
      : "light";
  }

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

  // ── 상태 ─────────────────────────
  let open = display !== "bubble";
  let sessionId = options.sessionId ?? genId();
  let turns: Turn[] = [];
  let busy = false;
  let live: { turn: Turn; version: AnswerVersion } | null = null;
  let draftAttachments: AttachmentDraft[] = [];
  let settings: WidgetSettings | null = null;
  let selectedModel: string | undefined = options.model;
  let thinkingOn = false;
  let titleFromServer = false;
  let expanded = false;
  let pinned = true;
  let workflowRuns = new Map<string, { stepEl: HTMLDivElement; steps: Map<string, HTMLElement> }>();

  // ── 틀 ─────────────────────────
  let bubbleBtn: HTMLButtonElement | null = null;
  if (display === "bubble") {
    bubbleBtn = el("button", "bubble-btn");
    bubbleBtn.innerHTML = icon("chat", 24);
    bubbleBtn.setAttribute("aria-label", "Open Aimbase chat");
    bubbleBtn.addEventListener("click", () => setOpen(true));
    root.appendChild(bubbleBtn);
  }

  const backdrop = el("div", "backdrop");
  backdrop.addEventListener("click", () => setExpanded(false));
  root.appendChild(backdrop);

  const panel = el("div", `panel ${displayClass(display)} ${open ? "" : "hidden"}`);
  root.appendChild(panel);

  // 머리줄: 제목, 새 대화, 크게 보기, 더보기(글자 크기), 닫기
  const header = el("div", "hd");
  const titleEl = el("div", "ttl", T.defaultTitle);
  const newBtn = iconButton("pen", T.newConversation);
  const expandBtn = iconButton("max", T.expand);
  expandBtn.setAttribute("aria-pressed", "false");
  const moreBtn = iconButton("more", T.more);
  header.append(titleEl, newBtn, expandBtn, moreBtn);
  if (display === "bubble" || display === "panel") {
    const closeBtn = iconButton("x", T.close);
    closeBtn.addEventListener("click", () => setOpen(false));
    header.appendChild(closeBtn);
  }
  panel.appendChild(header);

  const morePop = el("div", "pop");
  morePop.style.top = "40px";
  morePop.style.right = "8px";
  const fontRow = el("div", "row");
  fontRow.appendChild(el("span", "", T.fontSize));
  const fontButtons = el("span", "fsz");
  const fontLabels: Record<FontSize, string> = { small: T.fontSmall, medium: T.fontMedium, large: T.fontLarge };
  const fontButtonMap = new Map<FontSize, HTMLButtonElement>();
  for (const size of FONT_SIZES) {
    const b = el("button", "chipb", fontLabels[size]);
    b.type = "button";
    b.addEventListener("click", () => {
      setFontSize(size);
      storeFontSize(size);
    });
    fontButtonMap.set(size, b);
    fontButtons.appendChild(b);
  }
  fontRow.appendChild(fontButtons);
  morePop.appendChild(fontRow);
  panel.appendChild(morePop);

  // 대화 칸
  const stack = el("div", "stack");
  const scrollEl = el("div", "scroll");
  const thread = el("div", "thread");
  scrollEl.appendChild(thread);
  const toBottomBtn = el("button", "tobottom");
  toBottomBtn.type = "button";
  toBottomBtn.innerHTML = `${icon("arrowdown", 14)}<span>${T.toBottom}</span>`;
  toBottomBtn.addEventListener("click", () => scrollToBottom(true));
  stack.append(scrollEl, toBottomBtn);
  panel.appendChild(stack);

  const emptyEl = el("div", "empty");
  emptyEl.appendChild(el("h3", "", options.emptyTitle || T.defaultEmptyTitle));
  if (options.emptySubtitle) emptyEl.appendChild(el("p", "", options.emptySubtitle));
  const suggestions = (options.suggestions ?? []).filter((s) => s && s.trim()).slice(0, MAX_SUGGESTIONS);
  if (suggestions.length) {
    const sugs = el("div", "sugs");
    for (const s of suggestions) {
      const b = el("button", "sug", s);
      b.type = "button";
      b.addEventListener("click", () => void sendMessage(s));
      sugs.appendChild(b);
    }
    emptyEl.appendChild(sugs);
  }
  thread.appendChild(emptyEl);

  // 입력칸
  const cwrap = el("div", "cwrap");
  const comp = el("div", "comp");
  const attachmentsEl = el("div", "attachments");
  const textarea = document.createElement("textarea");
  textarea.placeholder = T.placeholder;
  textarea.rows = 1;
  const crow = el("div", "crow");
  const attachBtn = iconButton("attach", T.attach);
  const fileInput = document.createElement("input");
  fileInput.type = "file";
  fileInput.accept = ACCEPT_MIME;
  fileInput.multiple = true;
  fileInput.style.display = "none";
  const modelBtn = el("button", "model");
  modelBtn.type = "button";
  modelBtn.setAttribute("aria-label", T.model);
  modelBtn.hidden = true;
  const modelLabel = el("span");
  modelBtn.appendChild(modelLabel);
  modelBtn.insertAdjacentHTML("beforeend", icon("cdown", 12));
  const thinkBtn = iconButton("spark", T.deepThinking);
  thinkBtn.setAttribute("aria-pressed", "false");
  thinkBtn.hidden = true;
  const micBtn = iconButton("mic", "음성 입력 시작");
  micBtn.setAttribute("aria-pressed", "false");
  micBtn.hidden = true;
  const sendBtn = el("button", "send");
  sendBtn.type = "button";
  crow.append(attachBtn, fileInput, el("span", "sp"), modelBtn, thinkBtn, micBtn, sendBtn);
  comp.append(attachmentsEl, textarea, crow);
  cwrap.appendChild(comp);
  panel.appendChild(cwrap);

  const modelPop = el("div", "pop");
  modelPop.style.bottom = "56px";
  modelPop.style.right = "12px";
  panel.appendChild(modelPop);

  const toast = el("div", "toast");
  panel.appendChild(toast);

  // CR-060: 녹음 오버레이
  const recordingOverlay = el("div", "recording-overlay hidden");
  recordingOverlay.setAttribute("role", "status");
  recordingOverlay.setAttribute("aria-live", "polite");
  const wave = el("span", "rec-wave", "▁▃▅▇▅▃▁");
  const timer = el("span", "rec-timer", "0:00");
  const cancelRec = el("button", "btn", T.cancel);
  cancelRec.type = "button";
  cancelRec.setAttribute("aria-label", "녹음 취소");
  const stopRec = el("button", "btn pri", "■ 정지");
  stopRec.type = "button";
  stopRec.setAttribute("aria-label", "녹음 정지 후 변환");
  recordingOverlay.append(wave, timer, cancelRec, stopRec);
  panel.appendChild(recordingOverlay);

  // ── 클라이언트 ─────────────────────────
  const tokens = new TokenStore(options.authResolver, options.on?.onTokenExpiring);
  const chat = new ChatClient(tokens);
  const workflow = new WorkflowClient(tokens);
  const attachments = new AttachmentClient(tokens);
  const api = new WidgetApi(tokens, options.baseUrl);

  // ── 공통 동작 ─────────────────────────
  function setOpen(v: boolean): void {
    open = v;
    panel.classList.toggle("hidden", !v);
    if (!v) setExpanded(false);
  }

  function setExpanded(v: boolean): void {
    expanded = v;
    panel.classList.toggle("expanded", v);
    backdrop.classList.toggle("on", v);
    expandBtn.innerHTML = icon(v ? "min" : "max");
    expandBtn.setAttribute("aria-pressed", String(v));
  }

  function setFontSize(size: FontSize): void {
    host.setAttribute("data-font-size", size);
    fontButtonMap.forEach((b, s) => b.classList.toggle("on", s === size));
  }

  function closePops(): boolean {
    const wasOpen = morePop.classList.contains("on") || modelPop.classList.contains("on");
    morePop.classList.remove("on");
    modelPop.classList.remove("on");
    return wasOpen;
  }

  let toastTimer: ReturnType<typeof setTimeout> | null = null;
  function showToast(text: string): void {
    toast.textContent = text;
    toast.classList.add("on");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove("on"), 1600);
  }

  function setTitle(text: string): void {
    titleEl.textContent = text;
    titleEl.title = text;
  }

  function nearBottom(): boolean {
    return scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight < PIN_THRESHOLD_PX;
  }

  function scrollToBottom(force = false): void {
    if (force) pinned = true;
    if (pinned) {
      scrollEl.scrollTop = scrollEl.scrollHeight;
      toBottomBtn.classList.remove("on");
    } else {
      toBottomBtn.classList.add("on");
    }
  }

  scrollEl.addEventListener("scroll", () => {
    pinned = nearBottom();
    if (pinned) toBottomBtn.classList.remove("on");
  });

  function appendNotice(text: string): void {
    thread.appendChild(el("div", "notice", text));
    scrollToBottom();
  }

  function errorText(e: unknown): string {
    if (e instanceof WidgetApiError) return T.errors[e.code] ?? e.code;
    return (e as Error)?.message ?? String(e);
  }

  function setBusy(v: boolean): void {
    busy = v;
    panel.classList.toggle("busy", v);
    updateSendButton();
  }

  function updateSendButton(): void {
    if (busy) {
      sendBtn.innerHTML = icon("stop", 14);
      sendBtn.setAttribute("aria-label", T.stop);
      sendBtn.setAttribute("data-tip", T.stop);
      sendBtn.disabled = false;
      return;
    }
    sendBtn.innerHTML = icon("up");
    sendBtn.setAttribute("aria-label", T.send);
    sendBtn.setAttribute("data-tip", T.send);
    const hasUploading = draftAttachments.some((a) => a.status === "uploading");
    const hasText = textarea.value.trim().length > 0;
    const hasReady = draftAttachments.some((a) => a.status === "ready");
    sendBtn.disabled = hasUploading || (!hasText && !hasReady);
  }

  function autosize(ta: HTMLTextAreaElement): void {
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, TEXTAREA_MAX_PX)}px`;
  }

  // ── 모델과 깊게 생각하기 ─────────────────────────
  function modelLabelOf(id?: string): string {
    if (!id) return "";
    const list = settings?.models ?? [];
    const exact = list.find((m) => m.id === id);
    if (exact) return exact.label || exact.id;
    const tail = id.includes("/") ? id.slice(id.lastIndexOf("/") + 1) : id;
    const loose = list.find((m) => m.id === tail || m.id.endsWith(`/${tail}`));
    return loose ? loose.label || loose.id : id;
  }

  function renderModelControls(): void {
    const models = settings?.models ?? [];
    modelBtn.hidden = models.length < 2;
    modelLabel.textContent = modelLabelOf(selectedModel);
    modelPop.innerHTML = "";
    for (const m of models) {
      const item = el("button", "mi");
      item.type = "button";
      item.appendChild(el("span", "", m.label || m.id));
      if (m.id === selectedModel) {
        const mark = el("span", "mk");
        mark.innerHTML = icon("check", 14);
        item.appendChild(mark);
      }
      item.addEventListener("click", () => {
        selectedModel = m.id;
        closePops();
        renderModelControls();
      });
      modelPop.appendChild(item);
    }
    thinkBtn.hidden = !settings?.thinking_supported;
    thinkBtn.setAttribute("aria-pressed", String(thinkingOn));
  }

  modelBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    const willOpen = !modelPop.classList.contains("on");
    closePops();
    modelPop.classList.toggle("on", willOpen);
  });
  thinkBtn.addEventListener("click", () => {
    thinkingOn = !thinkingOn;
    thinkBtn.setAttribute("aria-pressed", String(thinkingOn));
  });
  moreBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    const willOpen = !morePop.classList.contains("on");
    closePops();
    morePop.classList.toggle("on", willOpen);
  });
  morePop.addEventListener("click", (e) => e.stopPropagation());
  modelPop.addEventListener("click", (e) => e.stopPropagation());
  panel.addEventListener("click", () => closePops());
  expandBtn.addEventListener("click", () => setExpanded(!expanded));
  newBtn.addEventListener("click", () => void startNewConversation());

  async function loadSettings(): Promise<void> {
    try {
      settings = await api.settings(options.connectionId);
      const ids = settings.models.map((m) => m.id);
      if (!selectedModel || !ids.includes(selectedModel)) selectedModel = settings.default_model;
      thinkingOn = settings.thinking_default === true;
    } catch (e) {
      // 설정을 못 읽어도 대화는 된다. 붙이는 앱이 준 모델(또는 auto)로 묻는다.
      settings = null;
      options.on?.onError?.(e as Error);
    }
    renderModelControls();
  }

  // ── 질문과 답 그리기 ─────────────────────────
  function makeFold(label: string, liveNow: boolean): { wrap: HTMLDivElement; btn: HTMLButtonElement; label: HTMLSpanElement; body: HTMLDivElement } {
    const wrap = el("div");
    const btn = el("button", `fold${liveNow ? " live" : ""}`);
    btn.type = "button";
    const cv = el("span", "cv");
    cv.innerHTML = icon("cright", 12);
    const lbl = el("span", "lbl", label);
    btn.append(cv, lbl);
    const body = el("div", "foldbody");
    btn.addEventListener("click", () => {
      btn.classList.toggle("open");
      body.classList.toggle("open");
    });
    wrap.append(btn, body);
    return { wrap, btn, label: lbl, body };
  }

  function toolRow(step: ToolStep): HTMLDivElement {
    const row = el("div", "trow");
    row.appendChild(el("span", `dot${step.status === "running" ? " run" : step.status === "failed" ? " fail" : ""}`));
    row.appendChild(el("span", "tn", prettyToolName(step.name)));
    if (step.arg) row.appendChild(el("span", "ta", step.arg));
    if (step.output) row.appendChild(el("span", "to", step.output));
    return row;
  }

  function renderUser(turn: Turn, index: number): void {
    const u = turn.userEl;
    u.innerHTML = "";
    const lines = [turn.question, ...turn.attachmentNames.map((n) => T.attachmentLabel(n))].filter(Boolean);
    u.appendChild(el("div", "bub", lines.join("\n")));
    const acts = el("div", "acts");
    const editable = latestSeq(turn) !== undefined;
    if (editable) {
      const editBtn = iconButton("pencil", T.edit, "sm needs-idle");
      editBtn.addEventListener("click", () => beginEdit(index));
      acts.appendChild(editBtn);
    }
    acts.appendChild(el("span", "time", formatTime(turn.askedAt)));
    u.appendChild(acts);
  }

  function latestSeq(turn: Turn): number | undefined {
    return turn.versions[turn.versions.length - 1]?.turnSeq;
  }

  function buildTurn(question: string, attachmentNames: string[], payload: Turn["attachments"]): Turn {
    const wrap = el("div");
    wrap.style.display = "contents";
    const userEl = el("div", "u");
    const answerEl = el("div", "a");
    wrap.append(userEl, answerEl);
    const turn: Turn = {
      question,
      attachmentNames,
      attachments: payload,
      askedAt: new Date(),
      versions: [],
      shown: 0,
      el: wrap,
      userEl,
      answerEl,
      view: buildAnswerShell(answerEl),
    };
    return turn;
  }

  function buildAnswerShell(answerEl: HTMLDivElement): TurnView {
    answerEl.innerHTML = "";
    const view: TurnView = {
      folds: el("div"),
      body: el("div", "md"),
      extras: el("div"),
      acts: el("div", "aacts"),
      feedback: el("div"),
      followUps: el("div", "fups"),
    };
    answerEl.append(view.folds, view.body, view.extras, view.acts, view.feedback, view.followUps);
    return view;
  }

  /** 보이는 판의 답을 처음부터 다시 그린다. 스트리밍이 끝났거나 판을 넘겼을 때 쓴다. */
  function renderAnswer(turn: Turn): void {
    const v = turn.versions[turn.shown];
    turn.view = buildAnswerShell(turn.answerEl);
    const view = turn.view;
    if (!v) return;

    if (v.thinkingText) {
      const sec = v.thinkingMs !== null ? Math.max(1, Math.round(v.thinkingMs / 1000)) : null;
      const fold = makeFold(sec !== null ? T.thoughtFor(sec) : T.thinking, v.live && v.thinkingMs === null);
      fold.body.textContent = v.thinkingText;
      view.folds.appendChild(fold.wrap);
      view.thinkingFold = fold;
    }
    if (v.tools.length) {
      const fold = makeFold(v.live ? T.toolsWorking : T.toolsUsed(v.tools.length), v.live);
      for (const step of v.tools) fold.body.appendChild(toolRow(step));
      view.folds.appendChild(fold.wrap);
      view.toolsFold = fold;
    }

    if (v.live) {
      view.body.className = "md raw caret";
      view.body.textContent = v.text;
    } else if (v.text.trim()) {
      view.body.className = "md";
      renderRichInto(view.body, v.text, { theme: resolvedTheme() });
    }
    if (v.stopped) view.body.after(el("div", "note", T.stopped));
    if (v.error) view.body.after(el("div", "note error", T.requestFailed(v.error)));

    if (v.citations.length) view.extras.appendChild(citationChips(v.citations));
    if (v.artifacts.length) view.extras.appendChild(artifactChips(v.artifacts));

    if (!v.live) renderActions(turn);
    renderFollowUps(turn);
  }

  function renderActions(turn: Turn): void {
    const v = turn.versions[turn.shown];
    const acts = turn.view.acts;
    acts.innerHTML = "";
    if (!v || v.live) return;

    if (v.text) {
      const copyBtn = iconButton("copy", T.copy, "sm");
      copyBtn.addEventListener("click", () => void copyAnswer(v.text));
      acts.appendChild(copyBtn);
    }

    const isLastTurn = turns[turns.length - 1] === turn;
    const isLatest = turn.shown === turn.versions.length - 1;
    if (isLastTurn && isLatest && v.turnSeq !== undefined) {
      const regenBtn = iconButton("redo", T.regenerate, "sm needs-idle");
      regenBtn.addEventListener("click", () => void regenerate(turn));
      acts.appendChild(regenBtn);
    } else if (isLastTurn && isLatest && v.rejected) {
      // 거절된 요청은 서버에 남은 것이 없어 같은 질문을 그대로 다시 보내도 겹치지 않는다.
      const retryBtn = el("button", "btn needs-idle", T.retry);
      retryBtn.type = "button";
      retryBtn.addEventListener("click", () => void retry(turn));
      acts.appendChild(retryBtn);
    }

    if (turn.versions.length > 1) {
      const vn = el("span", "vn");
      const prev = iconButton("cleft", T.prevVersion, "sm");
      prev.disabled = turn.shown === 0 || busy;
      prev.addEventListener("click", () => showVersion(turn, turn.shown - 1));
      const next = iconButton("cright", T.nextVersion, "sm");
      next.disabled = turn.shown === turn.versions.length - 1 || busy;
      next.addEventListener("click", () => showVersion(turn, turn.shown + 1));
      vn.append(prev, el("span", "", `${turn.shown + 1}/${turn.versions.length}`), next);
      acts.appendChild(vn);
    }

    if (v.turnId) {
      const like = iconButton("like", T.like, "sm");
      like.setAttribute("aria-pressed", String(v.rating === "UP"));
      like.addEventListener("click", () => void rateUp(turn, v));
      const dislike = iconButton("dislike", T.dislike, "sm");
      dislike.setAttribute("aria-pressed", String(v.rating === "DOWN" || v.feedbackOpen));
      dislike.addEventListener("click", () => void rateDown(turn, v));
      acts.append(like, dislike);
    }

    acts.appendChild(el("span", "time", formatTime(v.at)));
    if (v.model) acts.appendChild(el("span", "mdl", modelLabelOf(v.model)));
  }

  function renderFollowUps(turn: Turn): void {
    const box = turn.view.followUps;
    box.innerHTML = "";
    const v = turn.versions[turn.shown];
    const isLastTurn = turns[turns.length - 1] === turn;
    if (!v || !isLastTurn || turn.shown !== turn.versions.length - 1 || busy) return;
    for (const q of v.followUps) {
      const b = el("button", "fup");
      b.type = "button";
      const ar = el("span", "ar");
      ar.innerHTML = icon("cright", 12);
      b.append(ar, el("span", "", q));
      b.addEventListener("click", () => void sendMessage(q));
      box.appendChild(b);
    }
  }

  function showVersion(turn: Turn, index: number): void {
    if (busy || index < 0 || index >= turn.versions.length) return;
    turn.shown = index;
    renderAnswer(turn);
  }

  async function copyAnswer(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      showToast(T.copied);
    } catch (e) {
      options.on?.onError?.(e as Error);
    }
  }

  // ── 평가 ─────────────────────────
  async function rateUp(turn: Turn, v: AnswerVersion): Promise<void> {
    if (!v.turnId) return;
    turn.view.feedback.innerHTML = "";
    try {
      if (v.rating === "UP") {
        await api.deleteFeedback(v.turnId);
        v.rating = undefined;
      } else {
        await api.saveFeedback({ sessionId, turnId: v.turnId, rating: "UP" });
        v.rating = "UP";
        v.feedbackOpen = false;
      }
    } catch (e) {
      showToast(T.feedbackFailed);
      options.on?.onError?.(e as Error);
    }
    renderActions(turn);
  }

  async function rateDown(turn: Turn, v: AnswerVersion): Promise<void> {
    if (!v.turnId) return;
    if (v.rating === "DOWN") {
      try {
        await api.deleteFeedback(v.turnId);
        v.rating = undefined;
      } catch (e) {
        showToast(T.feedbackFailed);
        options.on?.onError?.(e as Error);
      }
      closeFeedbackForm(turn, v);
      return;
    }
    if (v.feedbackOpen) {
      closeFeedbackForm(turn, v);
      return;
    }
    openFeedbackForm(turn, v);
  }

  function closeFeedbackForm(turn: Turn, v: AnswerVersion): void {
    v.feedbackOpen = false;
    turn.view.feedback.innerHTML = "";
    renderActions(turn);
  }

  function openFeedbackForm(turn: Turn, v: AnswerVersion): void {
    v.feedbackOpen = true;
    renderActions(turn);
    const box = el("div", "fb");
    box.appendChild(el("div", "fq", T.feedbackQuestion));
    const picked = new Set<string>();
    const reasons = settings?.feedback_reasons ?? [];
    if (reasons.length) {
      const chips = el("div", "fc");
      chips.setAttribute("aria-label", T.reason);
      for (const r of reasons) {
        const c = el("button", "chipb", r.label);
        c.type = "button";
        c.addEventListener("click", () => {
          if (picked.has(r.code)) picked.delete(r.code);
          else picked.add(r.code);
          c.classList.toggle("on", picked.has(r.code));
        });
        chips.appendChild(c);
      }
      box.appendChild(chips);
    }
    const input = el("input");
    input.type = "text";
    input.placeholder = T.comment;
    input.setAttribute("aria-label", T.comment);
    const maxChars = settings?.feedback_comment_max_chars ?? DEFAULT_COMMENT_MAX;
    const tooLong = el("div", "warn", T.commentTooLong(maxChars));
    tooLong.hidden = true;
    box.append(input, tooLong);
    const eb = el("div", "eb");
    const cancel = el("button", "btn", T.close);
    cancel.type = "button";
    cancel.addEventListener("click", () => closeFeedbackForm(turn, v));
    const submit = el("button", "btn pri", T.send);
    submit.type = "button";
    input.addEventListener("input", () => {
      const over = input.value.length > maxChars;
      tooLong.hidden = !over;
      submit.disabled = over;
    });
    input.addEventListener("keydown", (e) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      if ((e as any).isComposing || e.keyCode === 229) return;
      if (e.key === "Enter" && !submit.disabled) submit.click();
    });
    submit.addEventListener("click", async () => {
      if (!v.turnId || input.value.length > maxChars) return;
      submit.disabled = true;
      try {
        await api.saveFeedback({
          sessionId,
          turnId: v.turnId,
          rating: "DOWN",
          reasonCodes: [...picked],
          comment: input.value.trim() || undefined,
        });
        v.rating = "DOWN";
        closeFeedbackForm(turn, v);
        showToast(T.feedbackSent);
      } catch (e) {
        submit.disabled = false;
        showToast(T.feedbackFailed);
        options.on?.onError?.(e as Error);
      }
    });
    eb.append(cancel, submit);
    box.appendChild(eb);
    turn.view.feedback.innerHTML = "";
    turn.view.feedback.appendChild(box);
    input.focus();
    scrollToBottom();
  }

  // ── 질문 수정 ─────────────────────────
  function beginEdit(index: number): void {
    if (busy) return;
    const turn = turns[index];
    const seq = latestSeq(turn);
    if (!turn || seq === undefined) return;
    const u = turn.userEl;
    u.innerHTML = "";
    const box = el("div", "edit");
    const ta = document.createElement("textarea");
    ta.value = turn.question;
    box.appendChild(ta);
    if (index < turns.length - 1) box.appendChild(el("div", "warn", T.editWarning));
    const eb = el("div", "eb");
    const cancel = el("button", "btn", T.cancel);
    cancel.type = "button";
    cancel.addEventListener("click", () => renderUser(turn, index));
    const submit = el("button", "btn pri", T.send);
    submit.type = "button";
    const commit = () => {
      const text = ta.value.trim();
      if (!text && turn.attachments.length === 0) return;
      void submitEdit(index, text);
    };
    submit.addEventListener("click", commit);
    ta.addEventListener("keydown", (e) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      if ((e as any).isComposing || e.keyCode === 229) return;
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        commit();
      } else if (e.key === "Escape") {
        e.stopPropagation();
        renderUser(turn, index);
      }
    });
    ta.addEventListener("input", () => autosize(ta));
    eb.append(cancel, submit);
    box.appendChild(eb);
    u.appendChild(box);
    autosize(ta);
    ta.focus();
    ta.setSelectionRange(ta.value.length, ta.value.length);
  }

  async function submitEdit(index: number, text: string): Promise<void> {
    if (busy) return;
    const turn = turns[index];
    const seq = latestSeq(turn);
    if (seq === undefined) return;
    const removed = turns.splice(index + 1);
    const before = { question: turn.question, askedAt: turn.askedAt, versions: turn.versions, shown: turn.shown };
    for (const later of removed) later.el.remove();
    turn.question = text;
    turn.askedAt = new Date();
    turn.versions = [];
    turn.shown = 0;
    renderUser(turn, index);
    const result = await runTurn(turn, seq);
    if (!result.rejected) return;
    // 서버가 되감지 않았으니 화면도 되돌린다. 지운 것처럼 보이고 서버에는 남는 어긋남을 막는다.
    Object.assign(turn, before);
    for (const later of removed) {
      turns.push(later);
      thread.appendChild(later.el);
    }
    turns.forEach((t, i) => {
      renderUser(t, i);
      renderAnswer(t);
    });
    appendNotice(T.requestFailed(result.error ?? ""));
  }

  // ── 보내기 ─────────────────────────
  async function sendMessage(text: string): Promise<void> {
    if (busy) return;
    const trimmed = text.trim();
    const readyAtts = draftAttachments.filter((a) => a.status === "ready" && a.serverMeta);
    if (!trimmed && readyAtts.length === 0) return;

    const payload = readyAtts.map((a) => ({
      attachmentId: a.serverMeta!.attachment_id,
      mediaType: a.serverMeta!.media_type,
    }));
    const names = readyAtts.map((a) => a.file.name);
    draftAttachments = [];
    renderChips();

    emptyEl.remove();
    const previousLast = turns[turns.length - 1];
    const turn = buildTurn(trimmed, names, payload);
    turns.push(turn);
    thread.appendChild(turn.el);
    renderUser(turn, turns.length - 1);
    if (previousLast) renderAnswer(previousLast);
    if (turns.length === 1 && !titleFromServer && trimmed) setTitle(trimmed.slice(0, TITLE_MAX_CHARS));
    scrollToBottom(true);
    await runTurn(turn);
  }

  async function regenerate(turn: Turn): Promise<void> {
    if (busy) return;
    const seq = latestSeq(turn);
    if (seq === undefined) return;
    const askedAt = turn.askedAt;
    turn.askedAt = new Date();
    const result = await runTurn(turn, seq);
    if (!result.rejected) return;
    turn.versions.pop();
    turn.shown = turn.versions.length - 1;
    turn.askedAt = askedAt;
    renderUser(turn, turns.indexOf(turn));
    renderAnswer(turn);
    appendNotice(T.requestFailed(result.error ?? ""));
  }

  async function retry(turn: Turn): Promise<void> {
    if (busy) return;
    turn.versions.pop();
    turn.shown = Math.max(0, turn.versions.length - 1);
    turn.askedAt = new Date();
    renderUser(turn, turns.indexOf(turn));
    await runTurn(turn);
  }

  /** 한 번의 대화 요청. rewindFromSeq 가 있으면 그 순번부터 지우고 다시 답하게 한다. */
  async function runTurn(turn: Turn, rewindFromSeq?: number): Promise<AnswerVersion> {
    const version = newVersion();
    turn.versions.push(version);
    turn.shown = turn.versions.length - 1;
    live = { turn, version };
    setBusy(true);
    renderAnswer(turn);
    scrollToBottom();

    try {
      await chat.sendMessage(
        {
          baseUrl: options.baseUrl,
          sessionId,
          text: turn.question,
          model: selectedModel,
          ragSourceId: options.ragSourceId,
          connectionId: options.connectionId,
          actionsEnabled: options.actionsEnabled,
          context: options.contextProvider?.(),
          attachments: turn.attachments,
          rewindFromSeq,
          // 끔도 보낸다. Claude CLI 연결은 아무것도 안 보내면 생각하므로 단추를 끈 뜻이 전해지지 않는다.
          thinking: settings?.thinking_supported ? (thinkingOn ? "on" : "off") : undefined,
        },
        (d) => onDelta(turn, version, d),
      );
    } catch (e) {
      version.rejected = e instanceof WidgetApiError;
      version.error = errorText(e);
      options.on?.onError?.(e as Error);
    } finally {
      finishThinking(version);
      version.live = false;
      if (live?.version === version) live = null;
      setBusy(false);
      renderAnswer(turn);
      renderUser(turn, turns.indexOf(turn));
      scrollToBottom();
    }
    void loadFollowUps(turn, version);
    return version;
  }

  function finishThinking(v: AnswerVersion): void {
    if (v.thinkingStartedAt !== null && v.thinkingMs === null) {
      v.thinkingMs = Date.now() - v.thinkingStartedAt;
    }
  }

  function onDelta(turn: Turn, v: AnswerVersion, d: ChatDelta): void {
    options.on?.onMessage?.(d);
    const view = turn.view;
    switch (d.type) {
      case "thinking": {
        if (v.thinkingStartedAt === null) v.thinkingStartedAt = Date.now();
        v.thinkingText += d.text ?? "";
        if (!view.thinkingFold) {
          const fold = makeFold(T.thinking, true);
          view.folds.prepend(fold.wrap);
          view.thinkingFold = fold;
        }
        view.thinkingFold.body.textContent = v.thinkingText;
        break;
      }
      case "delta": {
        if (v.thinkingStartedAt !== null && v.thinkingMs === null) {
          finishThinking(v);
          if (view.thinkingFold) {
            view.thinkingFold.btn.classList.remove("live");
            view.thinkingFold.label.textContent = T.thoughtFor(Math.max(1, Math.round((v.thinkingMs ?? 0) / 1000)));
          }
        }
        v.text += d.text ?? "";
        view.body.textContent = v.text;
        break;
      }
      case "tool_use_start": {
        const step: ToolStep = {
          id: d.tool?.id,
          name: d.tool?.name ?? "tool",
          arg: summarizeInput(d.tool?.input),
          status: "running",
        };
        v.tools.push(step);
        if (!view.toolsFold) {
          const fold = makeFold(T.toolsWorking, true);
          view.folds.appendChild(fold.wrap);
          view.toolsFold = fold;
        }
        view.toolsFold.body.appendChild(toolRow(step));
        break;
      }
      case "tool_result": {
        const step = v.tools.find((s) => s.id && s.id === d.toolResult?.tool_use_id);
        if (step) {
          step.status = d.toolResult?.is_error ? "failed" : "done";
          step.output = excerpt(d.toolResult?.output);
        }
        if (view.toolsFold) {
          view.toolsFold.body.innerHTML = "";
          for (const s of v.tools) view.toolsFold.body.appendChild(toolRow(s));
        }
        break;
      }
      case "done": {
        v.turnId = d.done?.turn_id;
        v.turnSeq = d.done?.turn_seq;
        v.model = d.done?.model;
        v.citations = d.done?.citations ?? [];
        v.artifacts = d.done?.artifacts ?? [];
        v.at = new Date();
        break;
      }
      case "error": {
        v.error = d.error ?? "unknown error";
        break;
      }
    }
    scrollToBottom();
  }

  async function loadFollowUps(turn: Turn, v: AnswerVersion): Promise<void> {
    if (!settings?.follow_ups_enabled || !v.turnId || v.stopped || v.error || !v.text.trim()) return;
    const askedIn = sessionId;
    try {
      const res = await api.followUps(askedIn, v.turnId, options.connectionId);
      if (askedIn !== sessionId) return;
      v.followUps = (res.follow_ups ?? []).filter((q) => q && q.trim());
      if (res.title) {
        titleFromServer = true;
        setTitle(res.title);
      }
      if (turns.includes(turn)) {
        renderFollowUps(turn);
        scrollToBottom();
      }
    } catch {
      // 후속 질문은 덧붙이는 기능이라 실패해도 답은 그대로 둔다.
    }
  }

  async function stop(): Promise<void> {
    if (!busy || !live) return;
    live.version.stopped = true;
    await chat.stop(options.baseUrl, sessionId);
  }

  async function startNewConversation(): Promise<void> {
    if (busy) await chat.abort(options.baseUrl, sessionId);
    const previousSessionId = sessionId;
    sessionId = genId();
    turns = [];
    live = null;
    titleFromServer = false;
    setBusy(false);
    thread.innerHTML = "";
    thread.appendChild(emptyEl);
    workflowRuns = new Map();
    for (const draft of draftAttachments) {
      if (draft.serverMeta) {
        void attachments.delete({ baseUrl: options.baseUrl, sessionId: previousSessionId, attachmentId: draft.serverMeta.attachment_id }).catch(() => undefined);
      }
    }
    draftAttachments = [];
    renderChips();
    setTitle(T.defaultTitle);
    textarea.value = "";
    autosize(textarea);
    updateSendButton();
    textarea.focus();
    options.on?.onNewConversation?.({ previousSessionId, sessionId });
  }

  // ── CR-131: 도구 이름과 입력 요약 ─────────────────────────
  /** `mcp__aimbase-server__web_search` 를 `web_search` 로 줄여 읽기 쉽게 한다. */
  function prettyToolName(name: string): string {
    if (!name) return "tool";
    const parts = name.split("__");
    return parts[parts.length - 1] || name;
  }

  function excerpt(text?: string): string | undefined {
    const flat = (text ?? "").replace(/\s+/g, " ").trim();
    if (!flat) return undefined;
    return flat.length > TOOL_OUTPUT_EXCERPT ? `${flat.slice(0, TOOL_OUTPUT_EXCERPT)}…` : flat;
  }

  function summarizeInput(input?: Record<string, unknown>): string {
    if (!input || typeof input !== "object") return "";
    const keys = Object.keys(input);
    if (!keys.length) return "";
    const first = input[keys[0]];
    let val = typeof first === "string" ? first : JSON.stringify(first);
    if (val && val.length > 48) val = val.slice(0, 48) + "…";
    return val ?? "";
  }

  // ── 출처와 산출물 ─────────────────────────
  function citationChips(citations: Citation[]): HTMLDivElement {
    const wrap = el("div", "citations");
    citations.forEach((c, idx) => {
      const badge = el("button", "citation", `[${c.index ?? idx + 1}] ${c.document_name ?? c.source_id}`);
      badge.type = "button";
      badge.title = c.content_preview;
      badge.addEventListener("click", () => void openCitationPreview(c));
      wrap.appendChild(badge);
    });
    return wrap;
  }

  /** CR-132: CLI 산출물을 이름과 크기를 단 다운로드 칩으로 그린다. */
  function artifactChips(artifacts: ChatArtifact[]): HTMLDivElement {
    const wrap = el("div", "artifacts");
    artifacts.forEach((a) => {
      const chip = el("button", "artifact");
      chip.type = "button";
      chip.innerHTML = icon("attach", 13);
      chip.appendChild(el("span", "", `${a.name} (${formatBytes(a.size)})`));
      chip.title = `다운로드 · ${a.name}`;
      chip.addEventListener("click", () => void downloadArtifact(a));
      wrap.appendChild(chip);
    });
    return wrap;
  }

  /** CR-132: 위젯 토큰으로 산출물을 받아 저장한다. download_url 은 서버 상대경로다. */
  async function downloadArtifact(a: ChatArtifact): Promise<void> {
    try {
      const token = await tokens.getToken();
      const res = await fetch(`${options.baseUrl}${a.download_url}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!res.ok) throw new Error(`download ${res.status}`);
      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = objectUrl;
      anchor.download = a.name;
      anchor.click();
      // 브라우저가 내려받기를 시작할 시간을 준 뒤 해제한다.
      setTimeout(() => URL.revokeObjectURL(objectUrl), 10_000);
    } catch (e) {
      appendNotice(`다운로드 실패: ${(e as Error).message}`);
    }
  }

  function formatBytes(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes < 0) return "";
    if (bytes < 1024) return `${bytes}B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
  }

  async function openCitationPreview(c: Citation): Promise<void> {
    panel.querySelector(".citation-preview")?.remove();
    const preview = el("div", "citation-preview");
    const closeBtn = iconButton("x", T.close, "close");
    closeBtn.addEventListener("click", () => preview.remove());
    preview.appendChild(closeBtn);
    preview.appendChild(el("h4", "", c.document_name ?? c.source_id));
    preview.appendChild(el("div", "meta", `score ${c.score.toFixed(2)}${c.page_number ? ` · page ${c.page_number}` : ""}`));
    const contentDiv = el("div", "content", c.content_preview);
    preview.appendChild(contentDiv);
    panel.appendChild(preview);
    if (c.chunk_id) {
      try {
        const detail = await workflow.fetchChunk(options.baseUrl, c.source_id, c.chunk_id);
        if (typeof detail.content === "string") contentDiv.textContent = detail.content;
      } catch {
        // 미리보기는 이미 보이고 있다.
      }
    }
  }

  // ── CR-061: 첨부 ─────────────────────────
  function genLocalId(): string { return `att-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`; }

  function validateFile(file: File): string | null {
    const isImage = file.type.startsWith("image/");
    const isPdf = file.type === "application/pdf";
    if (!isImage && !isPdf) return "이미지(PNG/JPEG/GIF/WEBP) 또는 PDF 만 첨부 가능합니다";
    const limit = isPdf ? MAX_PDF_BYTES : MAX_IMAGE_BYTES;
    if (file.size > limit) {
      const mb = Math.round(limit / 1024 / 1024);
      return `파일이 너무 큽니다 (최대 ${mb}MB)`;
    }
    return null;
  }

  function renderChips(): void {
    attachmentsEl.innerHTML = "";
    for (const draft of draftAttachments) {
      const chip = el("div", `chip ${draft.status}`);
      if (draft.previewDataUrl) {
        const img = document.createElement("img");
        img.className = "chip-thumb";
        img.src = draft.previewDataUrl;
        img.alt = draft.file.name;
        chip.appendChild(img);
      } else {
        chip.appendChild(el("span", "chip-thumb pdf", "PDF"));
      }
      const label = el("span", "chip-label");
      const pages = draft.serverMeta?.pages;
      const pageSuffix = pages ? ` · ${pages}p` : "";
      label.textContent = draft.status === "error"
        ? `${draft.file.name} — ${draft.error ?? "실패"}`
        : `${draft.file.name}${pageSuffix}`;
      label.title = label.textContent ?? "";
      chip.appendChild(label);
      if (draft.status === "uploading") {
        chip.appendChild(el("span", "chip-spinner"));
      } else {
        const rm = el("button", "chip-remove", "×");
        rm.type = "button";
        rm.setAttribute("aria-label", T.removeAttachment);
        rm.addEventListener("click", () => void removeAttachment(draft.localId));
        chip.appendChild(rm);
      }
      attachmentsEl.appendChild(chip);
    }
    updateSendButton();
  }

  async function readThumb(file: File): Promise<string | undefined> {
    if (!file.type.startsWith("image/")) return undefined;
    return new Promise<string | undefined>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : undefined);
      reader.onerror = () => resolve(undefined);
      reader.readAsDataURL(file);
    });
  }

  async function addAttachment(file: File): Promise<void> {
    const err = validateFile(file);
    if (err) {
      options.on?.onError?.(new Error(err));
      appendNotice(err);
      return;
    }
    const draft: AttachmentDraft = {
      localId: genLocalId(),
      file,
      status: "uploading",
      previewDataUrl: await readThumb(file),
    };
    draftAttachments.push(draft);
    renderChips();
    const uploadSession = sessionId;
    try {
      const meta = await attachments.upload({ baseUrl: options.baseUrl, sessionId: uploadSession, file });
      draft.serverMeta = meta;
      draft.status = "ready";
    } catch (e) {
      draft.status = "error";
      draft.error = (e as Error).message;
      options.on?.onError?.(e as Error);
    } finally {
      renderChips();
    }
  }

  async function removeAttachment(localId: string): Promise<void> {
    const idx = draftAttachments.findIndex((a) => a.localId === localId);
    if (idx < 0) return;
    const draft = draftAttachments[idx];
    draftAttachments.splice(idx, 1);
    renderChips();
    if (draft.serverMeta) {
      try {
        await attachments.delete({ baseUrl: options.baseUrl, sessionId, attachmentId: draft.serverMeta.attachment_id });
      } catch {
        // 서버 정리 작업이 지운다.
      }
    }
  }

  attachBtn.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    const files = fileInput.files ? Array.from(fileInput.files) : [];
    for (const f of files) void addAttachment(f);
    fileInput.value = "";
  });

  let dragCounter = 0;
  let dropOverlay: HTMLDivElement | null = null;
  function hasFile(e: DragEvent): boolean {
    return !!e.dataTransfer && Array.from(e.dataTransfer.types ?? []).includes("Files");
  }
  panel.addEventListener("dragenter", (e) => {
    if (!hasFile(e)) return;
    e.preventDefault();
    dragCounter += 1;
    if (!dropOverlay) {
      dropOverlay = el("div", "drop-overlay", T.dropHere);
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

  // ── CR-060: 음성 입력 ─────────────────────────
  const stt = new SttClient(tokens);
  let recorder: MediaRecorder | null = null;
  let recordedChunks: Blob[] = [];
  let micStream: MediaStream | null = null;
  let recStartedAt = 0;
  let recTickTimer: ReturnType<typeof setInterval> | null = null;
  let recAutoStopTimer: ReturnType<typeof setTimeout> | null = null;
  let recState: SttRecordingState = "idle";
  const MAX_RECORD_MS = 60_000; // 서버와 같은 기본값. 넘으면 서버가 400.

  function sttSupported(): boolean {
    return (
      typeof window !== "undefined"
      && !!window.isSecureContext
      && !!navigator.mediaDevices?.getUserMedia
      && typeof MediaRecorder !== "undefined"
    );
  }

  function pickMimeType(): string | undefined {
    for (const m of ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"]) {
      try {
        if (MediaRecorder.isTypeSupported(m)) return m;
      } catch {
        // 다음 후보를 본다.
      }
    }
    return undefined;
  }

  function setRecState(next: SttRecordingState): void {
    recState = next;
    const recording = next === "recording";
    micBtn.setAttribute("aria-pressed", String(recording));
    micBtn.setAttribute("aria-label", recording ? "녹음 정지" : "음성 입력 시작");
    micBtn.setAttribute("data-tip", recording ? "녹음 정지" : "음성 입력 시작");
    micBtn.innerHTML = icon(recording ? "stop" : "mic");
    micBtn.classList.toggle("rec-on", recording);
    recordingOverlay.classList.toggle("hidden", !recording);
  }

  function formatTimer(ms: number): string {
    const sec = Math.floor(ms / 1000);
    return `${Math.floor(sec / 60)}:${(sec % 60).toString().padStart(2, "0")}`;
  }

  function clearRecTimers(): void {
    if (recTickTimer) { clearInterval(recTickTimer); recTickTimer = null; }
    if (recAutoStopTimer) { clearTimeout(recAutoStopTimer); recAutoStopTimer = null; }
  }

  function releaseMic(): void {
    micStream?.getTracks().forEach((t) => t.stop());
    micStream = null;
  }

  async function startRecording(): Promise<void> {
    if (recState !== "idle") return;
    if (!sttSupported()) {
      appendNotice("이 브라우저·환경에서는 음성 입력을 사용할 수 없습니다 (HTTPS 필수)");
      return;
    }
    setRecState("requesting-permission");
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      appendNotice("마이크 권한이 필요합니다. 브라우저 주소창의 자물쇠 아이콘을 눌러 허용해주세요");
      options.on?.onError?.(e as Error);
      setRecState("idle");
      return;
    }
    const mimeType = pickMimeType();
    try {
      recorder = new MediaRecorder(micStream, mimeType ? { mimeType } : undefined);
    } catch (e) {
      releaseMic();
      setRecState("idle");
      appendNotice("이 브라우저의 MediaRecorder 포맷이 서버와 호환되지 않습니다");
      options.on?.onError?.(e as Error);
      return;
    }
    recordedChunks = [];
    recorder.ondataavailable = (e) => { if (e.data.size > 0) recordedChunks.push(e.data); };
    recorder.onstop = () => void finalizeRecording();
    recorder.onerror = (ev) => {
      options.on?.onError?.(new Error("recorder error: " + (ev as Event).type));
    };
    recorder.start();
    recStartedAt = Date.now();
    setRecState("recording");
    timer.textContent = "0:00";
    recTickTimer = setInterval(() => {
      timer.textContent = formatTimer(Date.now() - recStartedAt);
    }, 250);
    recAutoStopTimer = setTimeout(() => {
      if (recState === "recording") stopRecordingAndSend();
    }, MAX_RECORD_MS);
  }

  function stopRecordingAndSend(): void {
    if (recState !== "recording") return;
    clearRecTimers();
    try { recorder?.stop(); } catch { /* 이미 멈췄다 */ }
    setRecState("uploading");
  }

  function cancelRecording(): void {
    clearRecTimers();
    if (recorder && recorder.state !== "inactive") {
      recorder.onstop = null;
      try { recorder.stop(); } catch { /* 이미 멈췄다 */ }
    }
    releaseMic();
    recorder = null;
    recordedChunks = [];
    setRecState("idle");
  }

  async function finalizeRecording(): Promise<void> {
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
      const result = await stt.transcribe({ baseUrl: options.baseUrl, sessionId, blob });
      insertTextAtCursor(textarea, result.text);
      textarea.focus();
      autosize(textarea);
      updateSendButton();
    } catch (e) {
      const err = e instanceof SttError ? e : new Error(String(e));
      appendNotice(sttErrorMessage(err));
      options.on?.onError?.(err);
    } finally {
      setRecState("idle");
    }
  }

  function sttErrorMessage(err: SttError | Error): string {
    if (err instanceof SttError) {
      switch (err.code) {
        case "STT_RATE_LIMITED":  return "음성 입력을 너무 자주 사용했습니다. 잠시 후 다시 시도해주세요";
        case "STT_FILE_TOO_LARGE": return "녹음 파일이 너무 큽니다";
        case "STT_FILE_TOO_LONG":  return "녹음 시간이 너무 깁니다";
        case "STT_INVALID_MIME":   return "이 오디오 포맷은 서버에서 지원하지 않습니다";
        case "STT_PROVIDER_UNAVAILABLE": return "음성 인식 서비스를 사용할 수 없습니다 (OpenAI 연결 확인 필요)";
        case "STT_TIMEOUT":        return "음성 인식 응답이 지연됩니다. 다시 시도해주세요";
        case "STT_NETWORK":        return "네트워크 오류로 음성을 전송하지 못했습니다";
      }
    }
    return "음성 인식에 실패했습니다: " + err.message;
  }

  function insertTextAtCursor(ta: HTMLTextAreaElement, text: string): void {
    const start = ta.selectionStart ?? ta.value.length;
    const end = ta.selectionEnd ?? ta.value.length;
    const before = ta.value.slice(0, start);
    const after = ta.value.slice(end);
    const sep = before.length > 0 && !/\s$/.test(before) ? " " : "";
    const insert = sep + text;
    ta.value = before + insert + after;
    const caret = (before + insert).length;
    ta.setSelectionRange(caret, caret);
  }

  micBtn.addEventListener("click", () => {
    if (recState === "idle") void startRecording();
    else if (recState === "recording") stopRecordingAndSend();
  });
  stopRec.addEventListener("click", () => stopRecordingAndSend());
  cancelRec.addEventListener("click", () => cancelRecording());

  // ── 입력과 단축키 ─────────────────────────
  function submitComposer(): void {
    if (busy) return;
    const text = textarea.value;
    void sendMessage(text);
    textarea.value = "";
    autosize(textarea);
    updateSendButton();
  }

  sendBtn.addEventListener("click", () => {
    if (busy) void stop();
    else submitComposer();
  });
  textarea.addEventListener("keydown", (e) => {
    // CR-080: 한글 조합을 끝내는 Enter 는 보내지 않는다. isComposing(W3C) 과 keyCode 229(옛 WebKit, Gecko) 를 함께 본다.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    if ((e as any).isComposing || e.keyCode === 229) return;
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submitComposer();
    } else if (e.key === "ArrowUp" && !textarea.value && !busy && turns.length) {
      e.preventDefault();
      beginEdit(turns.length - 1);
    }
  });
  textarea.addEventListener("input", () => {
    autosize(textarea);
    updateSendButton();
  });
  // CR-061: 클립보드 이미지 붙여넣기
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

  // Esc 순서: 녹음 취소, 응답 중지, 열린 메뉴 닫기, 크게 보기 닫기
  panel.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    if (recState === "recording") {
      e.stopPropagation();
      cancelRecording();
    } else if (busy) {
      e.stopPropagation();
      void stop();
    } else if (closePops()) {
      e.stopPropagation();
    } else if (expanded) {
      e.stopPropagation();
      setExpanded(false);
    }
  });

  // ── 시작 ─────────────────────────
  setFontSize(readStoredFontSize() ?? (FONT_SIZES.includes(options.fontSize as FontSize) ? (options.fontSize as FontSize) : "medium"));
  updateSendButton();
  renderModelControls();
  (async () => {
    try {
      await tokens.ensureLoaded();
      if (sttSupported() && tokens.hasScope("chat:stt")) micBtn.hidden = false;
    } catch {
      // 토큰을 못 읽으면 첫 요청에서 오류로 보인다.
    }
    await loadSettings();
  })();

  // ── 워크플로우 ─────────────────────────
  async function subscribeWorkflow(runId: string): Promise<() => void> {
    const wfPanel = el("div", "workflow-panel");
    const wfTitle = el("div", "title", `워크플로우 ${runId.slice(0, 8)}…`);
    wfPanel.appendChild(wfTitle);
    const stepsList = el("div", "steps");
    wfPanel.appendChild(stepsList);
    emptyEl.remove();
    thread.appendChild(wfPanel);
    const steps = new Map<string, HTMLElement>();
    workflowRuns.set(runId, { stepEl: wfPanel, steps });

    return workflow.subscribe(options.baseUrl, runId, {
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
        stepEl.appendChild(el("span", `dot ${dotClass}`));
        stepEl.appendChild(el("span", "label", `${ev.step_id}${ev.duration_ms ? ` · ${ev.duration_ms}ms` : ""}`));
        if (ev.sub_workflow_id) {
          const sub = el("span", "sub", ` → ${ev.sub_workflow_id}`);
          sub.style.color = "var(--aimbase-muted)";
          stepEl.appendChild(sub);
        }
      },
      onApproval: (ev: ApprovalEvent) => {
        options.on?.onApprovalRequired?.(ev);
        const note = el("div", "workflow-step");
        note.textContent = `승인 대기: ${ev.step_id} — ${ev.reason ?? ev.policy_id}`;
        stepsList.appendChild(note);
      },
      onDone: (ev) => {
        wfTitle.textContent = `워크플로우 ${runId.slice(0, 8)}… — ${ev.status} (${ev.duration_ms}ms)`;
        workflowRuns.delete(runId);
      },
      onError: (e) => {
        options.on?.onError?.(e);
      },
    });
  }

  return {
    open: () => setOpen(true),
    close: () => setOpen(false),
    sendMessage,
    abort: () => stop(),
    subscribeWorkflow: (runId: string) => {
      let unsub: (() => void) | null = null;
      void subscribeWorkflow(runId).then((u) => (unsub = u));
      return () => unsub?.();
    },
    destroy: () => {
      cancelRecording();
      if (busy) void chat.abort(options.baseUrl, sessionId);
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
