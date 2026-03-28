package com.platform.api;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.ToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 도구 관리 API 컨트롤러 (CR-019).
 *
 * ToolRegistry에 등록된 모든 도구 목록을 조회.
 * 워크플로우 TOOL_CALL 스텝 설정 시 도구 선택에 사용.
 */
@RestController
@RequestMapping("/api/v1/tools")
@Tag(name = "Tool Management", description = "MCP Tool 관리 (CR-019)")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    @Operation(summary = "등록된 도구 전체 목록 조회")
    public ApiResponse<Map<String, Object>> listTools(
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String category) {

        List<UnifiedToolDef> allTools = toolRegistry.getToolDefs();

        List<Map<String, Object>> toolList = allTools.stream()
                .map(t -> Map.<String, Object>of(
                        "name", t.name(),
                        "description", t.description() != null ? t.description() : "",
                        "parameters", t.inputSchema() != null ? t.inputSchema() : Map.of()
                ))
                .toList();

        return ApiResponse.ok(Map.of(
                "tools", toolList,
                "count", toolList.size()
        ));
    }
}
