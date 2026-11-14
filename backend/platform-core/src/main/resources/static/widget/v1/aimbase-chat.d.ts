import { TokenizerAndRendererExtension } from 'marked';

/**
 * 화면 언어와 문구표(CR-177, BIZ-160, BIZ-161).
 * 받는 언어는 ko, en 둘이다. 그 밖의 값은 없는 것으로 본다.
 */
type Locale = "ko" | "en";
/** 붙이는 앱이 주는 글. 글 하나이거나 언어별 값이다. */
type LocalizedText = string | Partial<Record<Locale, string>>;
type LocalizedList = string[] | Partial<Record<Locale, string[]>>;
interface StringPack<S> {
    readonly ko: S;
    readonly en: S;
}

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
/** CR-132: CLI 가 생성한 다운로드 산출물(pptx/xlsx/pdf/docx …) 메타. */
interface ChatArtifact {
    name: string;
    size: number;
    /** 서버 상대 경로. 위젯이 baseUrl 앞에 붙여 위젯 토큰으로 GET. */
    download_url: string;
}
interface ChatDelta {
    type: "delta" | "thinking" | "tool_use_start" | "tool_result" | "done" | "error" | "event";
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
    event?: {
        name: string;
        data: unknown;
    };
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
    /** 승인 번호. 구독 때 다시 받은 승인과 실시간으로 받은 승인이 같은지 이것으로 가린다. 옛 서버는 보내지 않는다 */
    approval_id?: string;
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
        onNewConversation?: (ev: {
            previousSessionId: string;
            sessionId: string;
        }) => void;
    };
}
type FontSize = "small" | "medium" | "large";
type FeedbackRating = "UP" | "DOWN";
/** CR-175: GET /api/v1/chat/widget-settings 응답. */
interface WidgetSettings {
    models: Array<{
        id: string;
        label: string;
    }>;
    default_model: string;
    thinking_supported: boolean;
    /** 깊게 생각하기 단추의 처음 상태. 연결 설정, 없으면 연결 방식의 기본(Claude CLI 는 켬). */
    thinking_default?: boolean;
    feedback_reasons: Array<{
        code: string;
        label: string;
    }>;
    feedback_comment_max_chars: number;
    follow_ups_enabled: boolean;
    /** CR-183: 받는 확장자와 묶음(image, pdf, data, text)별 크기 상한(바이트). 없는 서버면 검사는 서버가 한다 */
    attachments?: {
        accept: string[];
        max_bytes: Partial<Record<"image" | "pdf" | "data" | "text", number>>;
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
/** CR-061: 업로드된 첨부 파일의 서버 응답 메타 */
interface Attachment {
    attachment_id: string;
    filename: string;
    media_type: string;
    size_bytes: number;
    /** PDF 업로드 시에만 채워짐 (업로드 즉시 추출 정책에 따라 null 가능) */
    pages?: number | null;
    expires_at: string;
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
 * 위젯 틀이 기능에 주는 자리(CR-177, PRD-400, BIZ-162).
 * 쓰는 법은 docs/guides/chat-widget-frame.md 다. 이 파일의 모양을 바꾸면 그 문서도 같이 고친다.
 */

interface WidgetFeature {
    id: string;
    /** 위젯 그림자 영역 안에 덧붙는 이 기능의 스타일 */
    css?: string;
    install(ctx: WidgetContext): void;
}
interface MenuItemSpec {
    id: string;
    /** 같은 group 안에서 작은 수가 위 */
    order: number;
    /** group 이 바뀌는 곳에 구분선. 기본 0 */
    group?: number;
    icon?: string;
    /** 메뉴를 열 때마다 불러 지금 언어로 그린다 */
    label: () => string;
    /** 오른쪽 회색 보조 글 */
    hint?: () => string;
    /** 거짓이면 이번에 열 때 빠진다 */
    visible?: () => boolean;
    /** 메뉴를 닫은 뒤 불린다 */
    onSelect: () => void;
}
/** 글자 크기 줄처럼 안에 단추가 있는 줄 */
interface MenuRowSpec {
    id: string;
    order: number;
    group?: number;
    visible?: () => boolean;
    /** 열 때마다 새로 만든다 */
    render: () => HTMLElement;
}
interface MenuSlot {
    addItem(spec: MenuItemSpec): void;
    addRow(spec: MenuRowSpec): void;
    close(): void;
}
interface LeftPaneSpec {
    /** 머리줄 여닫기 단추의 도움말 */
    label: () => string;
    /** 빈 칸을 받아 그린다. 언어가 바뀌면 다시 그리는 것은 기능이 한다 */
    render: (body: HTMLElement) => void;
}
interface LeftPaneHandle {
    open(): void;
    close(): void;
    isOpen(): boolean;
}
interface SidePaneSpec {
    /** 같은 id 로 다시 열면 내용만 새로 그린다 */
    id: string;
    title: () => string;
    icon?: string;
    /** 머리줄 닫기 단추 앞 단추 */
    tools?: HTMLElement[];
    render: (body: HTMLElement) => void;
    /** 아래 줄 */
    footer?: (foot: HTMLElement) => void;
    onClose?: () => void;
}
interface ComposerToolSpec {
    id: string;
    order: number;
    render: () => HTMLElement;
}
interface ComposerModeSpec {
    id: string;
    /** 입력칸 위 표시 글. 옆에 × 가 붙는다 */
    label: () => string;
    icon?: string;
    /** 보낼 때마다 대화 요청 본문에 더한다 */
    requestFields: () => Record<string, unknown>;
    /** × 또는 새 대화로 꺼졌을 때 */
    onClear?: () => void;
}
/** 응답 하나. 읽기 전용이다. */
interface AnswerView {
    sessionId: string;
    turnId?: string;
    turnSeq?: number;
    question: string;
    /** 응답 원문(마크다운) */
    text: string;
    model?: string;
    citations: Citation[];
    artifacts: ChatArtifact[];
    stopped: boolean;
    error?: string;
    /** 마지막 질문의 마지막 버전 */
    latest: boolean;
    at: Date;
    /** 이 응답을 만든 요청에 더한 칸. 없으면 {} */
    requestBody: Readonly<Record<string, unknown>>;
    /** 끝 이벤트(done)에서 위젯 본체가 쓰지 않는 칸 그대로. 없으면 {} */
    extra: Readonly<Record<string, unknown>>;
}
interface AnswerActionSpec {
    id: string;
    order: number;
    render: (a: AnswerView) => HTMLElement | null;
}
interface AnswerDecoratorSpec {
    id: string;
    order: number;
    decorate: (body: HTMLElement, a: AnswerView) => void;
    /** 이 손질이 그릴 수 있는 형식 이름. 모아서 대화 요청 render_features 로 싣는다 */
    renderFeatures?: string[];
}
interface ArtifactOpenerSpec {
    id: string;
    order: number;
    canOpen: (f: ChatArtifact) => boolean;
    open: (f: ChatArtifact, a: AnswerView) => void;
}
interface ArtifactActionSpec {
    id: string;
    order: number;
    render: (f: ChatArtifact, a: AnswerView) => HTMLElement | null;
}
interface MarkdownExtensionSpec {
    id: string;
    extension: TokenizerAndRendererExtension;
}
interface TitleTagSpec {
    text: string;
    icon?: string;
    onClick?: () => void;
}
interface ConversationView {
    sessionId: string;
    title?: string;
    /** 맨 위 회색 줄 한 줄. 문구는 부르는 건이 준다 */
    notice?: string;
    turns: Array<{
        /** 없으면 그 응답의 다시 생성, 질문 수정, 평가를 숨긴다 */
        turnId?: string;
        turnSeq?: number;
        question: {
            text: string;
            at?: Date;
            attachments?: Array<{
                filename: string;
                mediaType: string;
                available: boolean;
            }>;
        };
        answer: {
            text: string;
            model?: string;
            at?: Date;
            stopped?: boolean;
            tools?: string[];
            extra?: Record<string, unknown>;
            requestBody?: Record<string, unknown>;
        };
        feedback?: {
            rating: FeedbackRating;
            reasonCodes?: string[];
            comment?: string;
        };
    }>;
}
interface WorkflowApprovalEvent {
    runId: string;
    stepId: string;
    policyId: string;
    reason?: string;
    approvers: string[];
    timeoutAt?: string;
    /** 그 워크플로우 진행 칸의 승인 대기 줄 바로 아래에 끼운다 */
    mount: (el: HTMLElement) => boolean;
}
interface WorkflowDoneEvent {
    runId: string;
    status: "completed" | "failed" | "cancelled";
    /** 실패나 거절 이유. 서버가 준 글 그대로이고 없으면 비운다 */
    reason?: string;
    /** 서버 error.reason 원문(approval_timeout, Human approval rejected, step_budget_exceeded 등). 기능이 이것으로 문구를 고른다. 없으면 비운다 */
    code?: string;
}
interface WidgetEvents {
    /** 위젯 창을 열거나 닫았을 때. 늘 열려 있는 inline 은 오지 않는다 */
    panelToggle: {
        open: boolean;
    };
    localeChange: {
        locale: Locale;
    };
    newConversation: {
        previousSessionId: string;
        sessionId: string;
    };
    answerStart: AnswerView;
    answerDelta: {
        answer: AnswerView;
        text: string;
    };
    answerDone: AnswerView;
    streamEvent: {
        name: string;
        data: unknown;
        answer: AnswerView;
        /** 도는 중인 응답의 지금 위치에 끼운다. 응답이 끝났으면 거짓 */
        mount: (el: HTMLElement) => boolean;
    };
    modelChange: {
        model: string | undefined;
        thinking: boolean;
    };
    titleChange: {
        sessionId: string;
        title: string;
    };
    settingsLoaded: WidgetSettings;
    workflowApproval: WorkflowApprovalEvent;
    /** 워크플로우 진행 칸의 런이 끝났을 때 */
    workflowDone: WorkflowDoneEvent;
    destroy: void;
}
interface WidgetContext {
    locale(): Locale;
    /** 화면 언어를 바꾸고 그 브라우저에 기억한다 */
    setLocale(locale: Locale): void;
    strings<S extends object>(pack: StringPack<S>): S;
    text(value: LocalizedText | undefined): string | undefined;
    list(value: LocalizedList | undefined): string[];
    sessionId(): string;
    isBusy(): boolean;
    isRecording(): boolean;
    widgetSettings(): WidgetSettings | null;
    connectionId(): string | null;
    /** 붙이는 앱 옵션 allowApproval(속성 allow-approval). 기본 참 */
    allowApproval(): boolean;
    on<K extends keyof WidgetEvents>(name: K, cb: (payload: WidgetEvents[K]) => void): () => void;
    ui: {
        plusMenu: MenuSlot;
        moreMenu: MenuSlot;
        leftPane: {
            mount(spec: LeftPaneSpec): LeftPaneHandle;
        };
        sidePane: {
            open(spec: SidePaneSpec): void;
            close(): void;
            currentId(): string | null;
        };
        composer: {
            addTool(spec: ComposerToolSpec): void;
            setMode(spec: ComposerModeSpec): void;
            clearMode(): void;
            currentMode(): string | null;
        };
        answer: {
            addAction(spec: AnswerActionSpec): void;
            addDecorator(spec: AnswerDecoratorSpec): void;
            addArtifactOpener(spec: ArtifactOpenerSpec): void;
            addArtifactAction(spec: ArtifactActionSpec): void;
            addMarkdownExtension(spec: MarkdownExtensionSpec): void;
        };
        header: {
            setTitleTag(spec: TitleTagSpec | null): void;
        };
        addErrorTexts(map: Record<string, () => string>): void;
        /** 응답 본문과 같은 모양(표 단추, 코드 강조, 등록된 마크다운 확장, 지금 언어 문구)으로 마크다운을 그린다 */
        renderMarkdown(target: HTMLElement, text: string): void;
        fontSize(): FontSize;
        /** 글자 크기를 바꾸고 그 브라우저에 기억한다 */
        setFontSize(size: FontSize): void;
        layer: HTMLElement;
        toast(text: string): void;
        notice(text: string): void;
        onEscape(fn: () => boolean): () => void;
    };
    chat: {
        send(text: string, opts?: {
            body?: Record<string, unknown>;
        }): Promise<void>;
        abort(): Promise<void>;
        open(view: ConversationView): Promise<void>;
        selectModel(id: string): boolean;
        setThinking(on: boolean): boolean;
        setTitle(text: string): void;
        newConversation(): Promise<void>;
        attach(file: File): Promise<void>;
        attachUploaded(meta: Attachment): void;
        /** + 메뉴 「파일 올리기」와 같은 기준으로 본다. 받으면 null, 아니면 지금 언어 거절 문구 */
        checkAttachment(file: {
            name: string;
            type: string;
            size: number;
        }): string | null;
        addRequestFields(fn: () => Record<string, unknown>): void;
        /**
         * 위젯이 뜰 때 받아야 대화 요청에 실을 수 있는 것(내 선호의 지난 모델 등)을 건다. 첫 질문은 이것이 끝나거나
         * 시한이 지날 때까지 기다렸다 보낸다(CR-188). 위젯 설정을 처음 받을 때(settingsLoaded)까지 건 것만 기다린다
         */
        waitBeforeSend(task: Promise<unknown>): void;
    };
    api: {
        /** body 가 FormData 면 multipart 로 그대로, 그 밖에는 JSON 으로 싣는다 */
        request<R>(method: string, path: string, body?: unknown): Promise<R>;
        /** body 는 request 와 같이 싣는다. signal 로 받는 중인 요청을 끊는다 */
        requestBlob(method: string, path: string, body?: unknown, opts?: {
            signal?: AbortSignal;
        }): Promise<Blob>;
        /** 위젯 토큰에 그 scope 가 있으면 참. 토큰을 받기 전에는 거짓이라 settingsLoaded 뒤나 쓰는 때에 묻는다 */
        hasScope(scope: string): boolean;
        /** 위젯 토큰에 사용자 식별값(user_ref)이 있으면 참. hasScope 와 같이 토큰을 받기 전에는 거짓 */
        hasUserRef(): boolean;
        /** 위젯 서버 주소로 만든 절대 웹소켓 주소. http 는 ws, https 는 wss */
        wsUrl(path: string): string;
    };
}

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
    /**
     * 토큰을 받을 때 붙이는 앱이 사용자 식별값(user_ref)을 줬는지. 토큰을 받기 전에는 거짓.
     * 서명은 서버가 검사하므로 여기서는 화면을 정하는 데만 쓴다.
     */
    hasUserRef(): boolean;
    private claimsOf;
    private claims;
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

export { AimbaseChatElement, type AnswerActionSpec, type AnswerDecoratorSpec, type AnswerView, type ApprovalEvent, type ArtifactActionSpec, type ArtifactOpenerSpec, type AuthResolver, type ChatArtifact, type ChatDelta, type Citation, type ComposerModeSpec, type ComposerToolSpec, type ConversationView, type DisplayMode, type FeedbackRating, type FontSize, type LeftPaneHandle, type LeftPaneSpec, type Locale, type LocalizedList, type LocalizedText, type MarkdownExtensionSpec, type MenuItemSpec, type MenuRowSpec, type MenuSlot, type SidePaneSpec, type StringPack, SttClient, SttError, type SttRecordingState, type SttResult, type TitleTagSpec, type TokenResponse, type WidgetContext, type WidgetEvents, type WidgetFeature, type WidgetHandle, type WidgetOptions, type WidgetSettings, type WorkflowApprovalEvent, type WorkflowDoneEvent, type WorkflowStepEvent, createWidget, defineAimbaseChat, init };
