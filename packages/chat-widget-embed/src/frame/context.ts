/**
 * 위젯 틀이 기능에 주는 자리(CR-177, PRD-400, BIZ-162).
 * 쓰는 법은 docs/guides/chat-widget-frame.md 다. 이 파일의 모양을 바꾸면 그 문서도 같이 고친다.
 */
import type { TokenizerAndRendererExtension } from "marked";
import type { Attachment, ChatArtifact, Citation, FeedbackRating, FontSize, WidgetSettings } from "../types";
import type { LocalizedList, LocalizedText, Locale, StringPack } from "./i18n";

export interface WidgetFeature {
  id: string;
  /** 위젯 그림자 영역 안에 덧붙는 이 기능의 스타일 */
  css?: string;
  install(ctx: WidgetContext): void;
}

export interface MenuItemSpec {
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
export interface MenuRowSpec {
  id: string;
  order: number;
  group?: number;
  visible?: () => boolean;
  /** 열 때마다 새로 만든다 */
  render: () => HTMLElement;
}

export interface MenuSlot {
  addItem(spec: MenuItemSpec): void;
  addRow(spec: MenuRowSpec): void;
  close(): void;
}

export interface LeftPaneSpec {
  /** 머리줄 여닫기 단추의 도움말 */
  label: () => string;
  /** 빈 칸을 받아 그린다. 언어가 바뀌면 다시 그리는 것은 기능이 한다 */
  render: (body: HTMLElement) => void;
}

export interface LeftPaneHandle {
  open(): void;
  close(): void;
  isOpen(): boolean;
}

export interface SidePaneSpec {
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

export interface ComposerToolSpec {
  id: string;
  order: number;
  render: () => HTMLElement;
}

export interface ComposerModeSpec {
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
export interface AnswerView {
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

export interface AnswerActionSpec {
  id: string;
  order: number;
  render: (a: AnswerView) => HTMLElement | null;
}

export interface AnswerDecoratorSpec {
  id: string;
  order: number;
  decorate: (body: HTMLElement, a: AnswerView) => void;
  /** 이 손질이 그릴 수 있는 형식 이름. 모아서 대화 요청 render_features 로 싣는다 */
  renderFeatures?: string[];
}

export interface ArtifactOpenerSpec {
  id: string;
  order: number;
  canOpen: (f: ChatArtifact) => boolean;
  open: (f: ChatArtifact, a: AnswerView) => void;
}

export interface ArtifactActionSpec {
  id: string;
  order: number;
  render: (f: ChatArtifact, a: AnswerView) => HTMLElement | null;
}

export interface MarkdownExtensionSpec {
  id: string;
  extension: TokenizerAndRendererExtension;
}

export interface TitleTagSpec {
  text: string;
  icon?: string;
  onClick?: () => void;
}

export interface ConversationView {
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
      attachments?: Array<{ filename: string; mediaType: string; available: boolean }>;
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
    feedback?: { rating: FeedbackRating; reasonCodes?: string[]; comment?: string };
  }>;
}

export interface WorkflowApprovalEvent {
  runId: string;
  stepId: string;
  policyId: string;
  reason?: string;
  approvers: string[];
  timeoutAt?: string;
  /** 그 워크플로우 진행 칸의 승인 대기 줄 바로 아래에 끼운다 */
  mount: (el: HTMLElement) => boolean;
}

export interface WorkflowDoneEvent {
  runId: string;
  status: "completed" | "failed" | "cancelled";
  /** 실패나 거절 이유. 서버가 준 글 그대로이고 없으면 비운다 */
  reason?: string;
}

export interface WidgetEvents {
  /** 위젯 창을 열거나 닫았을 때. 늘 열려 있는 inline 은 오지 않는다 */
  panelToggle: { open: boolean };
  localeChange: { locale: Locale };
  newConversation: { previousSessionId: string; sessionId: string };
  answerStart: AnswerView;
  answerDelta: { answer: AnswerView; text: string };
  answerDone: AnswerView;
  streamEvent: {
    name: string;
    data: unknown;
    answer: AnswerView;
    /** 도는 중인 응답의 지금 위치에 끼운다. 응답이 끝났으면 거짓 */
    mount: (el: HTMLElement) => boolean;
  };
  modelChange: { model: string | undefined; thinking: boolean };
  titleChange: { sessionId: string; title: string };
  settingsLoaded: WidgetSettings;
  workflowApproval: WorkflowApprovalEvent;
  /** 워크플로우 진행 칸의 런이 끝났을 때 */
  workflowDone: WorkflowDoneEvent;
  destroy: void;
}

export interface WidgetContext {
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
    leftPane: { mount(spec: LeftPaneSpec): LeftPaneHandle };
    sidePane: { open(spec: SidePaneSpec): void; close(): void; currentId(): string | null };
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
    header: { setTitleTag(spec: TitleTagSpec | null): void };
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
    send(text: string, opts?: { body?: Record<string, unknown> }): Promise<void>;
    abort(): Promise<void>;
    open(view: ConversationView): Promise<void>;
    selectModel(id: string): boolean;
    setThinking(on: boolean): boolean;
    setTitle(text: string): void;
    newConversation(): Promise<void>;
    attach(file: File): Promise<void>;
    attachUploaded(meta: Attachment): void;
    addRequestFields(fn: () => Record<string, unknown>): void;
  };
  api: {
    /** body 가 FormData 면 multipart 로 그대로, 그 밖에는 JSON 으로 싣는다 */
    request<R>(method: string, path: string, body?: unknown): Promise<R>;
    /** body 는 request 와 같이 싣는다. signal 로 받는 중인 요청을 끊는다 */
    requestBlob(method: string, path: string, body?: unknown, opts?: { signal?: AbortSignal }): Promise<Blob>;
    /** 위젯 토큰에 그 scope 가 있으면 참. 토큰을 받기 전에는 거짓이라 settingsLoaded 뒤나 쓰는 때에 묻는다 */
    hasScope(scope: string): boolean;
    /** 위젯 토큰에 사용자 식별값(user_ref)이 있으면 참. hasScope 와 같이 토큰을 받기 전에는 거짓 */
    hasUserRef(): boolean;
    /** 위젯 서버 주소로 만든 절대 웹소켓 주소. http 는 ws, https 는 wss */
    wsUrl(path: string): string;
  };
}
