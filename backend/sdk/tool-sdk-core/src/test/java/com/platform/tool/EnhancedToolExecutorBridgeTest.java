package com.platform.tool;

import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-067: EnhancedToolExecutor.execute(Map) default bridge 가 ToolResultRenderer 를
 * 통과시켜 본문을 손실 없이 반환하는지 검증.
 */
class EnhancedToolExecutorBridgeTest {

    /** content 본문을 가진 가상 도구. */
    private static final EnhancedToolExecutor FILE_READ_LIKE = new EnhancedToolExecutor() {
        @Override
        public ToolContractMeta getContractMeta() {
            return ToolContractMeta.readOnlyNative("fake_read", List.of());
        }
        @Override
        public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
            return ToolResult.ok(
                    Map.of("path", "/x.java", "content", "package x;\nclass Y {}\n", "lineCount", 2),
                    "x.java (2줄)");
        }
        @Override
        public UnifiedToolDef getDefinition() {
            return new UnifiedToolDef("fake_read", "fake", Map.of());
        }
    };

    @Test
    @DisplayName("Bridge: EnhancedToolExecutor.execute(Map) 가 ToolResult output 본문을 직렬화해 반환")
    void bridge_returns_serialized_body_not_summary_only() {
        String rendered = FILE_READ_LIKE.execute(Map.of());

        assertThat(rendered)
                .startsWith("# x.java (2줄)\n")
                .contains("package x;")
                .contains("class Y {}")
                .contains("--- meta ---")
                .contains("\"path\"")
                .contains("/x.java");
    }

    @Test
    @DisplayName("Bridge: 에러 ToolResult 도 [ERROR] prefix + summary 로 반환")
    void bridge_renders_error_result() {
        EnhancedToolExecutor errorTool = new EnhancedToolExecutor() {
            @Override
            public ToolContractMeta getContractMeta() {
                return ToolContractMeta.readOnlyNative("fake_error", List.of());
            }
            @Override
            public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                return ToolResult.error("File not found: /missing");
            }
            @Override
            public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("fake_error", "fake", Map.of());
            }
        };

        assertThat(errorTool.execute(Map.of()))
                .isEqualTo("[ERROR] File not found: /missing");
    }
}
