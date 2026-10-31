# CR-161 연결 묶음 적용 요청 (bp-office)

> 보내는 쪽: aimbase CR-161 워커 | 작성: 2026-10-30 | 받는 쪽: bp-office 를 맡은 컨타
> CR 번호와 워커 배정은 받는 쪽 컨타가 정한다. 사용자 승인: 2026-10-30 06:06 「1」

## 1. 왜 바꾸나

bp 계열 Claude CLI 실행기 사용량을 therecommerce 계정 work01, work02, work03 에 나눠 쓴다. 한 계정이 사용 한도에 걸리면 다음 계정이 이어받는다. aimbase 는 이것을 연결 묶음(connection group, ROUND_ROBIN)으로 한다.

aimbase 개발기에서는 2026-10-30 06:07 에 bp-office 워크플로우 8개의 LLM_CALL 단계를 묶음 `cli-runner-bp-office-rr` 로 바꿨다. 정의 원본인 bp-office 파일은 아직 연결 `cli-runner-bp-office-001` 을 가리킨다. 이 파일로 워크플로우를 다시 등록하면 계정 하나로 돌아가고, 그 계정이 한도에 걸리면 bp-office 의 AI 기능이 같이 멈춘다.

## 2. 바꿀 곳

줄 번호는 2026-10-31 bp-office `develop`(371588c7) 기준이다. 모두 `docs/자산/AI_워크플로우/` 아래 파일이다. 10/30 에 8개로 보냈고, 그 뒤 생긴 9, 10 을 더했다. `bp-office-decision-report.json`, `bp-office-briefing.json` 은 이미 묶음 값이라 그대로 둔다.

| 순번 | 파일 | 줄 |
|---|---|---|
| 1 | `bp-office-assignment-proposal.json` | 37 |
| 2 | `bp-office-classification.json` | 95 |
| 3 | `bp-office-extraction.json` | 36 |
| 4 | `bp-office-intake-investigation.json` | 371 |
| 5 | `bp-office-meeting-prep.json` | 43 |
| 6 | `bp-office-meeting-proposal.json` | 40 |
| 7 | `bp-office-suspect-segments.json` | 30 |
| 8 | `bp-office-task-execution.json` | 38 |
| 9 | `bp-office-lead-report.json` | 275 |
| 10 | `bp-office-calendar-conflict.json` | 84 |

9 는 2026-10-30 06:48 에 이 파일 그대로 개발기에 다시 등록돼, 지금 개발기에서도 계정 하나로 돈다. 10 은 아직 개발기에 등록되지 않았다.

각 줄의 `"connection_id": "cli-runner-bp-office-001"` 을 `"connection_group_id": "cli-runner-bp-office-rr"` 로 바꾼다. 같은 단계 config 의 다른 값은 그대로 둔다. 두 값이 같이 있으면 묶음이 앞서지만, aimbase 화면이 저장하는 모양과 같게 `connection_id` 는 뺀다.

## 3. 계약

정본은 aimbase API 가이드 § 25 「연결 묶음」(v3.23.0): `/Users/sykim/Documents/GitHub/bp-platform/aimbase/docs/guides/aimbase-api-guide.md`

1. 워크플로우 실행 요청과 응답 형식은 바뀌지 않는다
2. 묶음의 세 계정이 모두 한도에 걸렸거나 닿지 않으면 그 단계가 `connection_group_unavailable:retry_after=<ISO 시각>` 으로 실패한다. 지금의 단계 실패 처리에 그대로 태우면 된다

## 4. 확인

1. 받는 쪽: 파일을 바꾼 뒤 다시 등록하는 경우, 등록된 단계 config 에 `connection_group_id` 가 남아 있는지 본다
2. aimbase 쪽: 개발기 첫 실행에서 묶음이 work 계정 연결로 가는지 aimbase CR-161 워커가 확인해 알려 준다

## 5. 묻는 곳

물을 것이 있으면 사용자를 통해 aimbase CR-161 워커에게 보낸다. 설계 전체는 `/Users/sykim/Documents/GitHub/bp-platform/aimbase/docs/CR-161_워크플로우_단계의_연결_묶음과_bp_계열_CLI_계정_3개_나눠_쓰기.md` 에 있다.
