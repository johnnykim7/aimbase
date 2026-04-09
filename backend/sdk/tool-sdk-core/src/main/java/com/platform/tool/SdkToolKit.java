package com.platform.tool;

import com.platform.tool.builtin.BashTool;
import com.platform.tool.builtin.CalculatorTool;
import com.platform.tool.builtin.GetCurrentTimeTool;
import com.platform.tool.builtin.ZipExtractTool;
import com.platform.tool.nativetool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspaceResolver;

import java.util.List;

/**
 * CR-041: SDK 도구 편의 팩토리.
 * 소비앱이 Spring 없이도 모든 SDK 도구를 한 번에 생성할 수 있다.
 *
 * <pre>{@code
 * SdkToolKit kit = new SdkToolKit("/workspace/path");
 * List<ToolExecutor> tools = kit.getAllTools();
 * }</pre>
 */
public final class SdkToolKit {

    private final WorkspaceResolver workspaceResolver;
    private final WorkspacePolicyEngine policyEngine;
    private final List<ToolExecutor> tools;

    public SdkToolKit(String workspaceBase) {
        this.workspaceResolver = new WorkspaceResolver(workspaceBase);
        this.policyEngine = new WorkspacePolicyEngine(workspaceResolver);

        SafeEditTool safeEditTool = new SafeEditTool(workspaceResolver, policyEngine);

        this.tools = List.of(
                // Native tools (filesystem)
                new FileReadTool(workspaceResolver, policyEngine),
                new FileWriteTool(workspaceResolver, policyEngine),
                new GlobTool(workspaceResolver),
                new GrepTool(workspaceResolver),
                safeEditTool,
                new PatchApplyTool(safeEditTool),
                new PathInfoTool(workspaceResolver),
                new StructuredSearchTool(workspaceResolver),
                new DocumentSectionReadTool(workspaceResolver),
                new WorkspaceSnapshotTool(workspaceResolver),
                // Builtin tools
                new BashTool(workspaceResolver),
                new CalculatorTool(),
                new GetCurrentTimeTool(),
                new ZipExtractTool()
        );
    }

    /** 기본 워크스페이스 경로 사용 */
    public SdkToolKit() {
        this(null);
    }

    /** 모든 SDK 도구 목록 반환 */
    public List<ToolExecutor> getAllTools() {
        return tools;
    }

    /** 워크스페이스 리졸버 반환 (커스텀 도구 생성용) */
    public WorkspaceResolver getWorkspaceResolver() {
        return workspaceResolver;
    }

    /** 워크스페이스 정책 엔진 반환 (커스텀 도구 생성용) */
    public WorkspacePolicyEngine getPolicyEngine() {
        return policyEngine;
    }
}
