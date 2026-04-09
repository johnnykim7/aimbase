package com.platform.llm.adapter;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.llm.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * CR-032 PRD-219: Google Vertex AI 어댑터.
 *
 * Vertex AI를 통해 Gemini/Claude 모델에 연결한다.
 * 현재는 OpenAI 호환 shim 방식으로 구현 (Vertex AI OpenAI 호환 엔드포인트 또는 프록시 경유).
 * 향후 Google Cloud SDK 직접 연동 시 com.google.cloud:google-cloud-vertexai 의존성 추가.
 *
 * Connection config 예시:
 * <pre>
 * {
 *   "project_id": "my-gcp-project",
 *   "location": "us-central1",
 *   "service_account_key": "{...JSON...}",
 *   "model_id": "gemini-2.0-flash",
 *   "base_url": "https://vertex-proxy.example.com/v1"  (선택: 프록시 경유 시)
 * }
 * </pre>
 */
public class VertexAIAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(VertexAIAdapter.class);

    private final Map<String, Object> config;
    private final String modelId;
    private final OpenAICompatibleAdapter delegate;

    public VertexAIAdapter(Map<String, Object> config) {
        this.config = config;
        this.modelId = getConfigString(config, "model_id", "gemini-2.0-flash");

        String baseUrl = getConfigString(config, "base_url", null);
        String apiKey = getConfigString(config, "apiKey",
                getConfigString(config, "service_account_key", "vertex"));

        if (baseUrl != null && !baseUrl.isBlank()) {
            this.delegate = new OpenAICompatibleAdapter(baseUrl, apiKey, modelId, "vertex_ai");
            log.info("VertexAIAdapter 초기화 (프록시 모드): baseUrl={}, modelId={}", baseUrl, modelId);
        } else {
            this.delegate = null;
            log.warn("VertexAIAdapter 초기화: base_url 미지정 — 직접 Google SDK 연동은 미구현. " +
                     "Vertex AI OpenAI 호환 엔드포인트 또는 프록시의 base_url을 설정하세요.");
        }
    }

    @Override
    public String getProvider() { return "vertex_ai"; }

    @Override
    public List<String> getSupportedModels() {
        return List.of(modelId);
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        if (delegate == null) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Vertex AI 직접 SDK 연동 미구현. Connection config에 base_url(프록시)을 설정하세요."));
        }
        return delegate.chat(request);
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        if (delegate == null) {
            chunkConsumer.accept(new LLMStreamChunk("error", request.model(),
                    "Vertex AI 직접 SDK 연동 미구현", true, null));
            return;
        }
        delegate.chatStream(request, chunkConsumer);
    }

    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        return delegate != null ? delegate.transformToolDefs(tools) : List.of();
    }

    @Override
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
        return delegate != null ? delegate.parseToolCalls(nativeResponse) : List.of();
    }

    private static String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        Object val = config.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
