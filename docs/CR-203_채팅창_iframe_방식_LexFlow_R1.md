# CR-203 채팅창 iframe 방식 (LexFlow R1)

> 설계 버전: 1.0 | 최종 수정: 2026-11-27 | 관련 CR: CR-203

- 원본 요구사항: [origins/원본_요구사항_CR203_채팅창_iframe_방식_LexFlow_R1_20261127.md](origins/원본_요구사항_CR203_채팅창_iframe_방식_LexFlow_R1_20261127.md)
- 기능 ID: PRD-660 ~ PRD-663
- 규칙: BIZ-300 ~ BIZ-302
- 범위 결정: 2026-11-27 사용자 「iframe 제공하는 건 어려운가요. 다른 곳에서 사용도 필요할 수 있어서 만들어 놓으면 좋을 듯합니다」. 다른 소비앱에도 같은 방식으로 붙일 것을 전제로 만든다. 기존 요소 직접 삽입 방식은 그대로 두고 두 방식을 나란히 안내한다
- 규모: 중(전용 페이지와 연결 스크립트 신규, 토큰 검사와 응답 머리글 변경. DB 와 API 변화 없음)

## 1. 범위

### 1.1 이번 CR 에 넣는 것

| 순번 | 기능 | 기능 ID | 바뀌는 곳 |
|---|---|---|---|
| 1 | Aimbase 가 띄우는 채팅창 전용 페이지. 소비앱 화면이 iframe 으로 끼운다 | PRD-660 | 위젯 패키지, api 정적 파일 |
| 2 | 소비앱 화면에 넣는 연결 스크립트. 토큰, 화면 자료, 화면 키, 열기와 닫기, 창 자리와 크기를 부모 화면과 전용 페이지 사이에서 메시지로 주고받는다 | PRD-661 | 위젯 패키지 |
| 3 | 전용 페이지를 끼울 수 있는 화면을 허용 주소 목록으로 제한하는 응답 머리글 | PRD-662 | api |
| 4 | 전용 페이지에서 보낸 요청은 토큰의 origin 과 요청 Origin 대조를 건너뛴다 | PRD-663 | api |
| 5 | 소비앱 연동 가이드에 두 방식(요소 직접 삽입, iframe)을 나란히 안내한다 | - | docs/guides |

### 1.2 넣지 않는 것

- 기존 요소 직접 삽입 방식(`<aimbase-chat>`) 제거. 그대로 둔다. 두 방식 중 소비앱이 고른다.
- 전용 페이지 전용의 새 화면 요소. 전용 페이지 안 채팅창은 지금 위젯과 같은 모양이다.
- 토큰 발급 경로 변경. 소비앱 BFF 가 발급하는 것은 그대로다. 전용 페이지는 토큰을 부모 화면에서 받는다.
- 부모 화면 없이 전용 페이지를 단독으로 여는 것(모바일 앱 웹뷰 등). 부모 화면이 없으면 채팅창을 띄우지 않고 안내만 보인다.
- 버전 고정 주소 체계. CR-202 가 정하고 전용 페이지는 그 주소 아래에 둔다.
- 테넌트별 허용 주소. 지금처럼 Aimbase 전체 설정 `widget.allowed-origins` 하나다.
- 연결 스크립트에서 `sendMessage`, `abort`, 워크플로우 구독 같은 프로그램 호출. 지금 요청한 소비앱이 없다. 열기, 닫기, 열림 여부만 둔다.

## 2. 지금 상태 (설계 근거, origin/dev b83dcb32)

