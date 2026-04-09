# CR-035 원본 요구사항: tool/ + policy/ 패키지 확장성/자동화

> 출처: 사용자 대화 (2026-04-08)
> 메모리 참조: project_openclaude_gap_missing.md, project_openclaude_gap_providers.md

## 요청 배경

OpenClaude 갭 분석 기반으로 tool/ + policy/ 패키지의 미구현 기능을 추가한다.
SendMessageTool은 CR-034 범위(멀티에이전트)로 분리.

## 구체적 범위 (사용자 확정)

### 1. ScheduleCronTool (Cron 스케줄링 자동화)
- 갭 분석 B.2: 우선순위 중간
- 반복/일회성 Cron 스케줄링

### 2. SkillTool / ToolSearchTool (스킬 동적 호출 + 도구 검색)
- 갭 분석 B.3: 우선순위 중간
- 스킬 동적 호출 + 도구 검색

### 3. Firecrawl 연동 (RAG 웹 소스 JS 렌더링)
- 기존: Python 사이드카의 scraper.py (basic/js_render/sitemap) + web_tools.py
- 개선: Firecrawl 연동으로 JS 렌더링 강화

### 4. 도메인 필터링 (PolicyEngine에 allowed_domains/blocked_domains)
- PolicyEngine에 도메인 기반 접근 제어 추가
- allowed_domains / blocked_domains 정책 규칙

## 제외 항목
- SendMessageTool → CR-034 (멀티에이전트)
- Built-in Agent 5타입, BriefTool, Coordinator Mode → 별도 CR
- CLI/플랫폼 전용 도구 10개 → 불필요
