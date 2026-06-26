package com.platform.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.platform.action.model.HealthStatus;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.adapter.AnthropicAdapter;
import com.platform.llm.adapter.ClaudeCliAdapter;
import com.platform.llm.adapter.ClaudeCliRunnerClient;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.adapter.OpenAIAdapter;
import com.platform.service.AgentRegistryService;
import com.platform.llm.model.ModelConfig;
import com.platform.repository.ConnectionRepository;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * connectionId로 DB의 ConnectionEntity를 조회해 LLMAdapter를 반환.
 *
 * 연결별로 API Key가 다를 수 있으므로 어댑터를 connectionId 단위로 캐싱한다.
 * (API Key 변경 시 캐시 무효화는 ConnectionController.update() 호출 시 evict)
 */
@Component
public class ConnectionAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(ConnectionAdapterFactory.class);
    private static final String DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";

    private final ConnectionRepository connectionRepository;
    private final int defaultMaxTokens;
    /** CR-048 PRD-302: Anthropic 어댑터 생성 시 주입 */
    private final com.platform.llm.thinking.AdaptiveThinkingPolicy adaptiveThinkingPolicy;
    /** CR-071: 테넌트 피처 플래그 조회용 (Phase 4 ClaudeCliAdapter 라우팅 게이트로 의미 변경) */
    private final PlatformSettingsService platformSettings;
    /** CR-071 Phase 4: ClaudeCliAdapter 가 ClaudeCliRunner 를 HTTP 로 호출 */
    private final ClaudeCliRunnerClient claudeCliRunnerClient;
    /** CR-071 Phase 4: agent-id → AgentEndpoint 라우팅 */
    private final AgentRegistryService agentRegistryService;

    /** CR-050/CR-071: 테넌트 피처 플래그 키 (global_config). 값은 쉼표 구분 tenantId 또는 '*' (전체 허용). */
    private static final String CLI_ENABLED_TENANTS_KEY = "llm.anthropic-cli.enabled-tenants";

    // connectionId → LLMAdapter (캐시: 동일 연결은 클라이언트 재사용)
    private final Map<String, LLMAdapter> cache = new ConcurrentHashMap<>();

    public ConnectionAdapterFactory(ConnectionRepository connectionRepository,
                                    @org.springframework.beans.factory.annotation.Value("${platform.orchestrator.default-max-tokens:16000}") int defaultMaxTokens,
                                    com.platform.llm.thinking.AdaptiveThinkingPolicy adaptiveThinkingPolicy,
                                    PlatformSettingsService platformSettings,
                                    ClaudeCliRunnerClient claudeCliRunnerClient,
                                    AgentRegistryService agentRegistryService) {
        this.connectionRepository = connectionRepository;
        this.defaultMaxTokens = defaultMaxTokens;
        this.adaptiveThinkingPolicy = adaptiveThinkingPolicy;
        this.platformSettings = platformSettings;
        this.claudeCliRunnerClient = claudeCliRunnerClient;
        this.agentRegistryService = agentRegistryService;
    }

    /**
     * connectionId에 해당하는 LLMAdapter를 반환한다.
     * 처음 호출 시 DB에서 Connection을 읽어 어댑터를 생성하고 캐싱한다.
     */
    public LLMAdapter getAdapter(String connectionId) {
        return cache.computeIfAbsent(connectionId, this::createAdapter);
    }

    /**
     * connection의 기본 모델을 반환.
     * requestedModel이 명시되어 있으면 그것을 우선 사용.
     */
    public String resolveModel(String connectionId, String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()
                && !"auto".equalsIgnoreCase(requestedModel)) {
            return requestedModel;
        }
        ConnectionEntity conn = findConnection(connectionId);
        Object defaultModel = conn.getConfig().get("model");
        return defaultModel != null ? defaultModel.toString() : DEFAULT_MODEL;
    }

    /**
     * CR-030: connection config에서 ModelConfig를 구성한다.
     * config JSONB에 extended_thinking, thinking_budget_tokens 필드가 있으면 반영.
     */
    public ModelConfig resolveModelConfig(String connectionId) {
        ConnectionEntity conn = findConnection(connectionId);
        Map<String, Object> cfg = conn.getConfig();

        Boolean extendedThinking = null;
        Integer thinkingBudgetTokens = null;
        Integer maxTokens = null;
        com.platform.llm.model.ThinkingMode thinkingMode = null;

        if (cfg.containsKey("extended_thinking")) {
            extendedThinking = Boolean.valueOf(cfg.get("extended_thinking").toString());
        }
        if (cfg.containsKey("thinking_budget_tokens")) {
            thinkingBudgetTokens = ((Number) cfg.get("thinking_budget_tokens")).intValue();
        }
        if (cfg.containsKey("max_tokens")) {
            maxTokens = ((Number) cfg.get("max_tokens")).intValue();
        }
        // CR-031 PRD-214: thinking_mode 파싱 (DISABLED/ENABLED/ADAPTIVE)
        if (cfg.containsKey("thinking_mode")) {
            try {
                thinkingMode = com.platform.llm.model.ThinkingMode.valueOf(
                        cfg.get("thinking_mode").toString().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Connection '{}' 잘못된 thinking_mode '{}' — 무시",
                        connectionId, cfg.get("thinking_mode"));
            }
        }

        return new ModelConfig(null, maxTokens, null, null, extendedThinking, thinkingBudgetTokens, thinkingMode);
    }

    /**
     * Connection의 API Key가 변경됐을 때 캐시를 무효화한다.
     * ConnectionController.update() 에서 호출.
     */
    public void evict(String connectionId) {
        cache.remove(connectionId);
    }

    /**
     * CR-075: connection_id 미지정 호출에 대해 현재 테넌트의 기본 anthropic-cli connection 을 반환.
     * 조건:
     *   1) {@link #isCliEnabledForCurrentTenant()} — 피처 플래그 통과
     *   2) tenant DB 에 adapter='anthropic-cli' 인 connection 존재
     * 둘 중 하나라도 안 되면 null — OrchestratorEngine 이 기존 modelRouter 폴백으로 진행.
     */
    public String findDefaultCliConnectionForCurrentTenant() {
        if (!isCliEnabledForCurrentTenant()) return null;
        // anthropic-cli 어댑터 connection 조회 (가장 최근 connected 우선, 없으면 첫 row)
        java.util.List<ConnectionEntity> cliConns = connectionRepository.findByAdapter("anthropic-cli");
        if (cliConns.isEmpty()) return null;
        return cliConns.stream()
                .filter(c -> "connected".equalsIgnoreCase(c.getStatus()))
                .findFirst()
                .orElse(cliConns.get(0))
                .getId();
    }

    /**
     * CR-008: LLM 연결 테스트 — 실제 API 호출로 API Key 유효성 및 네트워크 연결 검증.
     * max_tokens=1의 최소 요청을 보내 응답을 확인한다.
     */
    public HealthStatus ping(String connectionId) {
        ConnectionEntity conn = findConnection(connectionId);
        String adapterType = normalizeAdapterType(conn.getAdapter());
        String apiKey = (String) conn.getConfig().get("apiKey");

        // CR-071: anthropic-cli 는 Phase 4 에서 ClaudeCliAdapter(HTTP Runner) 로 재구현 예정.
        // Phase 1 단계에서는 피처 플래그만 확인 — Runner 가용성 검증은 Phase 4 에서 추가.
        if ("anthropic-cli".equals(adapterType)) {
            boolean ok = isCliEnabledForCurrentTenant();
            return new HealthStatus(ok, 0);
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Connection '{}' ping skipped — no 'apiKey'. Available keys: {}", connectionId, conn.getConfig().keySet());
            return new HealthStatus(false, 0);
        }

        long start = System.currentTimeMillis();
        try {
            switch (adapterType) {
                case "anthropic" -> {
                    AnthropicClient client = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey).build();
                    client.messages().create(MessageCreateParams.builder()
                            .model(Model.of(resolveModelId(conn, "claude-haiku-4-5-20251001")))
                            .maxTokens(1)
                            .addMessage(MessageParam.builder()
                                    .role(MessageParam.Role.USER)
                                    .content("ping")
                                    .build())
                            .build());
                }
                case "openai" -> {
                    OpenAIClient client = OpenAIOkHttpClient.builder()
                            .apiKey(apiKey).build();
                    client.chat().completions().create(ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(resolveModelId(conn, "gpt-4o-mini")))
                            .maxCompletionTokens(1L)
                            .addUserMessage("ping")
                            .build());
                }
                case "ollama" -> {
                    String baseUrl = conn.getConfig().get("baseUrl") != null
                            ? conn.getConfig().get("baseUrl").toString() : "http://localhost:11434";
                    OpenAIClient client = OpenAIOkHttpClient.builder()
                            .apiKey("ollama").baseUrl(baseUrl + "/v1").build();
                    client.chat().completions().create(ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(resolveModelId(conn, "llama3.2")))
                            .maxCompletionTokens(1L)
                            .addUserMessage("ping")
                            .build());
                }
                // CR-032: OpenAI 호환 / Bedrock / Vertex AI ping
                case "openai_compatible", "bedrock", "vertex_ai" -> {
                    String baseUrl = conn.getConfig().get("base_url") != null
                            ? conn.getConfig().get("base_url").toString() : null;
                    if (baseUrl == null || baseUrl.isBlank()) {
                        log.warn("Connection '{}' ping skipped — no 'base_url' for {}", connectionId, adapterType);
                        return new HealthStatus(false, 0);
                    }
                    String pingKey = apiKey != null ? apiKey : "none";
                    OpenAIClient client = OpenAIOkHttpClient.builder()
                            .apiKey(pingKey).baseUrl(baseUrl).build();
                    client.chat().completions().create(ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(resolveModelId(conn, "default")))
                            .maxCompletionTokens(1L)
                            .addUserMessage("ping")
                            .build());
                }
                default -> {
                    return new HealthStatus(false, 0);
                }
            }
            long latency = System.currentTimeMillis() - start;
            log.info("Connection '{}' ping succeeded in {}ms", connectionId, latency);
            return new HealthStatus(true, latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Connection '{}' ping failed: {}", connectionId, e.getMessage());
            return new HealthStatus(false, latency);
        }
    }

    private String resolveModelId(ConnectionEntity conn, String fallback) {
        Object model = conn.getConfig().get("model");
        if (model == null || model.toString().isBlank()) return fallback;
        String m = model.toString();
        return m.contains("/") ? m.substring(m.indexOf("/") + 1) : m;
    }

    // ─── private ───────────────────────────────────────────────────────────

    private LLMAdapter createAdapter(String connectionId) {
        ConnectionEntity conn = findConnection(connectionId);
        String adapterType = normalizeAdapterType(conn.getAdapter());
        String apiKey = (String) conn.getConfig().get("apiKey");

        // CR-050: anthropic-cli 는 OAuth(CLAUDE_CONFIG_DIR) 기반 — apiKey 불필요.
        boolean requiresApiKey = !"anthropic-cli".equals(adapterType);
        if (requiresApiKey && (apiKey == null || apiKey.isBlank())) {
            log.error("Connection '{}' config has no 'apiKey'. Available keys: {}, config: {}",
                    connectionId, conn.getConfig().keySet(), conn.getConfig());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Connection '" + connectionId + "' has no apiKey configured");
        }

        return switch (adapterType) {
            case "anthropic" -> {
                AnthropicClient client = AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .build();
                yield new AnthropicAdapter(client, defaultMaxTokens, adaptiveThinkingPolicy);
            }
            case "openai" -> {
                OpenAIClient client = OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .build();
                yield new OpenAIAdapter(client);
            }
            // CR-032 PRD-217: OpenAI 호환 범용 shim
            case "openai_compatible" -> {
                String baseUrl = (String) conn.getConfig().get("base_url");
                if (baseUrl == null || baseUrl.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Connection '" + connectionId + "' requires 'base_url' for openai_compatible adapter");
                }
                String model = resolveModelId(conn, "default");
                yield new com.platform.llm.adapter.OpenAICompatibleAdapter(
                        baseUrl, apiKey, model, "openai_compatible");
            }
            // CR-032 PRD-218: AWS Bedrock
            case "bedrock" -> {
                yield new com.platform.llm.adapter.BedrockAdapter(conn.getConfig());
            }
            // CR-032 PRD-219: Google Vertex AI
            case "vertex_ai" -> {
                yield new com.platform.llm.adapter.VertexAIAdapter(conn.getConfig());
            }
            // CR-071 Phase 4: ClaudeCliAdapter — Runner 를 HTTP 로 호출.
            // 호출 시점에 X-Aimbase-Agent-Id 헤더 → AgentRegistry 조회 → Runner endpoint 결정.
            case "anthropic-cli" -> {
                String model = resolveModelId(conn, null);
                String toolModeStr = (String) conn.getConfig().get("tool_mode");
                String configDirOpt = (String) conn.getConfig().get("config_dir");
                String systemPromptOverride = (String) conn.getConfig().get("system_prompt_override");
                String runnerApiKey = (String) conn.getConfig().get("runner_api_key");
                // CR-103: 워크플로우 경로(LLM_CALL/AGENT_CALL)는 X-Aimbase-Agent-Id/user_ref 가 없으므로
                // 커넥터 config.agent_name 으로 Runner 라우팅. null 이면 헤더/user_ref 만으로 동작(기존).
                String routingAgentName = (String) conn.getConfig().get("agent_name");
                // CR-117: CLI 본체 Agent 서브에이전트 허용 여부. 키 없음/null = true(현행 유지).
                // false 명시 시에만 차단. Boolean/String 둘 다 허용(FE 폼은 "true"/"false" 문자열).
                boolean subagentEnabled = parseBooleanConfig(conn.getConfig().get("subagent_enabled"), true);
                yield new ClaudeCliAdapter(
                        claudeCliRunnerClient,
                        agentRegistryService,
                        model,
                        toolModeStr,
                        configDirOpt,
                        systemPromptOverride,
                        runnerApiKey,
                        routingAgentName,
                        subagentEnabled);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported adapter type: " + adapterType);
        };
    }

    /**
     * CR-050 BIZ-099: 현재 테넌트가 Claude CLI 어댑터 사용 권한이 있는지 확인.
     * global_config 의 {@code llm.anthropic-cli.enabled-tenants} 값이
     *  - {@code *}: 전체 허용
     *  - 빈 값/키 없음: 전체 차단
     *  - 쉼표 구분 tenantId 리스트: 일치하는 테넌트만 허용
     */
    /**
     * CR-117: connection.config 의 boolean 값을 안전 파싱. JSONB 역직렬화 결과가
     * {@link Boolean} 일 수도, FE 폼 저장으로 {@link String}("true"/"false") 일 수도 있다.
     * null/빈 값/미인식이면 {@code defaultValue}.
     */
    private static boolean parseBooleanConfig(Object raw, boolean defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            if (s.isBlank()) return defaultValue;
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private boolean isCliEnabledForCurrentTenant() {
        String allowed = platformSettings.getString(CLI_ENABLED_TENANTS_KEY, "");
        if (allowed == null || allowed.isBlank()) return false;
        if ("*".equals(allowed.trim())) return true;
        String current = TenantContext.hasTenant() ? TenantContext.getTenantId() : null;
        if (current == null) return false;
        for (String t : allowed.split(",")) {
            if (current.equals(t.trim())) return true;
        }
        return false;
    }

    /**
     * FE에서 "Claude (Anthropic)", "OpenAI" 등 표시명으로 저장되므로
     * 내부 어댑터 타입으로 정규화한다.
     */
    /**
     * FE에서 다양한 표시명으로 저장되므로 내부 어댑터 타입으로 정규화한다.
     * CR-032: openai_compatible, bedrock, vertex_ai 추가.
     */
    private static String normalizeAdapterType(String adapter) {
        if (adapter == null) return "";
        String lower = adapter.toLowerCase();
        // CR-050: CLI 경로는 anthropic 보다 먼저 체크 (anthropic-cli → anthropic 으로 오분류 방지).
        // FE 표시명 "Claude CLI" (공백형) 포함 — 공백형을 먼저 잡지 않으면 아래 contains("claude") 가 anthropic 으로 오분류.
        if (lower.equals("anthropic-cli") || lower.equals("claude-cli")
                || lower.equals("claude cli") || lower.equals("anthropic cli")
                || lower.equals("claude-max") || lower.equals("claude-pro")) {
            return "anthropic-cli";
        }
        if (lower.contains("anthropic") || lower.contains("claude")) return "anthropic";
        if (lower.equals("openai_compatible") || lower.contains("deepseek")
                || lower.contains("groq") || lower.contains("mistral")
                || lower.contains("openrouter") || lower.contains("together")
                || lower.contains("fireworks") || lower.contains("lm studio")
                || lower.contains("localai") || lower.contains("vllm")) return "openai_compatible";
        if (lower.contains("openai") || lower.contains("gpt")) return "openai";
        if (lower.contains("ollama")) return "ollama";
        if (lower.contains("bedrock") || lower.contains("aws")) return "bedrock";
        if (lower.contains("vertex") || lower.contains("google") || lower.contains("gemini")) return "vertex_ai";
        return lower;
    }

    private ConnectionEntity findConnection(String connectionId) {
        return connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Connection not found: " + connectionId));
    }
}