| 순번 | 무엇 | 근거 |
|---|---|---|
| 1 | 소비앱은 Aimbase 의 스크립트 한 줄을 넣고 `<aimbase-chat>` 요소를 둔다. 스크립트는 소비앱 화면과 같은 출처에서 돈다. 토큰은 요소가 소비앱 BFF(`token-endpoint`)를 직접 불러 받는다 | web-component.ts:124-150 |
| 2 | display 는 bubble(떠 있는 창), inline(칸 안), panel(옆 칸) 셋. 버블 단추와 채팅창은 소비앱 화면에 fixed 좌표로 놓인다 | widget.ts:253, 356-369, styles.ts:52-80 |
| 3 | 떠 있는 창의 옮기기와 크기 바꾸기, 옆 칸 폭은 위젯 틀 `WindowDrag` 가 채팅창에 직접 좌표를 쓴다. 자리 계산은 `window-rect.ts` 의 순수 함수고 기억은 그 브라우저 저장소다 | frame/window-drag.ts, frame/window-rect.ts |
| 4 | 화면 자료는 질문을 보낼 때 `contextProvider` 를 동기로 불러 싣는다 | widget.ts:1552, types.ts:19-21 |
| 5 | 토큰 검사: 토큰의 origin claim 과 요청 Origin 헤더가 둘 다 있고 다르면 경고 기록 뒤 인증 없이 돌려보낸다. 헤더가 없으면 통과 | JwtAuthenticationFilter.java:171-178 |
| 6 | 토큰 발급: origin 은 설정 `widget.allowed-origins` 목록에 있어야 하고 토큰 claim 에 실린다 | WidgetTokenController.java:57-65, JwtProvider.java:81 |
| 7 | 모든 응답에 `X-Frame-Options: DENY` 가 붙는다(Spring Security 기본값. SecurityConfig 에 머리글 설정이 없다). 59 에서 `curl -I /widget/v1/index.html` 로 확인했다. 어떤 Aimbase 페이지도 다른 화면 안에 끼울 수 없다 | SecurityConfig.java, 59 응답 머리글 |
| 8 | 위젯 파일은 `/widget/**` 로 공개 서빙한다. 이름이 고정인 파일은 no-cache, 해시 이름 조각은 1년 캐시 | WebStaticConfig.java:32-45, SecurityConfig.java:75 |
| 9 | 위젯 번들은 tsup 으로 esm(쪼갠 조각)과 umd(얇은 로더) 두 벌을 만들고 gradle 이 dist 의 `*.js` 와 타입을 정적 폴더로 옮긴다. html 과 예제 폴더는 정적 폴더에 체크인돼 있다 | tsup.config.ts, build.gradle.kts:99-117 |
| 10 | CORS 는 `widget.allowed-origins` 목록으로 매 요청 허용한다 | CorsConfig.java:54-61 |

## 3. 규칙

| 규칙 | 내용 |
|---|---|
| BIZ-300 | 전용 페이지는 허용 주소 목록(`widget.allowed-origins`)의 화면에만 끼워진다. 전용 페이지 응답에만 `Content-Security-Policy: frame-ancestors <허용 주소 목록>` 을 붙이고 `X-Frame-Options` 를 뺀다. 목록이 비면 `'none'`. 그 밖의 모든 응답은 지금처럼 `X-Frame-Options: DENY` 다 |
| BIZ-301 | 전용 페이지는 토큰의 origin 과 같은 출처의 부모 화면과만 메시지를 주고받는다. 첫 메시지(init)를 보낸 부모 출처를 기억하고, 받은 토큰의 origin claim 이 그 출처와 다르면 채팅창을 띄우지 않고 안내만 보인다. 부모에게 보내는 메시지는 그 출처로만 보낸다. 연결 스크립트도 전용 페이지 출처의 그 iframe 에서 온 메시지만 받는다 |
| BIZ-302 | 같은 출처에서 온 위젯 토큰 요청은 origin 대조를 건너뛴다. 브라우저가 붙이는 `Sec-Fetch-Site: same-origin` 이 있을 때만이다. 다른 출처(cross-site, same-site)에서 온 요청은 지금처럼 토큰의 origin 과 Origin 헤더가 다르면 거절한다. Origin 헤더가 없는 서버 간 요청은 지금처럼 통과 |

## 4. 전용 페이지와 연결 스크립트

