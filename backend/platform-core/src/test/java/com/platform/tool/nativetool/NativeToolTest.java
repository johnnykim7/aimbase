package com.platform.tool.nativetool;

import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CR-029: Native Tool 9종 단위 테스트.
 */
class NativeToolTest {

    @TempDir
    Path tempDir;

    private ToolContext ctx;
    private WorkspaceResolver resolver;
    private WorkspacePolicyEngine policyEngine;

    @BeforeEach
    void setUp() throws IOException {
        // 테스트 워크스페이스 생성
        Files.writeString(tempDir.resolve("hello.java"), "public class Hello {\n    public void greet() {}\n}\n");
        Files.writeString(tempDir.resolve("config.yml"), "server:\n  port: 8080\n");
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(tempDir.resolve("src/main/App.java"), "package main;\npublic class App {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# Project\n## Setup\nInstall deps\n## Usage\nRun it\n");

        resolver = new WorkspaceResolver();
        policyEngine = new WorkspacePolicyEngine(resolver);

        ctx = new ToolContext(
                "test-tenant", null, null, "test-session",
                null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                tempDir.toString(), false, 0
        );
    }

    // --- FileReadTool ---

    @Test
    void fileRead_success() {
        var tool = new FileReadTool(resolver, policyEngine);
        ToolResult result = tool.execute(Map.of("file_path", "hello.java"), ctx);

        assertTrue(result.success());
        assertNotNull(result.summary());
        assertTrue(result.summary().contains("hello.java"));
    }

    @Test
    void fileRead_notFound() {
        var tool = new FileReadTool(resolver, policyEngine);
        ToolResult result = tool.execute(Map.of("file_path", "nonexistent.txt"), ctx);

        assertFalse(result.success());
        assertTrue(result.summary().contains("존재하지 않"));
    }

