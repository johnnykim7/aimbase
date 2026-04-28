package com.platform.tool;

/**
 * CR-072: 도구의 MCP 노출 레벨.
 *
 * <p>Aimbase 서버 도구를 MCP endpoint(/mcp/sse)로 외부 노출할 때 어느 레벨까지
 * 노출 가능한지 결정한다. {@code ToolContractMeta} 메타데이터로 도구별 선언.</p>
 *
 * <ul>
 *   <li>{@link #NONE} — MCP 노출 안 함 (기본값). 서버 내부 OrchestratorEngine 만 in-process 호출 가능.</li>
 *   <li>{@link #CLI} — Claude CLI 등 CLI 두뇌가 MCP 채널로 호출 가능.</li>
 *   <li>{@link #EXTERNAL} — 외부 시스템(다른 IDE / 외부 클라이언트)도 호출 가능. 별도 인증 정책 필요 (CR-072 범위 외, 후속 CR).</li>
 * </ul>
 */
public enum McpExposureLevel {
    NONE,
    CLI,
    EXTERNAL
}
