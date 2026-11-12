# 채팅 위젯 틀 사용법

> 문서 버전: 1.4 | 최종 수정: 2026-11-13 | 관련 CR: CR-177

채팅 위젯(`packages/chat-widget-embed`)에 기능을 붙이는 건이 지켜야 할 약속이다. CR-178 ~ CR-186 이 같은 위젯을 동시에 고치므로, 각 건은 아래 자리에 자기 기능을 등록만 하고 위젯 본체(`src/widget.ts`)와 틀(`src/frame/`)은 고치지 않는다.

- 화면 배치의 기준은 CR-175 실화면 목업 「개선안」(2026-11-10 사용자 확인)이다.
- 이 문서는 CR-177 이 dev 에 들어가는 시점의 코드와 같다. 들어가기 전에는 설계 약속이다.

## 1. 파일 자리

| 순번 | 경로 | 주인 | 각 건이 하는 일 |
|---|---|---|---|
| 1 | `src/widget.ts` | 위젯 본체(대화 칸, 입력칸, 첨부, 마이크, 모델 선택) | 고치지 않는다 |
| 2 | `src/frame/` | 틀(자리, 메뉴, 옆 창, 왼쪽 칸, 문구표, 알림) | 고치지 않는다 |
| 3 | `src/features/<기능>.ts` | 각 건 | 새 파일을 만든다. 기능 하나에 파일 하나, 크면 `src/features/<기능>/` 폴더 |
| 4 | `src/features/index.ts` | 공용 등록표 | import 한 줄과 목록 한 줄을 더한다 |
| 5 | `src/types.ts`, `src/web-component.ts` | 공용 | 붙이는 앱 옵션이 새로 필요할 때만 끝에 더한다 |
| 6 | `src/strings.ts`, `src/styles.ts`, `src/icons.ts`, `src/widget-api.ts` | 위젯 본체 | 고치지 않는다. 문구, 스타일, 아이콘, 서버 호출은 자기 파일에 둔다 |

등록표 예:

```ts
// src/features/index.ts
import { fontSize } from "./font-size";
import { language } from "./language";
import { history } from "./history";      // CR-178 이 더한 줄

export const FEATURES: WidgetFeature[] = [fontSize, language, history];
```

두 건이 같은 자리에 줄을 더해 git 충돌이 나면 양쪽 줄을 모두 남긴다. 목록 순서는 화면 순서와 상관없다(화면 순서는 각 자리의 `order` 가 정한다).

## 2. 기능 하나의 모양

```ts
import type { WidgetFeature } from "../frame/context";
import { defineStrings } from "../frame/i18n";

const S = defineStrings({
  ko: { exportChat: "대화 내보내기", exported: (n: number) => `${n}개 메시지를 내보냈습니다` },
  en: { exportChat: "Export chat", exported: (n: number) => `Exported ${n} messages` },
});

export const exportChat: WidgetFeature = {
  id: "export-chat",
  css: `.xc-row { display: flex; gap: 6px; }`,   // 이 기능에만 쓰는 스타일
  install(ctx) {
    const t = ctx.strings(S);                    // 지금 화면 언어의 문구를 돌려주는 보기
    ctx.ui.moreMenu.addItem({
      id: "export-chat", order: 10, group: 0, icon: DOWNLOAD_ICON,
      label: () => t.exportChat,
      onSelect: () => { /* ... */ },
    });
  },
};
```

- `install` 은 위젯이 뜰 때 한 번 불린다. 위젯 본체가 먼저 그려진 뒤다.
- `css` 는 위젯 그림자 영역(Shadow DOM) 안에 덧붙는다. 클래스 이름 앞에 기능 약자를 붙여 겹치지 않게 한다(`xc-`, `hist-`).
- 아이콘은 자기 파일에 SVG 경로로 두고 `svgIcon(paths, size)` 로 만든다(`src/icons.ts` 에서 import). 위젯 본체 아이콘(`icon("x")` 등)은 그대로 써도 된다.

