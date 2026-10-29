# CR-165: bp-office 음성 합성 연결과 본문 이스케이프

- 발번: 2026-10-30
- 원본 요구사항: `docs/origins/원본_요구사항_CR165_bp_office_음성_합성_연결과_본문_이스케이프_20261030.md`
- 요청: 컨타 7dde2327 브리핑. bp-office CR-042 요청서(`docs/협업/aimbase_요청_CR-042_브리핑_요약과_음성_합성.md`, 커밋 24fabce) 3절 표 1~3번
- 앞 건: CR-011(음성 합성, 음성 변환 창구), CR-158~160, 164(bp-office 연동)
- 규모: 소. 있는 창구 하나의 본문 만들기와 연결 찾기를 고친다. 새 API, 화면, 테이블이 없다
- 상태: 진행중

---

## 1. 문제

bp-office 음성 브리핑(OFC-REPORT-005)이 `POST /api/v1/speech/tts` 를 부르면 두 가지로 막힌다.

1. 503. 개발기 bp_office 테넌트에 OpenAI 연결이 없다
2. 본문 글에 역슬래시, 탭, 캐리지 리턴이 들면 OpenAI 로 가는 JSON 이 깨진다. 창구가 큰따옴표와 줄바꿈만 손으로 바꿔 끼운다(`SpeechController.java:68`)

## 2. 원인

### 2.1 연결을 넣어도 503 이다

창구는 테넌트 연결 중 `type` 이 `openai` 이고 상태가 `connected` 인 것의 `config.api_key` 를 키로 쓴다(`SpeechController.resolveOpenAIKey`). 관리 화면과 연결 API 로 만드는 OpenAI 연결은 모양이 다르다.

| 항목 | 창구가 찾는 모양 | 관리 화면이 만드는 모양 |
|---|---|---|
| 종류 | `type=openai` | `type=llm`, `adapter=OpenAI`(어댑터 이름은 `ConnectionAdapterFactory.normalizeAdapterType` 이 `openai` 로 맞춘다) |
| 키 | `config.api_key` | `config.apiKey`(`Connections.tsx`, `ConnectionAdapterFactory.createAdapter`) |

그래서 bp_office 에 관리 화면으로 OpenAI 연결을 넣어도 창구는 그 연결을 찾지 못해 503 이다.

### 2.2 본문을 글자 이어 붙이기로 만든다

`String.format` 에 글을 끼우고 `"` 와 줄바꿈만 바꾼다. JSON 이 허용하지 않는 제어 문자(탭, 캐리지 리턴 등)와 역슬래시는 그대로 들어간다. `model`, `voice` 도 이스케이프 없이 들어간다.

## 3. 개발기 연결 조사(2026-10-30, 읽기만)

| 항목 | 본 것 |
|---|---|
| 테넌트 9개의 OpenAI 연결 | 없다. OpenAI 계열은 bp_wes 의 OpenAI 호환 연결 2개(맥 Qwen, 끊김)뿐 |
| bp_office 연결 | CLI 연결 4개(1개 연결됨), 음성 변환 연결 1개 |
| 서버 공용 키 | api 컨테이너에 `OPENAI_API_KEY` 가 들어가 있으나 값이 5자라 실제 키가 아니다(임베딩용 자리) |
| 플랫폼 설정(`global_config`) | OpenAI, 음성 합성 항목 없음 |
| 맥 키 보관 폴더(`~/.claude-secrets`) | OpenAI 키 파일 없음(파일 이름만 봄) |

쓸 수 있는 공용 키가 없어 OpenAI 키를 새로 받아야 한다. 7절 질의 1.

## 4. 바꾸는 것

