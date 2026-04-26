package com.platform.mcp.agent;

import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.PermissionLevel;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolResult;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-067: AgentMcpServer.dispatch 가 EnhancedToolExecutor 의 본문을 노출하고
 * legacy ToolExecutor 회귀 + 에러 직렬화 + truncator 적용까지 보장하는지 검증.
 */
class AgentMcpServerTest {

    @Test
    @DisplayName("EnhancedToolExecutor: ToolResult.output 본문이 MCP 응답 텍스트에 포함됨 (CR-067 핫스팟)")
    void enhanced_tool_exposes_output_body_through_mcp() {
        EnhancedToolExecutor tool = new EnhancedToolExecutor() {
            @Override public ToolContractMeta getContractMeta() {
                return ToolContractMeta.readOnlyNative("file_read", List.of());
            }
            @Override public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                return ToolResult.ok(
                        Map.of("path", "/x.java", "content", "public class X {}\n", "lineCount", 1),
                        "x.java (1줄)");
            }
            @Override public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("file_read", "read file", Map.of());
            }
        };

        McpSchema.CallToolResult result = AgentMcpServer.dispatch(tool, "file_read", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text)
                .startsWith("# x.java (1줄)\n")
                .contains("public class X {}")
                .contains("--- meta ---")
                .contains("\"path\"")
                .contains("/x.java");
    }

    @Test
    @DisplayName("legacy ToolExecutor: 기존 String 반환 그대로 노출 (회귀)")
    void legacy_tool_executor_returns_raw_string() {
        ToolExecutor legacy = new ToolExecutor() {
            @Override public String execute(Map<String, Object> input) {
                return "legacy raw string output";
            }
            @Override public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("legacy_tool", "legacy", Map.of());
            }
        };

        McpSchema.CallToolResult result = AgentMcpServer.dispatch(legacy, "legacy_tool", Map.of());

        assertThat(result.isError()).isFalse();
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).isEqualTo("legacy raw string output");
    }

    @Test
    @DisplayName("EnhancedToolExecutor 의 에러 ToolResult 도 [ERROR] prefix 로 텍스트 노출 (isError=false 유지)")
    void enhanced_tool_error_result_renders_with_ERROR_prefix() {
        EnhancedToolExecutor errorTool = new EnhancedToolExecutor() {
            @Override public ToolContractMeta getContractMeta() {
                return ToolContractMeta.readOnlyNative("err", List.of());
            }
            @Override public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                return ToolResult.error("File not found: /missing");
            }
            @Override public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("err", "err", Map.of());
            }
        };

        McpSchema.CallToolResult result = AgentMcpServer.dispatch(errorTool, "err", Map.of());

        // ToolResult.error 는 도구 자체의 비즈니스 에러 → MCP 레벨 isError 가 아니라 본문에 [ERROR] prefix 로 노출.
        // (모델이 [ERROR] 를 보고 다음 행동을 결정할 수 있도록)
        assertThat(result.isError()).isFalse();
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).isEqualTo("[ERROR] File not found: /missing");
    }

    @Test
    @DisplayName("도구 실행 중 예외 발생: isError=true 로 패킹")
    void runtime_exception_is_packed_as_error() {
        EnhancedToolExecutor crashTool = new EnhancedToolExecutor() {
            @Override public ToolContractMeta getContractMeta() {
                return ToolContractMeta.readOnlyNative("crash", List.of());
            }
            @Override public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                throw new RuntimeException("boom \"with\" quotes");
            }
            @Override public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("crash", "crash", Map.of());
            }
        };

        McpSchema.CallToolResult result = AgentMcpServer.dispatch(crashTool, "crash", Map.of());

        assertThat(result.isError()).isTrue();
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // JSON quote escape 도 검증
        assertThat(text).contains("\"error\"").contains("boom").contains("\\\"with\\\"");
    }

    @Test
    @DisplayName("긴 본문은 McpResultTruncator 로 축약됨")
    void long_body_is_truncated() {
        String longText = "x".repeat(10_000);
        EnhancedToolExecutor bigTool = new EnhancedToolExecutor() {
            @Override public ToolContractMeta getContractMeta() {
                return ToolContractMeta.readOnlyNative("big", List.of());
            }
            @Override public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                return ToolResult.ok(Map.of("content", longText), "big (10000)");
            }
            @Override public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("big", "big", Map.of());
            }
        };

        McpSchema.CallToolResult result = AgentMcpServer.dispatch(bigTool, "big", Map.of());

        assertThat(result.isError()).isFalse();
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text.length()).isLessThan(longText.length()); // 축약됨
        assertThat(text).contains("결과 축약"); // McpResultTruncator 의 생략 메시지
    }
}
