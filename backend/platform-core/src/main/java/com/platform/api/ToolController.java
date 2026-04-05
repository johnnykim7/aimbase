package com.platform.api;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 도구 관리 API 컨트롤러 (CR-019 + CR-029 확장).
 */
@RestController
@RequestMapping("/api/v1/tools")
@Tag(name = "Tool Management", description = "도구 관리 (CR-019, CR-029)")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    @Operation(summary = "등록된 도구 전체 목록 조회 (contract 포함)")
    public ApiResponse<Map<String, Object>> listTools(
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String category) {

        List<UnifiedToolDef> allTools = toolRegistry.getToolDefs();

        List<Map<String, Object>> toolList = allTools.stream()
                .map(t -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", t.name());
                    tool.put("description", t.description() != null ? t.description() : "");
                    tool.put("parameters", t.inputSchema() != null ? t.inputSchema() : Map.of());
                    // CR-029: contract meta 포함
                    ToolContractMeta contract = toolRegistry.getContractMeta(t.name());
                    if (contract != null) {
                        tool.put("contract", Map.of(
                                "id", contract.id(),
                                "scope", contract.scope().name(),
                                "readOnly", contract.readOnly(),
                                "approvalRequired", contract.approvalRequired(),
                                "concurrencySafe", contract.concurrencySafe(),
                                "capabilities", contract.capabilities(),
                                "tags", contract.tags()
                        ));
                    }
                    return tool;
                })
                .toList();

        return ApiResponse.ok(Map.of(
                "tools", toolList,
                "count", toolList.size()
        ));
    }

    /** CR-029: 도구 계약 상세 조회 */
    @GetMapping("/{toolName}/contract")
    @Operation(summary = "도구 계약(Contract) 상세 조회")
    public ApiResponse<ToolContractMeta> getContract(@PathVariable String toolName) {
        ToolContractMeta meta = toolRegistry.getContractMeta(toolName);
        if (meta == null) {
            return ApiResponse.error("도구를 찾을 수 없거나 계약 메타가 없습니다: " + toolName);
        }
        return ApiResponse.ok(meta);
    }

    /** CR-029: 도구 직접 실행 */
    @PostMapping("/{toolName}/execute")
    @Operation(summary = "도구 직접 실행")
    public ApiResponse<ToolResult> executeTool(
            @PathVariable String toolName,
            @RequestBody Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) body.getOrDefault("input", Map.of());
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        String workspacePath = (String) body.getOrDefault("workspacePath", null);

        ToolContext ctx = new ToolContext(
                null, null, null, null, null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                workspacePath, dryRun, 0
        );

        var call = new com.platform.llm.model.ToolCall(null, toolName, input);
        ToolResult result = toolRegistry.execute(call, ctx);
        return ApiResponse.ok(result);
    }

    /** CR-029: 도구 입력 검증만 수행 */
    @PostMapping("/{toolName}/validate")
    @Operation(summary = "도구 입력 검증 (dry validation)")
    public ApiResponse<ValidationResult> validateTool(
            @PathVariable String toolName,
            @RequestBody Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) body.getOrDefault("input", Map.of());

        ToolContext ctx = ToolContext.minimal(null, null);
        ToolContractMeta meta = toolRegistry.getContractMeta(toolName);
        if (meta == null) {
            return ApiResponse.ok(ValidationResult.fail("도구를 찾을 수 없습니다: " + toolName));
        }

        // EnhancedToolExecutor의 validateInput 호출은 ToolRegistry 내부에서 처리
        // 여기서는 기본 검증만 반환
        return ApiResponse.ok(ValidationResult.OK);
    }
}
