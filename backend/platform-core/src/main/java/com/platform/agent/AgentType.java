package com.platform.agent;

/**
 * CR-034 PRD-229: Built-in 에이전트 타입.
 *
 * 각 타입은 고유한 시스템 프롬프트, 도구 필터, 실행 정책을 가진다.
 */
public enum AgentType {

    /** 범용 에이전트 — 모든 도구 사용 가능, 기본 타입 */
    GENERAL("general-purpose",
            "범용 에이전트. 복잡한 멀티스텝 작업을 자율적으로 처리합니다.",
            null,  // 도구 제한 없음
            false),

    /** 계획 수립 에이전트 — 읽기 전용, 구현 계획 설계 */
    PLAN("plan",
            "소프트웨어 아키텍트 에이전트. 구현 전략을 설계하고 단계별 계획을 수립합니다. "
            + "코드를 수정하지 않고 읽기/검색만 수행합니다.",
            "read,search,glob,grep",
            true),

    /** 탐색 에이전트 — 빠른 코드베이스 검색 특화 */
    EXPLORE("explore",
            "코드베이스 탐색 에이전트. 파일 검색, 키워드 검색, 구조 분석에 특화됩니다. "
            + "코드를 수정하지 않습니다.",
            "read,search,glob,grep",
            true),

    /** 가이드 에이전트 — 문서/API 안내 특화 */
    GUIDE("guide",
            "가이드 에이전트. 문서 검색, API 사용법 안내, 설정 도움에 특화됩니다.",
            "read,search,glob,grep,web_fetch,web_search",
            true),

    /** 검증 에이전트 — 테스트/검증 특화 */
    VERIFICATION("verification",
            "검증 에이전트. 코드 검증, 테스트 실행, 결과 분석에 특화됩니다. "
            + "검증에 필요한 읽기/실행 도구만 사용합니다.",
            "read,search,glob,grep,claude_code",
            true);

    private final String displayName;
    private final String systemPrompt;
    private final String allowedToolsCsv;  // null이면 제한 없음
    private final boolean readOnly;

    AgentType(String displayName, String systemPrompt, String allowedToolsCsv, boolean readOnly) {
        this.displayName = displayName;
        this.systemPrompt = systemPrompt;
        this.allowedToolsCsv = allowedToolsCsv;
        this.readOnly = readOnly;
    }

    public String getDisplayName() { return displayName; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getAllowedToolsCsv() { return allowedToolsCsv; }
    public boolean isReadOnly() { return readOnly; }

    /** 도구 필터 목록 반환 (null이면 제한 없음) */
    public String[] getAllowedTools() {
        return allowedToolsCsv != null ? allowedToolsCsv.split(",") : null;
    }

    /** 문자열에서 AgentType 변환 (대소문자 무시, 기본값 GENERAL) */
    public static AgentType fromString(String value) {
        if (value == null || value.isBlank()) return GENERAL;
        try {
            return valueOf(value.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            // display name으로도 매칭 시도
            for (AgentType type : values()) {
                if (type.displayName.equalsIgnoreCase(value)) return type;
            }
            return GENERAL;
        }
    }
}
