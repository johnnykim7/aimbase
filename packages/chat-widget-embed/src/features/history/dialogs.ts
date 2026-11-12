import type { WidgetContext } from "../../frame/context";
import { icon, svgIcon } from "../../icons";
import { WidgetApiError } from "../../widget-api";
import type { HistoryApi, ProjectDetail, ProjectFile, ProjectRow, SessionRow } from "./api";
import { formatSize, kindLabel } from "./convert";
import { button, el, errorLine, iconButton, openDialog, spinner } from "./dom";
import { FILE, FOLDER, FOLDER_MINUS, FOLDER_PLUS, TRASH, UPLOAD } from "./icons";
import type { Texts } from "./strings";

export interface DialogEnv {
  ctx: WidgetContext;
  api: HistoryApi;
  t: Texts;
  /** 위젯 설정 조회가 준 한도. 없으면 서버 검사에 맡긴다 */
  instructionsMax?: number;
  filesMax?: number;
}

function codeOf(e: unknown): string | undefined {
  return e instanceof WidgetApiError ? e.code : undefined;
}

/**
 * 프로젝트 설정 창(CR-178 4.1 9번, 4.2 9, 10, 12번). projectId 가 없으면 새 프로젝트다.
 * 새 프로젝트의 자료는 저장 뒤에 올리고, 설정의 자료는 바로 올리고 지운다.
 */
