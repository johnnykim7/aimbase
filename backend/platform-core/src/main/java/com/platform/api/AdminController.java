package com.platform.api;

import com.platform.domain.ModelPricingEntity;
import com.platform.domain.PendingApprovalEntity;
import com.platform.monitoring.TraceService;
import com.platform.repository.*;
import com.platform.workflow.WorkflowEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "관리 및 모니터링 API")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ActionLogRepository actionLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final UsageLogRepository usageLogRepository;
    private final PendingApprovalRepository pendingApprovalRepository;
    private final ConnectionRepository connectionRepository;
    private final ModelPricingRepository modelPricingRepository;
    private final WorkflowEngine workflowEngine;
    private final TraceService traceService;

    public AdminController(ActionLogRepository actionLogRepository,
                            AuditLogRepository auditLogRepository,
                            UsageLogRepository usageLogRepository,
                            PendingApprovalRepository pendingApprovalRepository,
                            ConnectionRepository connectionRepository,
                            ModelPricingRepository modelPricingRepository,
                            WorkflowEngine workflowEngine,
                            TraceService traceService) {
        this.actionLogRepository = actionLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.usageLogRepository = usageLogRepository;
        this.pendingApprovalRepository = pendingApprovalRepository;
        this.connectionRepository = connectionRepository;
        this.modelPricingRepository = modelPricingRepository;
        this.workflowEngine = workflowEngine;
        this.traceService = traceService;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "대시보드 통계")
    public ApiResponse<Map<String, Object>> dashboard() {
        OffsetDateTime since = OffsetDateTime.now().minusDays(1);
        BigDecimal costToday = usageLogRepository.sumCostUsdSince(since);
        Long tokensToday = usageLogRepository.sumTokensSince(since);
        long pendingCount = pendingApprovalRepository.findByStatusOrderByRequestedAtDesc(
                "pending", PageRequest.of(0, 1)).getTotalElements();
        long activeConnections = connectionRepository.findByStatus("connected").size();

        return ApiResponse.ok(Map.of(
                "cost_today_usd", costToday != null ? costToday : BigDecimal.ZERO,
                "tokens_today", tokensToday != null ? tokensToday : 0L,
                "pending_approvals", pendingCount,
                "active_connections", activeConnections
        ));
    }

    @GetMapping("/action-logs")
    @Operation(summary = "액션 실행 로그")
    public ApiResponse<?> actionLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ApiResponse.page(actionLogRepository.findAllByOrderByExecutedAtDesc(PageRequest.of(page, size)));
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "감사 로그")
    public ApiResponse<?> auditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ApiResponse.page(auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)));
    }

    @GetMapping("/usage")
    @Operation(summary = "사용량/비용 통계")
    public ApiResponse<?> usage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ApiResponse.page(usageLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)));
    }

    @GetMapping("/traces")
    @Operation(summary = "LLM 트레이스 조회", description = "PRD-112: LLM 호출 트레이스 조회. session_id, model, from/to 필터 지원.")
    public ApiResponse<?> traces(
            @RequestParam(value = "session_id", required = false) String sessionId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        var pageable = PageRequest.of(page, size);
        if (sessionId != null && !sessionId.isBlank()) {
            return ApiResponse.page(traceService.findBySessionId(sessionId, pageable));
        }
        if (model != null && !model.isBlank()) {
            return ApiResponse.page(traceService.findByModel(model, pageable));
        }
        if (from != null && to != null) {
            return ApiResponse.page(traceService.findByDateRange(from, to, pageable));
        }
        return ApiResponse.page(traceService.findAll(pageable));
    }

    @GetMapping("/approvals")
    @Operation(summary = "승인 대기 목록")
    public ApiResponse<?> approvals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(pendingApprovalRepository.findByStatusOrderByRequestedAtDesc("pending", PageRequest.of(page, size)));
    }

    @PostMapping("/approvals/{id}/approve")
    @Operation(summary = "승인 — approvalChannel=workflow 이면 WorkflowEngine.resume() 호출")
    public ApiResponse<PendingApprovalEntity> approve(@PathVariable UUID id,
                                                        @RequestBody(required = false) Map<String, String> body) {
        PendingApprovalEntity approval = pendingApprovalRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String reason = body != null ? body.get("reason") : null;
        approval.setStatus("approved");
        approval.setApprovedBy(body != null ? body.get("approved_by") : null);
        approval.setReason(reason);
        approval.setResolvedAt(OffsetDateTime.now());
        pendingApprovalRepository.save(approval);

        // 워크플로우 HUMAN_INPUT 승인 콜백
        resumeWorkflowIfNeeded(approval, true, reason);

        return ApiResponse.ok(approval);
    }

    @PostMapping("/approvals/{id}/reject")
    @Operation(summary = "거부 — approvalChannel=workflow 이면 WorkflowEngine.resume() 호출")
    public ApiResponse<PendingApprovalEntity> reject(@PathVariable UUID id,
                                                       @RequestBody(required = false) Map<String, String> body) {
        PendingApprovalEntity approval = pendingApprovalRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String reason = body != null ? body.get("reason") : null;
        approval.setStatus("rejected");
        approval.setApprovedBy(body != null ? body.get("rejected_by") : null);
        approval.setReason(reason);
        approval.setResolvedAt(OffsetDateTime.now());
        pendingApprovalRepository.save(approval);

        // 워크플로우 HUMAN_INPUT 거부 콜백
        resumeWorkflowIfNeeded(approval, false, reason);

        return ApiResponse.ok(approval);
    }

    @GetMapping("/cost-breakdown")
    @Operation(summary = "모델별 비용 집계", description = "PRD-109: 모델별 토큰 사용량 및 비용 집계")
    public ApiResponse<List<Map<String, Object>>> costBreakdown(
            @RequestParam(defaultValue = "30") int days
    ) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Object[]> rows = usageLogRepository.aggregateByModel(since);

        // 모델명 → 단가 매핑
        Map<String, ModelPricingEntity> pricingMap = new HashMap<>();
        modelPricingRepository.findByIsActiveTrue()
                .forEach(p -> pricingMap.put(p.getModelName(), p));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            String model = (String) row[0];
            long totalInput = ((Number) row[1]).longValue();
            long totalOutput = ((Number) row[2]).longValue();

            BigDecimal costUsd = BigDecimal.ZERO;
            String provider = "unknown";
            ModelPricingEntity pricing = pricingMap.get(model);
            if (pricing != null) {
                provider = pricing.getProvider();
                // price_per_1k → price_per_token = price / 1000
                BigDecimal inputCost = pricing.getInputPricePer1k()
                        .multiply(BigDecimal.valueOf(totalInput))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
                BigDecimal outputCost = pricing.getOutputPricePer1k()
                        .multiply(BigDecimal.valueOf(totalOutput))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
                costUsd = inputCost.add(outputCost);
            }

            result.add(Map.of(
                    "model", model,
                    "provider", provider,
                    "total_input_tokens", totalInput,
                    "total_output_tokens", totalOutput,
                    "total_cost_usd", costUsd
            ));
        }

        return ApiResponse.ok(result);
    }

    @GetMapping("/cost-trend")
    @Operation(summary = "일별 비용 추이", description = "PRD-110: 일별/모델별 토큰 사용량 및 비용 추이")
    public ApiResponse<List<Map<String, Object>>> costTrend(
            @RequestParam(defaultValue = "30") int days
    ) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Object[]> rows = usageLogRepository.aggregateByDateAndModel(since);

        Map<String, ModelPricingEntity> pricingMap = new HashMap<>();
        modelPricingRepository.findByIsActiveTrue()
                .forEach(p -> pricingMap.put(p.getModelName(), p));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            String date = row[0].toString();
            String model = (String) row[1];
            long inputTokens = ((Number) row[2]).longValue();
            long outputTokens = ((Number) row[3]).longValue();

            BigDecimal costUsd = BigDecimal.ZERO;
            ModelPricingEntity pricing = pricingMap.get(model);
            if (pricing != null) {
                BigDecimal inputCost = pricing.getInputPricePer1k()
                        .multiply(BigDecimal.valueOf(inputTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
                BigDecimal outputCost = pricing.getOutputPricePer1k()
                        .multiply(BigDecimal.valueOf(outputTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
                costUsd = inputCost.add(outputCost);
            }

            result.add(Map.of(
                    "date", date,
                    "model", model,
                    "input_tokens", inputTokens,
                    "output_tokens", outputTokens,
                    "cost_usd", costUsd
            ));
        }

        return ApiResponse.ok(result);
    }

    /**
     * approvalChannel = "workflow" 이면 WorkflowEngine.resume() 호출.
     * actionLogId 필드에 workflowRunId가 저장됨 (WorkflowEngine.handleHumanInput() 패턴).
     */
    private void resumeWorkflowIfNeeded(PendingApprovalEntity approval, boolean approved, String reason) {
        if (!"workflow".equals(approval.getApprovalChannel())) return;
        if (approval.getActionLogId() == null) return;

        try {
            workflowEngine.resume(approval.getActionLogId(), approved, reason);
            log.info("WorkflowEngine.resume({}, approved={}) triggered", approval.getActionLogId(), approved);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 이미 완료/취소된 run이면 무시 (멱등성)
            log.warn("WorkflowEngine.resume({}) ignored: {}", approval.getActionLogId(), e.getMessage());
        }
    }
}
