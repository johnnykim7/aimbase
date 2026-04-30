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
 * <p>호출 흐름 (CR-075 — 라우팅 우선순위 폴백):
 * <ol>
 *   <li>헤더 {@code X-Aimbase-Agent-Id} 명시 → {@link AgentRegistryService#resolveActiveRunner(String)}</li>
 *   <li>위젯 토큰 user_ref 클레임 → {@link AgentRegistryService#resolveActiveByUserRef(String)} (정상 흐름)</li>
 *   <li>둘 다 없으면 400</li>
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
            try {
                LLMResponse resp = runnerClient.chat(
                        endpoint, effective, toolMode, configDir, systemPromptOverride, runnerApiKey);
                return CompletableFuture.completedFuture(resp);
            } catch (RuntimeException re) {
                // CR-082: runner 호출 자체 실패 — 사유를 식별 가능한 prefix 로 감싸 RuntimeException 전파.
                // ResponseStatusException 을 던지면 Spring 이 ASYNC dispatch 를 통해 SecurityContext 재검사를 일으켜
                // 위젯에 "network error" 로 보이는 부수효과 발생 (운영 검증). 따라서 단순 RuntimeException 으로.
                String classified = classifyRunnerFailure(re);
                log.warn("CLI runner unreachable: {} ({})", classified, re.getMessage());
                throw new RuntimeException("cli_runner_unreachable:" + classified + ":" + re.getMessage(), re);
            }
        } catch (ResponseStatusException rse) {
            return CompletableFuture.failedFuture(rse);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        AgentEndpoint endpoint = resolveEndpoint();
        LLMRequest effective = ensureModel(request);
        try {
            runnerClient.chatStream(
                    endpoint, effective, toolMode, configDir, systemPromptOverride, runnerApiKey, chunkConsumer);
        } catch (RuntimeException re) {
            // CR-082: stream 경로도 동일 — RuntimeException 으로 일관.
            String classified = classifyRunnerFailure(re);
            log.warn("CLI runner unreachable (stream): {} ({})", classified, re.getMessage());
            throw new RuntimeException("cli_runner_unreachable:" + classified + ":" + re.getMessage(), re);
        }
    }

    /**
     * CR-082: runner 호출 실패의 원인을 진단 로그/에러 메시지에 식별 가능한 사유 코드로 분류.
     * 정확한 사유보다 "어디서 끊겼는지" 단서 하나가 운영 진단을 빠르게 한다.
     */
    private static String classifyRunnerFailure(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 5 && cur != null; i++) {
            String msg = cur.getMessage();
            String type = cur.getClass().getSimpleName();
            if (cur instanceof java.net.ConnectException) return "AGENT_OFFLINE";
            if (cur instanceof java.net.SocketTimeoutException) return "AGENT_TIMEOUT";
            if (cur instanceof java.net.http.HttpTimeoutException) return "AGENT_TIMEOUT";
            if (cur instanceof java.net.NoRouteToHostException) return "TURN_RELAY_DEAD";
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset")) return "TURN_BROKEN_PIPE";
                if (lower.contains("connection refused")) return "AGENT_OFFLINE";
            }
            // 마지막 fallback — 클래스명 노출
            if (cur.getCause() == null) return type;
            cur = cur.getCause();
        }
        return "UNKNOWN";
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

    /**
     * CR-075: 라우팅 우선순위
     *   1) {@code X-Aimbase-Agent-Id} 헤더 명시 (디버깅/특수 케이스 호환)
     *   2) 위젯 토큰 {@code user_ref} 클레임 (운영 정상 흐름)
     *   3) 둘 다 없으면 400
     */
    private AgentEndpoint resolveEndpoint() {
        // 1) 헤더 명시 우선
        String agentId = RequestContext.getAgentId();
        if (agentId != null && !agentId.isBlank()) {
            Optional<AgentEndpoint> ep = agentRegistry.resolveActiveRunner(agentId);
            if (ep.isEmpty()) {
                // CR-082: 인프라 문제(agent down/STALE) — RuntimeException 으로 일관.
                // ResponseStatusException 을 SSE catch 흐름에 던지면 Spring 이 ASYNC dispatch 로
                // SecurityContext 권한 재검사를 일으켜 위젯에 "network error" 발생 (운영 검증).
                throw new RuntimeException("cli_agent_offline:agent-id=" + agentId
                        + " (agent inactive 또는 runner_capability=false)");
            }
            return ep.get();
        }

        // 2) user_ref 자동 라우팅 (CR-075)
        String userRef = RequestContext.getUserRef();
        if (userRef != null && !userRef.isBlank()) {
            Optional<AgentEndpoint> ep = agentRegistry.resolveActiveByUserRef(userRef);
            if (ep.isEmpty()) {
                throw new RuntimeException("cli_agent_offline:user=" + userRef
                        + " (사용자 PC 의 aimbase-agent 가 기동되지 않았거나 runner_capability=false)");
            }
            return ep.get();
        }

        // 3) 둘 다 없으면 400 — 클라이언트가 토큰/헤더 자체를 안 보냄. 컨트롤러 진입 전 검증과 동일 의미.
        // 이 케이스는 stream:false sync 핸들러에서 자연스럽게 400 매핑되는 게 의도. SSE 진입 전이라 ASYNC dispatch 위험 없음.
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "cli_routing_missing: X-Aimbase-Agent-Id 헤더 또는 위젯 토큰 user_ref 클레임이 필요합니다");
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
