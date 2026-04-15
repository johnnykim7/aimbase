package com.platform.tool.workspace;

import com.platform.tool.ToolContext;

import java.nio.file.Path;
import java.util.List;

/**
 * CR-029: 테넌트/앱/프로젝트별 워크스페이스 루트 해석.
 * CR-045: 화이트리스트 클램프 추가 (L1 방어선).
 */
public class WorkspaceResolver {

    private final String workspaceBase;
    private final List<Path> whitelistRoots;

    public WorkspaceResolver(String workspaceBase) {
        this(workspaceBase, List.of());
    }

    public WorkspaceResolver() {
        this("/data/workspaces", List.of());
    }

    /**
     * CR-045: 화이트리스트 주입 생성자.
     *
     * @param workspaceBase  기본 워크스페이스 루트 (폴백 경로)
     * @param whitelistRoots 허용된 시스템 루트 목록. 비어있으면 클램프 비활성 (하위 호환).
     */
    public WorkspaceResolver(String workspaceBase, List<Path> whitelistRoots) {
        this.workspaceBase = workspaceBase != null ? workspaceBase : "/data/workspaces";
        this.whitelistRoots = whitelistRoots != null ? List.copyOf(whitelistRoots) : List.of();
    }

    /**
     * ToolContext에서 워크스페이스 루트 경로를 결정.
     * workspacePath가 명시되면 그대로 사용, 아니면 tenantId/projectId로 결정.
     * CR-045: 결과를 화이트리스트 안으로 클램프. 외부면 WorkspaceAccessException.
     */
    public Path getWorkspaceRoot(ToolContext ctx) {
        Path raw;
        if (ctx.workspacePath() != null && !ctx.workspacePath().isBlank()) {
            raw = Path.of(ctx.workspacePath());
        } else {
            String tenantId = ctx.tenantId() != null ? ctx.tenantId() : "default";
            String projectId = ctx.projectId() != null ? ctx.projectId() : "general";
            raw = Path.of(workspaceBase, tenantId, projectId);
        }

        if (whitelistRoots.isEmpty()) {
            return raw;
        }

        Path normalized = raw.toAbsolutePath().normalize();
        boolean inside = whitelistRoots.stream().anyMatch(normalized::startsWith);
        if (!inside) {
            throw new WorkspaceAccessException(
                    "요청된 workspace가 화이트리스트 외부: " + normalized);
        }
        return normalized;
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
