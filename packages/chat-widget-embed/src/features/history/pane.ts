import type { WidgetContext } from "../../frame/context";
import { icon, svgIcon } from "../../icons";
import { type HistoryApi, NO_PROJECT, PAGE_SIZE, type ProjectRow, type SessionRow } from "./api";
import { type DateGroup, dateGroup } from "./convert";
import { button, el, errorLine, iconButton, openMenu } from "./dom";
import { FOLDER, FOLDER_INPUT, FOLDER_PLUS, SEARCH, SETTINGS, TRASH } from "./icons";
import type { Texts } from "./strings";

/** 목록 칸이 화면 동작을 맡기는 쪽. 대화 열기, 옮기기, 삭제, 프로젝트 창은 기능 본체가 한다. */
export interface PaneHost {
  ctx: WidgetContext;
  api: HistoryApi;
  t(): Texts;
  currentSessionId(): string;
  /** 열었으면 참. 실패하면 그 줄 아래에 다시 시도를 보인다 */
  openSession(sessionId: string): Promise<boolean>;
  newChat(projectId: string | null): void;
  editProject(projectId: string | null): void;
  deleteProject(project: ProjectRow): void;
  moveSession(row: SessionRow): void;
  deleteSession(row: SessionRow): void;
  renamed(row: SessionRow): void;
  projectsChanged(): void;
}

interface ListState {
  rows: SessionRow[];
  /** 다음에 읽을 쪽(0부터) */
  page: number;
  done: boolean;
  loading: boolean;
  error: boolean;
  /** 목록을 비우면 하나 올라가 늦게 온 앞 응답을 버린다 */
  token: number;
  fetch: (page: number) => Promise<SessionRow[]>;
}

/** 검색 칸에 쓰다 멈춘 뒤 찾기까지 기다리는 시간. */
const SEARCH_DELAY_MS = 300;

function newList(fetch: (page: number) => Promise<SessionRow[]>): ListState {
  return { rows: [], page: 0, done: false, loading: false, error: false, token: 0, fetch };
}

function resetList(list: ListState): void {
  list.rows = [];
  list.page = 0;
  list.done = false;
  list.loading = false;
  list.error = false;
  list.token += 1;
}

function setLabel(b: HTMLButtonElement, iconHtml: string, label: string): void {
  b.innerHTML = iconHtml;
  b.append(document.createTextNode(label));
}

/** 왼쪽 칸 안(CR-178 4.1 2~6번). 틀은 칸을 한 번만 그리므로 처음 보일 때 목록을 읽는다. */
export class HistoryPane {
  private root: HTMLElement | null = null;
  private list!: HTMLElement;
  private newBtn!: HTMLButtonElement;
  private newProjectBtn!: HTMLButtonElement;
  private searchIcon!: HTMLElement;
  private searchInput!: HTMLInputElement;
  private searchClear!: HTMLButtonElement;

  private loaded = false;
  private projects: ProjectRow[] | null = null;
  private projectsError = false;
  private projectsTried = false;
  private projectsLoad: Promise<ProjectRow[] | null> | null = null;
  private readonly recent: ListState;
  private readonly byProject = new Map<string, ListState>();
  private readonly expanded = new Set<string>();
  private query = "";
  private readonly search: ListState;
  private searchTimer: ReturnType<typeof setTimeout> | undefined;
  private renaming: string | null = null;
  /** 이름 바꾸는 중 목록을 다시 그려도 쓰던 글이 남게 둔다 */
  private renameDraft = "";
  private renameFresh = false;
  private rerendering = false;
  private offRenameEscape: (() => void) | null = null;
  private openingId: string | null = null;
  private openErrorId: string | null = null;
  private readonly projectEls = new Map<string, HTMLElement>();
  private visObs: IntersectionObserver | null = null;
  private moreObs: IntersectionObserver | null = null;
  private closeMenu: (() => void) | null = null;

  constructor(private readonly host: PaneHost) {
    this.recent = newList((page) => host.api.sessions({ projectId: NO_PROJECT, page }));
    this.search = newList((page) => host.api.sessions({ query: this.query, page }));
  }

