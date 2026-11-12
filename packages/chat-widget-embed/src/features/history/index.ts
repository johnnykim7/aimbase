import type { AnswerView, LeftPaneHandle, WidgetContext, WidgetFeature } from "../../frame/context";
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
 */

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

    // 새 대화의 첫 질문에만 싣는다. 이미 있는 대화면 서버가 무시한다(T3-2 대화 요청 칸).
    ctx.chat.addRequestFields(() =>
      this.enabled && this.current.projectId && !this.current.saved
        ? { chat_project_id: this.current.projectId }
        : {},
    );

    ctx.ui.moreMenu.addItem({
      id: "history-move",
      order: 30,
      group: 0,
      icon: svgIcon(FOLDER_INPUT, 16),
      label: () => this.t().moveToProject,
      visible: () => this.enabled && this.current.saved,
      onSelect: () => void this.moveSession(this.currentRow()),
    });
    ctx.ui.plusMenu.addItem({
      id: "history-files",
      order: 20,
      group: 0,
      icon: svgIcon(FOLDER, 16),
      label: () => this.t().fromProjectFiles,
      visible: () => this.enabled && this.currentFileCount() > 0,
      onSelect: () => this.pickFile(),
    });
  }

  private onSettings(): void {
    this.enabled = historySettings(this.ctx)?.history_enabled === true;
    if (!this.enabled) {
      this.handle?.close();
      this.ctx.ui.header.setTitleTag(null);
      return;
    }
    if (!this.pane) this.mountPane();
    this.updateTitleTag();
    if (!this.restoreTried) {
      this.restoreTried = true;
      void this.restore();
    }
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
    });
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
    // 멈춘 응답도 서버에 남으므로 목록에 올린다. 실패한 응답만 건드리지 않는다.
    if (a.sessionId !== this.current.sessionId || (a.error && !a.stopped)) return;
    // 서버는 제목이 생기기 전까지 첫 질문을 제목으로 둔다. 목록도 같은 글을 보인다.
    if (!this.current.saved && !this.current.title && a.question.trim()) this.current.title = a.question.trim();
    this.current.saved = true;
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
