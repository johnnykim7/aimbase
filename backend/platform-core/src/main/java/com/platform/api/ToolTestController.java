package com.platform.api;

import com.platform.tool.ToolRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 도구 직접 실행 테스트용 임시 엔드포인트.
 * /actuator 경로 하위에 배치하여 SecurityConfig의 permitAll 적용.
 */
@RestController
@RequestMapping("/actuator/tool-test")
public class ToolTestController {

    private final ToolRegistry toolRegistry;

    public ToolTestController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostMapping("/{toolName}")
    public Map<String, Object> execute(@PathVariable String toolName,
                                        @RequestBody Map<String, Object> input) {
        String result = toolRegistry.execute(
                new com.platform.llm.model.ToolCall("test-call-1", toolName, input));
        return Map.of("tool", toolName, "result", result);
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("tools", toolRegistry.getToolDefs().stream()
                .map(d -> Map.of("name", d.name(), "description", d.description()))
                .toList());
    }
}
