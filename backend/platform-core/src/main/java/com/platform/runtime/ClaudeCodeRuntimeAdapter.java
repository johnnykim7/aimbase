package com.platform.runtime;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CR-029 (PRD-184): ClaudeTool 런타임 어댑터.
 * 고난도 자율 탐색, 다중 파일 추론, 복합 리팩터링에 적합.
 * STATELESS/PERSISTENT mode 분리.
 */
@Component
public class ClaudeCodeRuntimeAdapter implements RuntimeAdapter {

    public enum Mode { STATELESS, PERSISTENT }

    @Override
    public String getRuntimeId() {
        return "claude_tool";
    }

    @Override
    public RuntimeCapabilityProfile getCapabilities() {
        return new RuntimeCapabilityProfile(
                true,   // streaming
                true,   // toolUse
                true,   // multiTurn
                true,   // longContext (1M with Claude CLI)
                true,   // autonomousExploration
                false,  // structuredOutput (CLI는 schema-first 아님)
                1_000_000,
                List.of("claude-opus-4-6", "claude-sonnet-4-6"),
                List.of("code_refactoring", "multi_file_reasoning", "autonomous_exploration", "long_context")
        );
    }

    @Override
    public RuntimeResult execute(RuntimeRequest request) {
        // 실제 구현은 기존 ClaudeCodeTool을 통해 실행
        // 현재는 인터페이스 + 능력 프로필만 정의
        throw new UnsupportedOperationException(
                "ClaudeCodeRuntimeAdapter.execute()는 ClaudeCodeTool을 통해 호출합니다.");
    }

    /**
     * ToolContext에서 mode를 결정.
     */
    public static Mode resolveMode(String workflowRunId, String projectId, String sessionId) {
        if (workflowRunId != null) return Mode.STATELESS;
        if (projectId != null && sessionId != null) return Mode.PERSISTENT;
        return Mode.STATELESS;
    }
}
