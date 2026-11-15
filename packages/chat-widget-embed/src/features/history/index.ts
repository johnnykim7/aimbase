import type { AnswerView, LeftPaneHandle, QuestionView, WidgetContext, WidgetFeature } from "../../frame/context";
import { svgIcon } from "../../icons";
import { HistoryApi, historySettings, type MessagesResponse, type ProjectDetail, type SessionRow } from "./api";
import { toView } from "./convert";
import { type DialogEnv, filesDialog, moveDialog, projectDialog } from "./dialogs";
import { confirmDialog } from "./dom";
import { FOLDER, FOLDER_INPUT } from "./icons";
import { HistoryPane, type PaneHost } from "./pane";
import { S, type Texts } from "./strings";
import { CSS } from "./styles";

/**
 * 지난 대화 목록과 프로젝트 묶기(CR-178). 왼쪽 칸 목록, 더 보기 「프로젝트로 옮기기」,
 * + 메뉴 「프로젝트 자료에서」, 머리줄 프로젝트 표시를 단다. 대화 기록과 지시문 적용은 서버가 한다.
 * 위젯 설정 조회의 history_enabled 가 참일 때만 보인다(요청자를 알 수 없으면 서버가 거짓을 준다).
 *
 * CR-194: 붙이는 앱이 화면 키를 주면 목록을 그 화면의 대화로 거르고, 위젯이 뜰 때 BIZ-279 대로 대화를 연다(메뉴 화면).
 * 토큰에 함께 보기 키가 있으면 그 건의 대화 하나를 열고 목록과 새 대화를 숨긴다(건 화면, BIZ-280).
 */

/** BIZ-279 탭 기억. 탭을 닫으면 사라지도록 sessionStorage 에 화면 키마다 둔다. */
const SCREEN_MEMORY_PREFIX = "aimbase-chat:screen-session:";
const REWIND_REJECTED = "rewind_rejected:shared";

function recall(screenKey: string): string | null {
  try {
    return sessionStorage.getItem(SCREEN_MEMORY_PREFIX + screenKey);
  } catch {
    return null;
  }
}

function remember(screenKey: string, sessionId: string): void {
  try {
    sessionStorage.setItem(SCREEN_MEMORY_PREFIX + screenKey, sessionId);
  } catch {
    // 저장소를 쓸 수 없는 브라우저면 다음에 가장 최근 대화를 연다.
  }
}

interface Current {
  sessionId: string;
  projectId: string | null;
  /** 서버에 저장된 대화. 첫 응답이 끝났거나 지난 대화를 열었을 때 */
  saved: boolean;
  title: string | null;
}

class History {
  private readonly api: HistoryApi;
  private enabled = false;
  private pane: HistoryPane | null = null;
  private handle: LeftPaneHandle | null = null;
  private paneBody: HTMLElement | null = null;
  private current: Current;
  /** 「이 프로젝트에서 새 대화」가 새 대화 알림을 받을 때까지 들고 있는 프로젝트 */
  private pendingProject: string | null | undefined;
  private answered = false;
  private restoreTried = false;
  /** CR-194: 함께 보는 대화(건 화면) */
  private shared = false;
  private panelOpen = false;

  constructor(private readonly ctx: WidgetContext) {
    this.api = new HistoryApi(ctx);
    this.current = { sessionId: ctx.sessionId(), projectId: null, saved: false, title: null };
  }

  private t(): Texts {
    return this.ctx.strings(S);
  }

  install(): void {
    const ctx = this.ctx;
    ctx.on("settingsLoaded", () => this.onSettings());
    ctx.on("newConversation", (e) => {
      this.current = { sessionId: e.sessionId, projectId: this.pendingProject ?? null, saved: false, title: null };
      this.updateTitleTag();
      this.pane?.render();
    });
    ctx.on("answerStart", () => {
      this.answered = true;
    });
    ctx.on("answerDone", (a) => this.onAnswerDone(a));
    ctx.on("titleChange", (e) => {
      if (e.sessionId === this.current.sessionId) this.current.title = e.title;
      this.pane?.setTitle(e.sessionId, e.title);
    });
    ctx.on("localeChange", () => this.pane?.render());
    ctx.on("destroy", () => this.pane?.destroy());
    ctx.on("optionsChange", () => this.applyList());
    ctx.on("panelToggle", (e) => {
      this.panelOpen = e.open;
      if (e.open) this.openDocked();
    });

    // 새 대화의 첫 질문에만 싣는다. 이미 있는 대화면 서버가 무시한다(T3-2 대화 요청 칸, BIZ-277).
    ctx.chat.addRequestFields(() => {
      if (this.current.saved) return {};
      const fields: Record<string, unknown> = {};
      if (this.enabled && this.current.projectId) fields.chat_project_id = this.current.projectId;
      const screenKey = ctx.screenKey();
      if (screenKey && !this.shared) fields.screen_key = screenKey;
      return fields;
    });
    ctx.ui.addErrorTexts({ [REWIND_REJECTED]: () => this.t().rewindRejected });
    ctx.ui.question.addDecorator((q) => this.askerLabel(q));

    ctx.ui.moreMenu.addItem({
      id: "history-move",
      order: 30,
      group: 0,
      icon: svgIcon(FOLDER_INPUT, 16),
      label: () => this.t().moveToProject,
      visible: () => this.enabled && !this.shared && this.current.saved,
      onSelect: () => void this.moveSession(this.currentRow()),
    });
    ctx.ui.plusMenu.addItem({
      id: "history-files",
      order: 20,
      group: 0,
      icon: svgIcon(FOLDER, 16),
      label: () => this.t().fromProjectFiles,
      visible: () => this.enabled && !this.shared && this.currentFileCount() > 0,
      onSelect: () => this.pickFile(),
    });
  }

