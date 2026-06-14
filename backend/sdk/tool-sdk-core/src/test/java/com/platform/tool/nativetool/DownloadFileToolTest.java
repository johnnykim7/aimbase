package com.platform.tool.nativetool;

import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import com.platform.tool.ValidationResult;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspaceResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * download_file — URL 에서 바이트를 받아 워크스페이스에 바이너리로 저장.
 * JDK 내장 HttpServer 로 실제 다운로드 경로를 검증한다.
 */
class DownloadFileToolTest {

    private DownloadFileTool tool;
    private ToolContext ctx;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceResolver resolver = new WorkspaceResolver();
        WorkspacePolicyEngine policyEngine = new WorkspacePolicyEngine(resolver);
        tool = new DownloadFileTool(resolver, policyEngine);
        ctx = ToolContext.minimal("test-tenant", "test-session");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // \0 포함 바이너리 — 텍스트 도구라면 깨질 페이로드.
        byte[] payload = new byte[]{0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02, 0x03};
        server.createContext("/file.pdf", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.createContext("/notfound", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void definition_hasUrlAndFilePath() {
        var def = tool.getDefinition();
        assertThat(def.name()).isEqualTo("download_file");
        Map<String, Object> properties = (Map<String, Object>) def.inputSchema().get("properties");
        assertThat(properties).containsKeys("url", "file_path", "overwrite");
    }

    @Test
    void download_savesBinaryBytesAsIs(@TempDir Path tmp) throws Exception {
        byte[] expected = new byte[]{0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02, 0x03};
        Path dest = tmp.resolve("sub/out.pdf");

        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/file.pdf", "file_path", dest.toString()), ctx);

        assertThat(r.success()).isTrue();
        assertThat(Files.readAllBytes(dest)).isEqualTo(expected); // 바이너리 무손실 + 부모 디렉토리 자동 생성
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("bytes_written")).isEqualTo(expected.length);
        assertThat(out.get("created")).isEqualTo(true);
    }

    @Test
    void existingFile_withoutOverwrite_fails(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("exists.pdf");
        Files.write(dest, new byte[]{0x09});

        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/file.pdf", "file_path", dest.toString()), ctx);

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("already exists");
        assertThat(Files.readAllBytes(dest)).isEqualTo(new byte[]{0x09}); // 원본 보존
    }

    @Test
    void existingFile_withOverwrite_replaces(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("exists.pdf");
        Files.write(dest, new byte[]{0x09});

        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/file.pdf", "file_path", dest.toString(),
                        "overwrite", true), ctx);

        assertThat(r.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("overwritten")).isEqualTo(true);
    }

    @Test
    void httpError_returnsFailure(@TempDir Path tmp) {
        ToolResult r = tool.execute(
                Map.of("url", baseUrl + "/notfound", "file_path", tmp.resolve("x.pdf").toString()), ctx);
        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("HTTP 404");
    }

    @Test
    void validateInput_rejectsNonHttpScheme(@TempDir Path tmp) {
        ValidationResult v = tool.validateInput(
                Map.of("url", "ftp://host/x", "file_path", tmp.resolve("x").toString()), ctx);
        assertThat(v.valid()).isFalse();
        assertThat(v.message()).contains("http or https");
    }

    @Test
    void validateInput_requiresUrl(@TempDir Path tmp) {
        ValidationResult v = tool.validateInput(
                Map.of("file_path", tmp.resolve("x").toString()), ctx);
        assertThat(v.valid()).isFalse();
    }
}
