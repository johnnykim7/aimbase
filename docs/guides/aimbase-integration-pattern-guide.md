# Aimbase 연동 방식 선택 가이드

> **용도**: 소비앱에 Aimbase 를 붙일 때 "어떤 방식으로 붙일 것인가"를 결정하는 기획 판단 가이드.
> **대상**: 소비앱 기획자·개발자, 그리고 소비앱 작업을 돕는 Claude Code.
> **버전**: v1.0.0 (2026-07-24)

이 문서는 **API 사용법이 아니라 방식 선택**을 다룬다. 구체적 엔드포인트/파라미터는
[aimbase-api-guide.md](aimbase-api-guide.md), 관리자 운영은 [aimbase-ops-guide.md](aimbase-ops-guide.md) 참조.

---

## 0. 30초 요약 (결정 트리)

```
Q1. 같은 절차가 반복되고 매번 같은 형태의 산출물이 나와야 하나?
    YES → 워크플로우 (§1)
    NO  → Q2

Q2. 사용자 질문이 매번 다르고 예측 불가한가? (CS, 원인 추적, 탐색적 분석)
    YES → 자율 대화 (§2) → Q3

Q3. 데이터를 읽기만 하나, 바꾸기도 하나?
    읽기만  → NATIVE + 읽기전용 DB 계정 (§3.1)  ★가장 단순·강력
    쓰기 포함 → HYBRID: 읽기는 자율, 쓰기는 MCP 도구 창구 (§3.2)

Q4. (Q2 YES 인데) DB 가 아니라 **기존 웹 화면을 사람과 함께 조작**해야 하나?
    → 브라우저 코파일럿 → docs/guides/aimbase-browser-copilot-guide.md
       화면이 복잡해 사람이 절차를 못 외우는 경우. 작업자 PC 에서 브라우저를
       띄우고 대화하며 조작한다. HYBRID + Playwright MCP.
```

---

## 1. 워크플로우 방식

**언제**: 절차 고정 + 재현성 필요 + 감사 로그 필수.

**예**: 야간 배치 브리핑, 정산 처리, 대량 문서 전수 분석, 단계별 승인이 필요한 업무.

**장점**
- 실행 이력이 `workflow_runs` / `workflow_run_events` 에 남아 **FE "실행 내역" 화면에서 추적 가능**
- 중간에 사람 승인 단계(approve) 삽입 가능
- 단계별 재실행·취소 가능

**주의**
- 질문이 매번 다른 업무에는 부적합(노드를 계속 고쳐야 함)

---

## 2. 자율 대화 방식

**언제**: 질문이 매번 다름. CS 문의 응대, 장애·이상 원인 추적, 탐색적 데이터 분석.

**호출**: `POST /api/v1/chat/completions` (`connection_id` 로 CLI 커넥션 지정)

**실행 추적 (CR-130)**
- CLI 커넥션 채팅은 **"실행 내역" 메뉴에 워크플로우와 함께 표시**된다("채팅" 뱃지 + 유형 필터).
- CLI 가 내부에서 호출한 도구 궤적도 **TOOL_USE/TOOL_RESULT 로 적재**되어 상세 화면에서 열람된다.
- 대화 본문 자체는 `conversation_messages` 에 남는다(최종 응답 텍스트 기준).

---

## 3. 자율 대화의 3가지 도구 모드

`tool_mode` 는 커넥션 config 기본값을 쓰되, **요청마다 override 가능**(§4).

| 모드 | CLI 내장 도구(Bash/Read/Write…) | Aimbase·소비앱 MCP 도구 | 성격 |
|---|---|---|---|
| `NATIVE` | **전부 사용** | 미연결 | 자율성 최대, 통제는 외부(계정 권한)로 |
| `AIMBASE` | 3개로 봉인(Glob/Grep/ToolSearch) | 사용 | 통제 최대, CLI 능력 대부분 봉인 |
| `HYBRID` | **전부 사용** | 사용 | 자율 + 정해진 창구 병용 |

> **실측**: 같은 브리핑 작업에서 AIMBASE 는 CLI 내장 도구가 3개, HYBRID 는 29개였다.
> "우리 도구를 쓰면서 CLI 자율성도 살리는" 조합은 **HYBRID 하나뿐**이다.

