package com.platform.workflow.callback;

import com.platform.domain.ConnectionEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.ConnectionRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.tenant.TenantContext;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import com.platform.tool.builtin.HttpRequestTool;
import com.platform.workflow.event.WorkflowEvents;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;

/**
 * CR-174 PRD-363, BIZ-143: 실행 입력 {@code _callback} 을 실은 run 이 끝나면 바깥 앱에 결과를 한 번 POST 한다.
 *
 * <p>승인 거절, 승인 시한 초과, 단계 실패, 중지는 워크플로우 안의 단계로 보낼 수 없다(CR-164 와 같은 판단).
 * 그래서 모든 종료 경로가 내는 {@link WorkflowEvents.RunCompleted} 를 받아 보낸다. 칸 검사는 엔진이 run 을 만들기 전에
 * {@link #validate(Map)} 로 한다. 보내기는 HTTP 연결의 baseUrl, auth 를 그대로 쓰는 {@link HttpRequestTool} 에 맡겨
 * 머리글 주입과 기록 가림(BIZ-132)을 한 곳에 둔다. 재시도가 승인 API 응답이나 run 스레드를 잡지 않게 따로 보낸다.
 */
@Component
public class RunCompletionCallback {

    private static final Logger log = LoggerFactory.getLogger(RunCompletionCallback.class);

    public static final String INPUT_KEY = "_callback";

    record Binding(String connectionId, String path, String resultStep) {}

    private final WorkflowRunRepository runs;
    private final ConnectionRepository connections;
    private final HttpRequestTool http;
    private final WorkflowRunEventRecorder recorder;
    private final int retryMaxAttempts;
    private final long retryDelayMs;
    private final Executor executor;
    private final LongConsumer sleeper;

    @Autowired
    public RunCompletionCallback(WorkflowRunRepository runs, ConnectionRepository connections, HttpRequestTool http,
                                 ObjectProvider<WorkflowRunEventRecorder> recorderProvider,
                                 @Value("${aimbase.workflow.callback.retry-max-attempts:3}") int retryMaxAttempts,
                                 @Value("${aimbase.workflow.callback.retry-delay-ms:5000}") long retryDelayMs) {
        this(runs, connections, http, recorderProvider.getIfAvailable(), retryMaxAttempts, retryDelayMs,
                task -> Thread.ofVirtual().name("wf-callback-" + UUID.randomUUID().toString().substring(0, 8)).start(task),
                RunCompletionCallback::sleepQuietly);
    }

