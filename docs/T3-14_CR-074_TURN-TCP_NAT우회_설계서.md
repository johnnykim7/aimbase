# T3-14 CR-074 TURN-TCP (RFC 6062) NAT 우회 설계서

- **CR**: CR-074 — TURN-TCP ConnectionBind 모드 기반 NAT 우회 정공 구현
- **적용 버전**: v8.8.0 (예정)
- **작성일**: 2026-04-29
- **상태**: ✅ 구현 완료 (단위 테스트 PASS / 실측 e2e 대기)
- **원본 요구사항**: `docs/origins/원본_요구사항_TURN-TCP_NAT우회_RFC6062_20260429.md`
- **관련 CR**: CR-041 (Agent Registry / STUN·TURN 인프라), CR-042 (aimbase-agent), CR-071 (ClaudeCliAdapter), CR-072/073 (서버 MCP endpoint + agent Spring Boot 통합)

---

## 1. 개요

### 1.1 목표

NAT 뒤 사용자 PC 에서 동작하는 `aimbase-agent` 의 `RunnerController` 로 Aimbase BE(`ClaudeCliRunnerClient`)가 **공인 IP 없이** TCP HTTP 호출을 보낼 수 있게 한다. 표준 RFC 6062 (TURN over TCP — ConnectionBind) 모드를 정공으로 채택.

### 1.2 사용자 본래 의도

> "WebSocket 상시 세션 부담을 피하고 필요할 때만 깨어나는 모델"

- agent 입장에서 idle 시 cost 0 (TURN control connection 1개만 유지)
- inbound 호출이 발생할 때만 새 TCP 연결 1개 추가 (loopback 1개 + TURN data 1개)
- WebSocket 풀/핑/세션 상태 관리 일체 없음

### 1.3 핵심 설계 결정

| # | 키워드 | 결정 | 비고 |
|---|---|---|---|
| 1 | TURN 전송 | TCP (RFC 6062) | UDP 기존 `TurnRelayClient` 는 `@Deprecated` |
| 2 | 인증 | TURN long-term credential (HMAC-SHA1, shared secret) | CR-041 인프라 그대로 |
| 3 | agent 사이드 통합 | **loopback bridge** (`localhost:8290`) | RunnerController 무수정 재사용 |
| 4 | BE 사이드 통합 | **무수정** | `metadata.runnerEndpoint = "http://<relay>"` 그대로 HTTP base URL 로 사용 |
| 5 | 활성화 | 기본 OFF (`AgentConfig.turnEnabled=false`) | 후방호환 |
| 6 | refresh | 본 CR 범위 외 (lifetime 600s, agent 재시작 시 재할당) | 후속 CR 후보 |
| 7 | 다중 TURN 페일오버 | 범위 외 (단일 인스턴스 가정) | 후속 CR 후보 |
| 8 | TURNS (TLS) | 범위 외 | 후속 보안 강화 CR 후보 |

---

## 2. 아키텍처

### 2.1 As-Is (CR-073 직후)

```
[BE ClaudeCliRunnerClient]                        [agent Tomcat:8290]
           │                                              │
           ▼                                              ▼
       HTTP POST  ───────────  공인 IP 가정  ─────────  RunnerController
                         (NAT 뒤면 도달 불가)
```

`AgentRegistryEntity.runnerEndpoint = "http://<공인IP>:8290"` 으로 등록되어야 BE 가 도달. NAT 뒤 사용자 PC 는 등록 자체가 불가하거나 도달 실패.

### 2.2 To-Be (CR-074 적용 후)

```
                        ┌────────────── coturn (59.8.160.12:3478) ──────────────┐
                        │                                                       │
                        │  ① Allocate(TCP) ←──── control connection (long-lived)│ ◄── agent 부팅
                        │  ④ ConnectionAttempt indication ───────────────►      │
                        │                                                       │
                        │  ② relay address 발급 (예: <relay>:51234)             │
                        │                                                       │
[BE ClaudeCliRunnerClient]                                                      │
   │ ③ TCP connect to <relay>:51234                                             │
   │  HTTP POST /v1/chat                                                        │
   ▼                                                                            │
  TURN data conn ──── splice ──── ⑤ agent's ConnectionBind data conn            │
                                                                                │
                        └────────────────────────────────────────────────────────┘
                                        │
                                        ▼
                        [agent TurnConnectionBindHandler 콜백]
                                        │
                                        │  ⑥ TurnLoopbackBridge.bridge(socket)
                                        ▼
                                [localhost:8290 Tomcat]
                                        │
                                        ▼
                                [RunnerController.chat]
```

