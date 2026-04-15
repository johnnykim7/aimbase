# 원본 대화 — CR-043 ClaudeCodeTool 다중계정 운영 정책

날짜: 2026-04-14
참여: sykim (요청자), Claude Code (구현 담당)
맥락: 운영 서버(59.8.160.12) 배포 중 ClaudeCodeTool 인증 방식 논의

## 사용자 요구사항

> "현재 키 관리 방식이 어떻냐고요"

(브리핑 후)

> "태널트별로 키값을 분리해서 사용하거나 옵션에 따라 공통 계정을 같이 사용하거나 그래야할거 같습니다. 그리고 각 키값을 하나가 문제생기면 다른 하나를 사용할수 있게 해야하고요"

> "우선 CR로 남겨두세요"

> "그런데 아까 agent별로 키값을 관리한다는걸 언뜻본것 같아서 db테이블을."

## 핵심 발견사항 (현재 코드 상태)

### 이미 구축된 인프라 (Master DB)

- `agent_accounts` 테이블: 계정별 토큰 저장
  - `auth_type` 컬럼 + `config` JSONB(`auth_type` / `auth_token` / `token_saved_at`)
  - `priority`, `max_concurrent`, `status`, `health_status`
- `agent_account_assignments` 테이블: (agent_type, tenant_id, app_id) → account 매핑
  - `assignment_type`(fixed/round_robin), `priority`

### 이미 동작하는 페일오버

`AgentAccountPoolManager.resolveAccount(agentType, tenantId, appId)`:
1. (agent_type, tenant_id, app_id) 정확 매칭 → isAvailable이면 사용
2. (agent_type, tenant_id, *) 매칭 → 다음 후보
3. (agent_type, *, app_id) 매칭
4. (*, *) 라운드로빈 풀
5. 글로벌 키 폴백

`isAvailable()`은 GenericCircuitBreaker(3회 실패 → 5분 OPEN) + 동시성 카운터 확인. 즉 한 계정이 죽으면(서킷 OPEN) 자동으로 다음 후보로 넘어감.

### 미충족 요구사항

호출 중간에 토큰이 만료되거나 Rate Limit에 걸리면 그 호출 자체는 실패. 다음 호출부터 다른 계정 사용. **단일 호출 내 자동 재시도는 미구현**.

## 결론

- **운영 정책**(테넌트 전용 + 공통 폴백)은 이미 코드가 지원 → 운영 데이터 등록만 필요
- **호출중 자동 재시도**만 신규 코드 보강 필요 → CR-043으로 등록

## 권장 운영 세팅 (현재 5개 테넌트 기준)

계정 6개:
- `axopm-acct`, `flowguard-acct`, `bidding-acct`, `shopai-acct` (테넌트 전용 4)
- `common-acct-1`, `common-acct-2` (공통 폴백 2, 라운드로빈)

assignments:
- 각 테넌트별 fixed 매핑(priority=100)
- 공통 풀 round_robin 매핑(tenant_id=NULL, priority=50)

## 후속

- 호출중 재시도 코드 보강(ClaudeCodeTool 재시도 루프 + AgentAccountPoolManager 재진입)
- aimbase-ops-guide.md에 시나리오 추가
- PRD 번호 발번 후 본 CR 본문에 반영