### 4.1 전용 페이지 [PRD-660]

- 주소: `<Aimbase>/widget/v1/frame.html`. CR-202 가 버전 고정 주소를 정하면 그 폴더에도 같은 이름으로 둔다. 연결 스크립트가 끼우는 주소는 이 순서로 정한다. ① `frame-src` 속성 ② 연결 스크립트를 받은 폴더의 `frame.html`(스크립트 출처가 `base-url` 과 같거나 `base-url` 이 없을 때) ③ `base-url` + `/widget/v1/frame.html`(스크립트를 소비앱 서버에 복사해 두어 출처가 다를 때). ② 덕분에 버전 폴더에서 받은 스크립트는 같은 버전의 전용 페이지를 끼운다.
- 내용: 빈 html 한 장과 모듈 스크립트 `aimbase-chat.frame.js`. 몸체는 투명하고 여백 0. 스크립트가 `<aimbase-chat>` 요소를 만들어 몸체를 채운다. 위젯 코드는 지금 esm 번들과 조각을 같은 폴더에서 그대로 쓴다.
- 끼운 틀 모드(위젯 옵션 `frame`, 요소에 `data-framed`): 위젯은 fixed 좌표를 쓰지 않고 iframe 창을 꽉 채운다. 떠 있는 창이 닫혀 있으면 버블 단추만 창을 채우고, 열리면 채팅창이 채운다. 잡는 자리는 부모가 두고, 머리줄 끌기는 위젯이 화면 좌표만 부모에게 보낸다. 크게 보기의 어둡게 하는 뒷막도 부모가 그린다. 왼쪽 칸(지난 대화)을 붙일지는 iframe 폭이 아니라 부모가 알려 준 부모 화면 폭(680px 이상)으로 정한다. 그 밖의 모양과 기능(메뉴, 옆 창, 첨부, 음성)은 지금과 같다.
- 부모 화면이 없거나(`window.parent === window`) init 이 3초 안에 오지 않으면(연결 스크립트 없이 끼운 화면) 채팅창 대신 안내 「이 화면은 연결된 서비스 화면 안에서만 열 수 있습니다」 를 보인다.
- 받은 토큰의 origin claim 이 부모 출처와 다르면 안내 「이 화면에서는 채팅창을 열 수 없습니다」 를 보이고 아무 요청도 보내지 않는다(BIZ-301). claim 은 JWT 본문을 해독해 읽는다(서명 검증은 서버가 한다).

### 4.2 연결 스크립트 [PRD-661]

- 파일: `aimbase-chat-frame.js`. 한 파일이고 외부 의존이 없고 조각을 뒤에 더 받지 않는다. 소비앱이 복사해 자기 서버에서 서빙해도 된다. 요소 직접 삽입 방식의 「복사 금지」 는 조각 파일 때문이었고 이 파일에는 조각이 없다. 소비앱 화면에서 도는 Aimbase 코드는 이 파일뿐이고, 채팅창 코드는 전용 페이지(Aimbase 출처) 안에서만 돈다. 그래서 채팅창 코드는 소비앱의 쿠키, 저장소, 화면에 닿지 않는다.
- 요소: `<aimbase-chat-frame>`. display 가 없으면 `<aimbase-chat>` 과 같이 칸 안(inline)이다. 속성은 `<aimbase-chat>` 과 같다(`base-url`, `token-endpoint`, `display`, `theme-mode`, `screen-key`, `connection-id`, `connection-group-id`, `model`, `suggestions`, `empty-title`, `empty-subtitle`, `font-size`, `locale`, `allow-approval`, `history-list`, `rag-source-id`, `session-id`, `actions-enabled`). 받은 속성은 그대로 전용 페이지로 넘기고 전용 페이지의 `<aimbase-chat>` 이 지금처럼 읽는다. 더하는 속성은 `frame-src` 하나다.
- JS 속성과 메서드: `contextProvider`, `authResolver` 는 같은 이름으로 둔다. 요소가 등록되기 전에 넣은 값도 받는다(연결 스크립트를 `defer`, `async` 로 넣은 화면). `open()`, `close()`, `isOpen()`. `new-conversation` 이벤트는 전용 페이지에서 받아 같은 이름으로 다시 쏜다.
- 하는 일: iframe 만들기(`allow="microphone; display-capture; clipboard-read; clipboard-write; fullscreen"`), display 별 자리와 크기(4.3), 메시지 중계(4.5), 토큰 받기(`token-endpoint` 에 POST, 쿠키 포함. `authResolver` 가 있으면 그것), 화면 자료(`contextProvider` 를 불러 회신), 끌기와 크기 바꾸기(4.4), 열림과 크게 보기 상태에 따라 상자 바꾸기.
- 속성이 바뀌면 전용 페이지로 넘긴다. `screen-key` 가 바뀌면 전용 페이지의 `<aimbase-chat>` 이 CR-194 계약 6-1 대로 그 화면의 토큰과 대화로 다시 뜬다.

