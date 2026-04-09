package com.platform.config;

import com.platform.tool.builtin.BashTool;
import com.platform.tool.builtin.CalculatorTool;
import com.platform.tool.builtin.GetCurrentTimeTool;
import com.platform.tool.builtin.ZipExtractTool;
import com.platform.tool.nativetool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CR-041: SDK 도구를 Spring Bean으로 등록하는 브릿지.
 * SDK-core 모듈에서 @Component가 제거되었으므로 여기서 명시적으로 Bean 등록.
 * ToolRegistry의 @Lazy List&lt;ToolExecutor&gt; 자동 수집이 그대로 동작한다.
 */
@Configuration
public class SdkToolBeanConfig {

    @Value("${native-tools.workspace-base:/data/workspaces}")
    private String workspaceBase;

    @Bean
    public WorkspaceResolver workspaceResolver() {
        return new WorkspaceResolver(workspaceBase);
    }

    @Bean
    public WorkspacePolicyEngine workspacePolicyEngine(WorkspaceResolver workspaceResolver) {
        return new WorkspacePolicyEngine(workspaceResolver);
    }

    // ── Native Tools ──

    @Bean
    public FileReadTool fileReadTool(WorkspaceResolver wr, WorkspacePolicyEngine pe) {
        return new FileReadTool(wr, pe);
    }

    @Bean
    public FileWriteTool fileWriteTool(WorkspaceResolver wr, WorkspacePolicyEngine pe) {
        return new FileWriteTool(wr, pe);
    }

    @Bean
    public GlobTool globTool(WorkspaceResolver wr) {
        return new GlobTool(wr);
    }

    @Bean
    public GrepTool grepTool(WorkspaceResolver wr) {
        return new GrepTool(wr);
    }

    @Bean
    public SafeEditTool safeEditTool(WorkspaceResolver wr, WorkspacePolicyEngine pe) {
        return new SafeEditTool(wr, pe);
    }

    @Bean
    public PatchApplyTool patchApplyTool(SafeEditTool safeEditTool) {
        return new PatchApplyTool(safeEditTool);
    }

    @Bean
    public PathInfoTool pathInfoTool(WorkspaceResolver wr) {
        return new PathInfoTool(wr);
    }

    @Bean
    public StructuredSearchTool structuredSearchTool(WorkspaceResolver wr) {
        return new StructuredSearchTool(wr);
    }

    @Bean
    public DocumentSectionReadTool documentSectionReadTool(WorkspaceResolver wr) {
        return new DocumentSectionReadTool(wr);
    }

    @Bean
    public WorkspaceSnapshotTool workspaceSnapshotTool(WorkspaceResolver wr) {
        return new WorkspaceSnapshotTool(wr);
    }

    // ── Builtin Tools ──

    @Bean
    public BashTool bashTool(WorkspaceResolver wr) {
        return new BashTool(wr);
    }

    @Bean
    public CalculatorTool calculatorTool() {
        return new CalculatorTool();
    }

    @Bean
    public GetCurrentTimeTool getCurrentTimeTool() {
        return new GetCurrentTimeTool();
    }

    @Bean
    public ZipExtractTool zipExtractTool() {
        return new ZipExtractTool();
    }
}
