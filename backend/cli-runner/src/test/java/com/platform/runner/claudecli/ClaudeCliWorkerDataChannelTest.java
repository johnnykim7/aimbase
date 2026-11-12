package com.platform.runner.claudecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.UnifiedMessage;
import com.platform.skill.SkillSetHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-183 TC-CHT-098: CLI 작업자가 대화 세션의 계산 도구 통로(aimbase-data)를 MCP 설정에 붙이는지.
 */
class ClaudeCliWorkerDataChannelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER_CONFIG = """
            {"mcpServers":{"aimbase-server":{"type":"http","url":"http://api:8280/mcp",
             "headers":{"Authorization":"Bearer k","X-Aimbase-Workspace-Path":"/w","%s":"s1,s2"}}}}
            """.formatted(SkillSetHeader.NAME);

    @TempDir
    Path tempDir;

    private ClaudeCliWorker worker;

    @AfterEach
    void teardown() {
        if (worker != null) worker.close();
    }

    @Test
    @DisplayName("CR-185: 대화 요청이면 aimbase-server 항목에도 대화 세션 머리글을 싣는다")
    void injectServerHeaders_addsChatSessionForApproval() throws Exception {
        String base = "{\"mcpServers\":{\"aimbase-server\":{\"type\":\"http\",\"url\":\"http://api:8280/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer k\"}}}}";

        JsonNode withSession = MAPPER.readTree(ClaudeCliWorker.injectServerHeaders(base, null, null, "sess-1"))
                .path("mcpServers").path("aimbase-server").path("headers");
        assertThat(withSession.path("X-Aimbase-Session-Id").asText()).isEqualTo("sess-1");
        assertThat(withSession.path("Authorization").asText()).isEqualTo("Bearer k");

        JsonNode withoutSession = MAPPER.readTree(ClaudeCliWorker.injectServerHeaders(base, null, null, null))
                .path("mcpServers").path("aimbase-server").path("headers");
        assertThat(withoutSession.has("X-Aimbase-Session-Id")).isFalse();
    }

    @Test
    @DisplayName("aimbase-data 항목은 /mcp-data 주소, 인증 머리글, 세션 머리글만 갖는다")
    void withDataEntry_copiesServerEntryToDataChannel() throws Exception {
        JsonNode root = MAPPER.readTree(ClaudeCliWorker.withDataEntry(SERVER_CONFIG, SERVER_CONFIG, "sess-1"));

        JsonNode data = root.path("mcpServers").path("aimbase-data");
        assertThat(data.path("url").asText()).isEqualTo("http://api:8280/mcp-data");
        assertThat(data.path("type").asText()).isEqualTo("http");
        JsonNode headers = data.path("headers");
        assertThat(headers.path("Authorization").asText()).isEqualTo("Bearer k");
        assertThat(headers.path("X-Aimbase-Session-Id").asText()).isEqualTo("sess-1");
        assertThat(headers.has("X-Aimbase-Workspace-Path")).isFalse();
        assertThat(headers.has(SkillSetHeader.NAME)).isFalse();
        assertThat(root.path("mcpServers").path("aimbase-server").path("url").asText())
                .isEqualTo("http://api:8280/mcp");
    }

    @Test
    @DisplayName("NATIVE 처럼 기본 설정이 없으면 aimbase-data 항목 하나만 담는다")
    void withDataEntry_withoutBaseConfig() throws Exception {
        JsonNode root = MAPPER.readTree(ClaudeCliWorker.withDataEntry(null, SERVER_CONFIG, "sess-1"));

        assertThat(root.path("mcpServers").size()).isEqualTo(1);
        assertThat(root.path("mcpServers").has("aimbase-data")).isTrue();
    }

    @Test
    @DisplayName("aimbase-server 항목이 없으면 통로를 붙이지 않고 원래 설정을 쓴다")
    void withDataEntry_withoutServerEntry_keepsBase() {
        assertThat(ClaudeCliWorker.withDataEntry(null, "{\"mcpServers\":{}}", "sess-1")).isNull();
    }

    @Test
    @DisplayName("허용 도구 목록이 있으면 data_query 는 aimbase-data 이름으로만 허용한다")
    void allowedTools_useDataChannelName() throws IOException {
        String args = runAndDumpArgs(List.of("file_write", "data_query"), "sess-1", null);

        assertThat(args).contains("--allowedTools mcp__aimbase-data__data_query");
        assertThat(args).contains("--allowedTools mcp__aimbase-server__file_write");
        assertThat(args).doesNotContain("mcp__aimbase-server__data_query");
        assertThat(args).contains("/mcp-data").contains("sess-1");
    }

    @Test
    @DisplayName("대화 세션이 없는 요청(워크플로우 단계)은 통로를 붙이지 않는다")
    void noDataSession_noDataChannel() throws IOException {
        String args = runAndDumpArgs(List.of("file_write"), null, null);

        assertThat(args).doesNotContain("aimbase-data");
    }

    @Test
    @DisplayName("CR-185 TC-CHT-116: 계산 도구 통로가 없는 대화도 run 번호를 aimbase-server 대화 세션 머리글로 싣는다")
    void runId_isSentAsChatSessionWithoutDataChannel() throws IOException {
        String args = runAndDumpArgs(List.of("file_write"), null, "chat-sess-9");

        assertThat(args).doesNotContain("aimbase-data");
        assertThat(args).contains("\"X-Aimbase-Session-Id\":\"chat-sess-9\"");
    }

    @Test
    @DisplayName("CR-185: run 번호가 없으면 대화 세션 머리글을 싣지 않는다")
    void noRunId_noChatSessionHeader() throws IOException {
        String args = runAndDumpArgs(List.of("file_write"), null, null);

        assertThat(args).doesNotContain("X-Aimbase-Session-Id");
    }

    private String runAndDumpArgs(List<String> allowed, String dataSessionId, String runId) throws IOException {
        Path argsDump = tempDir.resolve("args-" + System.nanoTime() + ".txt");
        Path stub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-data"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-data","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump));
        worker = new ClaudeCliWorker(stub.toString(), null, null, false, null, Duration.ofSeconds(10),
                SERVER_CONFIG.strip());
        worker.setToolMode(ClaudeCliCommandBuilder.ToolMode.AIMBASE);
        worker.setAllowedTools(allowed);
        if (dataSessionId != null) worker.setDataSessionId(dataSessionId);
        if (runId != null) worker.setRunId(runId);
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));
        return Files.readString(argsDump);
    }

    private Path writeStub(String body) throws IOException {
        Path p = tempDir.resolve("claude-stub-" + System.nanoTime() + ".sh");
        Files.writeString(p, body);
        Files.setPosixFilePermissions(p, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return p;
    }
}
