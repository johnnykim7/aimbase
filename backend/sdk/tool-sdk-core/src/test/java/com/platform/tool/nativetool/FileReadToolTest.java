package com.platform.tool.nativetool;

import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-100: FileReadTool as_base64 모드 — 로컬 PC 문서를 parse_document(content=base64) 로
 * 넘기기 위한 byte 획득 단계.
 */
class FileReadToolTest {

    private FileReadTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        // whitelist 빈 리스트 = 클램프 비활성(하위호환) → 절대경로 TempDir 사용 가능.
        WorkspaceResolver resolver = new WorkspaceResolver();
        WorkspacePolicyEngine policyEngine = new WorkspacePolicyEngine(resolver);
        tool = new FileReadTool(resolver, policyEngine);
        ctx = ToolContext.minimal("test-tenant", "test-session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void definition_hasAsBase64Param() {
        var def = tool.getDefinition();
        Map<String, Object> properties = (Map<String, Object>) def.inputSchema().get("properties");
        assertThat(properties).containsKey("as_base64");
    }

    @Test
    void asBase64_returnsBase64Content(@TempDir Path tmp) throws Exception {
        // 바이너리 흉내 — \0 포함. 일반 read 면 메타만 나오지만 base64 면 raw 가 그대로 인코딩.
        byte[] raw = new byte[]{0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02};
        Path doc = tmp.resolve("doc.pdf");
        Files.write(doc, raw);

        ToolResult r = tool.execute(
                Map.of("file_path", doc.toString(), "as_base64", true), ctx);

        assertThat(r.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("encoding")).isEqualTo("base64");
        assertThat(out.get("content")).isEqualTo(Base64.getEncoder().encodeToString(raw));
        assertThat(out.get("extension")).isEqualTo("pdf");
        assertThat(out.get("size")).isEqualTo((long) raw.length);
    }

    @Test
    void asBase64_binaryFileGivesBytesNotMetaOnly(@TempDir Path tmp) throws Exception {
        // 일반 모드(as_base64 미지정)면 바이너리는 메타만 — base64 모드와 대비 검증.
        byte[] raw = new byte[]{0x00, 0x01, 0x02, 0x03};
        Path doc = tmp.resolve("bin.dat");
        Files.write(doc, raw);

        ToolResult plain = tool.execute(Map.of("file_path", doc.toString()), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> plainOut = (Map<String, Object>) plain.output();
        assertThat(plainOut.get("binary")).isEqualTo(true);
        assertThat(plainOut).doesNotContainKey("content");

        ToolResult b64 = tool.execute(
                Map.of("file_path", doc.toString(), "as_base64", true), ctx);
        Map<?, ?> b64Out = (Map<?, ?>) b64.output();
        assertThat(b64Out.get("content")).isEqualTo(Base64.getEncoder().encodeToString(raw));
    }

    @Test
    void asBase64_fileNotFound_returnsError(@TempDir Path tmp) {
        ToolResult r = tool.execute(
                Map.of("file_path", tmp.resolve("missing.pdf").toString(), "as_base64", true), ctx);
        assertThat(r.success()).isFalse();
    }
}
