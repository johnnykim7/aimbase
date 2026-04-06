package com.platform.tool.workspace;

import com.platform.tool.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * CR-029: 테넌트/앱/프로젝트별 워크스페이스 루트 해석.
 */
@Component
public class WorkspaceResolver {

    @Value("${native-tools.workspace-base:/data/workspaces}")
    private String workspaceBase;

    /**
     * ToolContext에서 워크스페이스 루트 경로를 결정.
     * workspacePath가 명시되면 그대로 사용, 아니면 tenantId/projectId로 결정.
     */
    public Path getWorkspaceRoot(ToolContext ctx) {
        if (ctx.workspacePath() != null && !ctx.workspacePath().isBlank()) {
            return Path.of(ctx.workspacePath());
        }
        String tenantId = ctx.tenantId() != null ? ctx.tenantId() : "default";
        String projectId = ctx.projectId() != null ? ctx.projectId() : "general";
        return Path.of(workspaceBase, tenantId, projectId);
    }

    /**
     * 주어진 경로를 워크스페이스 기준으로 해석.
     * - 절대 경로: 그대로 사용 (policy 검증에서 범위 체크)
     * - 상대 경로: workspace root 기준으로 해석
     */
    public Path resolve(ToolContext ctx, String path) {
        Path inputPath = Path.of(path);
        if (inputPath.isAbsolute()) {
            return inputPath.normalize();
        }
        Path root = getWorkspaceRoot(ctx);
        return root.resolve(path).normalize();
    }

    /**
     * 절대 경로를 워크스페이스 루트 기준 상대 경로로 변환 (토큰 절감용).
     */
    public String relativize(ToolContext ctx, Path absolutePath) {
        Path root = getWorkspaceRoot(ctx);
        try {
            return root.relativize(absolutePath).toString();
        } catch (IllegalArgumentException e) {
            return absolutePath.toString();
        }
    }
}