### 4.3 자리와 크기 (display 별)

연결 스크립트가 iframe 상자를 놓는다. 수치는 CR-198 과 같고 `window-rect.ts` 를 같이 번들해 쓴다. 기억(BIZ-288)은 소비앱 출처의 브라우저 저장소에 같은 이름으로 남는다.

| display | 상태 | iframe 상자 | 전용 페이지가 보이는 것 |
|---|---|---|---|
| bubble | 닫힘 | 56 x 56px, 아래 20px, 오른쪽 20px | 버블 단추 |
| bubble | 열림 | 기억한 자리와 크기. 없으면 처음 자리(아래 90px, 오른쪽 20px, 400 x 600px, 화면 높이 80% 까지). 화면 가장자리 8px 안쪽에 맞춤 | 채팅창 |
| bubble | 크게 보기 | 가장자리 28px 안쪽 전체. 부모가 뒷막을 그린다 | 채팅창 |
| bubble | 왼쪽 칸 붙임(CR-194) | 전용 페이지가 알린 대로 상자 폭을 왼쪽으로 240px 넓힌다(`widenForDock`). 부모 화면 폭이 680px 보다 좁으면 붙이지 않고 덮어 연다. 기억은 칸을 뺀 대화 창 | 채팅창과 대화 목록 |
| panel | 열림 | 오른쪽 세로 전체, 폭 420px(기억) | 채팅창 |
| inline | 열림 | 요소의 크기(폭 100%, 높이 100%). 소비앱이 요소 크기를 정한다 | 채팅창 |

### 4.4 끌기와 크기 바꾸기

- 머리줄 끌기: 전용 페이지 안 위젯이 머리줄을 누른 포인터를 붙잡고(pointer capture) 3px 넘게 움직이면 `drag` 메시지를 `start`, `move`, `end` 로 보낸다. 값은 화면 좌표(`screenX`, `screenY`)다. 포인터를 붙잡아 두어 상자 밖으로 빠르게 끌어도 손을 뗄 때까지 전용 페이지가 받는다. 부모는 `start` 의 좌표와 상자 자리를 기억하고 그 차이로 상자를 옮기고(`moveFloating`), `end` 에서 기억한다. 부모와 전용 페이지는 같은 창이라 화면 좌표의 단위가 같다. iframe 안 좌표를 쓰면 상자가 움직이는 만큼 좌표가 되돌아와 쓸 수 없다.
- 크기 바꾸기: 잡는 자리 8곳(변 6px, 모서리 14px)은 부모가 iframe 상자 바깥 가장자리에 둔다(지금 `.win-frame` 과 같은 자리). 옆 칸은 왼쪽 변 하나. 부모가 바로 받고, 끄는 동안 iframe 의 포인터를 꺼서(`pointer-events: none`) 커서가 상자 안으로 들어가도 놓치지 않는다.
- 크게 보기 동안에는 잡는 자리를 숨기고 끌기 메시지를 무시한다(BIZ-287).

