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
import com.platform.domain.ConnectionEntity;
import com.platform.llm.adapter.AnthropicAdapter;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.adapter.OpenAIAdapter;
import com.platform.llm.model.ModelConfig;
import com.platform.repository.ConnectionRepository;
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

    // connectionId → LLMAdapter (캐시: 동일 연결은 클라이언트 재사용)
    private final Map<String, LLMAdapter> cache = new ConcurrentHashMap<>();

    public ConnectionAdapterFactory(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
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
     * CR-008: LLM 연결 테스트 — 실제 API 호출로 API Key 유효성 및 네트워크 연결 검증.
     * max_tokens=1의 최소 요청을 보내 응답을 확인한다.
     */
    public HealthStatus ping(String connectionId) {
        ConnectionEntity conn = findConnection(connectionId);
        String adapterType = normalizeAdapterType(conn.getAdapter());
        String apiKey = (String) conn.getConfig().get("apiKey");

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
                    // Ollama는 로컬 서버 — 모델 목록 조회로 연결 확인
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

        if (apiKey == null || apiKey.isBlank()) {
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
                yield new AnthropicAdapter(client);
            }
            case "openai" -> {
                OpenAIClient client = OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .build();
                yield new OpenAIAdapter(client);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported adapter type: " + adapterType);
        };
    }

    /**
     * FE에서 "Claude (Anthropic)", "OpenAI" 등 표시명으로 저장되므로
     * 내부 어댑터 타입으로 정규화한다.
     */
    private static String normalizeAdapterType(String adapter) {
        if (adapter == null) return "";
        String lower = adapter.toLowerCase();
        if (lower.contains("anthropic") || lower.contains("claude")) return "anthropic";
        if (lower.contains("openai") || lower.contains("gpt")) return "openai";
        if (lower.contains("ollama")) return "ollama";
        return lower;
    }

    private ConnectionEntity findConnection(String connectionId) {
        return connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Connection not found: " + connectionId));
    }
}
