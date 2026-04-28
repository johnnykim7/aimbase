# 원본 요구사항 — TURN-TCP (RFC 6062) ConnectionBind 모드 기반 NAT 우회 정공 구현

**대화 일자**: 2026-04-29
**요청자**: sykim
**관련 CR**: CR-041 (Agent Registry / STUN·TURN 인프라), CR-042 (aimbase-agent), CR-071 (ClaudeCliAdapter — 3경로 통일), CR-072/073 (서버 MCP endpoint + agent Spring Boot 통합)
**발번 CR**: CR-074

---

## 사용자 원본 메시지 (그대로)

> 아래의 방법으로 진행해주세요
>
> TURN-TCP (RFC 6062) ConnectionBind 모드 기반 NAT 우회 정공 구현
>
> 사용자가 처음부터 가지고 계셨던 그림 ("WebSocket 상시 세션 부담 피하고 필요할 때만 깨어나는 모델")
> agent 가 부팅 시 TURN 서버에 TCP-Allocate → relay 주소를 metadata.runnerEndpoint 로 등록
> Aimbase BE 가 그 relay 주소로 일반 HTTP TCP connect → coturn 이 자동으로 agent 로 bridge
> 작업량 1~2일 (TurnTcpAllocator 신규 + 가드 정리 + e2e)
> 미완료 (다음 세션)
> flowguard_dev connections 등록 — TURN-TCP 정공 후
> widget.allowed-origins 에 FlowGuard FE Origin 추가 — TURN-TCP 정공 후
> 설계 캐스케이드 일괄 정리 — TURN-TCP 정공 끝난 후 일괄

---

## 의도 해석

1. **모델**: 상시 WebSocket 세션 부담을 피하고, agent 가 "필요할 때만 깨어나는" 모델을 유지하면서 NAT 뒤 agent 에 BE 가 TCP 로 도달할 경로를 확보.
2. **수단**: TURN(coturn) 서버를 중계로 활용. RFC 6062 (TURN over TCP) ConnectionBind 모드.
3. **흐름 (사용자 표현)**:
   - agent 부팅 → TURN 서버에 **TCP-Allocate** → relay 주소 획득
   - relay 주소를 `metadata.runnerEndpoint` 로 Aimbase BE 에 등록
   - BE 가 그 relay 주소로 **일반 HTTP TCP connect**
   - coturn 이 자동으로 agent 로 bridge

## 구현 시 정확한 RFC 6062 흐름 (기록용)

사용자 표현 "coturn 이 자동으로 agent 로 bridge" 는 결과적 인식이고, 실제 RFC 6062 는 다음을 요구한다:

1. agent → TURN: **TCP control connection** 수립 (장기 유지)
2. agent → TURN: `Allocate(REQUESTED-TRANSPORT=6/TCP)` → **relay address** 획득
3. (외부 client → relay address:port 로 TCP connect)
4. coturn → agent: control connection 위로 **ConnectionAttempt indication** (connection-id 포함)
5. agent → TURN: **새 TCP connection** 수립
6. agent → TURN: 새 connection 위로 `ConnectionBind(connection-id)` 송신
7. 이후부터 (외부 client ↔ agent) 양방향 TCP 데이터 — 두 connection 사이를 coturn 이 splice

→ 즉 agent 측에 (a) control connection 유지 (b) ConnectionAttempt 처리 루프 (c) ConnectionBind 응답 클라이언트가 필요. coturn 이 "자동" 으로 끝나는 게 아니라 agent 가 능동적으로 응답해야 함. 사용자 추정 작업량 1~2MD 보다 클 가능성 → 설계서에서 단계 분해 후 재추정.

## 미완료 항목 (TURN-TCP 정공 후 일괄)

- `flowguard_dev` 테넌트 `connections` 테이블 등록
- `widget.allowed-origins` 에 FlowGuard FE Origin 추가
- 설계 캐스케이드(T3 설계서 + CR_변경_이력) 일괄 정리

---

## 사용자 추가 결정 (2026-04-29 대화 中)

- 진행 모델: **RFC 6062 정공 (full ConnectionBind)** 선택. (대안인 단순 TCP relay / 리버스 프록시 / SSH 터널 거부)
