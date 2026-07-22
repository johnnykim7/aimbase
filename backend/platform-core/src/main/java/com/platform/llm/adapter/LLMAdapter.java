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

    /**
     * CR-121: 주어진 접두사(=워크플로우 run 의 부모 runId)로 시작하는 모든 세션의 워커를 일괄 정리한다.
     *
     * <p>LARGE_INPUT 은 청크/재시도마다 sessionId 가 달라({@code {runId}-li-{step}.body[N]-cM}) pool 에
     * 제각각 등록되므로, 단일 {@link #cleanupSession(String)} 로는 한 번에 회수할 수 없다. run 이 종료
     * (취소/정상/실패)되면 이 메서드로 그 run 의 모든 잔여 워커를 부모 runId 하나로 정리해 좀비 누수를 막는다.
     *
     * <p>기본은 no-op. worker pool 을 두는 어댑터(예: {@code ClaudeCliAdapter})만 오버라이드한다. best-effort.
     */
    default void cleanupSessionsByPrefix(String runIdPrefix) { }
}