### 3.1 읽기 전용 업무 → NATIVE + 읽기전용 DB 계정 (권장)

MCP 도구를 만들지 않는다. **접속정보만 프롬프트로 주면 CLI 가 알아서 한다.**

**실측 결과 (2026-07-24, bp-wes)**: 테이블 구조를 **일절 알려주지 않고** 접속법만 줬을 때 CLI 가 스스로
`\dt` 로 115개 테이블 조회 → 이름만 보고 출고/주문 관련 테이블 선별 → `\d` 로 컬럼 파악 →
집계 SQL 자작 → 일별추이·상태분포·고객사별·시간대 패턴 분석 → **데이터 누락 이상징후까지 자력 발견**.

- **스키마를 프롬프트에 넣지 마라.** CLI 가 직접 탐색하는 편이 더 정확하고(스키마 변경 자동 반영) 프롬프트도 짧다.
- 프롬프트에 담을 것: **접속 방법 + 도메인 힌트 2~3줄 + 안전 규칙**.

```
system_prompt_override 예시:
  당신은 WES 운영 데이터 분석가입니다.
  Bash 로 psql 을 사용해 운영 DB를 직접 조사할 수 있습니다.
    PGPASSWORD=<pw> psql -h <host> -U <readonly_user> -d <db> -c "<SQL>"
  이 계정은 읽기 전용입니다. 테이블 구조는 \dt 와 \d <테이블> 로 직접 탐색하세요.
  실제 조회한 수치만 사용하고 지어내지 마세요.
```

**통제는 MCP 창구가 아니라 DB 계정 권한으로 건다.**

```sql
CREATE ROLE <app>_readonly LOGIN PASSWORD '<pw>';
GRANT CONNECT ON DATABASE <db> TO <app>_readonly;
GRANT USAGE ON SCHEMA public TO <app>_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO <app>_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO <app>_readonly;
```

도구 N개를 구현·유지보수할 필요가 없어지므로 **MCP 방식보다 단순하고 강력하다.**

### 3.2 쓰기 업무 → HYBRID (읽기 자율 + 쓰기 창구)

주문 취소·상태 변경처럼 **데이터를 바꾸는 요청**은 자율에 맡기지 않는다.

- **읽기**: NATIVE 와 동일하게 읽기전용 계정으로 CLI 가 자율 조회
- **쓰기**: `cancel_order(order_no)` 처럼 **좁게 정의된 MCP 도구**로만. 잘못된 UPDATE 한 방을 구조적으로 막는다.
- 두 가지를 한 요청에서 같이 쓰려면 `tool_mode: "HYBRID"`.

### 3.3 AIMBASE 를 쓸 때

민감 데이터라 **정형화된 창구 외에는 아무것도 열 수 없을 때**만. CLI 내장 도구가 3개로 봉인되어
자율성이 크게 떨어진다는 점을 감수해야 한다.

---

## 4. 요청 단위 override (CR-128)

커넥션 설정을 고치지 않고 **요청마다** 모드·성격을 바꿀 수 있다. 생략하면 커넥션 config 값을 그대로 쓴다.

```json
POST /api/v1/chat/completions
{
  "connection_id": "cli-runner-<app>-001",
  "tool_mode": "NATIVE",
  "system_prompt_override": "당신은 ... (접속법·도메인힌트·안전규칙)",
  "messages": [{"role": "user", "content": "어제 출고 지연 원인 알려줘"}]
}
```

관리자 UI(연결 → Claude CLI 커넥션 편집)에도 **Tool Mode / System Prompt Override / Config Dir** 필드가 있다.
테넌트 고정 성격은 UI 에, 요청별 변화는 API 필드에 둔다.

---

## 5. 신규 소비앱 온보딩 체크리스트 (NATIVE 읽기 CS 기준)

1. 테넌트·API Key 발급, CLI 커넥션(`adapter=anthropic-cli`) 생성
2. 전용 Runner 컨테이너 기동 (`AGENT_TENANT_ID` = 해당 테넌트)
3. **읽기전용 DB role 생성** (§3.1 SQL)
4. **Runner ↔ 소비앱 DB 네트워크 연결**
   ```bash
   docker network connect aimbase_default <소비앱DB컨테이너>
   ```
   ※ compose 에 `networks: external: bridge` 로 붙이는 방식은 실패한다
   (`network-scoped alias is supported only for containers in user defined networks`).
   **DB 컨테이너를 aimbase 망에 붙이는 반대 방향**이 정답이며 재기동도 불필요하다.
