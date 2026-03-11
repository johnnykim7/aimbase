package com.platform.action;

import com.platform.action.model.*;
import com.platform.action.notify.NotifyAdapter;
import com.platform.action.write.WriteAdapter;
import com.platform.policy.model.PolicyResult;
import com.platform.repository.ActionLogRepository;
import com.platform.domain.ActionLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final AdapterRegistry adapterRegistry;
    private final ActionLogRepository actionLogRepository;

    public ActionExecutor(AdapterRegistry adapterRegistry, ActionLogRepository actionLogRepository) {
        this.adapterRegistry = adapterRegistry;
        this.actionLogRepository = actionLogRepository;
    }

    /**
     * ActionRequest를 병렬로 실행한다.
     * WRITE_AND_NOTIFY일 때 모든 target을 Virtual Threads로 동시 실행.
     */
    public ActionResult execute(ActionRequest request, PolicyResult policyResult) {
        log.info("Executing action: {} [{}]", request.intent(), request.type());

        List<CompletableFuture<TargetResult>> futures = request.targets().stream()
                .map(target -> CompletableFuture.supplyAsync(
                        () -> executeTarget(request, target),
                        Executors.newVirtualThreadPerTaskExecutor()
                ))
                .toList();

        List<TargetResult> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("Target execution timeout or error", e);
                        return TargetResult.failure(null, e.getMessage());
                    }
                })
                .toList();

        boolean allSuccess = results.stream().allMatch(r -> "success".equals(r.status()));
        ActionResult.ActionStatus status = allSuccess
                ? ActionResult.ActionStatus.SUCCESS
                : ActionResult.ActionStatus.FAILED;

        // 감사 로그 저장
        if (request.policy().auditLog()) {
            saveActionLog(request, policyResult, status, results);
        }

        return new ActionResult(request.id(), status, results, policyResult);
    }

    private TargetResult executeTarget(ActionRequest request, ActionTarget target) {
        try {
            return switch (request.type()) {
                case WRITE -> executeWrite(target, request.payload());
                case NOTIFY -> executeNotify(target, request.payload(), request.intent());
                case WRITE_AND_NOTIFY -> {
                    TargetResult write = executeWrite(target, request.payload());
                    yield write;
                }
            };
        } catch (Exception e) {
            log.error("Target execution failed for adapter {}: {}", target.adapter(), e.getMessage());
            return TargetResult.failure(target, e.getMessage());
        }
    }

    private TargetResult executeWrite(ActionTarget target, ActionPayload payload) {
        WriteAdapter adapter = adapterRegistry.getWriteAdapter(target.adapter());
        WriteResult result = adapter.write(target.destination(), payload.data(), null);
        if (result.success()) {
            return TargetResult.success(target, Map.of(
                    "affectedRows", result.affectedRows() != null ? result.affectedRows() : 0,
                    "recordId", result.recordId() != null ? result.recordId() : ""
            ));
        } else {
            return TargetResult.failure(target, result.error());
        }
    }

    private TargetResult executeNotify(ActionTarget target, ActionPayload payload, String intent) {
        NotifyAdapter adapter = adapterRegistry.getNotifyAdapter(target.adapter());
        NotifyResult result = adapter.publish(target.destination(), payload.data(), Map.of("intent", intent));
        if (result.success()) {
            return TargetResult.success(target, Map.of(
                    "messageId", result.messageId() != null ? result.messageId() : "",
                    "delivered", result.delivered()
            ));
        } else {
            return TargetResult.failure(target, result.error());
        }
    }

    private void saveActionLog(ActionRequest request, PolicyResult policyResult,
                                ActionResult.ActionStatus status, List<TargetResult> results) {
        try {
            ActionTarget firstTarget = request.targets().isEmpty() ? null : request.targets().get(0);
            ActionLogEntity log = new ActionLogEntity();
            log.setSessionId(request.metadata().sessionId());
            log.setIntent(request.intent());
            log.setType(request.type().name().toLowerCase());
            log.setAdapter(firstTarget != null ? firstTarget.adapter() : "unknown");
            log.setDestination(firstTarget != null ? firstTarget.destination() : null);
            log.setPayload(request.payload().data());
            log.setStatus(status.name().toLowerCase());
            actionLogRepository.save(log);
        } catch (Exception e) {
            ActionExecutor.log.warn("Failed to save action log: {}", e.getMessage());
        }
    }
}
