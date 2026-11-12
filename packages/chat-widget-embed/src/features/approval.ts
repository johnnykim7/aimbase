import type { WidgetContext, WidgetFeature, WorkflowApprovalEvent, WorkflowDoneEvent } from "../frame/context";
import { defineStrings } from "../frame/i18n";

/**
 * 승인 요청 카드(CR-185, PRD-560, PRD-561). 대화 중 도구 실행 전 승인과 워크플로우 승인을 위젯 안에서 정한다.
 * 도구 카드는 대화 스트림의 approval_required, approval_resolved 를 받고, 워크플로우 카드는 틀의 workflowApproval 로 뜨고
 * workflowDone 으로 닫힌다.
 * 기다림과 마감은 서버가 정한다. 카드는 보여 주고 결정을 보낼 뿐이다(T3-2 대화 승인 결정).
 */

const S = defineStrings({
  ko: {
    toolTitle: "이 작업을 실행할까요?",
    expand: "펼치기",
    timeLeft: (m: number, s: number) =>
      `${m > 0 ? `${m}분 ${s}초` : `${s}초`} 안에 정하지 않으면 실행하지 않습니다`,
    approve: "승인",
    reject: "거절",
    approvedAt: (time: string) => `승인했습니다 ${time}`,
    rejectedAt: (time: string) => `거절했습니다 ${time}`,
    timedOut: "시간이 지나 실행하지 않았습니다",
    stopped: "응답을 중지해 실행하지 않았습니다",
    sendFailed: "승인 결과를 보내지 못했습니다",
    workflowTitle: "승인이 필요합니다",
    approvers: (names: string) => `승인자: ${names}`,
    deadlineBy: (time: string) => `${time} 까지 정하지 않으면 실패합니다`,
    approversOnly: "승인자만 정할 수 있습니다",
    decidedByOther: "이미 다른 사람이 정했습니다",
    deadlinePassed: "마감이 지나 실패했습니다",
  },
  en: {
    toolTitle: "Run this action?",
    expand: "Expand",
    timeLeft: (m: number, s: number) => `It won't run unless you decide within ${m > 0 ? `${m}m ${s}s` : `${s}s`}`,
    approve: "Approve",
    reject: "Reject",
    approvedAt: (time: string) => `Approved ${time}`,
    rejectedAt: (time: string) => `Rejected ${time}`,
    timedOut: "Not run because time ran out",
    stopped: "Not run because the response was stopped",
    sendFailed: "Couldn't send your decision",
    workflowTitle: "Approval required",
    approvers: (names: string) => `Approvers: ${names}`,
    deadlineBy: (time: string) => `It fails unless decided by ${time}`,
    approversOnly: "Only approvers can decide",
    decidedByOther: "Someone else already decided",
    deadlinePassed: "Failed because the deadline passed",
  },
});

type Texts = (typeof S)["ko"];

const EVENT_REQUIRED = "approval_required";
const EVENT_RESOLVED = "approval_resolved";
const SCOPE_WORKFLOW_APPROVE = "workflow:approve";
/** 감시기가 마감 지난 run 에 적는 error.reason(T3-2 워크플로우 승인 시한). */
const REASON_APPROVAL_TIMEOUT = "approval_timeout";
const INPUT_SUMMARY_LINES = 3;
const INPUT_LINE_MAX_CHARS = 120;
const TICK_MS = 1000;

/** 서버 승인 상태(T3-1 pending_approvals.status). */
const STATUS = { approved: "approved", rejected: "rejected", timeout: "timeout", cancelled: "cancelled" } as const;
type Status = (typeof STATUS)[keyof typeof STATUS];

/** 대화 스트림 approval_required 의 data(T3-2 대화 스트림 이벤트). */
interface RequiredData {
  approval_id: string;
  tool_use_id?: string;
  tool_name: string;
  description?: string;
  input?: Record<string, unknown>;
  message?: string;
  expires_at?: string;
  timeout_seconds?: number;
}

interface ResolvedData {
  approval_id: string;
  status: string;
  decided_at?: string;
}

interface DecisionResponse {
  approval_id: string;
  status: string;
  decided_by?: string;
  decided_at?: string;
}