  private onSettings(): void {
    const s = historySettings(this.ctx);
    this.enabled = s?.history_enabled === true;
    if (s?.shared_available === true) {
      if (!this.shared) this.enterShared(s.user_name ?? null);
      return;
    }
    if (!this.enabled) {
      this.handle?.close();
      this.ctx.ui.header.setTitleTag(null);
      return;
    }
    this.applyList();
    this.updateTitleTag();
    if (!this.restoreTried) {
      this.restoreTried = true;
      const screenKey = this.ctx.screenKey();
      void (screenKey ? this.restoreScreen(screenKey) : this.restore());
    }
  }

  /** 대화 목록은 서버가 허용하고, 함께 보는 대화가 아니고, 붙이는 앱이 끄지 않았을 때 보인다(CR-194 4.2 1번, 3번). */
  private applyList(): void {
    const on = this.enabled && !this.shared && this.ctx.historyList();
    if (on && !this.pane) this.mountPane();
    this.handle?.setAvailable(on);
    if (on && this.panelOpen) this.openDocked();
  }

  private mountPane(): void {
    const pane = new HistoryPane(this.paneHost());
    this.pane = pane;
    this.handle = this.ctx.ui.leftPane.mount({
      label: () => this.t().paneLabel,
      render: (body) => {
        this.paneBody = body;
        pane.mount(body);
      },
      dock: this.ctx.screenKey() !== null,
    });
  }

  /** 메뉴 화면은 창을 열 때 목록을 대화 옆에 편다(T3-3). 붙일 수 없는 폭이면 열지 않는다. */
  private openDocked(): void {
    if (this.handle?.docks()) this.handle.open();
  }

  /** 건 화면: 그 건의 대화 하나를 열고 목록, 새 대화, 대화 옮기기를 숨긴다(BIZ-280). */
  private enterShared(userName: string | null): void {
    const ctx = this.ctx;
    this.shared = true;
    this.handle?.setAvailable(false);
    ctx.ui.header.setTitleTag(null);
    ctx.ui.header.setNewConversationVisible(false);
    ctx.ui.header.setNotice(() => this.t().sharedNotice);
    const self = ctx.api.userRef();
    if (self) ctx.chat.setAsker({ userRef: self, userName: userName ?? undefined });
    ctx.chat.setRewindGuard((q) => q.last && self !== null && q.asker?.userRef === self);
    // 첫 질문이 그 건의 대화로 가도록 대화를 열 때까지 기다리게 한다.
    ctx.chat.waitBeforeSend(this.openShared());
  }

  private async openShared(): Promise<void> {
    try {
      const opened = await this.api.openShared();
      const r = await this.api.messages(opened.session_id);
      if (this.answered) return;
      await this.show(r);
    } catch {
      if (this.answered) return;
      this.ctx.ui.notice(this.t().loadFailed, { label: this.t().retry, run: () => void this.openShared() });
    }
  }

  private askerLabel(q: QuestionView): HTMLElement | null {
    if (!this.shared || !q.asker) return null;
    const name = q.asker.userName || q.asker.userRef;
    const mine = q.asker.userRef === this.ctx.api.userRef();
    const label = document.createElement("div");
    label.className = mine ? "asker me" : "asker";
    label.textContent = mine ? this.t().askerMe(name) : name;
    return label;
  }