## 3. 자리 목록

`install(ctx)` 가 받는 `ctx` 의 자리다.

| 순번 | 자리 | 등록 | 쓰는 건 |
|---|---|---|---|
| 1 | + 메뉴 | `ctx.ui.plusMenu.addItem(spec)` | CR-178 프로젝트 자료에서, CR-184 그림 만들기 |
| 2 | 더 보기 메뉴 | `ctx.ui.moreMenu.addItem(spec)`, `addRow(spec)` | CR-178 프로젝트로 옮기기, CR-182 대화 내보내기, 공유 링크 |
| 3 | 왼쪽 칸 | `ctx.ui.leftPane.mount(spec)` | CR-178 지난 대화 목록 |
| 4 | 옆 창 | `ctx.ui.sidePane.open(spec)`, `close()` | CR-180 미리보기, CR-181 고치기 |
| 5 | 입력칸 단추, 입력칸 모드 | `ctx.ui.composer.addTool(spec)`, `setMode(spec)` | CR-186 음성 대화, CR-184 그림 만들기 |
| 6 | 응답 아래 단추 | `ctx.ui.answer.addAction(spec)` | CR-182 응답 저장, CR-186 읽어 주기 |
| 7 | 응답 본문 손질 | `ctx.ui.answer.addDecorator(spec)`, `addMarkdownExtension(spec)` | CR-180 HTML, SVG 보기 단추, CR-183 그래프, 수식, CR-184 그림 |
| 8 | 산출물 열기 | `ctx.ui.answer.addArtifactOpener(spec)` | CR-180 산출물 칩을 누르면 옆 창 |
| 9 | 산출물 칩 단추 | `ctx.ui.answer.addArtifactAction(spec)` | CR-182 파일 공유 |
| 10 | 문구표, 오류 문구 | `defineStrings`, `ctx.strings(pack)`, `ctx.ui.addErrorTexts(map)` | 모든 건, CR-184 그림 오류 |
| 11 | 알림 | `ctx.on(name, cb)` | CR-179, CR-181, CR-186 등 |
| 12 | 대화 동작 | `ctx.chat.send`, `abort`, `open`, `selectModel`, `setThinking`, `setTitle`, `newConversation`, `attach`, `attachUploaded`, `addRequestFields` | CR-178, CR-179, CR-181, CR-186 등 |
| 13 | 서버 호출 | `ctx.api.request(method, path, body?)`, `requestBlob(method, path)` | 서버를 부르는 건, CR-180 그림과 원본 파일 |
| 14 | 덮개, 토스트, Esc | `ctx.ui.layer`, `toast`, `notice`, `onEscape` | 필요한 건 |
| 15 | 머리줄 제목 표시 | `ctx.ui.header.setTitleTag(spec)` | CR-178 프로젝트 표시 |
| 16 | 위젯 설정 읽기 | `ctx.widgetSettings()`, `ctx.connectionId()`, 알림 `settingsLoaded` | CR-184 그림 만들기 보이기 |

### 3.1 + 메뉴와 더 보기 메뉴

```ts
interface MenuItemSpec {
  id: string;
  order: number;              // 같은 group 안에서 작은 수가 위
  group?: number;             // group 이 바뀌는 곳에 구분선. 기본 0
  icon?: string;              // svgIcon(...) 또는 icon(...) 결과
  label: () => string;        // 메뉴를 열 때마다 불러 지금 언어로 그린다
  hint?: () => string;        // 오른쪽 회색 보조 글
  visible?: () => boolean;    // 거짓이면 이번에 열 때 빠진다
  onSelect: () => void;       // 메뉴를 닫은 뒤 불린다
}
interface MenuRowSpec {        // 글자 크기 줄처럼 안에 단추가 있는 줄
  id: string; order: number; group?: number; visible?: () => boolean;
  render: () => HTMLElement;  // 열 때마다 새로 만든다
}
```

