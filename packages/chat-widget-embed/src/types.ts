/**
 * Aimbase Chat Widget — 공개 타입
 */

export interface TokenResponse {
  token: string;
  /** ISO-8601 또는 epoch ms */
  expires_at: string | number;
  /** exp - 300초. 위젯이 이 시점에 authResolver 재호출 */
  refresh_after: number;
  scopes?: string[];
}

export type AuthResolver = () => Promise<TokenResponse>;

export type DisplayMode = "bubble" | "inline" | "panel";

export interface ContextProvider {
  (): Record<string, unknown>;
}

export interface Citation {
  index?: number;
  chunk_id?: string;
  source_id: string;
  document_name?: string;
  score: number;
  content_preview: string;
  page_number?: number;
  metadata?: Record<string, unknown>;
}

export interface ChatDelta {
  type: "delta" | "thinking" | "tool_use_start" | "tool_result" | "done" | "error";
  text?: string;
  tool?: { id?: string; name?: string; input?: Record<string, unknown> };
  toolResult?: { tool_use_id: string; output: string; is_error: boolean };
  done?: {
    rag_used?: boolean;
    citations?: Citation[];
  };
  error?: string;
}

export interface WorkflowStepEvent {
  run_id: string;
  parent_run_id?: string;
  step_id: string;
  status: "running" | "completed" | "failed";
  started_at?: string;
  completed_at?: string;
  duration_ms?: number;
  sub_workflow_id?: string;
  output_preview?: Record<string, unknown>;
  error?: string;
}

export interface ApprovalEvent {
  run_id: string;
  step_id: string;
  policy_id: string;
  reason?: string;
  approvers: string[];
  timeout_at?: string;
}

export interface WidgetOptions {
  /** Aimbase 서버 베이스 URL (예: https://aimbase.company.com) */
  baseUrl: string;
  /** 소비앱 BFF 프록시를 호출하여 단기 위젯 토큰을 가져오는 함수 */
  authResolver: AuthResolver;
  /** 요청마다 주입할 컨텍스트 (현재 화면 orderId 등) */
  contextProvider?: ContextProvider;
  /** 기본 bubble (우하단). inline 은 target 필수. panel 은 측면 고정 */
  display?: DisplayMode;
  /** display === 'inline' 또는 'panel' 일 때 마운트할 컨테이너 */
  target?: string | HTMLElement;
  /** 초기 session_id. 미지정 시 crypto.randomUUID() 생성 */
  sessionId?: string;
  /** 대상 RAG 소스 ID — chat request 의 rag_source_id 로 전달 */
  ragSourceId?: string;
  /** 위젯 내 승인 UI 활성화 — 기본 false (이벤트만 발행) */
  allowApproval?: boolean;
  theme?: {
    mode?: "light" | "dark" | "auto";
    cssVars?: Record<string, string>;
  };
  on?: {
    onMessage?: (delta: ChatDelta) => void;
    onWorkflowStep?: (ev: WorkflowStepEvent) => void;
    onApprovalRequired?: (ev: ApprovalEvent) => void;
    onError?: (err: Error) => void;
    onTokenExpiring?: () => void;
  };
}

export interface WidgetHandle {
  open(): void;
  close(): void;
  sendMessage(text: string): Promise<void>;
  abort(): Promise<void>;
  subscribeWorkflow(runId: string): () => void; // unsubscribe fn
  destroy(): void;
}

/** CR-061: 업로드된 첨부 파일의 서버 응답 메타 */
export interface Attachment {
  attachment_id: string;
  filename: string;
  media_type: string;
  size_bytes: number;
  /** PDF 업로드 시에만 채워짐 (업로드 즉시 추출 정책에 따라 null 가능) */
  pages?: number | null;
  expires_at: string;
}

/**
 * CR-061: 업로드 진행 상태. 위젯 UI 의 칩 렌더링에 사용.
 * - uploading: 서버로 업로드 중
 * - ready: 서버 저장 성공, 전송 대기
 * - error: 실패 (에러 메시지 포함)
 */
export type AttachmentStatus = "uploading" | "ready" | "error";

export interface AttachmentDraft {
  /** 클라이언트 임시 id — ready 이전엔 서버 attachment_id 가 없음 */
  localId: string;
  file: File;
  status: AttachmentStatus;
  /** ready 이후에만 채워짐 */
  serverMeta?: Attachment;
  /** 이미지면 data URL 썸네일 */
  previewDataUrl?: string;
  error?: string;
}