### 4.5 메시지 규약

봉투는 `{ "aimbase-chat-frame": 1, "type": ..., "id"?: ..., ... }` 다. 구현은 `src/frame/frame-protocol.ts`. 표식이 없는 메시지는 양쪽 모두 무시한다. 전용 페이지는 `ready` 만 모든 출처로 보내고(내용 없음), init 을 받은 뒤로는 그 출처로만 보낸다. 부모는 자기가 만든 iframe 의 창에서 온, 전용 페이지 출처의 메시지만 받는다.

| 보내는 쪽 | type | 내용 | 때 |
|---|---|---|---|
| 전용 페이지 | `ready` | 없음 | 전용 페이지 스크립트가 뜰 때 |
| 부모 화면 | `init` | `attrs`(`<aimbase-chat>` 이 받는 18개 속성만. `frame-src`, `id`, `class`, `style` 은 가지 않는다), `display`(없으면 inline). 전용 페이지는 `token-endpoint`, `base-url` 은 쓰지 않는다(토큰은 부모가 받고 서버는 전용 페이지 출처) | `ready` 를 받으면 |
| 부모 화면 | `viewport` | `width`, `height`(부모 화면 크기) | init 바로 뒤, 부모 화면 크기가 바뀔 때. 왼쪽 칸을 붙일지 정하는 데 쓴다 |
| 전용 페이지 | `token:request` | `id` | 위젯이 토큰이 필요할 때(처음, 갱신 때) |
| 부모 화면 | `token:reply` | `id`, `token`(TokenResponse) 또는 `error` | 소비앱 BFF 응답을 받으면 |
| 전용 페이지 | `context:request` | `id` | 질문을 보낼 때 |
| 부모 화면 | `context:reply` | `id`, `context` | `contextProvider()` 결과. 구조 복사가 되는 값만 간다 |
| 부모 화면 | `attr` | `name`, `value` | 요소 속성이 바뀌면 |
| 부모 화면 | `open`, `close` | 없음 | `open()`, `close()` 호출 |
| 전용 페이지 | `state` | `open`, `expanded`, `docked` | 열림, 크게 보기, 왼쪽 칸 붙임이 바뀔 때 |
| 부모 화면 | `expand` | `expanded` | 부모가 그린 뒷막을 누르면 `false` |
| 전용 페이지 | `drag` | `phase`(`start`, `move`, `end`), `screenX`, `screenY` | 머리줄을 누르고 3px 넘게 움직일 때부터 손을 뗄 때까지 |
| 전용 페이지 | `event` | `name`, `detail` | `new-conversation` |

### 4.6 토큰과 화면 자료

- 전용 페이지의 `authResolver` 는 `token:request` 를 보내고 `token:reply` 를 기다리는 함수다. 부모는 `token-endpoint` 에 쿠키를 포함해 POST 한다. 소비앱 쿠키는 소비앱 출처라 지금처럼 간다. 갱신(`refresh_after`)도 같은 길이다. 10초 안에 답이 없으면 실패로 본다.
- 받은 토큰의 origin claim 은 부모 출처와 같아야 한다(BIZ-301).
- `contextProvider` 는 질문을 보낼 때 `context:request` 로 묻고 2초 안에 답이 없으면 자료 없이 보낸다. 위젯의 `ContextProvider` 타입을 Promise 도 돌려줄 수 있게 넓히고 보낼 때 기다린다. 기존 동기 함수는 그대로 동작한다.
- 전용 페이지 안 요청은 Aimbase 와 같은 출처라 CORS 를 타지 않는다. 브라우저가 `Origin: <Aimbase>` 와 `Sec-Fetch-Site: same-origin` 을 붙이고 api 가 BIZ-302 로 통과시킨다.

### 4.7 만드는 곳