    @Test
    void fileRead_offsetLimit() {
        var tool = new FileReadTool(resolver, policyEngine);
        ToolResult result = tool.execute(Map.of(
                "file_path", "hello.java",
                "offset", 1,
                "limit", 1
        ), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue((Boolean) output.get("truncated"));
    }

    // --- GlobTool ---

    @Test
    void glob_javaFiles() {
        var tool = new GlobTool(resolver);
        ToolResult result = tool.execute(Map.of("pattern", "**.java"), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        @SuppressWarnings("unchecked")
        List<String> filenames = (List<String>) output.get("filenames");
        assertTrue(filenames.size() >= 1, "Should find at least 1 java file, found: " + filenames);
    }

    @Test
    void glob_noMatch() {
        var tool = new GlobTool(resolver);
        ToolResult result = tool.execute(Map.of("pattern", "**/*.xyz"), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertEquals(0, output.get("numFiles"));
    }

    // --- PathInfoTool ---

    @Test
    void pathInfo_file() {
        var tool = new PathInfoTool(resolver);
        ToolResult result = tool.execute(Map.of("path", "hello.java"), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue((Boolean) output.get("exists"));
        assertTrue((Boolean) output.get("isFile"));
        assertEquals("java", output.get("extension"));
    }

    @Test
    void pathInfo_directory() {
        var tool = new PathInfoTool(resolver);
        ToolResult result = tool.execute(Map.of("path", "src"), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue((Boolean) output.get("isDirectory"));
    }

    @Test
    void pathInfo_notExists() {
        var tool = new PathInfoTool(resolver);
        ToolResult result = tool.execute(Map.of("path", "nope.txt"), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertFalse((Boolean) output.get("exists"));
    }

    // --- StructuredSearchTool ---

    @Test
    void structuredSearch_class() {
        var tool = new StructuredSearchTool(resolver);
        ToolResult result = tool.execute(Map.of(
                "query", "Hello",
                "search_type", "class",
                "glob", "**.java"
        ), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue((int) output.get("resultCount") >= 1,
                "Should find class Hello, results: " + output.get("results"));
    }

    @Test
    void structuredSearch_configKey() {
        var tool = new StructuredSearchTool(resolver);
        ToolResult result = tool.execute(Map.of(
                "query", "port",
                "search_type", "config_key"
        ), ctx);

        assertTrue(result.success());
    }

    // --- DocumentSectionReadTool ---

    @Test
    void docSection_listSections() {
        var tool = new DocumentSectionReadTool(resolver);
        ToolResult result = tool.execute(Map.of("file_path", "README.md"), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue((int) output.get("totalSections") >= 3);
    }

    @Test
    void docSection_readSpecific() {
        var tool = new DocumentSectionReadTool(resolver);
        ToolResult result = tool.execute(Map.of(
                "file_path", "README.md",
                "section", "Setup"
        ), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertEquals("Setup", output.get("section_title"));
    }

    @Test
    void docSection_lineRange() {
        var tool = new DocumentSectionReadTool(resolver);
        ToolResult result = tool.execute(Map.of(
                "file_path", "README.md",
                "line_range", "1-2"
        ), ctx);

        assertTrue(result.success());
    }

    // --- SafeEditTool + PatchApplyTool ---

    @Test
    void safeEdit_generatesDiff() {
        var tool = new SafeEditTool(resolver, policyEngine);
        ToolResult result = tool.execute(Map.of(
                "file_path", "hello.java",
                "old_string", "public class Hello",
                "new_string", "public final class Hello"
        ), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue((Boolean) output.get("didChange"));
        assertNotNull(output.get("patchId"));
    }

    @Test
    void safeEdit_oldStringNotFound() {
        var tool = new SafeEditTool(resolver, policyEngine);
        ToolResult result = tool.execute(Map.of(
                "file_path", "hello.java",
                "old_string", "nonexistent string",
                "new_string", "replacement"
        ), ctx);

        assertFalse(result.success());
    }

    @Test
    void patchApply_previewAndApply() throws IOException {
        var safeEdit = new SafeEditTool(resolver, policyEngine);
        var patchApply = new PatchApplyTool(safeEdit);

        // 1. SafeEdit으로 diff 생성
        ToolResult editResult = safeEdit.execute(Map.of(
                "file_path", "hello.java",
                "old_string", "public class Hello",
                "new_string", "public final class Hello"
        ), ctx);
        assertTrue(editResult.success());
        @SuppressWarnings("unchecked")
        String patchId = (String) ((Map<String, Object>) editResult.output()).get("patchId");

        // 2. Preview (confirm=false)
        ToolResult preview = patchApply.execute(Map.of(
                "patch_id", patchId,
                "confirm", false
        ), ctx);
        assertTrue(preview.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> previewOutput = (Map<String, Object>) preview.output();
        assertFalse((Boolean) previewOutput.get("applied"));

        // 3. Apply (confirm=true)
        ToolResult apply = patchApply.execute(Map.of(
                "patch_id", patchId,
                "confirm", true
        ), ctx);
        assertTrue(apply.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> applyOutput = (Map<String, Object>) apply.output();
        assertTrue((Boolean) applyOutput.get("applied"));

        // 4. 파일 내용 확인
        String content = Files.readString(tempDir.resolve("hello.java"));
        assertTrue(content.contains("public final class Hello"));
    }

    // --- ToolContractMeta 검증 ---

    @Test
    void allNativeTools_haveContractMeta() {
        List<EnhancedToolExecutor> tools = List.of(
                new FileReadTool(resolver, policyEngine),
                new GlobTool(resolver),
                new PathInfoTool(resolver),
                new StructuredSearchTool(resolver),
                new DocumentSectionReadTool(resolver),
                new SafeEditTool(resolver, policyEngine)
        );

        for (var tool : tools) {
            ToolContractMeta meta = tool.getContractMeta();
            assertNotNull(meta, tool.getDefinition().name() + " should have contract meta");
            assertNotNull(meta.id());
            assertNotNull(meta.scope());
        }
    }

    @Test
    void readOnlyTools_areMarkedCorrectly() {
        var fileRead = new FileReadTool(resolver, policyEngine);
        assertTrue(fileRead.getContractMeta().readOnly());
        assertTrue(fileRead.getContractMeta().concurrencySafe());

        var safeEdit = new SafeEditTool(resolver, policyEngine);
        assertFalse(safeEdit.getContractMeta().readOnly());
    }
}
