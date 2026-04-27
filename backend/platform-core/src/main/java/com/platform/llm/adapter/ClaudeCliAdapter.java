package com.platform.llm.adapter;

import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ToolCall;
import com.platform.service.AgentEndpoint;
import com.platform.service.AgentRegistryService;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * CR-071 Phase 4: LLMAdapter 구현 — ClaudeCliRunner HTTP 호출 경로.
 *
 * <p>호출 흐름:
 * <ol>
 *   <li>{@link RequestContext#requireAgentId()} — 헤더 X-Aimbase-Agent-Id 확인 (없으면 400)</li>
 *   <li>{@link AgentRegistryService#resolveActiveRunner(String)} — agent → AgentEndpoint</li>
 *   <li>{@link ClaudeCliRunnerClient} 로 Runner 호출</li>
 * </ol>
 *
 * <p>Connection 레벨 옵션 (생성자 주입):
 * <ul>
 *   <li>{@code defaultModel} — connection.config.model</li>
 *   <li>{@code toolMode} — AIMBASE / NATIVE / HYBRID (CR-069)</li>
 *   <li>{@code configDir} — Runner 측 CLAUDE_CONFIG_DIR (정액 플랜 OAuth 디렉토리)</li>
 *   <li>{@code runnerApiKey} — connection.config.runner_api_key (X-Api-Key 헤더로 송신)</li>
 * </ul>
 *
 * <p>도구 정의는 transformToolDefs 에서 null 반환 — 도구 노출은 Runner 측 MCP 채널을 통한다 (CR-044).
 */
public class ClaudeCliAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliAdapter.class);
    public static final String PROVIDER = "anthropic-cli";

    private final ClaudeCliRunnerClient runnerClient;
    private final AgentRegistryService agentRegistry;
    private final String defaultModel;
    private final String toolMode;
    private final String configDir;
    private final String systemPromptOverride;
    private final String runnerApiKey;

    public ClaudeCliAdapter(ClaudeCliRunnerClient runnerClient,
                             AgentRegistryService agentRegistry,
                             String defaultModel,
                             String toolMode,
                             String configDir,
                             String systemPromptOverride,
                             String runnerApiKey) {
        this.runnerClient = runnerClient;
        this.agentRegistry = agentRegistry;
        this.defaultModel = defaultModel;
        this.toolMode = toolMode;
        this.configDir = configDir;
        this.systemPromptOverride = systemPromptOverride;
        this.runnerApiKey = runnerApiKey;
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public List<String> getSupportedModels() {
        // CLI 가 지원하는 모델은 계정 플랜에 따라 달라진다 — 공식 목록 없음.
        return List.of();
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        try {
            AgentEndpoint endpoint = resolveEndpoint();
            LLMRequest effective = ensureModel(request);
            LLMResponse resp = runnerClient.chat(
                    endpoint, effective, toolMode, configDir, systemPromptOverride, runnerApiKey);
            return CompletableFuture.completedFuture(resp);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        AgentEndpoint endpoint = resolveEndpoint();
        LLMRequest effective = ensureModel(request);
        runnerClient.chatStream(
                endpoint, effective, toolMode, configDir, systemPromptOverride, runnerApiKey, chunkConsumer);
    }

    /**
     * Claude CLI 는 Anthropic API 의 {@code tools} 파라미터가 없으며, 도구 통합의 정식 채널은
     * Runner 가 spawn 시 연결하는 MCP 서버 ({@code aimbase-agent --mcp-stdio}, CR-042/044) 이다.
     * @return 항상 null — 도구는 MCP 채널로 전달됨.
     */
    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        return null;
    }

    @Override
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
        if (nativeResponse instanceof LLMResponse resp) {
            return resp.toolCalls() != null ? resp.toolCalls() : List.of();
        }
        return List.of();
    }

    // ─── 내부 ─────────────────────────────────────────────────────────────

    private AgentEndpoint resolveEndpoint() {
        String agentId = RequestContext.requireAgentId();
        Optional<AgentEndpoint> endpoint = agentRegistry.resolveActiveRunner(agentId);
        if (endpoint.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No active ClaudeCliRunner for agent-id: " + agentId
                    + " (agent inactive 또는 runner_capability=false)");
        }
        return endpoint.get();
    }

    private LLMRequest ensureModel(LLMRequest request) {
        if (request.model() != null && !request.model().isBlank()) return request;
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ClaudeCliAdapter: model 미지정 + connection defaultModel 도 없음");
        }
        return request.withModel(defaultModel);
    }
}
