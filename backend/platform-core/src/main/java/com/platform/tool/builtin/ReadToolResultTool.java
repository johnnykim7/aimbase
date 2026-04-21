package com.platform.tool.builtin;

import com.platform.tool.*;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.storage.ToolResultStorageService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-048 PRD-301: 외부 저장소에 보관된 대형 tool result 원본을 복구.
 *
 * 대형 tool result(>81920B)는 ToolCallHandler에서 tool_result_storage에 저장되고
 * 체인에는 {type:"tool_result_ref", id, summary} stub만 주입된다.
 * 모델이 원본이 필요하면 이 도구를 호출해 복구한다.
 *
 * 권한: 본인 세션의 result_id만 접근 가능 (session_id 불일치 시 403).
 */
@Component
public class ReadToolResultTool implements EnhancedToolExecutor {

    private final ToolResultStorageService storageService;

    public ReadToolResultTool(ToolResultStorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "read_tool_result",
                "이전 턴에서 크기 제한으로 요약 stub만 주입된 tool result의 원본 내용을 복구합니다. " +
                        "tool_result_ref의 id 값을 전달하세요.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "result_id", Map.of(
                                        "type", "string",
                                        "description", "tool_result_ref stub에 포함된 result_id (예: res_abc123...)"
                                )
                        ),
                        "required", List.of("result_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("read_tool_result",
                List.of("tool-result", "storage", "recovery"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String resultId = (String) input.get("result_id");
        if (resultId == null || resultId.isBlank()) {
            return ToolResult.error("INVALID_INPUT: result_id is required");
        }
        if (ctx == null || ctx.sessionId() == null) {
            return ToolResult.error("SESSION_REQUIRED: tool context must include sessionId");
        }

        ToolResultStorageService.ReadResult read = storageService.read(ctx.sessionId(), resultId);
        return switch (read.status()) {
            case OK -> {
                Map<String, Object> out = new HashMap<>();
                out.put("result_id", read.entity().getResultId());
                out.put("tool_name", read.entity().getToolName());
                out.put("full_content", read.entity().getFullContent());
                out.put("size_bytes", read.entity().getSizeBytes());
                out.put("created_at", read.entity().getCreatedAt() != null
                        ? read.entity().getCreatedAt().toString() : null);
                yield ToolResult.ok(out,
                        "Restored tool result (" + read.entity().getSizeBytes() + "B) from "
                                + read.entity().getToolName());
            }
            case NOT_FOUND -> ToolResult.error(
                    "TOOL_RESULT_NOT_FOUND: " + resultId);
            case FORBIDDEN -> ToolResult.error(
                    "TOOL_RESULT_FORBIDDEN: result belongs to a different session");
            case EXPIRED -> ToolResult.error(
                    "TOOL_RESULT_EXPIRED: TTL 24h exceeded");
        };
    }
}
