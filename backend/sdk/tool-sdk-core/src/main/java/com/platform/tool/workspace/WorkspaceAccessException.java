package com.platform.tool.workspace;

/**
 * CR-045: 화이트리스트 외부 경로 접근 시도 시 발생.
 * WorkspaceResolver(L1)가 요청된 workspacePath를 화이트리스트 내부로 클램프하지 못할 때 던진다.
 */
public class WorkspaceAccessException extends RuntimeException {
    public WorkspaceAccessException(String message) {
        super(message);
    }
}
