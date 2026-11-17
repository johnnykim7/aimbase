# 요청: CR-196 Aimbase 발신 키와 1년 토큰 만료 알림 틀

- **보내는 쪽(소비처)**: aimbase · CR-196(실행기 계정 1년 토큰과 만료 알림)
- **받는 쪽(제작)**: bp-notification
- **작성**: 2026-11-18, aimbase CR-196 워커 aimbase-9275
- **근거**: 사용자 2026-11-18 06:13 「bp-notification 창에 요청」 승인. 부르는 모양은 CR-196 문서 4.6, 4.7, 4.9
- **기준**: 59 개발기 `http://59.8.160.12:8185/api/v1`, 운영 chaws 알림 서버(같은 서버 8089), `docs/guides/notification-api-guide.md`
- **상태**: 보냄, 회신 기다림

---

## 1. 왜

Aimbase 실행기는 CR-196 부터 계정마다 `claude setup-token` 으로 받은 1년 토큰으로만 CLI 를 띄운다. 토큰이 만료되면 그 계정으로 질문이 가지 않는다.
그래서 Aimbase api 가 매일 09:00(Asia/Seoul) 실행기 계정 폴더의 토큰 기한을 보고, 만료 30, 7, 1, 0 일 전에 담당자에게 메일과 문자를 한 번씩 보낸다.
이 기능은 59 개발기와 운영 chaws 에 이미 올라가 있고, 발신 키가 없어 보내지 않고 건너뛰는 상태다.

2026-11-18 06:00 에 두 알림 서버의 솔루션 목록을 보니 Aimbase 솔루션이 없다(59 는 12개, 운영 chaws 는 4개). 키와 틀 둘이 필요하다.

## 2. 바라는 것

방식(관리자 창구, 마이그레이션 번호, 하루 한도)은 bp-notification 이 정한다.

| 순번 | 무엇 | 지킬 것 |
|---|---|---|
| 1 | 59 개발기와 운영 chaws 에 솔루션 `AIMBASE`(이름 `Aimbase`)를 등록하고 API 키를 발급한다. 메시지 유형은 EMAIL, SMS 를 허용한다. PUSH 는 쓰지 않는다 | 키 값은 메시지, 문서, 커밋, 로그에 쓰지 않는다. 서버마다 따로 발급해 `~/.claude-secrets/aimbase-notification-59.env`, `~/.claude-secrets/aimbase-notification-chaws.env` 에 `NOTIFICATION_API_KEY=` 한 줄로 둔다 |
| 2 | AIMBASE 전용 메일 틀 `AIMBASE_CLI_TOKEN_EXPIRY_EMAIL` | 변수 넷(3장). 제목과 본문 안은 4장 |
| 3 | AIMBASE 전용 문자 틀 `AIMBASE_CLI_TOKEN_EXPIRY_SMS` | 변수 넷(3장). 본문 안은 4장. 길이에 따른 SMS, LMS 처리는 bp-notification 규칙을 따른다 |
| 4 | 59 개발기와 운영 chaws 반영 | 솔루션을 먼저 등록하고 틀을 올린다. 거꾸로 하면 솔루션 코드로 고르는 틀이 0행으로 지나간다(CR-021 V121 과 같다) |

변수 값은 실행기 이름, 계정 폴더 이름, 날짜, 숫자뿐이고 토큰 값이나 사람이 쓴 글은 싣지 않는다. 발송 기록에 본문을 남겨도 된다.

## 3. Aimbase 가 부르는 모양

```
POST /api/v1/messages/email
X-API-Key: <Aimbase 키>
{"email": "<담당자 메일>", "templateCode": "AIMBASE_CLI_TOKEN_EXPIRY_EMAIL",
 "variables": {"runner": "cli-runner-bp-wes", "account": "work01", "expiresOn": "2027-11-16", "daysLeft": 30}}

POST /api/v1/messages/sms
X-API-Key: <Aimbase 키>
{"phoneNumber": "<담당자 휴대폰>", "templateCode": "AIMBASE_CLI_TOKEN_EXPIRY_SMS",
 "variables": {"runner": "cli-runner-bp-wes", "account": "work01", "expiresOn": "2027-11-16", "daysLeft": 30}}
```

| 변수 | 뜻 | 예 |
|---|---|---|
| `runner` | 실행기 이름 | `cli-runner-bp-wes` |
| `account` | 계정 폴더 이름 | `work01` |
| `expiresOn` | 만료일(Asia/Seoul, yyyy-MM-dd) | `2027-11-16` |
| `daysLeft` | 남은 날. 만료 당일은 0, 지난 뒤에는 음수 | `30`, `7`, `1`, `0`, `-2` |

`daysLeft` 는 숫자로 보낸다. 2xx 가 아니면 Aimbase 는 보낸 기록을 남기지 않고 다음 날 확인에서 다시 보낸다. `X-Idempotency-Key` 는 싣지 않는다.

## 4. 문구 안

`daysLeft` 가 0 이나 음수일 때도 어색하지 않게 「만료 N일 전」 대신 만료일과 남은 날을 같이 쓴다. 고칠 곳이 있으면 bp-notification 이 고치고 회신에 적는다.

| 틀 | 제목 | 본문 |
|---|---|---|
| `AIMBASE_CLI_TOKEN_EXPIRY_EMAIL` | `[Aimbase] 실행기 계정 {{account}} 1년 토큰 만료일 {{expiresOn}} (남은 날 {{daysLeft}}일)` | 실행기 {{runner}} 의 계정 {{account}} 에 넣은 1년 토큰이 {{expiresOn}} 에 만료됩니다. 남은 날은 {{daysLeft}}일입니다. 남은 날이 0 이하이면 이 계정으로는 질문이 가지 않습니다. 그 계정으로 `claude setup-token` 을 다시 받아 Aimbase 관리 화면에 넣어 주세요. 소비앱 계정은 연결 관리의 「1년 토큰」 칸, Aimbase 관리 계정은 「실행기 계정 토큰」 화면입니다. |
| `AIMBASE_CLI_TOKEN_EXPIRY_SMS` | 없음 | `[Aimbase] 실행기 {{runner}} 계정 {{account}} 1년 토큰 만료일 {{expiresOn}}(남은 날 {{daysLeft}}일). 새 토큰을 넣어 주세요.` |

## 5. 범위 밖과 주의

- 기존 솔루션과 틀은 건드리지 않는다.
- 실제 메일, 문자 발신은 되돌릴 수 없다. 틀 확인은 `GET /templates/code/{code}/variables` 와 받는 사람이 없는 확인으로 한다. 실제로 한 통 보내는 시험은 Aimbase 가 키를 붙인 뒤 사용자 승인을 받아 Aimbase 쪽에서 한다.
- 운영 chaws 반영과 운영 알림 서버 쓰기는 사용자 승인을 받는다.
- 수신자(메일 주소, 휴대폰 번호)는 Aimbase 설정에서 정한다. bp-notification 에 수신자를 등록할 것은 없다.

## 6. 회신에 바라는 것

- bp-notification CR 번호와 커밋, 59 개발기와 운영 chaws 반영 시각
- 두 서버에서 `GET /templates/code/AIMBASE_CLI_TOKEN_EXPIRY_EMAIL/variables`, `GET /templates/code/AIMBASE_CLI_TOKEN_EXPIRY_SMS/variables` 결과
- 문자 틀이 SMS 와 LMS 중 무엇으로 나가는지
- 키를 둔 파일 위치(값은 쓰지 않는다)
