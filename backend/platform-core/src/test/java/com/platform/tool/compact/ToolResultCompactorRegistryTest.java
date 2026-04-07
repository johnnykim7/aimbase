package com.platform.tool.compact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * CR-031 PRD-215: ToolResultCompactorRegistry 단위 테스트.
 */
class ToolResultCompactorRegistryTest {

    private final ToolResultCompactorRegistry registry = new ToolResultCompactorRegistry();

    @Test
    void compact_shortOutput_returnsOriginal() {
        String output = "short output";
        assertThat(registry.compact("file_read", output, 1000)).isEqualTo(output);
    }

    @Test
    void compact_fileRead_preservesHeaderAndTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line ").append(i).append(": some code content here\n");
        }
        String result = registry.compact("file_read", sb.toString(), 3000);

        assertThat(result).contains("line 0:");
        assertThat(result).contains("lines omitted");
        assertThat(result).contains("line 99:");
    }

    @Test
    void compact_grep_deduplicatesFilePaths() {
        String output = """
                src/Main.java:10: public class Main {
                src/Main.java:20: public void run() {
                src/Main.java:30: }
                src/Other.java:5: import java.util.List;
                """;
        String result = registry.compact("grep", output, 500);

        assertThat(result).contains("src/Main.java");
        assertThat(result).contains("src/Other.java");
    }

    @Test
    void compact_bash_preservesLastLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("log line ").append(String.format("%03d", i)).append(": some long log output content here padding\n");
        }
        // rawOutput이 maxChars보다 커야 compaction 로직에 진입
        String raw = sb.toString();
        String result = registry.compact("bash", raw, raw.length() / 2);

        assertThat(result).contains("lines omitted");
        assertThat(result).contains("log line 199");
    }

    @Test
    void compact_unknownTool_defaultTruncate() {
        String longOutput = "x".repeat(5000);
        String result = registry.compact("unknown_tool", longOutput, 100);

        assertThat(result.length()).isLessThanOrEqualTo(120); // truncated 텍스트 포함
        assertThat(result).contains("truncated");
    }

    @Test
    void needsCompaction_belowThreshold_false() {
        assertThat(registry.needsCompaction("short", 1000)).isFalse();
    }

    @Test
    void needsCompaction_aboveThreshold_true() {
        assertThat(registry.needsCompaction("x".repeat(2000), 1000)).isTrue();
    }

    @Test
    void needsCompaction_null_false() {
        assertThat(registry.needsCompaction(null, 1000)).isFalse();
    }
}
