# ClaudeCodeTool 안정화 및 확장성 개선 — 논의 원본

> 일시: 2026-03-29
> 참여: 사용자(sykim), Claude Code

---

## 1. 배경

ClaudeCodeTool을 Docker 환경에서 구동하며 발견된 문제들과, VS Code 플러그인 동등 품질 달성을 위한 개선, 향후 확장성에 대한 논의.

---

## 2. Docker 구동 시 발견된 문제 및 해결

| # | 문제 | 원인 | 해결 |
|---|------|------|------|
| 1 | "Not logged in" 에러 | `--bare` 플래그가 OAuth 인증 무시 | `--bare` 제거 |
| 2 | `.claude.json` 손상 | 볼륨 마운트 + CLI 동시 수정 | 백업 복원, 컨테이너 내 로그인 |
| 3 | 인증 리셋 | `/tmp/`에 저장 → OS 재부팅 시 삭제 | `~/.claude/docker-auth/` 영구 경로 |
| 4 | 도구 제한으로 품질 저하 | `allowedTools=Read,Grep,Glob` 고정 | 기본값 빈 문자열 (제한 없음) |
| 5 | max_turns 부족 | 기본값 10 → PDF 전체 읽기 불가 | 기본값 50 |
| 6 | PDF Read 권한 거부 | CLI가 작업 디렉토리를 프로젝트 외부로 인식 | `--add-dir` 플래그 추가 |
| 7 | PDF를 Read 대신 Bash(pdftotext)로 읽음 | CLI 도구 선택 전략이 Bash 우회 선호 | `--append-system-prompt`로 Read 도구 강제 |
| 8 | poppler-utils 미설치 | 이미지 재빌드 시 초기화 | Dockerfile에 포함 |
| 9 | application.yml이 Config 기본값 덮어씀 | yml에 하드코딩 | yml 기본값 통일 |

---

## 3. 에러 처리 및 서킷 브레이커 설계

### 3.1 에러 분류 — Master DB 패턴 매칭

`claude_code_error_patterns` 테이블로 관리. 재배포 없이 패턴 추가/변경 가능.

| pattern | error_type | action |
|---------|-----------|--------|
| Not logged in | AUTH_EXPIRED | NOTIFY |
| rate limit | RATE_LIMIT | NOTIFY |
| 429 | RATE_LIMIT | NOTIFY |
| ECONNREFUSED | NETWORK | CIRCUIT_BREAKER |
| error_max_turns | MAX_TURNS | RETRY |

### 3.2 액션 분기

| error_type | action | 동작 |
|-----------|--------|------|
| AUTH_EXPIRED | NOTIFY | 즉시 알림(문자), 재시도 X |
| RATE_LIMIT | NOTIFY | 즉시 알림(문자), 재시도 X |
| NETWORK | CIRCUIT_BREAKER | 서킷 브레이커 관리 |
| MAX_TURNS | RETRY | 재시도 후 실패 시 서킷 브레이커 |
| TIMEOUT | RETRY → CIRCUIT_BREAKER | 30초 간격 재시도, 3회 실패 시 차단 |
| UNKNOWN | CIRCUIT_BREAKER | 원인 불명 → 서킷 브레이커 |

### 3.3 서킷 브레이커 설정

- **임계치**: 연속 3회 실패
- **OPEN 지속**: 5분 (이후 HALF-OPEN → 1회 시도)
- **재시도 간격**: 30초
- **알림**: OPEN 진입 시 1회, 미복구 시 30분마다 재알림
- **리셋**: 성공 1회 → CLOSED
- **알림 채널**: Aimbase 알림 모듈 (문자 발송)

### 3.4 에러 분류 방식 결정

- LLM 호출로 분류하는 방안 검토 → **기각** (에러 처리 중 LLM도 실패할 수 있음)
- **문자열 매칭 채택**: 단순, 확실, 빠름, 실패 없음
- 패턴은 DB 관리로 CLI 업데이트 시 코드 변경 없이 대응

---

## 4. CLI 옵션 확장성 개선

### 4.1 현재 문제

- CLI 옵션을 Java 코드에 **하나하나 하드코딩** (buildCommand 메서드)
- 새 옵션 추가 시 소스 변경 + 빌드 + 배포 필요

### 4.2 개선 방향

- `cli_options` 맵 방식으로 전환
- 워크플로우에서 넘긴 옵션을 그대로 CLI에 전달
- CLI 업데이트 시 소스 변경 없이 워크플로우 설정만 변경

```json
{
  "prompt": "문서 요약해주세요",
  "cli_options": {
    "--model": "claude-sonnet-4-5-20250514",
    "--effort": "max",
    "--mcp-config": "/path/to/mcp.json",
    "--permission-mode": "acceptEdits"
  }
}
```

### 4.3 현재 미지원 주요 CLI 옵션

| 옵션 | 용도 |
|------|------|
| `--effort` | low/medium/high/max 품질 조절 |
| `--model` / `--fallback-model` | 모델 선택 + 폴백 |
| `--max-budget-usd` | 비용 제한 |
| `--permission-mode` | 도구 승인 자동화 |
| `--continue` / `--resume` | 세션 이어가기 |
| `--mcp-config` | MCP 서버 연결 |
| `--agents` | 커스텀 에이전트 |
| `--system-prompt` | 전체 시스템 프롬프트 교체 |
| `--disallowed-tools` | 도구 차단 |
| `--stream-json` | 스트리밍 출력 |

### 4.4 FE 워크플로우 스튜디오 UI

- `UnifiedToolDef.inputSchema` (JSON Schema)로 동적 폼 렌더링
- type별 자동 매핑: string→input, enum→select, boolean→toggle, array→태그
- 자주 쓰는 옵션은 스키마에 enum으로 정의, 나머지는 `cli_options`로 자유 입력

---

## 5. 도구 성격 확인

- Claude Code CLI 전용 **범용 도구** (문서 분석, 생성, 코드, 리서치 등)
- 워크플로우에서 프롬프트/모델/system prompt 조합으로 다목적 사용
- 다른 업체(OpenAI 등) 동급 에이전트 CLI는 현시점 없음 → Claude 전용으로 충분
- 향후 유사 CLI 등장 시 Aimbase 인터페이스로 플러그인 가능한 구조 (ToolRegistry)

---

## 6. 인증 관련

- OAuth 로그인은 사람 개입 필수 (브라우저 인증)
- refresh token으로 자동 갱신 → 일상 운영에서는 사람 개입 불필요
- access token 약 3시간 만료, CLI가 자동 갱신
- refresh token 만료(장기 미사용) 시에만 재로그인 알림

---

## 7. 시스템 프롬프트 (노하우)

- "PDF는 Read 도구로 읽어라" → yml 기본값으로 설정 (노하우 강제)
- 워크플로우에서 `append_system_prompt`로 동적 추가/덮어쓰기 가능
- 하드코딩이 아닌 yml 설정이 적절 (변경 시 재배포 없이 환경변수로 오버라이드)