export function projectDialog(
  env: DialogEnv,
  opts: {
    projectId: string | null;
    onSaved: (project: ProjectDetail, created: boolean) => void;
    onFilesChanged: () => void;
    onDelete: (project: { id: string; name: string }) => Promise<boolean>;
  },
): void {
  const { t, api } = env;
  let projectId = opts.projectId;
  let savedName = "";
  let files: ProjectFile[] = [];
  const queued: File[] = [];
  let uploading: string | null = null;
  let busy = false;

  const d = openDialog(env.ctx, { title: projectId ? t.projectSettings : t.newProject, closeLabel: t.close });
  d.body.classList.add("hist-form");

  const nameInput = el("input", "hist-input");
  nameInput.type = "text";
  const nameErr = el("div", "note error");
  const ta = el("textarea", "hist-input hist-ta");
  ta.rows = 6;
  const counter = el("span", "hist-count");
  const instrErr = el("div", "note error");
  const fileList = el("div", "hist-files");
  const fileErr = el("div", "note error");
  const formErr = el("div", "note error");
  const picker = el("input");
  picker.type = "file";
  picker.multiple = true;
  picker.hidden = true;
  const uploadBtn = button("btn hist-upload", t.upload, svgIcon(UPLOAD, 15));
  uploadBtn.addEventListener("click", () => picker.click());
  picker.addEventListener("change", () => {
    const picked = [...(picker.files ?? [])];
    picker.value = "";
    if (picked.length) void addFiles(picked);
  });

  const saveBtn = button("btn pri", t.save);
  const cancelBtn = button("btn", t.cancel);
  const deleteBtn = button("btn hist-danger-text hist-dleft", t.delete, svgIcon(TRASH, 15));
  cancelBtn.addEventListener("click", () => d.close());
  saveBtn.addEventListener("click", () => void save());
  deleteBtn.addEventListener("click", async () => {
    if (!projectId || busy) return;
    if (await opts.onDelete({ id: projectId, name: savedName })) d.close();
  });
  nameInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.isComposing) {
      e.preventDefault();
      void save();
    }
  });
  ta.addEventListener("input", () => {
    updateCount();
    instrErr.textContent = "";
  });
  nameInput.addEventListener("input", () => {
    nameErr.textContent = "";
  });

  function field(label: string, ...children: HTMLElement[]): HTMLElement {
    const box = el("div", "hist-field");
    box.append(el("div", "hist-label", label), ...children);
    return box;
  }

  function updateCount(): void {
    const max = env.instructionsMax;
    counter.hidden = !max;
    if (!max) return;
    counter.textContent = t.charCount(ta.value.length, max);
    counter.classList.toggle("over", ta.value.length > max);
  }

  function fileCount(): number {
    return projectId ? files.length : queued.length;
  }

  function renderFiles(): void {
    fileList.replaceChildren();
    const rows: Array<{ name: string; meta: string; remove: () => void }> = projectId
      ? files.map((f) => ({
          name: f.filename,
          meta: `${kindLabel(f.media_type)}, ${formatSize(f.size_bytes)}`,
          remove: () => void removeFile(f),
        }))
      : queued.map((q) => ({
          name: q.name,
          meta: formatSize(q.size),
          remove: () => {
            queued.splice(queued.indexOf(q), 1);
            fileErr.textContent = "";
            renderFiles();
          },
        }));
    for (const r of rows) {
      const line = el("div", "hist-file");
      const ic = el("span", "hist-fic");
      ic.innerHTML = svgIcon(FILE, 15);
      const main = el("span", "hist-pmain");
      main.append(el("span", "hist-title", r.name), el("span", "hist-meta", r.meta));
      const x = iconButton(svgIcon(TRASH, 15), t.delete);
      x.addEventListener("click", r.remove);
      line.append(ic, main, x);
      fileList.append(line);
    }
    if (uploading) {
      const line = el("div", "hist-file");
      line.append(spinner(true), el("span", "hist-title", uploading));
      fileList.append(line);
    }
    if (!rows.length && !uploading) fileList.append(el("div", "hist-empty", t.noFiles));
    const full = env.filesMax !== undefined && fileCount() >= env.filesMax;
    uploadBtn.disabled = full || uploading !== null;
    if (full && env.filesMax !== undefined && !fileErr.textContent) fileErr.textContent = t.filesFull(env.filesMax);
  }

  /** 다 올렸으면 참. 개수가 다 찼거나 못 올린 파일이 있으면 그 칸 아래에 알린다. */
  async function addFiles(picked: File[]): Promise<boolean> {
    fileErr.textContent = "";
    const failed: string[] = [];
    const refused: string[] = [];
    let full = false;
    let changed = false;
    for (const f of picked) {
      if (env.filesMax !== undefined && fileCount() >= env.filesMax) {
        full = true;
        break;
      }
      // 자료는 대화에 첨부로 붙이려고 두므로 + 메뉴 「파일 올리기」와 같은 기준으로 받는다.
      const reason = env.ctx.chat.checkAttachment(f);
      if (reason) {
        refused.push(t.notAccepted(f.name, reason));
        continue;
      }
      if (!projectId) {
        queued.push(f);
        continue;
      }
      uploading = f.name;
      renderFiles();
      try {
        files.push(await api.uploadFile(projectId, f));
        changed = true;
      } catch (e) {
        if (codeOf(e) === "project_files_full") {
          full = true;
          break;
        }
        failed.push(f.name);
      } finally {
        uploading = null;
      }
    }
    const notes = [...refused, ...failed.map((n) => t.uploadFailed(n))];
    if (full) notes.unshift(t.filesFull(env.filesMax ?? fileCount()));
    fileErr.textContent = notes.join("\n");
    renderFiles();
    if (changed) opts.onFilesChanged();
    return notes.length === 0;
  }

  async function removeFile(f: ProjectFile): Promise<void> {
    if (!projectId) return;
    try {
      await api.deleteFile(projectId, f.file_id);
      files = files.filter((x) => x.file_id !== f.file_id);
      fileErr.textContent = "";
      renderFiles();
      opts.onFilesChanged();
    } catch {
      env.ctx.ui.toast(t.deleteFailed);
    }
  }

  /** 새 프로젝트를 만든 뒤 줄 세운 자료를 올린다. 하나라도 못 올리면 창을 설정으로 두고 알린다. */
  async function uploadQueued(): Promise<boolean> {
    const pending = queued.splice(0);
    files = [];
    return addFiles(pending);
  }

  async function save(): Promise<void> {
    if (busy) return;
    nameErr.textContent = "";
    instrErr.textContent = "";
    formErr.textContent = "";
    const name = nameInput.value.trim();
    if (!name) {
      nameErr.textContent = t.nameRequired;
      nameInput.focus();
      return;
    }
    const instructions = ta.value;
    if (env.instructionsMax !== undefined && instructions.length > env.instructionsMax) {
      instrErr.textContent = t.instructionsTooLong(env.instructionsMax);
      ta.focus();
      return;
    }
    busy = true;
    saveBtn.disabled = true;
    try {
      if (projectId) {
        const p = await api.updateProject(projectId, { name, instructions });
        savedName = p.name;
        opts.onSaved(p, false);
        d.close();
        return;
      }
      const p = await api.createProject({ name, instructions });
      projectId = p.id;
      savedName = p.name;
      opts.onSaved(p, true);
      if (queued.length && !(await uploadQueued())) {
        becomeSettings();
        return;
      }
      d.close();
    } catch (e) {
      const code = codeOf(e);
      if (code === "project_invalid:name") nameErr.textContent = t.nameTooLong;
      else if (code === "project_invalid:instructions" && env.instructionsMax !== undefined)
        instrErr.textContent = t.instructionsTooLong(env.instructionsMax);
      else formErr.textContent = t.saveFailed;
    } finally {
      busy = false;
      saveBtn.disabled = false;
    }
  }

  /** 새 프로젝트를 만든 뒤 창을 닫지 못했으면 그 자리에서 설정 창으로 바꾼다. */
  function becomeSettings(): void {
    const h = d.body.parentElement?.querySelector(".hist-dhead h3");
    if (h) h.textContent = t.projectSettings;
    deleteBtn.hidden = false;
  }

  function buildForm(detail: ProjectDetail | null): void {
    nameInput.value = detail?.name ?? "";
    ta.value = detail?.instructions ?? "";
    savedName = detail?.name ?? "";
    const countRow = el("div", "hist-hintrow");
    countRow.append(el("span", "note", t.instructionsHint), counter);
    const fileHead = el("div", "hist-filehead");
    fileHead.append(uploadBtn, picker);
    d.body.replaceChildren(
      field(t.name, nameInput, nameErr),
      field(t.instructions, ta, countRow, instrErr),
      field(t.files, fileList, fileHead, fileErr),
      formErr,
    );
    deleteBtn.hidden = !projectId;
    d.foot.replaceChildren(deleteBtn, cancelBtn, saveBtn);
    updateCount();
    renderFiles();
    nameInput.focus();
  }

  async function load(): Promise<void> {
    if (!projectId) {
      buildForm(null);
      return;
    }
    d.body.replaceChildren(spinner());
    d.foot.replaceChildren(cancelBtn);
    try {
      const detail = await api.project(projectId);
      files = detail.files ?? (await api.files(projectId));
      buildForm(detail);
    } catch {
      d.body.replaceChildren(errorLine(t, () => void load()));
    }
  }

  void load();
}