  /**
   * BIZ-279: 이 탭이 그 화면에서 마지막으로 보던 대화, 그것을 열 수 없으면 그 화면의 가장 최근 대화, 그것도 없으면
   * 첫 화면 그대로다. 그 사이에 질문했거나 다른 대화로 옮겼으면 그만둔다.
   */
  private async restoreScreen(screenKey: string): Promise<void> {
    if (this.answered || this.ctx.isBusy()) return;
    const startId = this.ctx.sessionId();
    const moved = () => this.answered || this.ctx.isBusy() || this.ctx.sessionId() !== startId;
    try {
      const remembered = recall(screenKey);
      if (remembered) {
        const r = await this.api.messages(remembered).catch(() => null);
        if (moved()) return;
        if (r && r.turns.length > 0) {
          await this.show(r);
          return;
        }
      }
      const rows = await this.api.sessions({ screenKey, page: 0, size: 1 });
      if (moved() || rows.length === 0) return;
      const r = await this.api.messages(rows[0].session_id);
      if (moved() || r.turns.length === 0) return;
      await this.show(r);
    } catch {
      if (moved()) return;
      this.ctx.ui.notice(this.t().loadFailed, { label: this.t().retry, run: () => void this.restoreScreen(screenKey) });
    }
  }

  private rememberCurrent(): void {
    const screenKey = this.ctx.screenKey();
    if (screenKey && !this.shared && this.current.saved) remember(screenKey, this.current.sessionId);
  }

  /** 다시 생성이나 질문 수정이 BIZ-280 으로 거절되면 그 사이 다른 사람이 질문한 것이라 대화를 다시 읽어 그린다. */
  private async reloadShared(): Promise<void> {
    const id = this.current.sessionId;
    try {
      const r = await this.api.messages(id);
      if (this.ctx.isBusy() || this.ctx.sessionId() !== id) return;
      await this.show(r);
      this.ctx.ui.notice(this.t().rewindRejected);
    } catch {
      // 실패 알림은 위젯이 이미 보였다.
    }
  }

  private paneHost(): PaneHost {
    return {
      ctx: this.ctx,
      api: this.api,
      t: () => this.t(),
      currentSessionId: () => this.current.sessionId,
      openSession: (id) => this.openSession(id),
      newChat: (projectId) => void this.newChat(projectId),
      editProject: (projectId) => this.editProject(projectId),
      deleteProject: (p) => void this.deleteProject(p),
      moveSession: (row) => void this.moveSession(row),
      deleteSession: (row) => void this.deleteSession(row),
      renamed: (row) => this.renamed(row),
      projectsChanged: () => this.updateTitleTag(),
    };
  }

  private env(): DialogEnv {
    const s = historySettings(this.ctx);
    return {
      ctx: this.ctx,
      api: this.api,
      t: this.t(),
      instructionsMax: s?.project_instructions_max_chars,
      filesMax: s?.project_files_max_count,
    };
  }

  private currentRow(): SessionRow {
    return {
      session_id: this.current.sessionId,
      title: this.current.title,
      chat_project_id: this.current.projectId,
      updated_at: new Date().toISOString(),
    };
  }

  private currentFileCount(): number {
    const pid = this.current.projectId;
    return pid ? (this.pane?.project(pid)?.file_count ?? 0) : 0;
  }

  /** 겹쳐 열린 칸만 닫는다. 옆에 붙은 칸은 그대로 둔다(틀 사용법 3.2). */
  private closeIfOverlay(): void {
    if (this.paneBody?.parentElement?.classList.contains("overlay")) this.handle?.close();
  }

  private updateTitleTag(): void {
    const pid = this.current.projectId;
    if (!this.enabled || !pid) {
      this.ctx.ui.header.setTitleTag(null);
      return;
    }
    const p = this.pane?.project(pid);
    if (!p) {
      this.ctx.ui.header.setTitleTag(null);
      this.pane?.loadProjectsOnce();
      return;
    }
    this.ctx.ui.header.setTitleTag({
      text: p.name,
      icon: svgIcon(FOLDER, 14),
      onClick: () => {
        this.handle?.open();
        this.pane?.revealProject(pid);
      },
    });
  }

  /** 위젯이 뜰 때 붙이는 앱이 준 대화가 요청자의 것으로 있으면 보인다(4.2 13번). 없으면 첫 화면 그대로다. */
  private async restore(): Promise<void> {
    if (this.answered || this.ctx.isBusy()) return;
    const id = this.ctx.sessionId();
    let r: MessagesResponse;
    try {
      r = await this.api.messages(id);
    } catch {
      return;
    }
    if (this.answered || this.ctx.isBusy() || this.ctx.sessionId() !== id || r.turns.length === 0) return;
    await this.show(r);
  }

  private async show(r: MessagesResponse): Promise<void> {
    await this.ctx.chat.open(toView(r, this.t().truncated));
    this.current = { sessionId: r.session_id, projectId: r.chat_project_id, saved: true, title: r.title };
    this.rememberCurrent();
    this.updateTitleTag();
    this.pane?.render();
  }

