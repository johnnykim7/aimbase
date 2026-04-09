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
 * CR-032 PRD-218: AWS Bedrock 어댑터.
 *
 * Bedrock Converse API를 통해 Claude/Llama/Mistral 등 모델에 연결한다.
 * 현재는 OpenAI 호환 shim 방식으로 구현 (Bedrock Gateway / LiteLLM Proxy 경유).
 * 향후 AWS SDK 직접 연동 시 software.amazon.awssdk:bedrockruntime 의존성 추가.
 *
 * Connection config 예시:
 * <pre>
 * {
 *   "aws_region": "us-east-1",
 *   "aws_access_key_id": "AKIA...",
 *   "aws_secret_access_key": "...",
 *   "model_id": "anthropic.claude-sonnet-4-5-20250514-v1:0",
 *   "base_url": "https://bedrock-proxy.example.com/v1"  (선택: 프록시 경유 시)
 * }
 * </pre>
 */
public class BedrockAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(BedrockAdapter.class);

    private final Map<String, Object> config;
    private final String modelId;
    private final OpenAICompatibleAdapter delegate;

    public BedrockAdapter(Map<String, Object> config) {
        this.config = config;
        this.modelId = getConfigString(config, "model_id", "anthropic.claude-sonnet-4-5-20250514-v1:0");

        // Bedrock Gateway/Proxy 경유 방식: base_url이 있으면 OpenAI 호환으로 위임
        String baseUrl = getConfigString(config, "base_url", null);
        String apiKey = getConfigString(config, "apiKey",
                getConfigString(config, "aws_access_key_id", "bedrock"));

        if (baseUrl != null && !baseUrl.isBlank()) {
            this.delegate = new OpenAICompatibleAdapter(baseUrl, apiKey, modelId, "bedrock");
            log.info("BedrockAdapter 초기화 (프록시 모드): baseUrl={}, modelId={}", baseUrl, modelId);
        } else {
            // 직접 연동 시 AWS SDK 필요 — 현재 미구현, 에러 반환
            this.delegate = null;
            log.warn("BedrockAdapter 초기화: base_url 미지정 — 직접 AWS SDK 연동은 미구현. " +
                     "Bedrock Gateway 또는 LiteLLM Proxy의 base_url을 설정하세요.");
        }
    }

    @Override
    public String getProvider() { return "bedrock"; }

    @Override
    public List<String> getSupportedModels() {
        return List.of(modelId);
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        if (delegate == null) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Bedrock 직접 SDK 연동 미구현. Connection config에 base_url(프록시)을 설정하세요."));
        }
        return delegate.chat(request);
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        if (delegate == null) {
            chunkConsumer.accept(new LLMStreamChunk("error", request.model(),
                    "Bedrock 직접 SDK 연동 미구현", true, null));
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