자리 번호는 목업 순서를 따른다. 새 항목은 빈 번호를 쓴다.

| 순번 | 메뉴 | group | order | 항목 | 건 |
|---|---|---|---|---|---|
| 1 | + | 0 | 10 | 파일 올리기 | 위젯 본체 |
| 2 | + | 0 | 20 | 프로젝트 자료에서 | CR-178 |
| 3 | + | 0 | 30 | 이 화면 캡처 | (주인 없음) |
| 4 | + | 1 | 10 | 그림 만들기 | CR-184 |
| 5 | 더 보기 | 0 | 10 | 대화 내보내기 | CR-182 |
| 6 | 더 보기 | 0 | 20 | 공유 링크 만들기 | CR-182 |
| 7 | 더 보기 | 0 | 30 | 프로젝트로 옮기기 | CR-178 |
| 8 | 더 보기 | 1 | 10 | 글자 크기 | CR-177 (`features/font-size.ts`) |
| 9 | 더 보기 | 1 | 20 | 화면 언어 | CR-177 (`features/language.ts`) |

- 보이는 항목이 하나도 없는 메뉴는 단추째 숨는다. 눌러도 아무 일 없는 단추를 두지 않는다(BIZ-162).
- + 단추는 첨부 클립 단추 자리에 있다. 파일 올리기는 위젯 본체가 등록한다.

### 3.2 왼쪽 칸

```ts
interface LeftPaneSpec {
  label: () => string;                    // 머리줄 여닫기 단추의 도움말(예: 대화 목록)
  render: (body: HTMLElement) => void;    // 빈 칸을 받아 그린다. 언어가 바뀌면 다시 그리는 것은 기능이 한다
}
const pane = ctx.ui.leftPane.mount(spec); // 위젯 하나에 하나. 두 번째 mount 는 오류
pane.open(); pane.close(); pane.isOpen();
```

- mount 하면 머리줄 맨 왼쪽에 여닫기 단추가 생긴다. mount 하지 않으면 단추도 칸도 없다.
- 위젯 폭이 760px 이상(크게 보기 포함)이면 칸이 대화 옆에 228px 로 붙고 처음에 열려 있다.
- 760px 보다 좁으면 대화 위에 겹쳐 열리고 처음에 닫혀 있다. 칸 밖을 누르거나 Esc 로 닫힌다. 목록에서 대화를 고른 뒤 닫는 것은 기능이 `pane.close()` 로 한다.
- 옆 창이 열리면 왼쪽 칸은 닫힌다.

### 3.3 옆 창

```ts
interface SidePaneSpec {
  id: string;                             // 같은 id 로 다시 열면 내용만 새로 그린다
  title: () => string;
  icon?: string;
  tools?: HTMLElement[];                  // 머리줄 닫기 단추 앞 단추(내려받기, 코드 보기 등)
  render: (body: HTMLElement) => void;
  footer?: (foot: HTMLElement) => void;   // 아래 줄(CR-181 고치기 입력칸)
  onClose?: () => void;
}
ctx.ui.sidePane.open(spec);
ctx.ui.sidePane.close();
ctx.ui.sidePane.currentId();              // 열려 있는 id, 없으면 null
```

- 위젯 폭이 760px 보다 좁으면 열 때 먼저 크게 보기로 바뀐다. 옆 창은 위젯 폭의 56% 다.
- 크게 보기를 닫아 폭이 760px 보다 좁아지면 옆 창도 닫힌다. Esc 는 열린 메뉴, 옆 창, 크게 보기 순으로 닫는다.
- 옆 창은 한 번에 하나다. 다른 id 로 열면 앞 창의 `onClose` 를 부른 뒤 바꾼다.
- CR-181 은 CR-180 이 여는 창의 `footer` 를 쓴다. 둘이 [계약] 으로 맞춘다.

### 3.4 입력칸 단추

```ts
ctx.ui.composer.addTool({ id: "voice-chat", order: 10, render: () => button });
```

