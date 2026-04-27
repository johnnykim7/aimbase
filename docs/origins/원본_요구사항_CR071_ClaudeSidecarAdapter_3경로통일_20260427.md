# (DEPRECATED) 원본 요구사항 — CR-071 ClaudeSidecarAdapter

> **이 파일은 더 이상 사용 안 함**. 명명 정정으로 대체됨.
> 정본: [원본_요구사항_CR071_ClaudeCliAdapter_3경로통일_20260427.md](원본_요구사항_CR071_ClaudeCliAdapter_3경로통일_20260427.md)

## 정정 사유 (2026-04-27)

초기 작명 `ClaudeSidecarAdapter` → 사이드카 패턴 의미 검토(같은 호스트 보조 프로세스가 핵심) 과정에서 사용자 PC 분리 케이스에 부적합 판정. `ClaudeCliRunner` + `ClaudeCliAdapter`로 분리 명명 후, API/CLI 어댑터 대칭(`AnthropicAdapter` vs `ClaudeCliAdapter`) 정리로 최종 확정.