1. 본문은 JSON 직렬화기(Jackson)로 만든다. `input`, `model`, `voice`, `speed` 를 맵에 담아 직렬화한다
2. 연결 찾기는 지금 모양(`type=openai`, `config.api_key`)을 먼저 보고, 없으면 관리 화면 모양(어댑터가 OpenAI, `config.apiKey`)을 본다. 지금 모양을 쓰는 곳이 있으면 그대로 돈다
3. OpenAI 음성 합성 주소를 설정값(`aimbase.speech.openai-tts-url`, 기본값은 지금 주소)으로 뺀다. 로컬에서 가짜 OpenAI 로 본문을 받아 보기 위해서다
4. 키를 서버 환경변수에 두고 연결 설정에는 이름만 둘 수 있게 한다(컨타 [승인] 06:11, 7절 질의 1 의 1번). `config.apiKey_env` 에 변수 이름을 두면 `apiKey` 가 비었을 때 그 변수 값을 키로 쓴다. 연결 만들기, 연결 시험, 음성 합성이 같은 규칙(`ConnectionApiKeys`)으로 읽는다. 값이 든 `apiKey` 가 먼저라 기존 연결은 그대로다
5. 빈 글은 400 으로 돌려준다(지금은 글이 없으면 500)
6. 관리 화면이 저장하는 어댑터 표시명 `OpenAI Compatible` 이 `contains("openai")` 에 걸려 OpenAI 로 분류되던 것을 OpenAI 호환으로 바로잡는다. 그대로 두면 음성 합성이 OpenAI 호환 연결(DeepSeek 등)의 키를 OpenAI 로 보낼 수 있다. 그 연결은 지금 연결 시험에서 OpenAI 로 핑을 보내 실패하므로 연결됨이 될 수 없었다

음성 변환(STT)의 OpenAI 폴백(`WhisperSpeechService.resolveOpenAIKey`)도 같은 모양만 찾는다. 고치면 OpenAI 연결이 있는 테넌트의 음성 변환이 실패할 때 녹음이 OpenAI 로 나가기 시작하므로 이 건에서 건드리지 않는다. 7절 질의 3.

## 5. 바뀌는 파일

| 파일 | 바뀌는 것 |
|---|---|
| `api/SpeechController.java` | 본문 직렬화(`buildTtsBody`), 연결 찾기 두 모양, 주소 설정값, 빈 글 400 |
| `llm/ConnectionApiKeys.java`(새) | `apiKey` 다음 `apiKey_env` 순서로 키를 읽는다 |
| `llm/ConnectionAdapterFactory.java` | 연결 만들기, 연결 시험이 `ConnectionApiKeys` 로 키를 읽는다. `normalizeAdapterType` 을 공개하고 `OpenAI Compatible` 분류 |
| `application.yml` | `aimbase.speech.openai-tts-url`(env `AIMBASE_SPEECH_OPENAI_TTS_URL`) |
| 시험 `SpeechControllerTtsTest`(새), `ConnectionApiKeysTest`(새), `ConnectionAdapterTypeTest`(새), `SpeechControllerRegressionTest`(생성자) | 6절 |
| `T3-1_데이터_모델.md` | connections config 의 `apiKey_env` |
| 운용 가이드 v3.18.0 § 3-1, API 가이드 v3.24.0 § 26 | 키를 환경변수에 두는 연결, 음성 합성 창구 |

## 6. 시험과 개발기 반영

1. 단위시험(`com.platform.llm.*`, `com.platform.speech.*`, `SpeechController*` 142건 통과)
   - 역슬래시, 탭, 캐리지 리턴, 큰따옴표, 작은따옴표, 제어 문자 U+0001, 줄분리 U+2028 이 든 글이 JVM 안 가짜 OpenAI 에 받은 그대로 간다
   - 관리 화면 모양 연결의 `apiKey`, `apiKey_env` 로 키를 찾는다. 처음 모양(`type=openai`, `api_key`)이 있으면 그것이 먼저다
   - OpenAI 호환 연결, 끊긴 연결, 변수가 빈 연결만 있으면 503 이고 OpenAI 로 보내지 않는다
   - 음성 변환(STT) 시험 42건, STT 창구 회귀 4건, 나머지 LLM 연결 시험 그대로 통과
2. 로컬 실제 호출(10/30 06:16~, 로컬 api 8190, 가짜 OpenAI 18765, 로컬 테넌트 `tenant_dev`)
   - 연결 전 503. 관리 화면 모양 연결(`adapter=OpenAI`, `apiKey_env` 만)을 넣은 뒤 200 `audio/mpeg`
   - 고치기 전 방식으로 만든 본문은 두 글 모두 JSON 해석 실패(`Invalid \escape`, `Invalid control character`)
   - 61자 특수문자 글과 4,000자 글(탭, 캐리지 리턴 85개, 역슬래시)이 가짜 OpenAI 에 글자 하나 다르지 않게 갔다. 키는 환경변수 값(길이로만 대조)
   - 빈 글 400. 연결 조회 응답의 config 에는 변수 이름만 있다
   - 시험 연결은 지웠다
