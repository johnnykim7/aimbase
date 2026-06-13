# CR-098 원본 요구사항 — Claude CLI 어댑터 FE 노출 + 운영 Runner docker 상주

작성일: 2026-06-05
요청자: 사용자(대화)

## 발단

연결 수정 화면 스크린샷 — LLM 어댑터가 6종(Claude/OpenAI/Ollama/OpenAI Compatible/AWS Bedrock/Vertex AI)인데
"우리가 자체로 만들 Claude CLI 어댑터는 안 보이네요" 지적.

## 실측 결과

- BE 에는 `anthropic-cli` 어댑터(`ClaudeCliAdapter`, CR-071)가 정식 구현돼 있으나 FE 어댑터 선택지에서 누락.
- normalize 함수가 `anthropic-cli/claude-cli/claude-max/claude-pro` 만 인식 — FE 표시명 "Claude CLI"(공백형)는 `anthropic` 으로 오분류되는 함정 존재.

## 추가 토론

1. **Gemini 추가 요청** → openclaude(`/Users/sykim/Documents/GitHub/openclaude-main`) 실측: Gemini 는 별도 어댑터 없이
   OpenAI 호환 표준 API(`https://generativelanguage.googleapis.com/v1beta/openai`)로 처리. openclaude 도 anthropic 만
   네이티브, 나머지(gemini/deepseek/groq/mistral/openrouter/moonshot 등)는 전부 OpenAI 호환 프리셋.
   → 사용자 결정: **Claude CLI 만** 추가. Gemini/기타는 기존 OpenAI Compatible 로 수동 처리.

2. **피처 플래그(BIZ-099)** — `llm.anthropic-cli.enabled-tenants` global_config. CLI 어댑터는 우리 정액 구독 계정(OAuth)을
   빌려 쓰는 구조라 ToS 경계상 화이트리스트로 통제. 기존값 `flowguard_dev` → 사용자 결정: **전체 허용(`*`)**.

3. **런타임 설정 화면 편집 가능화** — `PlatformSettingsService.CATEGORIES` 에 `llm` 미포함이라 화면에 안 떴음.
   BE CATEGORIES + FE CATEGORY_LABELS 에 `llm` 추가. 추가로 테넌트 화이트리스트는 ID 직접 입력 대신
   **테넌트 멀티셀렉트(name 표시/id 저장) + 전체허용(*) 토글**, 체크박스는 프로젝트 UI 컴포넌트로 교체.

4. **운영 Runner 상주** — adapter='anthropic-cli' chat e2e 를 하려면 aimbase-agent(Runner)가 떠 있어야 함.
   - agent 는 같은 jar 에 두 역할: SERVLET 모드=Runner(HTTP /v1/chat, CLI 를 LLM처럼 호출), `--mcp-stdio`=Tool agent(로컬 도구 MCP).
   - BE(platform-core)엔 RunnerController 없음 → 반드시 별도 agent 프로세스 필요.
   - 사용자 결정: **Runner 를 59번 운영 서버에 docker-compose 서비스로 기본 상주.** (같은 compose 네트워크 → TURN 불필요)
   - 계정: `claude-accounts/local-agent-1` OAuth 유효(claude --print "Hi" 정상 응답 실측 확인).
   - 현재 anthropic-cli connection 은 어느 테넌트에도 없음 — e2e 위해 신규 생성 필요.

## 작업 범위

- FE: Connections.tsx (Claude CLI 항목+폼분기), PlatformSettings.tsx (llm 카테고리+테넌트 멀티셀렉트)
- BE: ConnectionAdapterFactory normalize 공백형, PlatformSettingsService CATEGORIES llm
- 인프라: aimbase-agent Dockerfile 신규, docker-compose.prod.yml agent 서비스, deploy.sh agent 타겟
- DB: anthropic-cli connection 생성(config_dir/runner_api_key), global_config `*`

## CR 처리 방침

사용자: "CR 은 그냥 기록만, 바로 작성 후 배포."
