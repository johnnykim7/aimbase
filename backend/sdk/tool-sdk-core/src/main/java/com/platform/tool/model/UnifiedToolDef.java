package com.platform.tool.model;

import java.util.Map;

/**
 * LLM에 전달할 도구 정의 (이름, 설명, 입력 스키마).
 * SDK-core 모듈 소속 — Spring/DB 의존성 없음.
 */
public record UnifiedToolDef(
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
