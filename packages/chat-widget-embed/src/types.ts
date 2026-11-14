/**
 * Aimbase Chat Widget — 공개 타입
 */
import type { LocalizedList, LocalizedText } from "./frame/i18n";

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

/** CR-132: CLI 가 생성한 다운로드 산출물(pptx/xlsx/pdf/docx …) 메타. */
export interface ChatArtifact {
  name: string;
  size: number;
  /** 서버 상대 경로. 위젯이 baseUrl 앞에 붙여 위젯 토큰으로 GET. */
  download_url: string;
}

export interface ChatDelta {
  type: "delta" | "thinking" | "tool_use_start" | "tool_result" | "done" | "error" | "event";
  text?: string;
  tool?: { id?: string; name?: string; input?: Record<string, unknown> };
  toolResult?: { tool_use_id: string; output: string; is_error: boolean };
  done?: {
    rag_used?: boolean;
    citations?: Citation[];
    /** CR-132: 이 턴에 생성된 산출물. 위젯이 다운로드 칩으로 렌더. */
    artifacts?: ChatArtifact[];
    /** CR-175: 이 답의 번호. 평가와 후속 질문이 이 번호로 답을 가리킨다. */
    turn_id?: string;
    /** CR-175: 이 답을 이룬 메시지 묶음의 첫 순번. 다시 생성, 질문 수정이 이 순번부터 되감는다. */
    turn_seq?: number;
    /** CR-175: 실제로 답한 모델. */
    model?: string;
    /** CR-177: 위젯 본체가 쓰지 않는 끝 이벤트 칸. 기능이 응답 보기(extra)로 읽는다. */
    extra?: Record<string, unknown>;
  };
  error?: string;
  /** CR-177: 응답 중 서버가 보낸 오류 이벤트의 코드 */
  errorCode?: string;
  /** CR-177: 위젯 본체가 모르는 이벤트. 기능에 그대로 알린다. */
  event?: { name: string; data: unknown };
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
  /** 승인 번호. 구독 때 다시 받은 승인과 실시간으로 받은 승인이 같은지 이것으로 가린다. 옛 서버는 보내지 않는다 */
  approval_id?: string;
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
  /** 사용할 Connection ID — chat request 의 connection_id 로 전달. 미지정 시 테넌트 default. */
  connectionId?: string;
  /** MCP 도구 자동 호출 활성화 — chat request 의 actions_enabled=true 로 전달. 기본 false. */
  actionsEnabled?: boolean;
  /** 모델 명시 지정 — chat request 의 model 로 전달. 미지정 시 "auto". */
  model?: string;
  /** CR-175: 첫 화면 추천 질문. 최대 4개까지 보인다. CR-177: 언어별 목록({ ko: [...], en: [...] })도 받는다. */
  suggestions?: LocalizedList;
  /** CR-175: 첫 화면 안내 제목. 없으면 위젯 기본 문구. CR-177: 언어별 값({ ko, en })도 받는다. */
  emptyTitle?: LocalizedText;
  /** CR-175: 첫 화면 안내 한 줄. 없으면 비운다. CR-177: 언어별 값({ ko, en })도 받는다. */
  emptySubtitle?: LocalizedText;
  /** CR-177: 화면 언어 기본값(ko, en). 사용자가 더 보기에서 바꾸면 그 브라우저에서는 그 값을 따른다. */
  locale?: string;
  /** CR-175: 글자 크기 기본값. 사용자가 더보기에서 바꾸면 그 브라우저에서는 그 값을 따른다. */
  fontSize?: FontSize;
  /** 위젯 안에서 승인과 거절 단추를 둘지. 기본 true(CR-185). false 면 붙이는 앱이 onApprovalRequired 로 자기 결재 화면을 쓴다 */
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
    /** CR-175: 새 대화를 시작했을 때. 앞 대화는 서버에 남는다. */
    onNewConversation?: (ev: { previousSessionId: string; sessionId: string }) => void;
  };
}

export type FontSize = "small" | "medium" | "large";

export type FeedbackRating = "UP" | "DOWN";

/** CR-175: GET /api/v1/chat/widget-settings 응답. */
export interface WidgetSettings {
  models: Array<{ id: string; label: string }>;
  default_model: string;
  thinking_supported: boolean;
  /** 깊게 생각하기 단추의 처음 상태. 연결 설정, 없으면 연결 방식의 기본(Claude CLI 는 켬). */
  thinking_default?: boolean;
  feedback_reasons: Array<{ code: string; label: string }>;
  feedback_comment_max_chars: number;
  follow_ups_enabled: boolean;
  /** CR-183: 받는 확장자와 묶음(image, pdf, data, text)별 크기 상한(바이트). 없는 서버면 검사는 서버가 한다 */
  attachments?: { accept: string[]; max_bytes: Partial<Record<"image" | "pdf" | "data" | "text", number>> };
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

/** CR-060: STT 변환 결과 (POST /api/v1/chat/stt 응답) */
export interface SttResult {
  text: string;
  /** Whisper 자동 감지 or 요청한 language */
  language: string;
  /** verbose_json duration (seconds). 일부 응답에서 누락 가능 */
  duration_sec?: number;
}

/** CR-060: 위젯 STT 녹음 상태 머신 */
export type SttRecordingState =
  | "idle"
  | "requesting-permission"
  | "recording"
  | "uploading"
  | "error";