마이크 단추와 전송 단추 사이에 order 순으로 놓인다. 단추 상태와 도움말 언어는 기능이 관리한다.

입력칸 모드는 「그림 만들기」처럼 그동안 보내는 질문에 칸을 더 싣는 상태다.

```ts
interface ComposerModeSpec {
  id: string;
  label: () => string;                          // 입력칸 위 표시 글. 옆에 × 가 붙는다
  icon?: string;
  requestFields: () => Record<string, unknown>; // 보낼 때마다 대화 요청 본문에 더한다
  onClear?: () => void;                         // × 또는 새 대화로 꺼졌을 때
}
ctx.ui.composer.setMode(spec);
ctx.ui.composer.clearMode();
ctx.ui.composer.currentMode();                  // id, 없으면 null
```

- 모드는 한 번에 하나다. 다른 모드를 켜면 앞 모드의 `onClear` 를 부른 뒤 바꾼다.
- × 를 누르거나 새 대화를 시작하면 꺼진다. 보낸 뒤에는 유지한다.

### 3.5 응답 자리

```ts
interface AnswerView {          // 읽기 전용
  sessionId: string;
  turnId?: string; turnSeq?: number;
  question: string;
  text: string;                 // 응답 원문(마크다운)
  model?: string;
  citations: Citation[];
  artifacts: ChatArtifact[];
  stopped: boolean; error?: string;
  latest: boolean;              // 마지막 질문의 마지막 버전
  at: Date;
  requestBody: Readonly<Record<string, unknown>>; // 이 응답을 만든 요청에 더한 칸(3.7 요청 본문 덧붙임). 없으면 {}
  extra: Readonly<Record<string, unknown>>;       // 끝 이벤트(done)에서 위젯 본체가 쓰지 않는 칸 그대로. 없으면 {}
}

ctx.ui.answer.addAction({ id, order, render: (a) => HTMLElement | null });
ctx.ui.answer.addDecorator({ id, order, decorate: (body, a) => void, renderFeatures?: string[] });
ctx.ui.answer.addArtifactOpener({ id, order, canOpen: (f) => boolean, open: (f, a) => void });
ctx.ui.answer.addArtifactAction({ id, order, render: (f, a) => HTMLElement | null });
ctx.ui.answer.addMarkdownExtension({ id, extension });   // extension 은 marked 의 TokenizerAndRendererExtension
```

- 응답 아래 단추는 끝난 응답에만 그린다. 기본 단추(복사, 다시 생성, 버전, 좋아요, 아쉬워요) 뒤, 시각 앞에 order 순으로 놓인다. null 을 돌려주면 그 응답에는 그리지 않는다.
- 손질(decorate)은 응답이 끝나 마크다운으로 그려진 뒤마다 불린다(버전 바꿈, 언어 바꿈 포함). 본문은 매번 새로 그려지므로 같은 일을 다시 해도 겹치지 않는다. 답이 도는 중에는 부르지 않는다.
- `renderFeatures` 는 그 손질이 그릴 수 있는 형식 이름이다(예: `chart`, `math`). 등록된 모든 손질의 이름을 모아 대화 요청에 `render_features` 배열로 싣는다. 하나도 없으면 싣지 않는다. 서버가 그 칸을 어떻게 쓰는지는 그 건이 정한다.
- 응답 글이 비어도 오류가 아니면 응답 칸과 아래 줄을 그린다(그림만 있는 답).
- 산출물 칩을 누르면 order 순으로 `canOpen` 이 참인 첫 열기가 받는다. 없으면 위젯 본체가 내려받는다.
- 산출물 칩 단추는 칩 끝에 order 순으로 붙는 작은 단추다. 단추를 누르면 칩 열기는 불리지 않는다. null 을 돌려주면 그 칩에는 붙이지 않는다.
- 마크다운 확장은 응답 원문을 HTML 로 바꾸는 단계에 끼어든다(수식 `$...$` 처럼 강조 해석보다 먼저 잡아야 하는 것). 답이 도는 중에도 쓰인다. 만든 HTML 은 위젯 본체의 소독(DOMPurify)을 거치므로 허용 태그와 `data-` 속성으로 자리만 남기고, 실제 그리기는 손질(decorate)에서 한다. 확장은 그 위젯에만 걸린다.

