# CR-161 연결 묶음 적용 요청 (bp-wes, bp-wms)

> 보내는 쪽: aimbase CR-161 워커 | 작성: 2026-10-28 | 받는 쪽: bp-wes, bp-wms 를 맡은 컨타
> CR 번호와 워커 배정은 받는 쪽 컨타가 정한다. 사용자 승인: 2026-10-28 05:25

## 1. 왜 바꾸나

bp 계열 Claude CLI 실행기 사용량을 therecommerce 계정 work01, work02, work03 에 나눠 쓴다. 한 계정이 사용 한도에 걸리면 다음 계정이 이어받는다.

aimbase 는 이것을 연결 묶음(connection group, ROUND_ROBIN)으로 한다. 계정마다 커넥션을 하나씩 두고 그 커넥션들을 묶음 하나로 묶는다. 호출하는 쪽이 `connection_id` 대신 `connection_group_id` 를 보내야 묶음을 탄다. 지금처럼 `connection_id` 를 보내면 계정 하나에 계속 몰리고, 그 계정이 한도에 걸리면 호출이 실패한다.

## 2. 계약

정본은 aimbase API 가이드 § 25 「연결 묶음」(v3.23.0): `/Users/sykim/Documents/GitHub/bp-platform/aimbase/docs/guides/aimbase-api-guide.md`

1. `POST /api/v1/chat/completions` 본문에서 `connection_id` 자리에 `connection_group_id` 를 넣는다. 둘 다 오면 묶음이 앞선다
2. `system_prompt_override`, `tool_mode`, `allowed_tools`, `session_id` 같은 다른 필드는 그대로다. aimbase 가 고른 멤버 커넥션에 그대로 싣는다
3. 같은 `session_id` 는 24시간 동안 같은 멤버에 붙는다. `session_id` 가 없으면 호출마다 차례대로 고른다
4. 응답 형식은 바뀌지 않는다
5. 새로 생기는 오류

| 순번 | 상황 | HTTP | 메시지 머리 |
|---|---|---|---|
| 1 | 묶음의 모든 멤버가 한도에 걸렸거나 닿지 않음 | 503 | `connection_group_unavailable:retry_after=<ISO 시각>` |
| 2 | 단일 커넥션(`connection_id`)으로 보냈는데 그 계정이 한도에 걸림 | 503 | `cli_account_limited:resets_at=<초>:type=<종류>:` |

503 은 지금 실패 처리(재시도, 폴백 문구)에 그대로 태우면 된다. `retry_after` 전에 다시 보내도 같은 503 이 온다.

## 3. 바꿀 곳

줄 번호는 2026-10-28 각 레포 기본 작업 폴더 기준이다.

| 순번 | 레포 | 위치 | 지금 값 | 바꿀 묶음 |
|---|---|---|---|---|
| 1 | bp-wes | `backend/src/main/java/com/therecommerce/wes/infra/aimbase/AimbaseClient.java:124` 와 `chat(ChatOptions, ...)` 본문. 설정 `wes.aimbase.connection-id` | `cli-runner-bp-wes-001` | `cli-runner-bp-wes-rr` |
| 2 | bp-wes | `backend/src/main/java/com/therecommerce/wes/domain/cs/service/CsSlackTriageService.java:64`, `CsLookupQueueService.java:69`. 설정 `wes.cs.aimbase.connection-id` | `cli-runner-bp-wes-cs-001` | `cli-runner-bp-wes-cs-rr` |
| 3 | bp-wes | `backend/src/main/java/com/therecommerce/wes/infra/aimbase/AimbaseVisionClient.java` 의 `VisionModel.CLAUDE_CLI` | `cli-runner-bp-wes-001` | `cli-runner-bp-wes-rr` |
| 4 | bp-wms | `src/main/java/com/therecommerce/wms/infra/client/AimbaseChatClient.java:94`. 설정 `integration.aimbase.field-issue-connection-id` | 환경변수로 받음 | `cli-runner-bp-wms-field-issue-rr` |

`AimbaseVisionClient` 의 `FAST_7B`, `ACCURATE_32B`(qwen 판독 모델)은 CLI 계정과 상관없어 그대로 둔다.

권하는 방식: 묶음 id 를 설정 값으로 하나 더 받는다. 값이 있으면 `connection_group_id` 를 보내고, 비어 있으면 지금처럼 `connection_id` 를 보낸다. 묶음 id 를 코드에 박지 않는다. 되돌릴 때는 그 값만 비운다.

## 4. 순서

1. 코드는 먼저 들어가도 된다. 묶음 id 설정이 비어 있으면 지금과 똑같이 돈다
2. aimbase 가 개발기에 CR-161 을 반영하고 묶음 4개를 만든 뒤에 묶음 id 값을 넣는다. 묶음이 없는데 값을 넣으면 호출이 실패한다
3. aimbase 쪽 반영이 끝나면 aimbase CR-161 워커가 사용자를 통해 알린다

## 5. 확인

1. 받는 쪽: 묶음 id 를 넣은 뒤 평소 기능(일일 브리핑, CS 슬랙 분류와 조회, CLI 사진 판독, 현장 이슈 질의)이 전과 같이 답하는지 본다
2. aimbase 쪽: 같은 시간대 채팅 실행 기록의 커넥션이 `-work01`, `-work02` 같은 멤버로 찍히는지 aimbase 워커가 확인해 알려 준다

## 6. 묻는 곳

계약이나 오류 처리에 물을 것이 있으면 사용자를 통해 aimbase CR-161 워커에게 보낸다. 설계 전체는 `/Users/sykim/Documents/GitHub/bp-platform/aimbase/docs/CR-161_워크플로우_단계의_연결_묶음과_bp_계열_CLI_계정_3개_나눠_쓰기.md` 에 있다.
