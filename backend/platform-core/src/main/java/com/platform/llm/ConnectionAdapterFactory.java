package com.platform.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.adapter.AnthropicAdapter;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.adapter.OpenAIAdapter;
import com.platform.repository.ConnectionRepository;
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
     * Connection의 API Key가 변경됐을 때 캐시를 무효화한다.
     * ConnectionController.update() 에서 호출.
     */
    public void evict(String connectionId) {
        cache.remove(connectionId);
    }

    // ─── private ───────────────────────────────────────────────────────────

    private LLMAdapter createAdapter(String connectionId) {
        ConnectionEntity conn = findConnection(connectionId);
        String adapterType = conn.getAdapter().toLowerCase();
        String apiKey = (String) conn.getConfig().get("apiKey");

        if (apiKey == null || apiKey.isBlank()) {
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

    private ConnectionEntity findConnection(String connectionId) {
        return connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Connection not found: " + connectionId));
    }
}