| 순번 | 경로 | 하는 일 |
|---|---|---|
| 1 | `packages/chat-widget-embed/src/frame/frame-protocol.ts` (새) | 메시지 봉투, 타입, 검사, 토큰 origin claim 해독. 양쪽 공용 |
| 2 | `packages/chat-widget-embed/src/frame-page/main.ts` (새) | 전용 페이지 스크립트. ready, init 받기, `<aimbase-chat framed>` 만들기, 토큰과 자료 중계, 상태 보고, 안내 문구 |
| 3 | `packages/chat-widget-embed/src/frame-host/element.ts`, `host-window.ts`, `index.ts` (새) | 연결 스크립트. `<aimbase-chat-frame>` 요소, iframe 상자, 화면 좌표 끌기, 잡는 자리, 기억, 부모 화면 크기 알림 |
| 4 | `backend/platform-core/src/main/resources/static/widget/v1/frame.html` (새) | 전용 페이지 html. 같은 폴더의 `index.html`, `guide.html` 처럼 손으로 관리한다. gradle 복사(`copyWidgetBundle`)는 `*.js` 만 바꾸고 html 은 남긴다 |
| 5 | `packages/chat-widget-embed/tsup.config.ts` | esm 설정에 `aimbase-chat.frame` 진입점 추가(조각 공유), iife 설정에 `aimbase-chat-frame` 추가 |
| 6 | `src/widget.ts`, `src/styles.ts`, `src/types.ts`, `src/web-component.ts`, `src/frame/panes.ts`, `src/frame/frame-drag.ts`(새), `src/frame/token-endpoint.ts`(새), `src/frame/frame-strings.ts`(새), `src/frame/resize-handle-css.ts`(새) | `frame` 옵션과 `frameBridge` 속성, 끼운 틀 모드 스타일, 머리줄 끌기 알림, `ContextProvider` 비동기, 상태 알림, 왼쪽 칸 붙임 폭을 부모 화면 폭으로, 안내 문구, 잡는 자리 스타일 공용 |
| 7 | `backend/.../config/SecurityConfig.java`, `config/WidgetFrameHeaderWriter.java` (새) | BIZ-300 머리글 |
| 8 | `backend/.../auth/JwtAuthenticationFilter.java` | BIZ-302 |
| 9 | `docs/guides/embed-chat-widget.md`, `aimbase-api-guide.md`, `aimbase-ops-guide.md`, `static/widget/v1/index.html` | 두 방식 안내, 토큰 검사와 머리글 설명, 허용 주소 설정의 뜻 |

## 5. 서버, 데이터, API

- 새 API 없음. 정적 파일 둘(`frame.html`, `aimbase-chat.frame.js`)과 연결 스크립트 한 개(`aimbase-chat-frame.js`)가 `/widget/v1/` 에 늘어난다. 캐시 규칙은 지금과 같다(이름 고정 파일은 no-cache).
- 응답 머리글(BIZ-300): `/widget/*/frame.html` 응답에 `Content-Security-Policy: frame-ancestors <widget.allowed-origins 를 공백으로 이은 것>`, `X-Frame-Options` 없음. 목록은 매 요청 설정에서 읽는다(PlatformSettingsService 5분 캐시, CorsConfig 와 같은 방식). 다른 모든 응답은 지금처럼 `X-Frame-Options: DENY`.
- 토큰 검사(BIZ-302): origin claim 과 Origin 헤더가 다를 때 `Sec-Fetch-Site` 가 `same-origin` 이면 통과, 아니면 지금처럼 거절. 경고 기록은 거절할 때만.
- DB, 설정 키 변화 없음. `widget.allowed-origins` 의 뜻이 넓어진다: 토큰 발급 허용, CORS 허용, 전용 페이지 끼우기 허용.

## 6. 레포 간 요청

