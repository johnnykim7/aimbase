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
                "/path/.claude", null, "runner-key-xyz");
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
    @DisplayName("chat — agent 비활성/runner 미지원 시 400")
    void chatInactiveAgent() {
        RequestContext.setAgentId("agent-1");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.empty());

        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No active ClaudeCliRunner");
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
                any(), eq("runner-key-xyz"))).thenReturn(expected);

        LLMResponse resp = adapter.chat(sampleRequest()).get();
        assertThat(resp.textContent()).isEqualTo("ok");
        verify(client, times(1)).chat(eq(endpoint), any(), eq("AIMBASE"),
                eq("/path/.claude"), any(), eq("runner-key-xyz"));
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
        when(client.chat(any(), any(), any(), any(), any(), any())).thenReturn(expected);

        adapter.chat(noModel).get();

        org.mockito.ArgumentCaptor<LLMRequest> captor = org.mockito.ArgumentCaptor.forClass(LLMRequest.class);
        verify(client).chat(any(), captor.capture(), any(), any(), any(), any());
        assertThat(captor.getValue().model()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    @DisplayName("chatStream — agent 비활성 시 즉시 예외")
    void streamInactiveAgent() {
        RequestContext.setAgentId("agent-1");
        when(agentRegistry.resolveActiveRunner("agent-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.chatStream(sampleRequest(), c -> {}))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No active ClaudeCliRunner");
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
                any(), eq("runner-key-xyz"), any());
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
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any())).thenReturn(expected);

        LLMResponse resp = adapter.chat(sampleRequest()).get();
        assertThat(resp.textContent()).isEqualTo("ok");
        // resolveActiveRunner 는 호출되지 않아야 한다 (헤더 없음)
        verify(agentRegistry, times(0)).resolveActiveRunner(any());
        verify(agentRegistry, times(1)).resolveActiveByUserRef("alice@example.com");
    }

    @Test
    @DisplayName("CR-075: 헤더와 user_ref 모두 없으면 400")
    void chatNoHeaderNoUserRef() {
        // RequestContext 완전 빈 상태
        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("X-Aimbase-Agent-Id 헤더 또는 위젯 토큰 user_ref 클레임이 필요");
    }

    @Test
    @DisplayName("CR-075: user_ref 폴백 — 활성 agent 0건이면 400")
    void chatUserRefNoActiveAgent() {
        RequestContext.setUserRef("bob@example.com");
        when(agentRegistry.resolveActiveByUserRef("bob@example.com")).thenReturn(Optional.empty());

        var future = adapter.chat(sampleRequest());
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No active ClaudeCliRunner registered for user: bob@example.com");
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
        when(client.chat(eq(endpoint), any(), any(), any(), any(), any())).thenReturn(expected);

        adapter.chat(sampleRequest()).get();

        verify(agentRegistry, times(1)).resolveActiveRunner("explicit-agent");
        // user_ref 폴백 분기는 호출되지 않아야 한다
        verify(agentRegistry, times(0)).resolveActiveByUserRef(any());
    }
}