### 3.6 문구표

```ts
const S = defineStrings({ ko: {...}, en: {...} });   // ko 와 en 의 키가 다르면 타입 검사에서 막힌다
const t = ctx.strings(S);                            // t.key 를 읽을 때마다 지금 언어 값
```

- 화면에 나가는 글은 모두 문구표에서 꺼낸다. 기능 코드에 한글 글자를 직접 쓰지 않는다(주석 제외).
- 값이 바뀌는 문구는 함수로 둔다(`(n: number) => ...`).
- 한국어 이름은 `docs/T1-10_용어표.md` 를 따른다. 용어표에 없는 화면 이름은 먼저 용어표에 올리고 사용자 승인을 받는다.
- 서버가 주는 표시 글(아쉬워요 이유 등)은 서버가 언어별로 준다. 위젯 설정 조회에 `locale` 을 실어 부른다(위젯 본체가 한다).
- 서버 오류 코드를 글로 바꿀 때 위젯 본체 문구표를 먼저 보고, 없으면 `ctx.ui.addErrorTexts({ code: () => t.text })` 로 등록된 글을 본다. 둘 다 없으면 서버가 준 글, 그것도 없으면 코드를 보인다.
- 붙이는 앱이 주는 글(`emptyTitle`, `suggestions` 등)은 글 하나 또는 언어별 값(`{ ko, en }`)이다. 새 옵션도 같은 모양으로 받고 `ctx.text(value)` 로 지금 언어 값을 꺼낸다.

### 3.7 알림과 대화 동작

```ts
ctx.on("localeChange", ({ locale }) => ...);       // 화면 언어가 바뀜. 자기가 그린 글을 다시 그린다
ctx.on("newConversation", ({ previousSessionId, sessionId }) => ...);
ctx.on("answerStart", (a: AnswerView) => ...);     // 질문을 보내 응답을 기다리기 시작함(다시 생성, 질문 수정 포함). a.text 는 빈 글
ctx.on("answerDelta", ({ answer, text }) => ...);  // 응답 글 조각이 옴. text 는 이번 조각, answer.text 는 지금까지. 생각, 도구 줄은 오지 않는다
ctx.on("answerDone", (a: AnswerView) => ...);      // 응답 하나가 끝남(중지, 오류 포함)
ctx.on("streamEvent", ({ name, data, answer, mount }) => ...); // 대화 스트림에서 위젯 본체가 모르는 이벤트. mount 는 아래
ctx.on("workflowApproval", ({ runId, stepId, policyId, reason, approvers, timeoutAt, mount }) => ...); // 워크플로우 진행 칸에 승인 대기가 옴
ctx.on("modelChange", ({ model, thinking }) => ...); // 사람이 모델이나 깊게 생각하기를 바꿈. selectModel, setThinking 으로 바꾼 것은 오지 않는다
ctx.on("titleChange", ({ sessionId, title }) => ...); // 서버가 대화 제목을 만들어 머리줄 제목이 바뀜. setTitle 로 바꾼 것은 오지 않는다
ctx.on("settingsLoaded", (settings) => ...);      // 위젯 설정을 받았거나 다시 받음(화면 언어를 바꿀 때 포함)
ctx.on("destroy", () => ...);                       // 위젯을 떼어 냄. 타이머, 마이크 등을 정리한다

ctx.locale();            // "ko" | "en"
ctx.sessionId();
ctx.isBusy();            // 응답이 도는 중
ctx.isRecording();       // 위젯 본체 마이크가 녹음 중이거나 글로 바꾸는 중
ctx.widgetSettings();    // 위젯 본체가 받은 위젯 설정 응답 그대로(모르는 칸 포함). 받기 전이거나 실패면 null
ctx.connectionId();      // 붙이는 앱이 준 연결 id, 없으면 null
ctx.chat.send(text, { body });  // 입력칸에서 보낸 것과 같다. body 는 아래 요청 본문 덧붙임
ctx.chat.abort();        // 응답 중지 단추와 같다. 돌고 있지 않으면 아무 일 없음
ctx.chat.selectModel(id);   // 모델 고르기. 위젯 설정의 목록에 없으면 거짓
ctx.chat.setThinking(on);   // 깊게 생각하기. 그 연결이 지원하지 않으면 거짓
ctx.chat.newConversation();
ctx.chat.attach(file);   // 입력칸 첨부 칩으로 더한다(검사, 올리기 포함)
ctx.chat.attachUploaded(meta);  // 서버가 이미 만든 첨부(Attachment 모양)를 올린 상태의 칩으로 더한다
ctx.chat.addRequestFields(() => ({ ... }));  // 보낼 때마다 불러 대화 요청 본문에 더한다. 없으면 {} 를 돌려준다
ctx.chat.setTitle(text); // 머리줄 대화 제목만 바꾼다. 서버에는 쓰지 않는다
ctx.chat.open(view);     // 지난 대화를 연다. 아래 ConversationView
```

