package com.platform.monitoring;

import com.platform.domain.TraceEntity;
import com.platform.repository.TraceRepository;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * LLM 트레이싱 서비스 (PRD-112~113).
 * LLM 호출의 입력/출력/토큰/지연시간을 비동기 기록.
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    private final TraceRepository traceRepository;

    public TraceService(TraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    /**
     * LLM 호출 트레이스를 비동기 저장 (Virtual Thread).
     * 호출 실패가 메인 플로우에 영향을 주지 않도록 내부에서 예외 처리.
     */
    public void record(String sessionId, String model, Object messagesIn,
                       Map<String, Object> response, int inputTokens, int outputTokens,
                       int latencyMs) {
        String tenantId = TenantContext.getTenantId();
        Thread.ofVirtual()
                .name("trace-record-" + UUID.randomUUID().toString().substring(0, 8))
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        TraceEntity trace = new TraceEntity();
                        trace.setTraceId(UUID.randomUUID().toString());
                        trace.setSessionId(sessionId);
                        trace.setModel(model);
                        trace.setMessagesIn(messagesIn);
                        trace.setResponse(response);
                        trace.setInputTokens(inputTokens);
                        trace.setOutputTokens(outputTokens);
                        trace.setLatencyMs(latencyMs);

                        // 비용 계산 (usage log와 동일한 방식으로 저장, 정확한 값은 호출 측에서 제공 가능)
                        trace.setCostUsd(BigDecimal.ZERO);

                        traceRepository.save(trace);
                        log.debug("Trace recorded: session={}, model={}, latency={}ms, tokens={}/{}",
                                sessionId, model, latencyMs, inputTokens, outputTokens);
                    } catch (Exception e) {
                        log.warn("Failed to record trace: {}", e.getMessage());
                    } finally {
                        TenantContext.clear();
                    }
                });
    }

    /**
     * costUsd를 포함한 트레이스 기록.
     */
    public void record(String sessionId, String model, Object messagesIn,
                       Map<String, Object> response, int inputTokens, int outputTokens,
                       int latencyMs, double costUsd) {
        String tenantId = TenantContext.getTenantId();
        Thread.ofVirtual()
                .name("trace-record-" + UUID.randomUUID().toString().substring(0, 8))
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        TraceEntity trace = new TraceEntity();
                        trace.setTraceId(UUID.randomUUID().toString());
                        trace.setSessionId(sessionId);
                        trace.setModel(model);
                        trace.setMessagesIn(messagesIn);
                        trace.setResponse(response);
                        trace.setInputTokens(inputTokens);
                        trace.setOutputTokens(outputTokens);
                        trace.setLatencyMs(latencyMs);
                        trace.setCostUsd(BigDecimal.valueOf(costUsd));

                        traceRepository.save(trace);
                        log.debug("Trace recorded: session={}, model={}, latency={}ms, cost={}",
                                sessionId, model, latencyMs, costUsd);
                    } catch (Exception e) {
                        log.warn("Failed to record trace: {}", e.getMessage());
                    } finally {
                        TenantContext.clear();
                    }
                });
    }

    // ─── 조회 API ───

    public Page<TraceEntity> findAll(Pageable pageable) {
        return traceRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<TraceEntity> findBySessionId(String sessionId, Pageable pageable) {
        return traceRepository.findBySessionId(sessionId, pageable);
    }

    public Page<TraceEntity> findByModel(String model, Pageable pageable) {
        return traceRepository.findByModel(model, pageable);
    }

    public Page<TraceEntity> findByDateRange(OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        return traceRepository.findByCreatedAtBetween(from, to, pageable);
    }
}