3. 개발기 반영(남음): OpenAI 키를 받은 뒤 서버 `.env` 에 `BP_OFFICE_OPENAI_API_KEY` 를 더하고, 작업 폴더 사본으로 `./deploy.sh be`(api 재생성 1회), bp_office 테넌트에 OpenAI 연결 `openai-tts-001`(`apiKey_env` 만) 등록과 연결 시험. 셋 다 직전에 컨타 `[승인요청]`
4. 실제 OpenAI 호출(남음): 컨타 승인 뒤 최대 8회(연결 시험 1, 특수문자 글 1, 목소리 비교 6)
5. 로컬 한국어 음성 엔진 비교(10/30, 컨타 [승인] ②안, 사용자 06:28). 같은 브리핑 글 217자를 맥에서 엔진마다 소리로 만들고 로컬 Whisper 로 다시 받아써 견줬다. 소리와 표는 맥 `~/Downloads/CR-165_음성엔진_비교/`(`결과.md`)

| 엔진 | 걸린 시간 / 소리 길이 | 라이선스 | 받아쓰기로 본 틀린 곳 |
|---|---|---|---|
| Supertonic 3(ONNX, CPU) | 9.6초 / 33.2초 | 코드 MIT, 모델 OpenRAIL-M. 원 저장소 보관 처리(수정, 보안 패치 없음) | 날짜 시각 |
| Qwen3-TTS 1.7B, 0.6B(MLX, 맥 전용) | 29.4초 / 45.5초, 22.0초 / 37.5초 | Apache-2.0 | 날짜 시각(0.6B 는 "3건" 하나 더) |
| MeloTTS 한국어(CPU) | 16.9초 / 37.5초 | MIT, 문장 분석 모델은 표기 없음 | 금액, ERP, 날짜 시각 |
| Chatterbox 다국어(맥 GPU) | 100초 / 24.3초 | MIT | 숫자 대부분. 탈락 |
| 맥 기본 음성 Yuna | 1.2초 / 30.0초 | Apple 개인 비상업 | 비교용 |

   - 로컬 엔진은 모두 숫자로 쓴 날짜 시각(`2026-10-31 14:00`)을 못 읽는다. 로컬로 가면 읽는 말로 바꾸는 단계가 필요하다
   - 비상업 라이선스(XTTS-v2, MMS-TTS, Fish Speech, F5-TTS)와 한국어 목소리가 없는 엔진(Kokoro, Piper)은 넣지 않았다
   - 맥 부하 평균 20 안팎에서 잰 값이다

## 7. 질의

1. OpenAI 키를 어디에 둘지(컨타 `[승인요청]`, 10/30)
2. 실제 OpenAI 호출 횟수(컨타 `[승인요청]`, 10/30)
3. 음성 변환 OpenAI 폴백의 같은 연결 찾기 문제를 따로 낼지(컨타 `[알림]`)
4. 음성 엔진을 OpenAI 로 갈지 로컬 엔진으로 갈지(컨타 `[승인요청]`, 10/30, 6절 5)

## 8. 진행

| 시각 | 내용 |
|---|---|
| 2026-10-30 06:03 | 착수. 컨타 [지시] |
| 2026-10-30 | 개발기 읽기 조사(3절). 창구가 찾는 연결 모양이 관리 화면 모양과 다름을 소스로 확인(2.1) |
| 2026-10-30 06:11 | 컨타 [승인] 키는 서버 환경변수 `BP_OFFICE_OPENAI_API_KEY`, 연결엔 이름만(사용자 위임 10/25 13:22). 키 발급과 실제 호출 8회는 사용자에게 올라감 |
| 2026-10-30 06:16 | 구현, 단위시험 142건, 로컬 실제 호출(6절 2) |
| 2026-10-30 06:28 | 컨타 [승인] ②안. 키 없이 로컬 한국어 음성 엔진부터 견준다. OpenAI 실제 호출 보류 |
| 2026-10-30 07:05 | 로컬 엔진 비교(6절 5). 엔진 고르기 컨타 [승인요청] |
