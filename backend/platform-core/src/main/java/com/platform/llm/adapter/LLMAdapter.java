package com.platform.llm.adapter;

import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ToolCall;
import com.platform.tool.model.UnifiedToolDef;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface LLMAdapter {

    String getProvider();

    List<String> getSupportedModels();

    CompletableFuture<LLMResponse> chat(LLMRequest request);

    void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer);

    Object transformToolDefs(List<UnifiedToolDef> tools);

    List<ToolCall> parseToolCalls(Object nativeResponse);

    default boolean supports(String modelId) {
        String provider = modelId.split("/")[0];
        return getProvider().equals(provider);
    }

    /** CR-061: 어댑터 멀티모달 지원 선언 (기본 NONE — 필요한 어댑터가 오버라이드). */
    default AdapterCapability capabilities() {
        return AdapterCapability.NONE;
    }

    /**
     * CR-114: run(또는 서브에이전트 turn) 정상 종료 시 어댑터별 세션 자원을 결정적으로 정리한다.
     *
     * <p>기본은 no-op. worker pool 을 두는 어댑터(예: {@code ClaudeCliAdapter})만 오버라이드하여
     * 해당 sessionId 의 워커(claude CLI 프로세스)를 닫는다. CR-109 가 timeout/실패 경로만 정리했던 것과 달리,
     * 정상 완료(success) 경로에서도 호출되어 좀비 워커 누수를 막는다. best-effort — 정리 실패가 호출처 흐름을 막지 않는다.
     */
    default void cleanupSession(String sessionId) { }
}