  mount(body: HTMLElement): void {
    const root = el("div", "hist");
    const top = el("div", "hist-top");
    this.newBtn = button("hist-new", "");
    this.newBtn.addEventListener("click", () => this.host.newChat(null));
    const sw = el("label", "hist-search");
    this.searchIcon = el("span", "hist-sicon");
    this.searchIcon.innerHTML = svgIcon(SEARCH, 15);
    this.searchInput = el("input");
    this.searchInput.type = "text";
    this.searchInput.addEventListener("input", () => this.onSearchInput());
    this.searchInput.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && this.searchInput.value) {
        e.preventDefault();
        e.stopPropagation();
        this.clearSearch();
      }
    });
    this.searchClear = iconButton(icon("x", 14), "");
    this.searchClear.hidden = true;
    this.searchClear.addEventListener("click", (e) => {
      e.preventDefault();
      this.clearSearch();
      this.searchInput.focus();
    });
    sw.append(this.searchIcon, this.searchInput, this.searchClear);
    top.append(this.newBtn, sw);

    this.list = el("div", "hist-list");
    const foot = el("div", "hist-foot");
    this.newProjectBtn = button("hist-new", "");
    this.newProjectBtn.addEventListener("click", () => this.host.editProject(null));
    foot.append(this.newProjectBtn);

    root.append(top, this.list, foot);
    body.append(root);
    this.root = root;

    if (typeof IntersectionObserver === "undefined") {
      this.ensureLoaded();
    } else {
      this.visObs = new IntersectionObserver((entries) => {
        if (entries.some((e) => e.isIntersecting)) this.ensureLoaded();
      });
      this.visObs.observe(root);
      this.moreObs = new IntersectionObserver(
        (entries) => {
          if (!entries.some((e) => e.isIntersecting)) return;
          void this.loadMore(this.query ? this.search : this.recent);
        },
        { root: this.list, rootMargin: "120px" },
      );
    }
    this.render();
  }

  destroy(): void {
    this.visObs?.disconnect();
    this.moreObs?.disconnect();
    this.closeMenu?.();
    this.offRenameEscape?.();
    clearTimeout(this.searchTimer);
  }

  /** 칸이 처음 보이거나 기능 본체가 칸을 열 때 한 번 읽는다. */
  ensureLoaded(): void {
    if (this.loaded || !this.root) return;
    this.loaded = true;
    this.visObs?.disconnect();
    void this.loadProjects();
    void this.loadMore(this.recent);
  }

  /** 머리줄 표시와 + 메뉴가 쓰는 프로젝트 목록. 아직 없으면 읽는다. */
  ensureProjects(): Promise<ProjectRow[] | null> {
    if (this.projects) return Promise.resolve(this.projects);
    return this.loadProjects();
  }

  project(id: string): ProjectRow | undefined {
    return this.projects?.find((p) => p.id === id);
  }

  projectList(): ProjectRow[] {
    return this.projects ?? [];
  }

  /** 한 번도 읽지 않았을 때만 읽는다. 머리줄 표시가 실패를 되풀이해 부르지 않게 한다. */
  loadProjectsOnce(): void {
    if (!this.projectsTried) void this.loadProjects();
  }

  loadProjects(): Promise<ProjectRow[] | null> {
    this.projectsTried = true;
    if (!this.projectsLoad) {
      this.projectsLoad = this.host.api
        .projects()
        .then(
          (rows) => {
            this.projects = rows;
            this.projectsError = false;
            return rows as ProjectRow[] | null;
          },
          () => {
            this.projectsError = true;
            return null;
          },
        )
        .finally(() => {
          this.projectsLoad = null;
          for (const id of [...this.expanded]) {
            if (this.projects && !this.projects.some((p) => p.id === id)) this.expanded.delete(id);
          }
          this.render();
          this.host.projectsChanged();
        });
    }
    return this.projectsLoad;
  }

  /** 옮기기, 프로젝트 만들기와 삭제 뒤 펼친 목록을 첫 쪽부터 다시 읽는다. */
  reload(): void {
    if (!this.loaded) {
      if (this.projects) void this.loadProjects();
      return;
    }
    void this.loadProjects();
    resetList(this.recent);
    void this.loadMore(this.recent);
    for (const [id, list] of [...this.byProject]) {
      if (!this.expanded.has(id)) {
        this.byProject.delete(id);
        continue;
      }
      resetList(list);
      void this.loadMore(list);
    }
    if (this.query) {
      resetList(this.search);
      void this.loadMore(this.search);
    }
  }

  /** 응답이 끝난 대화를 그 목록 맨 위로 올린다. 서버를 다시 읽지 않는다. */
  touch(row: SessionRow): void {
    if (!this.loaded) return;
    const prev = this.findRow(row.session_id);
    const merged: SessionRow = { ...row, title: row.title ?? prev?.title ?? null };
    this.recent.rows = this.recent.rows.filter((r) => r.session_id !== row.session_id);
    for (const list of this.byProject.values()) {
      list.rows = list.rows.filter((r) => r.session_id !== row.session_id);
    }
    const target = merged.chat_project_id ? this.byProject.get(merged.chat_project_id) : this.recent;
    target?.rows.unshift(merged);
    this.search.rows = this.search.rows.map((r) => (r.session_id === row.session_id ? merged : r));
    this.render();
  }

  remove(sessionId: string): void {
    this.recent.rows = this.recent.rows.filter((r) => r.session_id !== sessionId);
    this.search.rows = this.search.rows.filter((r) => r.session_id !== sessionId);
    for (const list of this.byProject.values()) {
      list.rows = list.rows.filter((r) => r.session_id !== sessionId);
    }
    this.render();
  }

  setTitle(sessionId: string, title: string | null): void {
    const apply = (rows: SessionRow[]) => {
      for (const r of rows) if (r.session_id === sessionId) r.title = title;
    };
    apply(this.recent.rows);
    apply(this.search.rows);
    for (const list of this.byProject.values()) apply(list.rows);
    this.render();
  }

  /** 머리줄 프로젝트 표시를 누르면 그 프로젝트를 펼쳐 보인다(4.1 1번). */
  revealProject(projectId: string): void {
    this.ensureLoaded();
    if (this.query) this.clearSearch();
    if (!this.expanded.has(projectId)) this.toggle(projectId);
    this.projectEls.get(projectId)?.scrollIntoView({ block: "nearest" });
  }

  render(): void {
    if (!this.root) return;
    const t = this.host.t();
    setLabel(this.newBtn, icon("pen", 16), t.newChat);
    setLabel(this.newProjectBtn, svgIcon(FOLDER_PLUS, 16), t.newProject);
    this.searchInput.placeholder = t.search;
    this.searchInput.setAttribute("aria-label", t.search);
    this.searchClear.setAttribute("aria-label", t.close);
    this.searchClear.dataset.tip = t.close;

    const scroll = this.list.scrollTop;
    const frag = document.createDocumentFragment();
    this.projectEls.clear();
    this.moreObs?.disconnect();
    if (this.query) {
      for (const row of this.search.rows) frag.append(this.sessionRow(row, t));
      this.tail(frag, this.search, t, t.noResults, false);
    } else {
      this.renderProjects(frag, t);
      this.renderRecent(frag, t);
    }
    // 바꾸는 칸이 빠질 때 오는 blur 를 취소로 보지 않는다.
    this.rerendering = true;
    try {
      this.list.replaceChildren(frag);
    } finally {
      this.rerendering = false;
    }
    this.list.scrollTop = scroll;
  }

  private renderProjects(frag: DocumentFragment, t: Texts): void {
    if (this.projectsError) {
      frag.append(el("div", "hist-sec", t.projects));
      frag.append(errorLine(t, () => void this.loadProjects()));
      return;
    }
    if (!this.projects || this.projects.length === 0) return;
    frag.append(el("div", "hist-sec", t.projects));
    for (const p of this.projects) frag.append(this.projectRow(p, t));
  }

  private renderRecent(frag: DocumentFragment, t: Texts): void {
    frag.append(el("div", "hist-sec", t.recent));
    const now = new Date();
    let last: DateGroup | null = null;
    for (const row of this.recent.rows) {
      const g = dateGroup(row.updated_at, now);
      if (g !== last) {
        frag.append(el("div", "hist-grp", t[g]));
        last = g;
      }
      frag.append(this.sessionRow(row, t));
    }
    this.tail(frag, this.recent, t, t.noChats, false);
  }

  /** 목록 끝. 읽는 중이면 돌림표, 실패면 다시 시도, 더 있으면 다음 쪽 자리. */
  private tail(parent: ParentNode, list: ListState, t: Texts, empty: string, asButton: boolean): void {
    if (list.loading) {
      parent.append(el("div", "hist-spin"));
    } else if (list.error) {
      parent.append(errorLine(t, () => void this.loadMore(list)));
    } else if (list.done) {
      if (list.rows.length === 0) parent.append(el("div", "hist-empty", empty));
    } else if (asButton) {
      const more = button("hist-morebtn", t.more);
      more.addEventListener("click", (e) => {
        e.stopPropagation();
        void this.loadMore(list);
      });
      parent.append(more);
    } else if (this.moreObs) {
      const sentinel = el("div", "hist-sentinel");
      parent.append(sentinel);
      this.moreObs.observe(sentinel);
    } else {
      const more = button("hist-morebtn", t.more);
      more.addEventListener("click", () => void this.loadMore(list));
      parent.append(more);
    }
  }

  private async loadMore(list: ListState): Promise<void> {
    if (list.loading || list.done) return;
    list.loading = true;
    list.error = false;
    const token = list.token;
    this.render();
    try {
      const rows = await list.fetch(list.page);
      if (token !== list.token) return;
      const seen = new Set(list.rows.map((r) => r.session_id));
      list.rows.push(...rows.filter((r) => !seen.has(r.session_id)));
      list.page += 1;
      list.done = rows.length < PAGE_SIZE;
    } catch {
      if (token !== list.token) return;
      list.error = true;
    } finally {
      if (token === list.token) {
        list.loading = false;
        this.render();
      }
    }
  }

  private projectSessions(projectId: string): ListState {
    let list = this.byProject.get(projectId);
    if (!list) {
      list = newList((page) => this.host.api.sessions({ projectId, page }));
      this.byProject.set(projectId, list);
    }
    return list;
  }

  private toggle(projectId: string): void {
    if (this.expanded.has(projectId)) {
      this.expanded.delete(projectId);
      this.render();
      return;
    }
    this.expanded.add(projectId);
    const list = this.projectSessions(projectId);
    if (list.page === 0 && !list.loading) void this.loadMore(list);
    else this.render();
  }

  private projectRow(p: ProjectRow, t: Texts): HTMLElement {
    const open = this.expanded.has(p.id);
    const wrap = el("div", "hist-proj");
    const r = el("div", "hist-row hist-prow");
    r.tabIndex = 0;
    r.setAttribute("role", "button");
    r.setAttribute("aria-expanded", String(open));
    const chev = el("span", "hist-chev");
    chev.innerHTML = icon(open ? "cdown" : "cright", 14);
    const folder = el("span", "hist-fic");
    folder.innerHTML = svgIcon(FOLDER, 15);
    const main = el("span", "hist-pmain");
    main.append(el("span", "hist-title", p.name));
    const meta = [p.file_count > 0 ? t.fileCount(p.file_count) : "", p.has_instructions ? t.hasInstructions : ""]
      .filter((s) => s)
      .join(", ");
    if (meta) main.append(el("span", "hist-meta", meta));
    const more = iconButton(icon("more", 16), t.more);
    more.classList.add("hist-rmore");
    more.addEventListener("click", (e) => {
      e.stopPropagation();
      this.menu(more, [
        { label: t.newInProject, icon: icon("pen", 15), onSelect: () => this.host.newChat(p.id) },
        { label: t.projectSettings, icon: svgIcon(SETTINGS, 15), onSelect: () => this.host.editProject(p.id) },
        { label: t.delete, icon: svgIcon(TRASH, 15), danger: true, onSelect: () => this.host.deleteProject(p) },
      ]);
    });
    r.append(chev, folder, main, more);
    r.addEventListener("click", () => this.toggle(p.id));
    r.addEventListener("keydown", (e) => {
      if (e.target !== r || (e.key !== "Enter" && e.key !== " ")) return;
      e.preventDefault();
      this.toggle(p.id);
    });
    wrap.append(r);
    if (open) {
      const box = el("div", "hist-psess");
      const list = this.projectSessions(p.id);
      for (const row of list.rows) box.append(this.sessionRow(row, t));
      this.tail(box, list, t, t.noChats, true);
      wrap.append(box);
    }
    this.projectEls.set(p.id, wrap);
    return wrap;
  }

  private sessionRow(row: SessionRow, t: Texts): HTMLElement {
    const wrap = el("div", "hist-item");
    const current = row.session_id === this.host.currentSessionId();
    const r = el("div", current ? "hist-row on" : "hist-row");
    if (current) r.setAttribute("aria-current", "true");
    if (this.renaming === row.session_id) {
      r.append(this.renameInput(row, t));
      wrap.append(r);
      return wrap;
    }
    r.tabIndex = 0;
    r.setAttribute("role", "button");
    r.append(el("span", "hist-title", row.title || t.newChat));
    if (this.openingId === row.session_id) r.append(el("span", "hist-spin sm"));
    const more = iconButton(icon("more", 16), t.more);
    more.classList.add("hist-rmore");
    more.addEventListener("click", (e) => {
      e.stopPropagation();
      this.menu(more, [
        { label: t.rename, icon: icon("pencil", 15), onSelect: () => this.startRename(row) },
        { label: t.moveToProject, icon: svgIcon(FOLDER_INPUT, 15), onSelect: () => this.host.moveSession(row) },
        { label: t.delete, icon: svgIcon(TRASH, 15), danger: true, onSelect: () => this.host.deleteSession(row) },
      ]);
    });
    r.append(more);
    r.addEventListener("click", () => void this.open(row));
    r.addEventListener("keydown", (e) => {
      if (e.target !== r || (e.key !== "Enter" && e.key !== " ")) return;
      e.preventDefault();
      void this.open(row);
    });
    wrap.append(r);
    if (this.openErrorId === row.session_id) wrap.append(errorLine(t, () => void this.open(row)));
    return wrap;
  }

  private menu(anchor: HTMLElement, items: Parameters<typeof openMenu>[3]): void {
    if (!this.root) return;
    this.closeMenu?.();
    this.closeMenu = openMenu(this.host.ctx, this.root, anchor, items);
  }

  private async open(row: SessionRow): Promise<void> {
    if (this.openingId) return;
    this.openingId = row.session_id;
    this.openErrorId = null;
    this.render();
    let ok = false;
    try {
      ok = await this.host.openSession(row.session_id);
    } finally {
      this.openingId = null;
      this.openErrorId = ok ? null : row.session_id;
      this.render();
    }
  }

  private startRename(row: SessionRow): void {
    this.renaming = row.session_id;
    this.renameDraft = row.title ?? "";
    this.renameFresh = true;
    this.offRenameEscape?.();
    this.offRenameEscape = this.host.ctx.ui.onEscape(() => {
      this.endRename();
      return true;
    });
    this.render();
  }

  private endRename(): void {
    if (this.renaming === null) return;
    this.renaming = null;
    this.offRenameEscape?.();
    this.offRenameEscape = null;
    this.render();
  }

  /** Enter 저장, Esc 취소, 비우고 Enter 면 되돌린다(4.2 6번). 칸을 벗어나면 저장하지 않는다. */
  private renameInput(row: SessionRow, t: Texts): HTMLInputElement {
    const input = el("input", "hist-rename");
    input.value = this.renameDraft;
    input.addEventListener("input", () => {
      this.renameDraft = input.value;
    });
    input.setAttribute("aria-label", t.rename);
    input.addEventListener("click", (e) => e.stopPropagation());
    input.addEventListener("keydown", (e) => {
      if (e.isComposing) return;
      if (e.key === "Enter") {
        e.preventDefault();
        void this.commitRename(row, input.value);
      } else if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        this.endRename();
      }
    });
    input.addEventListener("blur", () => {
      if (!this.rerendering && this.renaming === row.session_id) this.endRename();
    });
    const select = this.renameFresh;
    this.renameFresh = false;
    queueMicrotask(() => {
      if (!input.isConnected) return;
      input.focus();
      if (select) input.select();
    });
    return input;
  }

  private async commitRename(row: SessionRow, value: string): Promise<void> {
    const title = value.trim();
    const old = row.title;
    this.endRename();
    if (!title || title === (old ?? "")) return;
    this.setTitle(row.session_id, title);
    try {
      const saved = await this.host.api.renameSession(row.session_id, title);
      const next = saved?.title ?? title;
      if (next !== title) this.setTitle(row.session_id, next);
      this.host.renamed({ ...row, title: next });
    } catch {
      this.setTitle(row.session_id, old);
      this.host.ctx.ui.toast(this.host.t().saveFailed);
    }
  }

  private findRow(sessionId: string): SessionRow | undefined {
    const lists = [this.recent, this.search, ...this.byProject.values()];
    for (const list of lists) {
      const hit = list.rows.find((r) => r.session_id === sessionId);
      if (hit) return hit;
    }
    return undefined;
  }

  private onSearchInput(): void {
    this.searchClear.hidden = !this.searchInput.value;
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.applySearch(this.searchInput.value.trim()), SEARCH_DELAY_MS);
  }

  private clearSearch(): void {
    clearTimeout(this.searchTimer);
    this.searchInput.value = "";
    this.searchClear.hidden = true;
    this.applySearch("");
  }

  private applySearch(q: string): void {
    if (q === this.query) return;
    this.ensureLoaded();
    this.query = q;
    resetList(this.search);
    this.list.scrollTop = 0;
    if (q) void this.loadMore(this.search);
    else this.render();
  }
}
