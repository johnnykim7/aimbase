/**
 * Aimbase Chat Widget — 공개 타입
 */
interface TokenResponse {
    token: string;
    /** ISO-8601 또는 epoch ms */
    expires_at: string | number;
    /** exp - 300초. 위젯이 이 시점에 authResolver 재호출 */
    refresh_after: number;
    scopes?: string[];
}
type AuthResolver = () => Promise<TokenResponse>;
type DisplayMode = "bubble" | "inline" | "panel";
interface ContextProvider {
    (): Record<string, unknown>;
}
interface Citation {
    index?: number;
    chunk_id?: string;
    source_id: string;
    document_name?: string;
    score: number;
    content_preview: string;
    page_number?: number;
    metadata?: Record<string, unknown>;
}
interface ChatDelta {
    type: "delta" | "thinking" | "tool_use_start" | "tool_result" | "done" | "error";
    text?: string;
    tool?: {
        id?: string;
        name?: string;
        input?: Record<string, unknown>;
    };
    toolResult?: {
        tool_use_id: string;
        output: string;
        is_error: boolean;
    };
    done?: {
        rag_used?: boolean;
        citations?: Citation[];
    };
    error?: string;
}
interface WorkflowStepEvent {
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
interface ApprovalEvent {
    run_id: string;
    step_id: string;
    policy_id: string;
    reason?: string;
    approvers: string[];
    timeout_at?: string;
}
interface WidgetOptions {
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
interface WidgetHandle {
    open(): void;
    close(): void;
    sendMessage(text: string): Promise<void>;
    abort(): Promise<void>;
    subscribeWorkflow(runId: string): () => void;
    destroy(): void;
}
/** CR-060: STT 변환 결과 (POST /api/v1/chat/stt 응답) */
interface SttResult {
    text: string;
    /** Whisper 자동 감지 or 요청한 language */
    language: string;
    /** verbose_json duration (seconds). 일부 응답에서 누락 가능 */
    duration_sec?: number;
}
/** CR-060: 위젯 STT 녹음 상태 머신 */
type SttRecordingState = "idle" | "requesting-permission" | "recording" | "uploading" | "error";

declare function createWidget(options: WidgetOptions): WidgetHandle;

/**
 * <aimbase-chat> 커스텀 엘리먼트.
 *
 * 기본 사용 — 스크립트에 baseUrl / token-endpoint 속성만 주면 동작.
 *   <aimbase-chat base-url="https://aimbase.company.com" token-endpoint="/my-bff/aimbase-token"></aimbase-chat>
 *
 * 고급 사용 — JS 에서 프로퍼티로 authResolver 직접 주입 가능:
 *   document.querySelector('aimbase-chat').authResolver = async () => ({ ... });
 */
declare class AimbaseChatElement extends HTMLElement {
    private handle;
    /** 사용자가 JS 로 직접 설정할 수 있는 프로퍼티 */
    authResolver: WidgetOptions["authResolver"] | null;
    contextProvider: WidgetOptions["contextProvider"] | null;
    ragSourceId: string | null;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    disconnectedCallback(): void;
    /** JS 에서 프로그램적으로 워크플로우 구독 */
    subscribeWorkflow(runId: string): () => void;
    open(): void;
    close(): void;
}
/** 엘리먼트 등록 — 중복 등록 방지. */
declare function defineAimbaseChat(): void;

/**
 * 토큰 수명주기 관리 — 만료 5분 전 자동 재호출.
 * 메모리 내 클로저 보관 (localStorage 사용 금지 — XSS 노출).
 */
declare class TokenStore {
    private readonly resolver;
    private token;
    private expiresAtMs;
    private refreshTimer;
    private onExpiring?;
    constructor(resolver: AuthResolver, onExpiring?: () => void);
    getToken(): Promise<string>;
    /** 현재 토큰이 특정 scope 를 포함하는지 (지연 초기화됨 — 미초기화면 false) */
    hasScope(scope: string): boolean;
    /** 토큰 메타 미리 로드 (getToken 호출로 초기화만 수행) */
    ensureLoaded(): Promise<void>;
    private refresh;
    private scheduleRefresh;
    destroy(): void;
}

interface SttArgs {
    baseUrl: string;
    sessionId: string;
    /** 녹음된 오디오 Blob (MediaRecorder 결과) */
    blob: Blob;
    /** "auto" 또는 ISO-639-1 코드. 미지정 시 서버 기본값 사용 */
    language?: string;
    /** MIME 에 맞는 확장자로 붙이는 파일명 (디폴트: rec.<ext>) */
    filename?: string;
}
/**
 * CR-060: 위젯 STT (Whisper) 클라이언트.
 * 엔드포인트: POST /api/v1/chat/stt (multipart/form-data, scope=chat:stt)
 *
 * 녹음 완료 후 1회 일괄 전송 방식 — 스트리밍 미지원(Whisper 한계).
 * XHR 로 구현해 업로드 진행률(onProgress) 을 얻는다.
 */
declare class SttClient {
    private readonly tokens;
    constructor(tokens: TokenStore);
    transcribe(args: SttArgs, onProgress?: (pct: number) => void): Promise<SttResult>;
}
declare class SttError extends Error {
    readonly code: string;
    readonly status: number;
    constructor(code: string, message: string, status: number);
}

/** 전역 네임스페이스 편의 API — <script> 로드 시 `window.AimbaseChat.init(...)` 로 호출 가능. */
declare function init(options: WidgetOptions): WidgetHandle;

export { AimbaseChatElement, type ApprovalEvent, type AuthResolver, type ChatDelta, type Citation, type DisplayMode, SttClient, SttError, type SttRecordingState, type SttResult, type TokenResponse, type WidgetHandle, type WidgetOptions, type WorkflowStepEvent, createWidget, defineAimbaseChat, init };
