import type { WidgetContext } from "../../frame/context";
import type { Attachment, FeedbackRating } from "../../types";

/** 대화 목록 한 줄(T3-2 위젯 대화 목록). */
export interface SessionRow {
  session_id: string;
  title: string | null;
  chat_project_id: string | null;
  updated_at: string;
}

/** 프로젝트 목록 한 줄. */
export interface ProjectRow {
  id: string;
  name: string;
  has_instructions: boolean;
  file_count: number;
  session_count: number;
  last_active_at: string;
}

export interface ProjectFile {
  file_id: string;
  filename: string;
  media_type: string;
  size_bytes: number;
  pages: number | null;
  created_at: string;
}

/** 프로젝트 설정 창용 한 건. */
export interface ProjectDetail {
  id: string;
  name: string;
  instructions: string | null;
  has_instructions: boolean;
  files?: ProjectFile[];
}

export interface TurnDto {
  turn_id: string | null;
  user_seq: number | null;
  question: {
    text: string;
    created_at: string | null;
    attachments: Array<{ attachment_id: string | null; filename: string; media_type: string; available: boolean }>;
  };
  answer: {
    text: string;
    model: string | null;
    created_at: string | null;
    stopped: boolean;
    tool_uses: Array<{ name: string }>;
  } | null;
  feedback: { rating: FeedbackRating; reason_codes: string[] | null; comment: string | null } | null;
}

/** 이어보기 응답(T3-2 위젯 대화 이어보기). */
export interface MessagesResponse {
  session_id: string;
  title: string | null;
  chat_project_id: string | null;
  truncated: boolean;
  turns: TurnDto[];
}

/** 위젯 설정 조회의 CR-178 칸. */
export interface HistorySettings {
  history_enabled?: boolean;
  project_instructions_max_chars?: number;
  project_files_max_count?: number;
}

/** 서버 목록 응답은 쪽 정보를 data 밖에 두므로, 쪽 크기를 정해 부르고 꽉 찬 쪽이면 다음 쪽이 있다고 본다. */
export const PAGE_SIZE = 30;

/** 프로젝트에 없는 대화만 고르는 목록 조건 값. */
export const NO_PROJECT = "none";

const enc = encodeURIComponent;

export function historySettings(ctx: WidgetContext): HistorySettings | null {
  return ctx.widgetSettings() as unknown as HistorySettings | null;
}

export class HistoryApi {
  constructor(private readonly ctx: WidgetContext) {}

  sessions(args: { projectId?: string; query?: string; page: number }): Promise<SessionRow[]> {
    const q = new URLSearchParams({ page: String(args.page), size: String(PAGE_SIZE) });
    if (args.query) q.set("query", args.query);
    else if (args.projectId) q.set("chat_project_id", args.projectId);
    return this.ctx.api.request<SessionRow[]>("GET", `/api/v1/chat/sessions?${q.toString()}`).then((r) => r ?? []);
  }

  messages(sessionId: string): Promise<MessagesResponse> {
    return this.ctx.api.request("GET", `/api/v1/chat/sessions/${enc(sessionId)}/messages`);
  }

  renameSession(sessionId: string, title: string): Promise<SessionRow> {
    return this.ctx.api.request("PATCH", `/api/v1/chat/sessions/${enc(sessionId)}`, { title });
  }

  moveSession(sessionId: string, projectId: string | null): Promise<SessionRow> {
    return this.ctx.api.request("PATCH", `/api/v1/chat/sessions/${enc(sessionId)}`, { chat_project_id: projectId });
  }

  deleteSession(sessionId: string): Promise<unknown> {
    return this.ctx.api.request("DELETE", `/api/v1/chat/sessions/${enc(sessionId)}`);
  }

  projects(): Promise<ProjectRow[]> {
    return this.ctx.api.request<ProjectRow[]>("GET", "/api/v1/chat/projects").then((r) => r ?? []);
  }

  project(projectId: string): Promise<ProjectDetail> {
    return this.ctx.api.request("GET", `/api/v1/chat/projects/${enc(projectId)}`);
  }

  createProject(body: { name: string; instructions: string | null }): Promise<ProjectDetail> {
    return this.ctx.api.request("POST", "/api/v1/chat/projects", body);
  }

  updateProject(projectId: string, body: { name?: string; instructions?: string | null }): Promise<ProjectDetail> {
    return this.ctx.api.request("PATCH", `/api/v1/chat/projects/${enc(projectId)}`, body);
  }

  deleteProject(projectId: string): Promise<unknown> {
    return this.ctx.api.request("DELETE", `/api/v1/chat/projects/${enc(projectId)}`);
  }

  files(projectId: string): Promise<ProjectFile[]> {
    return this.ctx.api.request<ProjectFile[]>("GET", `/api/v1/chat/projects/${enc(projectId)}/files`).then((r) => r ?? []);
  }

  uploadFile(projectId: string, file: File): Promise<ProjectFile> {
    const form = new FormData();
    form.append("file", file, file.name);
    return this.ctx.api.request("POST", `/api/v1/chat/projects/${enc(projectId)}/files`, form);
  }

  deleteFile(projectId: string, fileId: string): Promise<unknown> {
    return this.ctx.api.request("DELETE", `/api/v1/chat/projects/${enc(projectId)}/files/${enc(fileId)}`);
  }

  attachFile(projectId: string, fileId: string, sessionId: string): Promise<Attachment> {
    return this.ctx.api.request("POST", `/api/v1/chat/projects/${enc(projectId)}/files/${enc(fileId)}/attach`, {
      session_id: sessionId,
    });
  }
}