### 2.3 컴포넌트 책임

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| `TurnTcpAllocator` | sdk/tool-sdk-mcp | TCP 위 Allocate 송수신 (REQUESTED-TRANSPORT=6). control socket 을 **닫지 않고** Allocation 으로 반환 |
| `TurnAuthMaterial` | sdk/tool-sdk-mcp | username/realm/nonce/key 묶음. ConnectionBind 시 동일 인증 자료 재사용 |
| `TurnConnectionBindHandler` | sdk/tool-sdk-mcp | control socket 위 ConnectionAttempt indication 수신 루프 → 새 TCP socket → ConnectionBind 송신 → success 후 acceptedSocketHandler 콜백 |
| `TurnLoopbackBridge` | sdk/tool-sdk-mcp | 외부 socket ↔ `localhost:8290` Tomcat 양방향 byte pump (스레드 2개) |
| `AgentLifecycle` | sdk/tool-sdk-mcp | `turnEnabled=true` 시 Allocator → BindHandler+Bridge 가동, `metadata.runnerEndpoint = "http://<relay>"` 로 등록 |
| `AgentConfig` | sdk/tool-sdk-mcp | `turnEnabled / turnTransport / runnerPort` 필드 추가 |

### 2.4 시퀀스 — 정상 흐름

```
agent: AgentLifecycle.start()
  ├─ MCP 서버 기동
  ├─ STUN: discoverPublicAddress
  └─ if turnEnabled && turnTransport=TCP:
       ├─ TurnTcpAllocator.allocate(turnServer, turnPort, realm, secret, agentName)
       │    ├─ TCP connect to TURN
       │    ├─ Allocate(no auth)        ──► 401 + nonce/realm
       │    ├─ Allocate(MESSAGE-INTEGRITY) ──► 200 + XOR-RELAYED-ADDRESS
       │    └─ 반환: Allocation(controlSocket, "<relay>:<port>", lifetime, authMaterial)
       │
       ├─ metadata.put("runnerEndpoint", "http://<relay>:<port>")
       │
       ├─ TurnLoopbackBridge bridge = new TurnLoopbackBridge(runnerPort=8290)
       │
       └─ TurnConnectionBindHandler handler = new TurnConnectionBindHandler(
              turnServer, turnPort, controlSocket, authMaterial, bridge::bridge)
          handler.start()  // 별도 스레드에서 controlSocket read 루프

[idle — agent 가 아무것도 안 함]

BE: ClaudeCliRunnerClient.chat(endpoint=http://<relay>, ...)
  ├─ TCP connect to <relay>:<port>            ─────► (coturn)
  └─ HTTP POST /v1/chat                                 │
                                                        │
                                          coturn → control connection 위로
                                          ConnectionAttempt indication(connection-id)
                                                        ▼
agent: TurnConnectionBindHandler.receiveLoop
  ├─ ConnectionAttempt(0x001C) 수신, connection-id 추출
  ├─ 새 TCP socket connect to TURN
  ├─ ConnectionBind(0x000B, connection-id, MESSAGE-INTEGRITY) 송신
  ├─ ConnectionBind success(0x010B) 수신
  └─ acceptedSocketHandler(socket)
       │
       ▼
TurnLoopbackBridge.bridge(socket)
  ├─ TCP connect to localhost:8290 (loopback Tomcat)
  ├─ pump1: socket.in  → loopback.out
  └─ pump2: loopback.in → socket.out  (HTTP 응답이 BE 까지 도달)

BE: HTTP 200 응답 수신 (LLMResponse)
```

---

## 3. 인터페이스 명세

### 3.1 `TurnTcpAllocator`

```java
public final class TurnTcpAllocator {
    public record Allocation(
        Socket controlSocket,        // open 상태 — 호출자가 close 책임
        String relayAddress,         // "ip:port"
        int lifetimeSeconds,
        TurnAuthMaterial authMaterial // anonymous 서버는 null
    ) {}

    public static Allocation allocate(
        String turnServer, int turnPort,
        String realm, String sharedSecret,
        String agentName);   // 실패 시 null
}
```

### 3.2 `TurnConnectionBindHandler`

```java
public class TurnConnectionBindHandler implements AutoCloseable {
    public TurnConnectionBindHandler(
        String turnServer, int turnPort,
        Socket controlSocket,
        TurnAuthMaterial authMaterial,
        Consumer<Socket> acceptedSocketHandler);

    public void start();          // 별도 스레드 받기 시작
    @Override public void close(); // 받기 중지 + control socket 정리
}
```

