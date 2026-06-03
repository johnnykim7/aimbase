import { AttachmentClient } from "./attachment-client";
import { ChatClient } from "./chat-client";
import { SttClient, SttError } from "./stt-client";
import { WIDGET_CSS } from "./styles";
import { TokenStore } from "./token-store";
import type {
  ApprovalEvent,
  AttachmentDraft,
  Citation,
  DisplayMode,
  SttRecordingState,
  WidgetHandle,
  WidgetOptions,
  WorkflowStepEvent,
} from "./types";
import { WorkflowClient } from "./workflow-client";

const ACCEPT_MIME = "image/png,image/jpeg,image/gif,image/webp,application/pdf";
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_PDF_BYTES = 32 * 1024 * 1024;

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
  /** CR-061: 전송 전 첨부 목록 */
  attachments: AttachmentDraft[];
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
    attachments: [],
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

  // CR-061: 첨부 칩 리스트 (composer 위)
  const attachmentsEl = el("div", "attachments");
  panel.appendChild(attachmentsEl);

  const composer = el("div", "composer");
  // CR-061: 파일 아이콘 버튼 + 숨은 input
  const attachBtn = el("button", "attach-btn", "📎");
  attachBtn.setAttribute("aria-label", "파일 첨부 (이미지/PDF)");
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
  textarea.placeholder = "메시지를 입력하세요… (Enter 전송, Shift+Enter 줄바꿈)";
  textarea.rows = 1;
  // CR-060: 마이크 버튼 (위젯 토큰에 chat:stt 스코프 있고 브라우저 지원 시 활성)
  const micBtn = el("button", "mic-btn", "🎤");
  micBtn.type = "button";
  micBtn.setAttribute("aria-label", "음성 입력 시작");
  micBtn.setAttribute("aria-pressed", "false");
  micBtn.hidden = true; // 기본 숨김, 토큰·환경 체크 후 노출
  const sendBtn = el("button", "send-btn", "전송");
  composer.appendChild(attachBtn);
  composer.appendChild(fileInput);
  composer.appendChild(textarea);
  composer.appendChild(micBtn);
  composer.appendChild(sendBtn);
  panel.appendChild(composer);

  // CR-060: 녹음 오버레이 (recording 상태에서만 표시)
  const recordingOverlay = el("div", "recording-overlay hidden");
  recordingOverlay.setAttribute("role", "status");
  recordingOverlay.setAttribute("aria-live", "polite");
  const wave = el("span", "rec-wave", "▁▃▅▇▅▃▁");
  const timer = el("span", "rec-timer", "0:00");
  const cancelRec = el("button", "rec-cancel", "취소");
  cancelRec.type = "button";
  cancelRec.setAttribute("aria-label", "녹음 취소");
  const stopRec = el("button", "rec-stop", "■ 정지");
  stopRec.type = "button";
  stopRec.setAttribute("aria-label", "녹음 정지 후 변환");
  recordingOverlay.appendChild(wave);
  recordingOverlay.appendChild(timer);
  recordingOverlay.appendChild(cancelRec);
  recordingOverlay.appendChild(stopRec);
  panel.appendChild(recordingOverlay);

  // CR-061: 드래그앤드롭 오버레이 (panel 전체 범위)
  let dragCounter = 0;
  let dropOverlay: HTMLDivElement | null = null;
  panel.addEventListener("dragenter", (e) => {
    if (!hasFile(e)) return;
    e.preventDefault();
    dragCounter += 1;
    if (!dropOverlay) {
      dropOverlay = el("div", "drop-overlay", "파일을 놓아 첨부하세요");
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

  function hasFile(e: DragEvent): boolean {
    return !!e.dataTransfer && Array.from(e.dataTransfer.types ?? []).includes("Files");
  }

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
  const attachments = new AttachmentClient(tokens);

  // ── CR-061: 첨부 로직 ─────────────────────────
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
    for (const draft of state.attachments) {
      const chip = el("div", `chip ${draft.status}`);
      // 썸네일
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
      // 라벨
      const label = el("span", "chip-label");
      const pages = draft.serverMeta?.pages;
      const pageSuffix = pages ? ` · ${pages}p` : "";
      label.textContent = draft.status === "error"
        ? `${draft.file.name} — ${draft.error ?? "실패"}`
        : `${draft.file.name}${pageSuffix}`;
      label.title = label.textContent ?? "";
      chip.appendChild(label);
      // 진행/제거
      if (draft.status === "uploading") {
        chip.appendChild(el("span", "chip-spinner"));
      } else {
        const rm = el("button", "chip-remove", "×");
        rm.type = "button";
        rm.setAttribute("aria-label", "첨부 제거");
        rm.addEventListener("click", () => void removeAttachment(draft.localId));
        chip.appendChild(rm);
      }
      attachmentsEl.appendChild(chip);
    }
    updateSendDisabled();
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
      appendMessage("error", err);
      return;
    }
    const draft: AttachmentDraft = {
      localId: genLocalId(),
      file,
      status: "uploading",
      previewDataUrl: await readThumb(file),
    };
    state.attachments.push(draft);
    renderChips();

    try {
      const meta = await attachments.upload({
        baseUrl: options.baseUrl,
        sessionId: state.sessionId,
        file,
      });
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
    const idx = state.attachments.findIndex((a) => a.localId === localId);
    if (idx < 0) return;
    const draft = state.attachments[idx];
    state.attachments.splice(idx, 1);
    renderChips();
    // 서버에도 등록된 경우 best-effort 삭제
    if (draft.serverMeta) {
      try {
        await attachments.delete({
          baseUrl: options.baseUrl,
          sessionId: state.sessionId,
          attachmentId: draft.serverMeta.attachment_id,
        });
      } catch {
        // 실패는 무시 — GC 가 정리
      }
    }
  }

  // ── CR-060: STT 로직 ─────────────────────────
  const stt = new SttClient(tokens);
  let recorder: MediaRecorder | null = null;
  let recordedChunks: Blob[] = [];
  let micStream: MediaStream | null = null;
  let recStartedAt = 0;
  let recTickTimer: ReturnType<typeof setInterval> | null = null;
  let recAutoStopTimer: ReturnType<typeof setTimeout> | null = null;
  let recState: SttRecordingState = "idle";
  const MAX_RECORD_MS = 60_000; // 서버와 동일 기본값. 초과 시 서버 400.

  function sttSupported(): boolean {
    return (
      typeof window !== "undefined"
      && !!window.isSecureContext
      && !!navigator.mediaDevices?.getUserMedia
      && typeof MediaRecorder !== "undefined"
    );
  }

  function pickMimeType(): string | undefined {
    const candidates = [
      "audio/webm;codecs=opus",
      "audio/webm",
      "audio/mp4",
    ];
    for (const m of candidates) {
      try {
        if (MediaRecorder.isTypeSupported(m)) return m;
      } catch {
        // ignore
      }
    }
    return undefined;
  }

  function setRecState(next: SttRecordingState): void {
    recState = next;
    micBtn.setAttribute("aria-pressed", next === "recording" ? "true" : "false");
    micBtn.setAttribute(
      "aria-label",
      next === "recording" ? "녹음 정지" : "음성 입력 시작",
    );
    micBtn.textContent = next === "recording" ? "⏹" : "🎤";
    if (next === "recording") {
      recordingOverlay.classList.remove("hidden");
    } else {
      recordingOverlay.classList.add("hidden");
    }
  }

  function formatTimer(ms: number): string {
    const sec = Math.floor(ms / 1000);
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${s.toString().padStart(2, "0")}`;
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
      appendMessage("error", "이 브라우저·환경에서는 음성 입력을 사용할 수 없습니다 (HTTPS 필수)");
      return;
    }
    setRecState("requesting-permission");
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      setRecState("error");
      appendMessage("error", "마이크 권한이 필요합니다. 브라우저 주소창에서 🔒 아이콘을 눌러 허용해주세요");
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
      appendMessage("error", "이 브라우저의 MediaRecorder 포맷이 서버와 호환되지 않습니다");
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
      const elapsed = Date.now() - recStartedAt;
      timer.textContent = formatTimer(elapsed);
    }, 250);
    recAutoStopTimer = setTimeout(() => {
      if (recState === "recording") stopRecordingAndSend();
    }, MAX_RECORD_MS);
  }

  function stopRecordingAndSend(): void {
    if (recState !== "recording") return;
    clearRecTimers();
    try { recorder?.stop(); } catch { /* ignore */ }
    setRecState("uploading");
  }

  function cancelRecording(): void {
    clearRecTimers();
    if (recorder && recorder.state !== "inactive") {
      // ondataavailable 는 발생하지만 finalize 에서 cancel 플래그로 무시
      recorder.onstop = null;
      try { recorder.stop(); } catch { /* ignore */ }
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
      const result = await stt.transcribe(
        { baseUrl: options.baseUrl, sessionId: state.sessionId, blob },
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

  function insertTextAtCursor(el: HTMLTextAreaElement, text: string): void {
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? el.value.length;
    const before = el.value.slice(0, start);
    const after = el.value.slice(end);
    const sep = before.length > 0 && !/\s$/.test(before) ? " " : "";
    const insert = sep + text;
    el.value = before + insert + after;
    const caret = (before + insert).length;
    el.setSelectionRange(caret, caret);
  }

  micBtn.addEventListener("click", () => {
    if (recState === "idle") void startRecording();
    else if (recState === "recording") stopRecordingAndSend();
  });
  stopRec.addEventListener("click", () => stopRecordingAndSend());
  cancelRec.addEventListener("click", () => cancelRecording());
  // 키보드 접근성: Escape 로 녹음 취소
  panel.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && recState === "recording") {
      e.stopPropagation();
      cancelRecording();
    }
  });

  // 최초 토큰 로드 후 마이크 버튼 가시성 결정
  (async () => {
    try {
      await tokens.ensureLoaded();
      if (sttSupported() && tokens.hasScope("chat:stt")) {
        micBtn.hidden = false;
      }
    } catch {
      // 토큰 로드 실패는 기존 경로에서 처리
    }
  })();

  function updateSendDisabled(): void {
    const hasUploading = state.attachments.some((a) => a.status === "uploading");
    const hasText = textarea.value.trim().length > 0;
    const hasReady = state.attachments.some((a) => a.status === "ready");
    if (hasUploading || (!hasText && !hasReady)) {
      sendBtn.setAttribute("disabled", "true");
    } else {
      sendBtn.removeAttribute("disabled");
    }
  }

  async function sendMessage(text: string): Promise<void> {
    const trimmed = text.trim();
    // CR-061: 첨부만 있고 텍스트가 비었어도 전송 허용
    const readyAtts = state.attachments.filter((a) => a.status === "ready" && a.serverMeta);
    if (!trimmed && readyAtts.length === 0) return;

    // 사용자 표시: 텍스트 + 첨부 파일명
    const userDisplay = [
      trimmed,
      ...readyAtts.map((a) => `📎 ${a.file.name}`),
    ].filter((s) => s.length > 0).join("\n");
    appendMessage("user", userDisplay);
    resetAssistantBlock();
    sendBtn.setAttribute("disabled", "true");

    const attachmentPayload = readyAtts.map((a) => ({
      attachmentId: a.serverMeta!.attachment_id,
      mediaType: a.serverMeta!.media_type,
    }));
    // 전송 직후 첨부 비움 (서버는 TTL 까지 유지하지만 화면에선 초기화)
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
          attachments: attachmentPayload,
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
      // chat-client 가 housekeeping abort 는 silent return 하지만, 안전망으로 한 번 더 필터.
      const err = e as Error;
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
    // CR-080: IME 조합 중인 Enter 무시 — 한글/일본어/중국어 입력기에서
    // 조합 종료용 Enter 가 keydown 으로도 발화하여 더블 송신 발생.
    // isComposing(W3C) + keyCode 229(legacy WebKit/Gecko) 양쪽 체크.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    if ((e as any).isComposing || e.keyCode === 229) return;
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void sendMessage(textarea.value);
      textarea.value = "";
      updateSendDisabled();
    }
  });
  textarea.addEventListener("input", updateSendDisabled);
  // CR-061: 클립보드 이미지 붙여넣기 (Ctrl+V)
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

  // 초기 버튼 상태
  updateSendDisabled();

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
      // CR-060: 녹음 중이면 마이크/리소스 정리
      cancelRecording();
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
