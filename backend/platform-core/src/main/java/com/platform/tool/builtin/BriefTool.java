package com.platform.tool.builtin;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.session.SessionBriefService;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-038 PRD-248: 세션 이전 작업 브리핑.
 * 세션 메시지를 LLM으로 요약하여 브리핑을 생성한다.
 * BIZ-068: 캐시 TTL 1시간. BIZ-069: 최근 50개 메시지.
 */
@Component
public class BriefTool implements EnhancedToolExecutor {

    private final SessionBriefService briefService;

    public BriefTool(SessionBriefService briefService) {
        this.briefService = briefService;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "brief",
                "세션의 이전 작업을 요약하여 브리핑을 생성합니다. " +
                        "새 세션에서 이전 작업 컨텍스트를 즉시 복원할 때 사용합니다. " +
                        "메모리(장기 저장)와 달리 세션 단위 즉시 요약입니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "session_id", Map.of("type", "string",
                                        "description", "대상 세션 ID (미지정 시 현재 세션)"),
                                "force_refresh", Map.of("type", "boolean",
                                        "description", "캐시 무시하고 재생성 (기본: false)")
                        ),
                        "required", List.of()
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("brief",
                List.of("session", "briefing", "summary"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String sessionId = (String) input.get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = ctx.sessionId();
        }
        if (sessionId == null) {
            return ToolResult.error("세션 ID가 필요합니다 (session_id 파라미터 또는 현재 세션)");
        }

        boolean forceRefresh = Boolean.TRUE.equals(input.get("force_refresh"));

        try {
            Map<String, Object> brief = briefService.getBrief(sessionId, forceRefresh);
            String summary = (String) brief.getOrDefault("summary", "");
            return ToolResult.ok(brief, "브리핑 완료: " + summary);
        } catch (Exception e) {
            return ToolResult.error("브리핑 생성 실패: " + e.getMessage());
        }
    }
}
