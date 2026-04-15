package com.platform.config;

import com.platform.tool.builtin.BashTool;
import com.platform.tool.builtin.CalculatorTool;
import com.platform.tool.builtin.GetCurrentTimeTool;
import com.platform.tool.builtin.ZipExtractTool;
import com.platform.tool.nativetool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CR-041: SDK 도구를 Spring Bean으로 등록하는 브릿지.
 * CR-045: WorkspaceProperties(단일 진실 원천) 주입. L1/L2 방어선에 화이트리스트 전달.
 * ToolRegistry의 @Lazy List&lt;ToolExecutor&gt; 자동 수집이 그대로 동작한다.
 */
@Configuration
@EnableConfigurationProperties(WorkspaceProperties.class)
public class SdkToolBeanConfig {

    @Bean
    public WorkspaceResolver workspaceResolver(WorkspaceProperties props) {
        return new WorkspaceResolver(props.getBase(), props.whitelistPaths());
    }

    @Bean
    public WorkspacePolicyEngine workspacePolicyEngine(WorkspaceResolver workspaceResolver,
                                                       WorkspaceProperties props) {
        return new WorkspacePolicyEngine(workspaceResolver, props.whitelistPaths());
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