    RunCompletionCallback(WorkflowRunRepository runs, ConnectionRepository connections, HttpRequestTool http,
                          WorkflowRunEventRecorder recorder, int retryMaxAttempts, long retryDelayMs,
                          Executor executor, LongConsumer sleeper) {
        this.runs = runs;
        this.connections = connections;
        this.http = http;
        this.recorder = recorder;
        this.retryMaxAttempts = Math.max(0, retryMaxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.executor = executor;
        this.sleeper = sleeper;
    }

    /** run 을 만들기 전에 부른다. 칸이 틀리면 run 을 만들지 않게 던진다(400 callback_invalid:칸). */
    public void validate(Map<String, Object> input) {
        Binding binding = parse(input);
        if (binding == null) {
            return;
        }
        ConnectionEntity connection = connections.findById(binding.connectionId()).orElse(null);
        if (connection == null || !"HTTP".equalsIgnoreCase(connection.getType())) {
            throw invalid("connection_id");
        }
    }

    @EventListener
    public void onRunCompleted(WorkflowEvents.RunCompleted event) {
        if (event.parentRunId() != null) {
            return;
        }
        // 같은 이벤트를 받는 실행 구독(SSE)과 개발 작업 잇기가 뒤에 있으니 여기서 예외를 밖으로 내지 않는다
        try {
            WorkflowRunEntity run = runs.findById(event.runId()).orElse(null);
            if (run == null) {
                return;
            }
            Binding binding;
            try {
                binding = parse(run.getInputData());
            } catch (IllegalArgumentException e) {
                log.warn("CR-174 run {} 의 {} 가 틀려 회신하지 않음: {}", run.getId(), INPUT_KEY, e.getMessage());
                return;
            }
            if (binding == null) {
                return;
            }
            Map<String, Object> payload = payload(run, event, binding);
            String tenantId = TenantContext.getTenantId();
            String sessionId = run.getSessionId();
            UUID runId = run.getId();
            executor.execute(() -> {
                try {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    deliver(runId, sessionId, binding, payload);
                } finally {
                    TenantContext.clear();
                }
            });
        } catch (RuntimeException e) {
            log.error("CR-174 run {} 종료 회신을 준비하지 못함: {}", event.runId(), e.getMessage(), e);
        }
    }

    void deliver(UUID runId, String sessionId, Binding binding, Map<String, Object> payload) {
        int attempts = retryMaxAttempts + 1;
        String lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            ToolResult result;
            try {
                result = http.execute(Map.of(
                        "connection_id", binding.connectionId(),
                        "method", "POST",
                        "path", binding.path(),
                        "body", payload), ToolContext.minimal(TenantContext.getTenantId(), sessionId));
            } catch (RuntimeException e) {
                result = ToolResult.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            if (result != null && result.success()) {
                log.info("CR-174 run {} 종료 회신 보냄({}/{}): {}", runId, attempt, attempts, result.summary());
                return;
            }
            lastError = result != null ? result.summary() : "no result";
            log.warn("CR-174 run {} 종료 회신 실패({}/{}): {}", runId, attempt, attempts, lastError);
            if (attempt < attempts) {
                sleeper.accept(retryDelayMs);
            }
        }
        log.error("CR-174 run {} 종료 회신을 {}번 보내도 실패. 연결 {} 경로 {}. run 상태는 그대로 둔다",
                runId, attempts, binding.connectionId(), binding.path());
        if (recorder != null) {
            recorder.callbackFailed(runId, binding.connectionId(), binding.path(), attempts, lastError);
        }
    }

    /** 회신 본문. result 는 result_step 단계의 structured_data, 없으면 output 이다. */
    static Map<String, Object> payload(WorkflowRunEntity run, WorkflowEvents.RunCompleted event, Binding binding) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run_id", run.getId() != null ? run.getId().toString() : null);
        payload.put("workflow_id", run.getWorkflowId());
        payload.put("status", event.status());
        payload.put("error", run.getError());
        payload.put("result", resultOf(run, binding.resultStep()));
        payload.put("completed_at", run.getCompletedAt() != null ? run.getCompletedAt().toInstant().toString() : null);
        payload.put("duration_ms", event.durationMs());
        return payload;
    }

    private static Object resultOf(WorkflowRunEntity run, String resultStep) {
        if (resultStep == null || run.getStepResults() == null) {
            return null;
        }
        Object stepResult = run.getStepResults().get(resultStep);
        if (stepResult instanceof Map<?, ?> map) {
            Object structured = map.get("structured_data");
            return structured != null ? structured : map.get("output");
        }
        return stepResult;
    }

    static Binding parse(Map<String, Object> input) {
        if (input == null || !input.containsKey(INPUT_KEY)) {
            return null;
        }
        if (!(input.get(INPUT_KEY) instanceof Map<?, ?> raw)) {
            throw invalid(INPUT_KEY);
        }
        String connectionId = text(raw.get("connection_id"));
        if (connectionId == null || connectionId.isBlank()) {
            throw invalid("connection_id");
        }
        String path = text(raw.get("path"));
        if (path == null || !path.trim().startsWith("/")) {
            throw invalid("path");
        }
        String resultStep = text(raw.get("result_step"));
        return new Binding(connectionId.trim(), path.trim(),
                resultStep != null && !resultStep.isBlank() ? resultStep.trim() : null);
    }

    static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("callback_invalid:" + field);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