지난 대화 열기:

```ts
interface ConversationView {
  sessionId: string;
  title?: string;
  notice?: string;                 // 맨 위 회색 줄 한 줄(앞부분이 잘렸다는 안내 등). 문구는 부르는 건이 준다
  turns: Array<{
    turnId?: string; turnSeq?: number;   // 없으면 그 응답의 다시 생성, 질문 수정, 평가를 숨긴다
    question: { text: string; at?: Date; attachments?: Array<{ filename: string; mediaType: string; available: boolean }> };
    answer: { text: string; model?: string; at?: Date; stopped?: boolean; tools?: string[];
              extra?: Record<string, unknown>; requestBody?: Record<string, unknown> };
    feedback?: { rating: "UP" | "DOWN"; reasonCodes?: string[]; comment?: string };
  }>;
}
```

- 응답이 돌고 있으면 먼저 멈춘다. 입력칸 첨부, 입력칸 모드, 열린 옆 창을 비우고 session_id 를 바꾼 뒤 대화 칸을 다시 그린다.
- 마지막 응답에만 다시 생성을 그린다. 복사, 평가(남긴 평가는 켜진 상태로)는 모든 응답에 그린다. `stopped` 면 「응답을 중지했습니다」, `tools` 는 접힌 도구 줄로 그린다.
- 응답마다 손질(decorate), 응답 아래 단추가 새 응답과 같이 불린다. `answerDone` 은 오지 않는다.
- 서버 응답을 이 모양으로 바꾸는 것은 부르는 건(CR-178)이 한다. 붙이는 앱에 `onNewConversation` 은 보내지 않는다.

기능 요소 끼우기(`mount(el)`):

- `streamEvent` 의 `mount(el)` 은 도는 중인 응답의 지금 위치(그때까지 받은 글 바로 아래)에 요소를 끼운다. 그 뒤에 오는 글은 요소 아래에 이어 그린다. 응답이 끝나도 남는다. 응답이 이미 끝났으면 끼우지 않고 거짓을 돌려준다.
- 끼운 요소는 응답 글(`AnswerView.text`)에 들어가지 않는다. 복사는 글만 복사하고, 다시 생성한 새 버전과 지난 대화 열기에는 없다. 손질(decorate)은 요소로 나뉜 글 조각마다 불린다.
- `workflowApproval` 의 `mount(el)` 은 그 워크플로우 진행 칸의 승인 대기 줄 바로 아래에 요소를 끼운다. 붙이는 앱의 `onApprovalRequired` 는 그대로 불린다.
- 끼운 요소의 글은 기능이 `localeChange` 를 받아 다시 그린다.