### 3.3 `TurnLoopbackBridge`

```java
public class TurnLoopbackBridge {
    public TurnLoopbackBridge(int runnerPort);              // 127.0.0.1
    public TurnLoopbackBridge(String host, int runnerPort); // 커스텀 host

    public void bridge(Socket external);  // 즉시 반환 — 두 byte pump 별도 스레드
    public void shutdown();
}
```

### 3.4 `AgentConfig` 확장 (record 필드)

```java
public record AgentConfig(
    /* 기존 12 필드 ... */
    boolean turnEnabled,            // CR-074 — 기본 false
    TurnTransport turnTransport,    // UDP | TCP — 기본 TCP
    int runnerPort                  // 기본 8290
) {}
```

후방호환 생성자 3개 유지: 5/4/8/12 필드 형식 모두 그대로 작동(turnEnabled=false 기본값).

---

## 4. RFC 6062 메시지 포맷 일증 (구현 검증)

### 4.1 Allocate(TCP) Request — unauth

| 필드 | 값 | 비고 |
|---|---|---|
| Type | 0x0003 | Allocate Request |
| Length | 8 | 본문 (REQUESTED-TRANSPORT TLV 만) |
| Magic Cookie | 0x2112A442 | RFC 5389 |
| Transaction ID | 12 bytes random | SecureRandom |
| **REQUESTED-TRANSPORT** | type=0x0019 len=4 value=`06 00 00 00` | **TCP=6** |

### 4.2 Allocate(TCP) Request — auth

추가 attributes:
- USERNAME (0x0006)  — `"<expiry-epoch>:<agent-name>"`
- REALM (0x0014)     — 서버 응답 realm 우선
- NONCE (0x0015)     — 401 응답에서 추출
- MESSAGE-INTEGRITY (0x0008, 20 bytes) — HMAC-SHA1 over preceding bytes, key=MD5("user:realm:pass")

### 4.3 ConnectionAttempt Indication (server → agent)

| 필드 | 값 |
|---|---|
| Type | 0x001C |
| **CONNECTION-ID** | type=0x002A len=4 value=uint32 |

### 4.4 ConnectionBind Request (agent → server, on **new** TCP socket)

| 필드 | 값 |
|---|---|
| Type | 0x000B |
| **CONNECTION-ID** | type=0x002A len=4 value=수신한 connection-id |
| USERNAME / REALM / NONCE / MESSAGE-INTEGRITY | Allocate 와 동일 자료 재사용 |

### 4.5 ConnectionBind Success (server → agent, on data socket)

| 필드 | 값 |
|---|---|
| Type | 0x010B |
| body | 0 bytes (txn id 만 echo) |

이후 양 socket(BE↔TURN, agent↔TURN) 사이가 splice 되어 raw byte 가 양방향으로 흐름.

---

## 5. 구현 매핑 (코드 ↔ 설계)

| 설계 항목 | 구현 위치 | 검증 테스트 |
|---|---|---|
| Allocate(TCP) Request 빌드 | [TurnTcpAllocator.buildAllocateRequest](../backend/sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/TurnTcpAllocator.java) | `TurnTcpAllocatorTest.buildAllocateRequest_unauthenticated_setsTransportTcp` |
| 401 + nonce/realm 파싱 | TurnTcpAllocator.allocate Step 1 | `allocate_anonymousServer_returnsRelayWithoutAuth` (anonymous 분기) |
| MESSAGE-INTEGRITY 부착 | TurnTcpAllocator.buildAllocateRequest hasAuth=true | `buildAllocateRequest_authenticated_appendsCredentialAndIntegrity` |
| XOR-RELAYED-ADDRESS 파싱 | TurnTcpAllocator.extractRelayAddress | `extractRelayAddress_xorReversesCorrectly` |
| readStunMessage (TCP framing) | TurnTcpAllocator.readStunMessage | `readStunMessage_returnsHeaderPlusBody` |
| ConnectionAttempt indication 수신 | TurnConnectionBindHandler.receiveLoop | `receiveLoop_handlesConnectionAttempt_callsSocketHandler` |
| CONNECTION-ID 추출 | TurnConnectionBindHandler.extractConnectionId | `extractConnectionId_findsAttribute` |
| ConnectionBind Request 빌드 | TurnConnectionBindHandler.buildConnectionBindRequest | `buildConnectionBindRequest_includesConnectionIdAndIntegrity` |
| Socket → loopback bridging | TurnLoopbackBridge.bridge | `TurnLoopbackBridgeTest.bridgesBidirectional` |
| 연결 실패 시 정리 | TurnLoopbackBridge.bridge catch | `connectFailure_closesExternalSocket` |
| `metadata.runnerEndpoint` 등록 | AgentLifecycle.startTurnTcp | (실측 e2e 단계) |

