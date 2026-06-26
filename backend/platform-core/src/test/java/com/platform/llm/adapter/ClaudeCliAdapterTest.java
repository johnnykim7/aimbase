package com.platform.llm.adapter;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.UnifiedMessage;
import com.platform.service.AgentEndpoint;
import com.platform.service.AgentRegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeCliAdapterTest {

    private ClaudeCliRunnerClient client;
    private AgentRegistryService agentRegistry;
    private ClaudeCliAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(ClaudeCliRunnerClient.class);
        agentRegistry = mock(AgentRegistryService.class);
        adapter = new ClaudeCliAdapter(
                client, agentRegistry,
                "claude-sonnet-4-5", "AIMBASE",
                "/path/.claude", null, "runner-key-xyz",
                null /* routingAgentName — 기존 테스트는 헤더/user_ref 경로만 검증 */,
                true /* subagentEnabled — CR-117 기본 */);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static LLMRequest sampleRequest() {
        return new LLMRequest(
                "claude-sonnet-4-5",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "안녕")),
                null, ModelConfig.defaults(), false, "run-1");
    }

    @Test
    @DisplayName("getProvider — anthropic-cli")
    void provider() {
        assertThat(adapter.getProvider()).isEqualTo("anthropic-cli");
    }

    @Test
    @DisplayName("transformToolDefs — null (도구는 MCP 채널)")
    void transformToolDefs() {
        assertThat(adapter.transformToolDefs(List.of())).isNull();
    }

    @Test
    @DisplayName("chat — agent-id 누락 시 400 (CompletableFuture 예외)")
    void chatMissingAgentId() {
        // RequestContext 미설정
        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("CR-082: agent 비활성/runner 미지원 시 RuntimeException(cli_agent_offline) — SSE ASYNC dispatch 회피")
    void chatInactiveAgent() {
        RequestContext.setAgentId("agent-1");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.empty());

        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("cli_agent_offline");
    }

    @Test
    @DisplayName("chat — 정상 흐름: AgentEndpoint 조회 → Runner 호출 → 응답 반환")
    void chatHappyPath() throws Exception {
        RequestContext.setAgentId("agent-1");
        AgentEndpoint endpoint = new AgentEndpoint("agent-1", "http://host:8290", "hash");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.of(endpoint));

        LLMResponse expected = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(client.chat(eq(endpoint), any(), eq("AIMBASE"), eq("/path/.claude"),
                any(), eq("runner-key-xyz"), anyBoolean())).thenReturn(expected);

        LLMResponse resp = adapter.chat(sampleRequest()).get();
        assertThat(resp.textContent()).isEqualTo("ok");
        verify(client, times(1)).chat(eq(endpoint), any(), eq("AIMBASE"),
                eq("/path/.claude"), any(), eq("runner-key-xyz"), anyBoolean());
    }

    @Test
    @DisplayName("chat — 요청에 model 미지정이면 connection defaultModel 사용")
    void chatModelFallback() throws Exception {
        RequestContext.setAgentId("agent-1");
        AgentEndpoint endpoint = new AgentEndpoint("agent-1", "http://host:8290", null);
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.of(endpoint));

        LLMRequest noModel = new LLMRequest(
                null,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")),
                null, ModelConfig.defaults(), false, "run-1");

        LLMResponse expected = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 0L, 0.0);
        when(client.chat(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(expected);

        adapter.chat(noModel).get();

        org.mockito.ArgumentCaptor<LLMRequest> captor = org.mockito.ArgumentCaptor.forClass(LLMRequest.class);
        verify(client).chat(any(), captor.capture(), any(), any(), any(), any(), anyBoolean());
        assertThat(captor.getValue().model()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    @DisplayName("CR-082: chatStream agent 비활성 시 RuntimeException(cli_agent_offline)")
    void streamInactiveAgent() {
        RequestContext.setAgentId("agent-1");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.chatStream(sampleRequest(), c -> {}))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cli_agent_offline");
    }

    @Test
    @DisplayName("chatStream — 정상 흐름: client.chatStream 위임")
    void streamHappyPath() {
        RequestContext.setAgentId("agent-1");
        AgentEndpoint endpoint = new AgentEndpoint("agent-1", "http://host:8290", null);
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.of(endpoint));

        adapter.chatStream(sampleRequest(), chunk -> {});

        verify(client, times(1)).chatStream(
                eq(endpoint), any(), eq("AIMBASE"), eq("/path/.claude"),
                any(), eq("runner-key-xyz"), any(), anyBoolean());
    }

    // ─── CR-075: user_ref 자동 라우팅 폴백 ───

    @Test
    @DisplayName("CR-075: 헤더 없을 때 user_ref → resolveActiveByUserRef 로 라우팅")
    void chatFallbackToUserRef() throws Exception {
        // 헤더 없음, user_ref 만 있음
        RequestContext.setUserRef("alice@example.com");
        AgentEndpoint endpoint = new AgentEndpoint("agent-7", "http://host:8290", "hash");
        when(agentRegistry.resolveActiveByUserRef("alice@example.com")).thenReturn(Optional.of(endpoint));

        LLMResponse expected = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(expected);

        LLMResponse resp = adapter.chat(sampleRequest()).get();
        assertThat(resp.textContent()).isEqualTo("ok");
        // resolveActiveRunner 는 호출되지 않아야 한다 (헤더 없음)
        verify(agentRegistry, times(0)).resolveActiveRunner(any());
        verify(agentRegistry, times(1)).resolveActiveByUserRef("alice@example.com");
    }

    @Test
    @DisplayName("CR-075: 헤더와 user_ref 모두 없으면 400 (클라이언트 잘못 — 그대로 유지)")
    void chatNoHeaderNoUserRef() {
        // RequestContext 완전 빈 상태
        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cli_routing_missing");
    }

    @Test
    @DisplayName("CR-082: user_ref 폴백 — 활성 agent 0건이면 RuntimeException(cli_agent_offline)")
    void chatUserRefNoActiveAgent() {
        RequestContext.setUserRef("bob@example.com");
        when(agentRegistry.resolveActiveByUserRef("bob@example.com")).thenReturn(Optional.empty());

        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("cli_agent_offline")
                .hasMessageContaining("user=bob@example.com");
    }

    @Test
    @DisplayName("CR-075: 헤더 명시가 user_ref 보다 우선 — 디버깅 호환")
    void chatHeaderTakesPrecedenceOverUserRef() throws Exception {
        // 둘 다 세팅 — 헤더가 이겨야 한다
        RequestContext.setAgentId("explicit-agent");
        RequestContext.setUserRef("alice@example.com");
        AgentEndpoint endpoint = new AgentEndpoint("explicit-agent", "http://host:8290", null);
        when(agentRegistry.resolveActiveRunner("explicit-agent")).thenReturn(Optional.of(endpoint));

        LLMResponse expected = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(expected);

        adapter.chat(sampleRequest()).get();

        verify(agentRegistry, times(1)).resolveActiveRunner("explicit-agent");
        // user_ref 폴백 분기는 호출되지 않아야 한다
        verify(agentRegistry, times(0)).resolveActiveByUserRef(any());
    }

    // ─── CR-103: 커넥터 config agent_name 라우팅 (워크플로우 경로) ───

    /** routingAgentName 이 박힌 어댑터 — 워크플로우 커넥터를 모사. */
    private ClaudeCliAdapter adapterWithAgentName(String agentName) {
        return new ClaudeCliAdapter(
                client, agentRegistry,
                "claude-sonnet-4-5", "AIMBASE",
                "/path/.claude", null, "runner-key-xyz", agentName, true);
    }

    @Test
    @DisplayName("CR-103: 헤더·user_ref 없고 커넥터 agent_name 만 있을 때 → resolveActiveByAgentName 로 라우팅 (워크플로우 정상 흐름)")
    void chatRoutesByConnectorAgentName() throws Exception {
        // RequestContext 완전 빈 상태 (워크플로우 스텝과 동일)
        ClaudeCliAdapter wf = adapterWithAgentName("cli-runner-bidding");
        AgentEndpoint endpoint = new AgentEndpoint("agent-9", "http://aimbase-agent:8290", "hash");
        when(agentRegistry.resolveActiveByAgentName("cli-runner-bidding")).thenReturn(Optional.of(endpoint));

        LLMResponse expected = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(expected);

        LLMResponse resp = wf.chat(sampleRequest()).get();
        assertThat(resp.textContent()).isEqualTo("ok");
        verify(agentRegistry, times(1)).resolveActiveByAgentName("cli-runner-bidding");
        // 헤더/user_ref 분기는 비어 있으므로 호출되지 않는다
        verify(agentRegistry, times(0)).resolveActiveRunner(any());
        verify(agentRegistry, times(0)).resolveActiveByUserRef(any());
    }

    @Test
    @DisplayName("CR-103: 헤더 명시가 커넥터 agent_name 보다 우선 — 호출자 명시가 커넥터 기본값을 덮는다")
    void chatHeaderTakesPrecedenceOverConnectorAgentName() throws Exception {
        ClaudeCliAdapter wf = adapterWithAgentName("cli-runner-bidding");
        RequestContext.setAgentId("explicit-agent");
        AgentEndpoint endpoint = new AgentEndpoint("explicit-agent", "http://host:8290", null);
        when(agentRegistry.resolveActiveRunner("explicit-agent")).thenReturn(Optional.of(endpoint));

        LLMResponse expected = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(expected);

        wf.chat(sampleRequest()).get();

        verify(agentRegistry, times(1)).resolveActiveRunner("explicit-agent");
        verify(agentRegistry, times(0)).resolveActiveByAgentName(any());
    }

    @Test
    @DisplayName("CR-103: 커넥터 agent_name 으로도 활성 agent 0건이면 RuntimeException(cli_agent_offline:agent-name=)")
    void chatConnectorAgentNameNoActiveAgent() {
        ClaudeCliAdapter wf = adapterWithAgentName("cli-runner-bidding");
        when(agentRegistry.resolveActiveByAgentName("cli-runner-bidding")).thenReturn(Optional.empty());

        var future = wf.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("cli_agent_offline")
                .hasMessageContaining("agent-name=cli-runner-bidding");
    }

    // ─── CR-109: timeout 시 Runner cancel 신호 (좀비 누수 방지) ───

    @Test
    @DisplayName("CR-109: chat HTTP timeout 시 Runner cancel 신호 전송 (좀비 워커 정리)")
    void chatTimeoutSendsCancel() {
        RequestContext.setAgentId("agent-1");
        AgentEndpoint endpoint = new AgentEndpoint("agent-1", "http://host:8290", "hash");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.of(endpoint));
        // runner 호출이 HTTP timeout 으로 실패 → classifyRunnerFailure = AGENT_TIMEOUT
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("call failed", new java.net.http.HttpTimeoutException("timed out")));

        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("AGENT_TIMEOUT");

        // 핵심: timeout 분류 → run-1 의 워커 정리를 위한 cancel 호출
        verify(client, times(1)).cancel(eq(endpoint), eq("run-1"), eq("runner-key-xyz"));
    }

    @Test
    @DisplayName("CR-109: chat 연결 실패(AGENT_OFFLINE)는 cancel 보내지 않음 — Runner 에 닿지도 못함")
    void chatOfflineDoesNotSendCancel() {
        RequestContext.setAgentId("agent-1");
        AgentEndpoint endpoint = new AgentEndpoint("agent-1", "http://host:8290", "hash");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.of(endpoint));
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("connect failed", new java.net.ConnectException("refused")));

        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("AGENT_OFFLINE");

        verify(client, times(0)).cancel(any(), any(), any());
    }

    @Test
    @DisplayName("CR-109: chatStream timeout 시에도 Runner cancel 신호 전송")
    void streamTimeoutSendsCancel() {
        RequestContext.setAgentId("agent-1");
        AgentEndpoint endpoint = new AgentEndpoint("agent-1", "http://host:8290", "hash");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.of(endpoint));
        org.mockito.Mockito.doThrow(
                        new RuntimeException("stream failed", new java.net.http.HttpTimeoutException("timed out")))
                .when(client).chatStream(eq(endpoint), any(), any(), any(), any(), any(), any(), anyBoolean());

        assertThatThrownBy(() -> adapter.chatStream(sampleRequest(), c -> {}))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("AGENT_TIMEOUT");

        verify(client, times(1)).cancel(eq(endpoint), eq("run-1"), eq("runner-key-xyz"));
    }
}