- CR-202(같은 레포, 채팅창 묶음): 버전 고정 주소 체계. 전용 페이지와 연결 스크립트의 주소가 그 체계를 따른다. 확정되면 4.1 과 가이드를 맞춘다.
- LexFlow(소비앱, 다른 레포): 완료 때 안내서와 답변 문서에 반영할 사항(요소 이름, 속성, 주소, 두 방식의 차이)을 컨타(lexflow-7434)에 `[알림]` 한 줄과 가이드 경로로 보낸다. LexFlow 쪽 BFF 는 바뀌지 않는다.

## 7. 시험

### 7.1 단위시험 (T3-5)

- `frame-protocol.test.ts`: 표식 없는 메시지 무시, 다른 출처 무시, 토큰 origin claim 해독과 부모 출처 대조(같음, 다름, 깨진 토큰), 속성 전달 목록, display 기본값(TC-CHT-170 ~ 172), 전용 페이지 주소 기본값(TC-CHT-181), 화면 자료 JSON 값만(TC-CHT-182).
- `host-window.test.ts`: display 와 상태별 상자 계산(TC-CHT-173), 화면 좌표 차이로 상자 옮기기(TC-CHT-174).
- `JwtAuthenticationFilterTest`: claim 과 Origin 이 다르고 `Sec-Fetch-Site: same-origin` 이면 인증, `cross-site` 면 거절, Origin 없으면 인증.
- `WidgetFrameHeaderWriterTest`: `frame.html` 에 CSP 와 `X-Frame-Options` 없음, 다른 경로에 DENY, 목록이 비면 `'none'`.

### 7.2 로컬 확인

- 로컬 api 와 붙이는 앱 흉내 화면(다른 포트)을 띄우고 `<aimbase-chat-frame>` 으로 붙인다. 허용 주소에 그 포트를 넣는다. 8280 은 이 맥에서 다른 컨테이너가 쓰고 있어 18203 으로 띄웠다(9절).
- 본다: 끼워짐(DENY 없음), 토큰 받기와 갱신, 질문과 답, 화면 자료가 요청에 실림, `screen-key` 바꾸기, 열기와 닫기와 크게 보기, 끌기와 크기 바꾸기와 기억, panel 과 inline, 허용 목록에 없는 출처에서 끼우면 막힘(브라우저 콘솔 CSP), `frame.html` 을 바로 열면 안내 문구.
- 기존 `<aimbase-chat>` 방식에 회귀 없음(같은 예제 페이지의 기존 방식 칸).

### 7.3 통합 시험항목

시험 영역 27(채팅 위젯)에 iframe 방식 항목을 더한다. 사용자가 시킬 때 `testbook.py` 로 넣는다.

## 8. 구현 순서

1. 설계 캐스케이드(T1-1, T1-3, T2-1, T3-2, T3-3, T3-4, T3-5), 대장, 설계분 커밋
2. 공용 규약(`frame-protocol.ts`)과 단위시험
3. 위젯 끼운 틀 모드, `ContextProvider` 비동기
4. 전용 페이지 스크립트와 html, 연결 스크립트, tsup, gradle 복사
5. api 머리글과 토큰 검사, 시험
6. 가이드 셋과 `index.html`
7. 로컬 확인, 59 배포(`./deploy.sh be`) 승인요청, 완료 보고, 컨타에 LexFlow 문서 반영 사항 `[알림]`

## 9. 진행 기록