5. Runner 이미지에 `postgresql-client` 포함 확인 (`aimbase-agent/Dockerfile` 에 반영됨)
6. **커넥션 `exposed_tools` 설정** (§7) — 비워두면 소비앱과 무관한 도구 ~110개가 통째로 노출된다
7. 요청에 `tool_mode: "NATIVE"` + 접속법 `system_prompt_override` 로 검증

---

## 7. 노출 도구 제한 (`exposed_tools`) — 성능에 직결

커넥션 config 의 `exposed_tools` 를 **비워두면 서버 MCP 노출 도구 전체(~110개)가 CLI 에 실린다.**
소비앱이 실제 쓰는 도구만 지정하면 CLI 가 도구를 고르기 쉬워지고 **응답이 크게 빨라진다.**

**실측 (bp-wes, 동일 질문 · AIMBASE 모드)**

| 설정 | CLI 에 실린 도구 | 소요 |
|---|---|---|
| 미설정(기존) | ~110개 | 72초 |
| `exposed_tools` 10개 | 10개 | **19초** |

- **설정 위치**: 관리자 UI(연결 → Claude CLI 커넥션 편집 → **Exposed Tools**) 또는 커넥션 config 직접
- **형식**: CSV 또는 배열. 예 `get_progress,get_bottleneck,bash,get_current_time`
- **우선순위**: 요청의 `tool_filter` > 커넥션 `exposed_tools` > 미설정(전체 노출)
- **주의**: 소비앱이 등록한 원격 도구(MCP)도 여기 포함시켜야 CLI 가 쓸 수 있다.
  최소한 실제 쓰는 도메인 도구 + `bash`(NATIVE/HYBRID 시) + `get_current_time` 정도는 넣는다.

---

## 8. 기획 시 미리 알아둘 제약

| 제약 | 내용 | 영향 |
|---|---|---|
| ~~도구 궤적 비가시~~ | **CR-130 해소** — 채팅의 CLI 도구 호출이 TOOL_USE/TOOL_RESULT 로 적재됨 | 실행 내역 상세에서 열람 가능 |
| ~~실행 내역 화면~~ | **CR-130 해소** — 채팅도 같은 "실행 내역" 메뉴에 "채팅" 뱃지로 표시 | 유형 필터(전체/워크플로우/채팅) 제공 |
| 도구 과다 노출 | 기본값이 전체 노출(~110개) | **§7 `exposed_tools` 로 반드시 좁힐 것** |
| 사용자별 데이터 범위 | "누가 묻느냐"에 따른 범위 제한 없음 | 상담원 CS 등에서 추가 설계 필요 |
| 초기 지연 | MCP 도구는 deferred 라 ToolSearch 왕복 발생 | 도구를 좁히면 크게 줄어듦(§7) |

> **CR-130 적용 범위**: 실행 내역 기록은 **CLI 커넥션 채팅**에만 적용된다(도구 루프를 실제로 도는 경로).
> 일반 LLM 채팅은 내부 도구 궤적이 없어 run 을 만들지 않는다.

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|---|---|---|
| v1.3.0 | 2026-07-26 | 결정 트리에 Q4(브라우저 코파일럿) 추가 — 기존 웹 화면을 사람과 함께 조작하는 패턴. 각론은 `aimbase-browser-copilot-guide.md` |
| v1.2.0 | 2026-07-24 | CR-130 반영 — 채팅도 "실행 내역"에 표시 + CLI 도구 궤적 적재(§2·§8 제약 2건 해소) |
| v1.1.0 | 2026-07-24 | CR-129 반영 — §7 `exposed_tools` 노출 도구 제한(110개→10개, 72초→19초 실측) 추가, 온보딩 체크리스트에 설정 단계 추가 |
| v1.0.0 | 2026-07-24 | 최초 작성. 워크플로우/자율대화 선택 기준, 3모드 비교, NATIVE+읽기전용 계정 패턴(실측 기반), 쓰기 CS 는 HYBRID 창구, 온보딩 체크리스트, 알려진 제약 |