/** 프로젝트로 옮기기 창(4.1 10번). 고르면 닫고 옮기기는 부른 쪽이 한다. */
export function moveDialog(
  env: DialogEnv,
  opts: {
    row: SessionRow;
    projects: ProjectRow[];
    onPick: (projectId: string | null) => void;
    onNewProject: () => void;
  },
): void {
  const { t } = env;
  const d = openDialog(env.ctx, { title: t.moveToProject, closeLabel: t.close });
  const list = el("div", "hist-picks");
  const pick = (iconHtml: string, label: string, onSelect: () => void, current = false) => {
    const b = el("button", current ? "hist-pick on" : "hist-pick");
    b.type = "button";
    b.innerHTML = iconHtml;
    b.append(el("span", "hist-title", label));
    if (current) {
      b.disabled = true;
      b.setAttribute("aria-current", "true");
      const mk = el("span", "hist-mk");
      mk.innerHTML = icon("check", 15);
      b.append(mk);
    }
    b.addEventListener("click", () => {
      d.close();
      onSelect();
    });
    list.append(b);
  };
  for (const p of opts.projects) {
    pick(svgIcon(FOLDER, 15), p.name, () => opts.onPick(p.id), p.id === opts.row.chat_project_id);
  }
  if (opts.row.chat_project_id) pick(svgIcon(FOLDER_MINUS, 15), t.removeFromProject, () => opts.onPick(null));
  pick(svgIcon(FOLDER_PLUS, 15), t.newProject, () => opts.onNewProject());
  d.body.append(list);
}

/** 프로젝트 자료에서 창(4.1 11번). 고르면 서버가 첨부로 복사하고 부른 쪽이 입력칸에 붙인다. */
export function filesDialog(
  env: DialogEnv,
  opts: { projectId: string; onPick: (file: ProjectFile) => Promise<boolean> },
): void {
  const { t, api } = env;
  const d = openDialog(env.ctx, { title: t.fromProjectFiles, closeLabel: t.close });
  let busy = false;

  const render = (files: ProjectFile[]) => {
    const list = el("div", "hist-picks");
    for (const f of files) {
      const b = el("button", "hist-pick");
      b.type = "button";
      b.innerHTML = svgIcon(FILE, 15);
      // 받는 종류가 바뀌기 전에 올린 자료는 남아 있어도 지금 첨부로 붙지 않는다.
      const refused = env.ctx.chat.checkAttachment({ name: f.filename, type: f.media_type, size: f.size_bytes });
      const main = el("span", "hist-pmain");
      main.append(
        el("span", "hist-title", f.filename),
        el("span", "hist-meta", refused ?? `${kindLabel(f.media_type)}, ${formatSize(f.size_bytes)}`),
      );
      b.append(main);
      if (refused) {
        b.disabled = true;
        b.classList.add("off");
        list.append(b);
        continue;
      }
      b.addEventListener("click", async () => {
        if (busy) return;
        busy = true;
        const sp = spinner(true);
        b.append(sp);
        try {
          if (await opts.onPick(f)) d.close();
        } finally {
          busy = false;
          sp.remove();
        }
      });
      list.append(b);
    }
    if (!files.length) list.append(el("div", "hist-empty", t.noFiles));
    d.body.replaceChildren(list);
  };

  const load = async () => {
    d.body.replaceChildren(spinner());
    try {
      render(await api.files(opts.projectId));
    } catch {
      d.body.replaceChildren(errorLine(t, () => void load()));
    }
  };
  void load();
}