- 2026-11-27 22:44 착수. 작업 폴더 `~/Documents/GitHub/_worktrees/aimbase-cr203`(origin/dev b83dcb32, 브랜치 `cr203-iframe`). 메인 폴더는 origin/dev 보다 448 커밋 뒤고 다른 창의 미커밋 수정이 있어 건드리지 않는다. 대장 등록도 작업 폴더의 대장에 했다.
- 2026-11-27 23:05 화면 컨셉 승인요청을 컨타에 보냄(새 화면 요소 없음).
- 2026-11-27 23:10 CR-206 이 PRD-648, 649, BIZ-290, 291, TC-CHT-159 ~ 161 을 먼저 잡아 이 CR 의 번호를 PRD-660 ~ 663, BIZ-300 ~ 302, TC-CHT-170 ~ 174 로 옮김. 설계 캐스케이드(T1-1, T1-3, T1-7, T2-1, T3-2, T3-3, T3-4, T3-5)와 대장 등록.
- 2026-11-27 23:16 설계분 커밋(949d6c18). 화면이 아닌 부분 구현: 메시지 규약(`frame-protocol.ts`, TC-CHT-170~172 13건 통과), BIZ-302 토큰 검사(TC-FRM-001~003), BIZ-300 머리글(`WidgetFrameHeaderWriter`, TC-FRM-004~006). 서버 시험 14건 통과. 화면 컨셉 답 대기 중. CR-204 예약 번호(PRD-650·651, BIZ-292~296, TC-CHT-162~166, 영역 29)와 겹침 없음
- 2026-11-27 23:33 화면 컨셉 승인(①컨셉만, 사용자 23:30)을 받고 컨셉 커밋(dc094392).
- 2026-11-28 07:43 화면 구현 커밋(22bb7662). 전용 페이지(`frame.html`, `aimbase-chat.frame.js`), 연결 스크립트(`aimbase-chat-frame.js`), 위젯 끼운 틀 모드, 부모 쪽 상자(`host-window.ts`, TC-CHT-173, 174).
- 2026-11-28 07:59 로컬 확인에서 나온 세 가지를 고쳐 커밋(d60a9d90). ① 끼운 채팅창이 iframe 폭으로 판정해 대화 목록을 붙이지 않던 것을 부모 화면 폭(viewport 메시지)으로 판정. ② 전용 페이지 주소를 스크립트 폴더 기준으로(버전 폴더에서 받은 스크립트가 같은 버전 페이지를 끼움, TC-CHT-181). ③ 칸 안(inline) iframe 이 150px 로 뜨던 높이. 또 연결 요소에 스크립트 등록 전에 넣은 `contextProvider` 가 등록 때 지워지지 않게 했다.
- 2026-11-28 08:00 로컬 확인 35건 통과(18203 api, 13203~13205 붙이는 앱 흉내 화면, 가짜 모델). 끼워짐, 질문과 답(요청이 `Sec-Fetch-Site: same-origin` 으로 와 토큰 검사 통과), 화면 자료 실림, `screen-key` 거름과 바꾸기, 끌기, 크기 바꾸기, 기억(붙이는 앱 출처 저장소), 크게 보기와 뒷막, 새 대화 이벤트 전달, `open()`, `close()`, panel, inline, 허용 목록 밖 출처 막힘(CSP), 토큰 origin 다름 안내, `frame.html` 바로 열기 안내, 스크립트 없이 iframe 만 끼우면 3초 뒤 안내, defer 스크립트에서 화면 자료, 대화 목록 붙임이 기존 방식과 같은 자리, 680px 아래 붙이지 않음, 기존 `<aimbase-chat>` 방식 회귀 없음. 토큰 갱신은 처음 받기와 같은 경로라 따로 보지 않았다.
- 2026-11-28 08:00 번호 옮김. CR-202 가 TC-CHT-175 ~ 180 을 쓰므로 새 시험을 TC-CHT-181(전용 페이지 주소), 182(화면 자료)로, CR-186 ~ 188 이 FE-140 ~ 159 를 잡아 화면 번호를 FE-160 으로 했다.
- 2026-11-28 08:00 본 것(고치지 않음): 기존 `<aimbase-chat>` 방식에서 위젯 스크립트가 늦게 등록되면 그 전에 넣은 `contextProvider` 가 등록 때 지워진다(요소 필드 초기값이 덮음). 이 CR 범위 밖이라 컨타에 알린다.

## 기획서 화면 대조

해당 없음: LexFlow 검토 회신(2026-11-25)은 글로만 왔고 기획서 화면이 없다. 전용 페이지 안 채팅창은 지금 위젯과 같은 모양이고 새 화면 요소가 없다.