스트림 중간 오류: 서버가 응답 도중 `event: error`(`data: {"code": "...", "message": "..."}`)를 보내면 위젯 본체가 그 응답을 오류로 끝낸다. 받은 글은 남기고 「답을 받지 못했습니다: (오류 문구)」를 붙이며 다시 시도 단추는 두지 않는다(CR-175 T3-4 전송 7단계와 같다). 오류 문구는 3.6 의 규칙으로 고른다.

요청 본문 덧붙임:

- 대화 요청 본문에 더하는 칸은 `ctx.chat.addRequestFields` 로 등록한 것, 입력칸 모드의 `requestFields()`, `ctx.chat.send` 의 `body` 세 곳에서 온다. 이 순서로 합쳐 같은 키면 뒤가 이긴다.
- 위젯 본체가 쓰는 키(`model`, `session_id`, `stream`, `messages`, `connection_id`, `rag_source_id`, `actions_enabled`, `rewind_from_seq`, `thinking`, `render_features`)는 덮어쓰지 못한다. 본체 값이 남는다.
- 칸을 싣는 건은 서버 대화 요청(`ChatController.ChatCompletionRequest`)에 그 칸을 같은 건에서 더한다. 서버가 모르는 칸은 요청째 거절될 수 있다.
- 합친 칸은 그 질문에 기억해 두었다가 다시 생성, 질문 수정 때 그대로 다시 싣는다. 그 응답의 `AnswerView.requestBody` 로 읽을 수 있다.

### 3.8 서버 호출과 그 밖

- 서버 호출은 `ctx.api.request<R>(method, path, body?)` 로 한다. 위젯 토큰을 싣고, 실패하면 `WidgetApiError(status, code)` 를 던지고, 성공하면 `ApiResponse` 의 `data` 를 돌려준다. `widget-api.ts` 에 메서드를 더하지 않는다.
- 그림, 파일처럼 JSON 이 아닌 응답은 `ctx.api.requestBlob(method, path)` 로 받는다. 위젯 토큰을 싣고, 실패하면 `WidgetApiError` 를 던지고, 성공하면 `Blob` 을 돌려준다.
- `ctx.ui.layer` 는 위젯 판(panel) 요소다. 녹음 덮개, 발표 화면 같은 겹치는 요소를 여기에 붙인다.
- `ctx.ui.onEscape(fn)` 은 Esc 를 위젯 본체보다 먼저 받는다. 처리했으면 참을 돌려준다.
- `ctx.ui.toast(text)` 는 잠깐 보이는 알림, `ctx.ui.notice(text)` 는 대화 칸 회색 줄이다.

## 4. 이 문서에 없는 자리가 필요할 때

- CR-177 이 dev 에 들어가기 전: CR-177 창에 [요청] 으로 보낸다. 틀은 CR-177 만 고친다.
- 들어간 뒤: 자리 하나를 더하는 작은 변경은 그 건이 `src/frame/` 에 직접 한다. 같은 커밋에 이 문서 3절 표에 한 줄을 더하고 컨타에 [알림] 한다. 있는 자리의 모양을 바꾸는 변경은 컨타에 먼저 묻는다.
- 대화 칸 자체를 바꾸는 일은 자리가 아니라 위젯 본체 변경이다. 그 건이 본체를 고치되 컨타에 먼저 알린다. 지난 대화 열기는 틀이 맡는다(3.7 `ctx.chat.open`).

## 5. 빌드와 합치기

- 확인은 `npm run typecheck` 와 `npm run build`(위젯 폴더)다. 문구표의 ko, en 짝은 타입 검사가 본다.
- 빌드 산출물(`backend/platform-core/src/main/resources/static/widget/v1/`)은 dev 를 받은 뒤 다시 빌드해 통째로 덮는다. 산출물 충돌을 손으로 고치지 않는다.
- 각 건의 위젯 코드는 CR-177 이 dev 에 들어간 뒤 그 dev 에서 시작한다. 컨타가 알린다.