  private async openSession(id: string): Promise<boolean> {
    if (id === this.current.sessionId && this.current.saved) {
      this.closeIfOverlay();
      return true;
    }
    try {
      if (this.ctx.isBusy()) await this.ctx.chat.abort();
      await this.show(await this.api.messages(id));
      this.closeIfOverlay();
      return true;
    } catch {
      return false;
    }
  }

  private async newChat(projectId: string | null): Promise<void> {
    this.pendingProject = projectId;
    try {
      await this.ctx.chat.newConversation();
    } finally {
      this.pendingProject = undefined;
    }
    this.closeIfOverlay();
  }

  private onAnswerDone(a: AnswerView): void {
    if (this.shared && a.errorCode === REWIND_REJECTED && a.sessionId === this.current.sessionId) {
      void this.reloadShared();
      return;
    }
    // 멈춘 응답도 서버에 남으므로 목록에 올린다. 실패한 응답만 건드리지 않는다.
    if (a.sessionId !== this.current.sessionId || (a.error && !a.stopped)) return;
    // 서버는 제목이 생기기 전까지 첫 질문을 제목으로 둔다. 목록도 같은 글을 보인다.
    if (!this.current.saved && !this.current.title && a.question.trim()) this.current.title = a.question.trim();
    this.current.saved = true;
    this.rememberCurrent();
    this.pane?.touch({ ...this.currentRow(), updated_at: a.at.toISOString() });
    if (this.current.projectId) void this.pane?.loadProjects();
  }

  private renamed(row: SessionRow): void {
    if (row.session_id !== this.current.sessionId || !row.title) return;
    this.current.title = row.title;
    this.ctx.chat.setTitle(row.title);
  }

  private async moveSession(row: SessionRow): Promise<void> {
    const projects = this.pane ? await this.pane.ensureProjects() : null;
    if (!projects) {
      this.ctx.ui.toast(this.t().loadFailed);
      return;
    }
    moveDialog(this.env(), {
      row,
      projects,
      onPick: (projectId) => void this.move(row, projectId),
      onNewProject: () => this.editProject(null, (p) => void this.move(row, p.id)),
    });
  }

  private async move(row: SessionRow, projectId: string | null): Promise<void> {
    try {
      await this.api.moveSession(row.session_id, projectId);
    } catch {
      this.ctx.ui.toast(this.t().saveFailed);
      return;
    }
    if (row.session_id === this.current.sessionId) {
      this.current.projectId = projectId;
      this.updateTitleTag();
    }
    this.pane?.reload();
  }

  private editProject(projectId: string | null, afterCreate?: (p: ProjectDetail) => void): void {
    projectDialog(this.env(), {
      projectId,
      onSaved: (p, created) => {
        if (created && afterCreate) afterCreate(p);
        else void this.pane?.loadProjects();
      },
      onFilesChanged: () => void this.pane?.loadProjects(),
      onDelete: (p) => this.deleteProject(p),
    });
  }

  private async deleteProject(p: { id: string }): Promise<boolean> {
    const t = this.t();
    const ok = await confirmDialog(this.ctx, {
      message: t.confirmDeleteProject,
      ok: t.delete,
      cancel: t.cancel,
      close: t.close,
    });
    if (!ok) return false;
    try {
      await this.api.deleteProject(p.id);
    } catch {
      this.ctx.ui.toast(t.deleteFailed);
      return false;
    }
    if (this.current.projectId === p.id) {
      this.current.projectId = null;
      this.updateTitleTag();
    }
    this.pane?.reload();
    return true;
  }

  private async deleteSession(row: SessionRow): Promise<void> {
    const t = this.t();
    const ok = await confirmDialog(this.ctx, {
      message: t.confirmDeleteChat,
      ok: t.delete,
      cancel: t.cancel,
      close: t.close,
    });
    if (!ok) return;
    try {
      await this.api.deleteSession(row.session_id);
    } catch {
      this.ctx.ui.toast(t.deleteFailed);
      return;
    }
    this.pane?.remove(row.session_id);
    if (row.chat_project_id) void this.pane?.loadProjects();
    if (row.session_id === this.current.sessionId) await this.newChat(null);
  }

  private pickFile(): void {
    const pid = this.current.projectId;
    if (!pid) return;
    filesDialog(this.env(), {
      projectId: pid,
      onPick: async (f) => {
        try {
          this.ctx.chat.attachUploaded(await this.api.attachFile(pid, f.file_id, this.ctx.sessionId()));
          return true;
        } catch {
          this.ctx.ui.toast(this.t().attachFailed);
          return false;
        }
      },
    });
  }
}

export const history: WidgetFeature = {
  id: "history",
  css: CSS,
  install: (ctx) => new History(ctx).install(),
};
