# Aimbase Sprint 진행 이력

> CLAUDE.md 에서 분리(2026-08-29). 완료된 스프린트 목록은 히스토리이므로 매 세션 로드하지 않는다.
>
> **언제 여는가**: 특정 기능이 어느 스프린트/CR 에서 들어왔는지 추적할 때,
> 또는 PRD 번호로 구현 시점을 찾을 때.

- [x] Sprint 0: 인프라 세팅 (Gradle, Flyway, Docker Compose, CI)
- [x] Sprint 1-10: BE 구현
- [x] Sprint 11-14: FE 구현
- [x] Sprint 15-18: Python 사이드카 (RAG Pipeline, Safety, Evaluation, 고급 기능)
- [x] Sprint 19: FE 워크플로우 스튜디오 (React Flow 기반 비주얼 DAG 에디터) [CR-005]
- [x] Sprint 20: 도구 선택 제어 (ToolFilterContext, tool_choice) [CR-006]
- [x] Sprint 21: 구조화된 출력 (response_format, output_schema, FE 스키마 편집) [CR-007]
- [x] Sprint 22-25: Python 알파 + 플랫폼 강화 [CR-009, CR-010]
- [x] Sprint 26: RAG A++ Phase 1 — Contextual Retrieval + Parent-Child + Query Transform [CR-011]
- [x] Sprint 27: RAG A++ Phase 2 — RAGAS 평가 + 고급 파싱 [CR-011]
- [x] Sprint 28: RAG A++ Phase 3 — 시맨틱 캐시 + Citation + 증분 인제스션 + TTS/STT [CR-011]
- [x] Sprint 29: 로컬 임베딩 전환 (BGE-M3 기본화, 가변 차원) [CR-012]
- [x] Sprint 30-31: 플랫폼 공통 Tool 확장 + 문서 생성 MCP [CR-018, CR-019]
- [x] Sprint 32: CR-015 정책 UI 개선 + CR-016 LLM Judge + CR-017 ConfigPanel 완성 + 인프라 수정
- [x] Sprint 33: CR-020 MCP 관리 도구 (14개) + CR-021 프로젝트 계층 (BE+FE)
- [x] Sprint 34: Metronic 인프라 + 레이아웃 교체 [CR-026]
- [x] Sprint 35: 공통 컴포넌트 교체 (8개 + theme.ts) [CR-026]
- [x] Sprint 36: 기본 CRUD 페이지 9개 스타일 교체 [CR-026]
- [x] Sprint 37: 고급 페이지 6개 스타일 교체 [CR-026]
- [x] Sprint 38: WorkflowStudio + 플랫폼 4개 교체 [CR-026]
- [x] Sprint 39: 정리 + 최종 검증 [CR-026]
- [x] Sprint 40: LLM_CALL 토큰 초과 자동 처리 — 에스컬레이션 + 자동분할 [CR-028]
- [x] Sprint 41: CR-030 2단계 고도화 6 Phase (PRD-186~210) — Extended Thinking, Hook Architecture, Permission AUTO, Memory Scope, 압축 5전략, Subagent+Worktree Isolation
- [x] Sprint 42: CR-031 성능/퀄리티 메커니즘 6 Phase (PRD-211~216) — Post-Compact Recovery, MICRO_COMPACT 0비용, Extract Memories 자동화, Adaptive Thinking, Tool Result 축약, Agent 진행 요약
- [x] Sprint 43: CR-032 프로바이더 확장 6 Phase (PRD-217~221) — OpenAI Compatible shim, AWS Bedrock, Vertex AI, 에이전트별 라우팅, FE Connection 폼
- [x] Sprint 44: CR-033 에이전트 구조적 사고 체계 6 Phase (PRD-222~227, FE-015) — Plan Mode(Enter/Exit/Verify), TodoWrite, Task(Create/Get/List/Update/Output/Stop), FE 대시보드
- [x] Sprint 45: CR-034 멀티에이전트 협업 완성 6 Phase (PRD-228~233, FE-016) — SendMessageTool, Built-in Agent 5타입, Hook 이벤트 14개 추가, FE 메시지 패널
- [x] Sprint 46: CR-035 Tool/Policy 확장성·자동화 6 Phase (PRD-234~240, FE-017) — ScheduleCron(3 Tool), SkillTool, ToolSearchTool, Firecrawl 연동, DOMAIN_FILTER 정책, FE 관리 UI
- [x] Sprint 47: CR-037 핵심 도구 네이티브화 4 Phase (PRD-241~244, FE-018) — BashTool, FileWriteTool, WebSearchTool, SuggestBackgroundPR (ClaudeCodeTool 의존 해소)
- [x] Sprint 48: CR-038 에이전트 자율성 강화 4 Phase (PRD-245~248, FE-019) — ListMcpResources, ReadMcpResource, RemoteTriggerTool, BriefTool, FE 세션 브리핑
- [x] Sprint 49: CR-036 프롬프트 외부화 + 영문 전환 + OpenClaude 포팅 10 Phase (PRD-249~260, FE-020~021) — prompt_templates 테이블, PromptTemplateService(캐시+폴백), 기존 25개 외부화, OpenClaude 48개 포팅, Python 연동, FE 관리화면
- [x] Sprint 50: CR-040 런타임 설정 관리 3 Phase (PRD-269~272, FE-023) — global_config seed 12개, PlatformSettingsService(캐시+감사), GET/PUT API, 하드코딩 7곳 교체, FE 설정 관리 페이지
- [ ] Sprint 50: CR-039 고급 확장 도구 4 Phase (PRD-265~268, FE-022) — TeamCreate/Delete(Swarm 팀 협업), NotebookEditTool(.ipynb 편집), LSPTool(코드 분석), FE 팀 관리 UI
- [x] Sprint 51: CR-041 Agent SDK 추출 + Agent Registry 5 Phase (PRD-273~278) — aimbase-tool-sdk-core(도구 17개 추출), aimbase-tool-sdk-mcp(MCP 서버+STUN+등록), Agent Registry(등록/해제/하트비트), RemoteToolDiscovery(원격 도구 동기화)
- [x] CR-050: Claude CLI → LLM 어댑터 승격 5 Phase (PRD-306~309, BIZ-099/100) — ClaudeCliWorker/Pool/Adapter/Config + WorkflowEngine run-종료 정리 훅, 단위 19 PASS, IT 4(CLAUDE_CLI_IT=true)