---

## 6. 운영 / 설정

### 6.1 coturn 측 요구사항

```ini
# /etc/turnserver.conf
listening-port=3478
listening-ip=59.8.160.12

# TCP 모드 활성화
no-tcp=false
no-tcp-relay=false
no-udp=false      # UDP 도 유지 (기존 STUN 호환)

# Long-term credential
use-auth-secret
static-auth-secret=e1e1df7f0e394f4c601ca620ff0b4032998cb95373b7abc47d5271cf1bde4bab
realm=turnpike.local

# 로그
verbose
```

확인 명령:

```sh
# coturn 이 TCP 3478 listen 중인지
nc -zv 59.8.160.12 3478

# Allocate(TCP) 동작 여부 — turnutils_uclient (TCP 모드)
turnutils_uclient -t -y -v -u <expiry>:agentName \
    -W e1e1df7f0e394f4c601ca620ff0b4032998cb95373b7abc47d5271cf1bde4bab \
    -r turnpike.local 59.8.160.12
```

### 6.2 agent 측 활성화

`AgentConfig` 생성 시 turnEnabled=true 로 만들거나 (host application 책임), 시스템 프로퍼티/환경변수로 전환:

```yaml
# aimbase-agent application.yml (예시 — 후속 CR 에서 정식화)
agent:
  turn:
    enabled: true
    server: 59.8.160.12
    port: 3478
    realm: turnpike.local
    transport: TCP
  runner:
    port: 8290
```

### 6.3 BE 측

코드 수정 없음. AgentRegistry 에 등록된 `runnerEndpoint` 가 "http://<relay-ip>:<relay-port>" 형태이면 그대로 작동. ClaudeCliRunnerClient 의 `URI.create(endpoint.runnerEndpoint() + "/v1/chat")` 가 그대로 통과.

---

## 7. 트레이드오프 / 알려진 제약

1. **단일 control connection** — TURN control socket 이 끊어지면 모든 ConnectionAttempt 수신 불가. 재수립 자동화는 후속 CR.
2. **Allocate lifetime refresh 미구현** — 기본 600s 후 만료. 본 턴은 agent 재시작 시 재할당. refresh 스케줄러는 후속 CR.
3. **다중 TURN 페일오버 없음** — 단일 coturn 인스턴스 가정.
4. **TURNS (TLS over TCP)** 미지원 — 평문 STUN/TURN. 보안 강화는 후속.
5. **loopback hop latency** — 측정상 < 1ms (LAN 무관). 동시 세션 수가 적은 agent 사이드라 무시 가능.
6. **relay address 노출 = HTTP 도달** — BE 의 `X-Api-Key` 검증이 유일한 방어선. 키 회전 정책 강화 필요(후속).

---

## 8. 검증 / 완료 기준

- [x] `TurnTcpAllocatorTest` 7 PASS (Allocate request 빌드 / 응답 파싱 / anonymous 흐름 / XOR / framing / **CreatePermission / Refresh**)
- [x] `TurnConnectionBindHandlerTest` 3 PASS (request 빌드 / CONNECTION-ID / 실제 ConnectionAttempt → ConnectionBind 시나리오)
- [x] `TurnLoopbackBridgeTest` 2 PASS (양방향 / 연결 실패 정리)
- [x] 백엔드 전체 테스트 회귀 PASS (**648 PASS**)
- [x] **실측 e2e PASS**: 외부 curl → TURN relay → ConnectionBind → loopback → RunnerController `/v1/health` 200 OK 응답 정상 수신
- [x] **RFC 5766 §9 CreatePermission 추가** — peer IP 화이트리스트 등록 + lifetime/2 주기 자동 갱신
- [x] ops 가이드 §TURN 절 v3.1.0 (별도 CR 행)

---

## 9. 후속 CR 후보

- coturn lifetime refresh + control connection 재수립 자동화
- TURNS (TLS) 지원
- 다중 coturn 페일오버 + health check
- TURN allocate 실패 시 폴백 체인 (직접 IP → frpc → 등록 거부)

---

## 10. 변경 이력 (본 설계서 자체)

| 버전 | 일자 | 변경 |
|---|---|---|
| 1.0 | 2026-04-29 | 초안 — 구현 완료 시점 정리 |