function asRequired(data: unknown): RequiredData | null {
  const d = data as Partial<RequiredData> | null;
  return d && typeof d.approval_id === "string" && typeof d.tool_name === "string" ? (d as RequiredData) : null;
}

function asResolved(data: unknown): ResolvedData | null {
  const d = data as Partial<ResolvedData> | null;
  return d && typeof d.approval_id === "string" && typeof d.status === "string" ? (d as ResolvedData) : null;
}

function isStatus(s: string): s is Status {
  return (Object.values(STATUS) as string[]).includes(s);
}

function httpStatus(e: unknown): number | null {
  const s = (e as { status?: unknown } | null)?.status;
  return typeof s === "number" ? s : null;
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

function clock(at: Date): string {
  return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

function monthDayClock(at: Date): string {
  return `${at.getMonth() + 1}/${at.getDate()} ${clock(at)}`;
}

function parseDate(s: string | undefined): Date | null {
  if (!s) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

function valueText(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "string") return v;
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function button(label: string, cls: string, onClick: () => void): HTMLButtonElement {
  const b = el("button", cls, label);
  b.type = "button";
  b.addEventListener("click", (e) => {
    e.stopPropagation();
    onClick();
  });
  return b;
}

/** 정한 결과 한 줄. 카드가 닫힌 뒤 단추 자리에 남는다. */
type Outcome =
  | { kind: "decided"; approved: boolean; at: Date }
  | { kind: "timeout" }
  | { kind: "stopped" }
  | { kind: "other" }
  | { kind: "deadlinePassed" }
  | { kind: "approversOnly" }
  /** 붙이는 앱이 allowApproval: false 로 자기 결재 화면을 쓴다. 단추도 안내도 두지 않는다. */
  | { kind: "hostDecides" };

function outcomeText(t: Texts, o: Outcome): string {
  switch (o.kind) {
    case "decided":
      return o.approved ? t.approvedAt(clock(o.at)) : t.rejectedAt(clock(o.at));
    case "timeout":
      return t.timedOut;
    case "stopped":
      return t.stopped;
    case "other":
      return t.decidedByOther;
    case "deadlinePassed":
      return t.deadlinePassed;
    case "approversOnly":
      return t.approversOnly;
    case "hostDecides":
      return "";
  }
}

function outcomeOf(status: Status, decidedAt: string | undefined): Outcome {
  const at = parseDate(decidedAt) ?? new Date();
  switch (status) {
    case STATUS.approved:
      return { kind: "decided", approved: true, at };
    case STATUS.rejected:
      return { kind: "decided", approved: false, at };
    case STATUS.timeout:
      return { kind: "timeout" };
    case STATUS.cancelled:
      return { kind: "stopped" };
  }
}

/** 단추 줄, 보내지 못함 안내, 결과 줄. 두 카드가 같이 쓴다. */
function appendDecision(
  root: HTMLElement,
  t: Texts,
  state: { outcome: Outcome | null; sending: boolean; sendFailed: boolean },
  decide: (approve: boolean) => void,
): void {
  if (state.outcome) {
    if (state.outcome.kind === "hostDecides") return;
    const line = el("div", "apv-result", outcomeText(t, state.outcome));
    line.setAttribute("role", "status");
    root.append(line);
    return;
  }
  const actions = el("div", "apv-actions");
  const approve = button(t.approve, "apv-btn apv-btn--primary", () => decide(true));
  const reject = button(t.reject, "apv-btn", () => decide(false));
  approve.disabled = reject.disabled = state.sending;
  actions.append(approve, reject);
  root.append(actions);
  if (state.sendFailed) {
    const err = el("div", "apv-error", t.sendFailed);
    err.setAttribute("role", "alert");
    root.append(err);
  }
}

interface Card {
  readonly root: HTMLElement;
  render(): void;
  dispose(): void;
}

/** 대화 중 도구 실행 전 승인 카드(PRD-560). */
class ToolCard implements Card {
  readonly root = el("div", "apv-card");
  private readonly deadline: number;
  private timer: ReturnType<typeof setInterval> | null = null;
  private outcome: Outcome | null = null;
  private sending = false;
  private sendFailed = false;
  private inputOpen = false;

  constructor(
    private readonly ctx: WidgetContext,
    private readonly t: Texts,
    private readonly data: RequiredData,
  ) {
    // 위젯 쪽 시계가 서버와 어긋나도 남은 시간이 맞도록 받은 때부터 timeout_seconds 를 센다.
    const fromServer = parseDate(data.expires_at)?.getTime() ?? Date.now();
    this.deadline = typeof data.timeout_seconds === "number" ? Date.now() + data.timeout_seconds * 1000 : fromServer;
    this.root.setAttribute("role", "group");
    this.render();
    this.timer = setInterval(() => this.tick(), TICK_MS);
  }

  get waiting(): boolean {
    return this.outcome === null;
  }

  close(outcome: Outcome): void {
    if (this.outcome) return;
    this.outcome = outcome;
    this.stopTimer();
    this.render();
  }

  dispose(): void {
    this.stopTimer();
  }

  render(): void {
    const t = this.t;
    this.root.replaceChildren();
    this.root.setAttribute("aria-label", t.toolTitle);
    this.root.append(el("div", "apv-head", t.toolTitle));

    const tool = el("div", "apv-tool");
    tool.append(el("span", "apv-name", this.data.tool_name));
    const desc = this.data.description?.split("\n", 1)[0]?.trim();
    if (desc) tool.append(el("span", "apv-desc", desc));
    this.root.append(tool);

    this.renderInput();
    if (this.data.message) this.root.append(el("div", "apv-msg", this.data.message));
    if (!this.outcome) this.root.append(el("div", "apv-left", this.leftText()));
    appendDecision(this.root, t, { outcome: this.outcome, sending: this.sending, sendFailed: this.sendFailed }, (approve) => void this.decide(approve));
  }

  private renderInput(): void {
    const input = this.data.input ?? {};
    const entries = Object.entries(input);
    if (entries.length === 0) return;
    const box = el("div", "apv-input");
    if (this.inputOpen) {
      box.append(el("pre", "apv-raw", JSON.stringify(input, null, 2)));
      this.root.append(box);
      return;
    }
    for (const [k, v] of entries.slice(0, INPUT_SUMMARY_LINES)) {
      const text = `${k}: ${valueText(v)}`;
      const line = el("div", "apv-line", text.length > INPUT_LINE_MAX_CHARS ? `${text.slice(0, INPUT_LINE_MAX_CHARS)}...` : text);
      line.title = text;
      box.append(line);
    }
    box.append(
      button(this.t.expand, "apv-more", () => {
        this.inputOpen = true;
        this.render();
      }),
    );
    this.root.append(box);
  }

  private leftText(): string {
    const left = Math.max(0, Math.ceil((this.deadline - Date.now()) / 1000));
    return this.t.timeLeft(Math.floor(left / 60), left % 60);
  }

  private tick(): void {
    const left = this.root.querySelector(".apv-left");
    if (left) left.textContent = this.leftText();
  }

  private async decide(approve: boolean): Promise<void> {
    if (this.sending || this.outcome) return;
    this.sending = true;
    this.sendFailed = false;
    this.render();
    try {
      const res = await this.ctx.api.request<DecisionResponse>(
        "POST",
        `/api/v1/chat/approvals/${encodeURIComponent(this.data.approval_id)}`,
        { decision: approve ? "approve" : "reject" },
      );
      this.close(isStatus(res.status) ? outcomeOf(res.status, res.decided_at) : { kind: "other" });
    } catch (e) {
      // 409 는 관리자나 마감이 먼저 닫은 것이다. 서버가 같은 대화 스트림으로 approval_resolved 를 보내
      // 그 상태로 카드를 닫으므로 단추를 잠근 채 기다린다.
      if (httpStatus(e) === 409) return;
      this.sending = false;
      this.sendFailed = true;
      this.render();
    }
  }

  private stopTimer(): void {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
  }
}

/** 워크플로우 사람 확인 단계의 승인 카드(PRD-561). */
class WorkflowCard implements Card {
  readonly root = el("div", "apv-card");
  private outcome: Outcome | null = null;
  private sending = false;
  private sendFailed = false;

  constructor(
    private readonly ctx: WidgetContext,
    private readonly t: Texts,
    private readonly ev: WorkflowApprovalEvent,
    closedAs: Outcome | null,
  ) {
    this.outcome = closedAs;
    this.root.setAttribute("role", "group");
    this.render();
  }

  get runId(): string {
    return this.ev.runId;
  }

  /** 이 사용자가 정하지 않은 채 워크플로우가 끝났으면 끝난 까닭으로 닫는다. 보내는 중이면 그 응답이 닫는다. */
  finish(done: WorkflowDoneEvent): void {
    if (this.sending || this.outcome?.kind === "decided") return;
    this.outcome = done.code === REASON_APPROVAL_TIMEOUT ? { kind: "deadlinePassed" } : { kind: "other" };
    this.render();
  }

  dispose(): void {
    // 타이머나 구독을 두지 않는다.
  }

  render(): void {
    const t = this.t;
    this.root.replaceChildren();
    this.root.setAttribute("aria-label", t.workflowTitle);
    const head = el("div", "apv-head", t.workflowTitle);
    head.append(el("span", "apv-step", this.ev.stepId));
    this.root.append(head);
    if (this.ev.reason) this.root.append(el("div", "apv-msg", this.ev.reason));
    if (this.ev.approvers.length > 0) this.root.append(el("div", "apv-meta", t.approvers(this.ev.approvers.join(", "))));
    const deadline = parseDate(this.ev.timeoutAt);
    if (deadline) this.root.append(el("div", "apv-meta", t.deadlineBy(monthDayClock(deadline))));
    appendDecision(this.root, t, { outcome: this.outcome, sending: this.sending, sendFailed: this.sendFailed }, (approved) => void this.decide(approved));
  }

  private async decide(approved: boolean): Promise<void> {
    if (this.sending || this.outcome) return;
    this.sending = true;
    this.sendFailed = false;
    this.render();
    try {
      await this.ctx.api.request<unknown>("POST", `/api/v1/workflows/runs/${encodeURIComponent(this.ev.runId)}/approve`, {
        approved,
      });
      this.outcome = { kind: "decided", approved, at: new Date() };
    } catch (e) {
      const status = httpStatus(e);
      if (status === 403) this.outcome = { kind: "approversOnly" };
      else if (status === 409) this.outcome = { kind: "other" };
      else this.sendFailed = true;
    }
    this.sending = false;
    this.render();
  }
}

class Approval {
  private readonly t: Texts;
  private readonly toolCards = new Map<string, ToolCard>();
  private readonly workflowCards = new Map<string, WorkflowCard>();

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(S);
  }

  install(): void {
    const ctx = this.ctx;
    ctx.on("streamEvent", ({ name, data, mount }) => {
      if (name === EVENT_REQUIRED) this.onRequired(data, mount);
      else if (name === EVENT_RESOLVED) this.onResolved(data);
    });
    // 서버는 승인을 기다리는 동안 응답을 끝내지 않는다. 기다리는 카드가 남은 채 응답이 끝났으면 중지나 오류로 끝난 것이다.
    ctx.on("answerDone", () => {
      for (const card of this.toolCards.values()) if (card.waiting) card.close({ kind: "stopped" });
    });
    ctx.on("workflowApproval", (ev) => this.onWorkflow(ev));
    ctx.on("workflowDone", (ev) => {
      for (const card of this.workflowCards.values()) if (card.runId === ev.runId) card.finish(ev);
    });
    ctx.on("localeChange", () => this.renderAll());
    ctx.on("newConversation", () => this.clear());
    ctx.on("destroy", () => this.clear());
  }

  private onRequired(data: unknown, mount: (el: HTMLElement) => boolean): void {
    const d = asRequired(data);
    if (!d || this.toolCards.has(d.approval_id)) return;
    const card = new ToolCard(this.ctx, this.t, d);
    if (!mount(card.root)) {
      card.dispose();
      return;
    }
    this.toolCards.set(d.approval_id, card);
  }

  private onResolved(data: unknown): void {
    const d = asResolved(data);
    if (!d) return;
    this.toolCards.get(d.approval_id)?.close(isStatus(d.status) ? outcomeOf(d.status, d.decided_at) : { kind: "other" });
  }

  private onWorkflow(ev: WorkflowApprovalEvent): void {
    const key = `${ev.runId}/${ev.stepId}`;
    const prev = this.workflowCards.get(key);
    if (prev?.root.isConnected) return;
    const card = new WorkflowCard(this.ctx, this.t, ev, this.workflowClosedAs(ev));
    if (ev.mount(card.root)) this.workflowCards.set(key, card);
  }

  /**
   * 단추를 둘 수 없으면 처음부터 닫힌 줄로 띄운다. 승인자 목록이 있으면 서버가 토큰의 user_ref 로 대조하므로
   * user_ref 없는 토큰은 눌러도 403 이다.
   */
  private workflowClosedAs(ev: WorkflowApprovalEvent): Outcome | null {
    if (!this.ctx.allowApproval()) return { kind: "hostDecides" };
    const api = this.ctx.api;
    if (!api.hasScope(SCOPE_WORKFLOW_APPROVE)) return { kind: "approversOnly" };
    if (ev.approvers.length > 0 && !api.hasUserRef()) return { kind: "approversOnly" };
    return null;
  }

  private renderAll(): void {
    this.renderOrDrop(this.toolCards);
    this.renderOrDrop(this.workflowCards);
  }

  /** 새 대화나 지난 대화 열기로 화면에서 빠진 카드는 버린다. */
  private renderOrDrop<C extends Card>(map: Map<string, C>): void {
    for (const [id, card] of map) {
      if (card.root.isConnected) {
        card.render();
        continue;
      }
      card.dispose();
      map.delete(id);
    }
  }

  private clear(): void {
    for (const card of this.toolCards.values()) card.dispose();
    for (const card of this.workflowCards.values()) card.dispose();
    this.toolCards.clear();
    this.workflowCards.clear();
  }
}

const CSS = `
.apv-card { margin: 8px 0; padding: 10px 12px; border: 1px solid var(--aimbase-border-strong); border-radius: 8px; background: var(--aimbase-bg); color: var(--aimbase-fg); }
.apv-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; font-weight: 600; }
.apv-step { font-weight: 400; color: var(--aimbase-muted); font-size: .9em; }
.apv-tool { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; font-size: .9em; }
.apv-name { font-weight: 600; }
.apv-desc { color: var(--aimbase-muted); }
.apv-input { margin-top: 6px; font-size: .85em; }
.apv-line { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.apv-more { margin-top: 2px; padding: 0; border: 0; background: none; color: var(--aimbase-primary); font: inherit; cursor: pointer; }
.apv-raw { max-height: 200px; overflow: auto; margin: 0; padding: 6px 8px; border-radius: 6px; background: var(--aimbase-chip-bg); white-space: pre-wrap; word-break: break-all; font-size: .9em; }
.apv-msg { margin-top: 6px; font-size: .9em; }
.apv-meta { margin-top: 2px; font-size: .85em; color: var(--aimbase-muted); }
.apv-left { margin-top: 6px; font-size: .85em; color: var(--aimbase-muted); }
.apv-actions { display: flex; gap: 8px; margin-top: 8px; }
.apv-btn { padding: 4px 14px; border: 1px solid var(--aimbase-border); border-radius: 6px; background: var(--aimbase-bg); color: var(--aimbase-fg); font: inherit; font-size: .9em; cursor: pointer; }
.apv-btn:hover:not(:disabled) { background: var(--aimbase-chip-hover); }
.apv-btn--primary { border-color: var(--aimbase-primary); background: var(--aimbase-primary); color: var(--aimbase-on-primary); }
.apv-btn--primary:hover:not(:disabled) { background: var(--aimbase-primary-hover); }
.apv-btn:disabled { opacity: .5; cursor: default; }
.apv-btn:focus-visible, .apv-more:focus-visible { outline: 2px solid var(--aimbase-primary); outline-offset: 2px; }
.apv-result { margin-top: 8px; font-size: .9em; color: var(--aimbase-muted); }
.apv-error { margin-top: 6px; font-size: .85em; color: var(--aimbase-danger); }
`;

export const approval: WidgetFeature = {
  id: "approval",
  css: CSS,
  install(ctx) {
    new Approval(ctx).install();
  },
};
