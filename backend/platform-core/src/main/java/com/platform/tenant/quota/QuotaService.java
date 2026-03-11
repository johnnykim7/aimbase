package com.platform.tenant.quota;

import com.platform.domain.master.SubscriptionEntity;
import com.platform.domain.master.TenantUsageSummaryEntity;
import com.platform.domain.master.TenantUsageSummaryEntityId;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.repository.master.TenantUsageSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 테넌트별 쿼터 체크 서비스.
 *
 * LLM 호출, RAG 수집, 워크플로우 생성 전 쿼터를 체크하여
 * 초과 시 QuotaExceededException을 던집니다.
 *
 * 쿼터 기준:
 *   - LLM 토큰: subscriptions.llm_monthly_token_quota vs tenant_usage_summary 합산
 *   - Knowledge Sources: subscriptions.max_knowledge_sources vs 현재 개수
 *   - Workflows: subscriptions.max_workflows vs 현재 개수
 *   - Connections: subscriptions.max_connections vs 현재 개수
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SubscriptionRepository subscriptionRepository;
    private final TenantUsageSummaryRepository usageSummaryRepository;

    public QuotaService(SubscriptionRepository subscriptionRepository,
                         TenantUsageSummaryRepository usageSummaryRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.usageSummaryRepository = usageSummaryRepository;
    }

    /**
     * LLM 토큰 쿼터 체크.
     *
     * @param tenantId 테넌트 ID
     * @param estimatedTokens 예상 토큰 수 (input + output 합산 추정)
     * @throws QuotaExceededException 쿼터 초과 시
     */
    public QuotaCheckResult checkLLMQuota(String tenantId, int estimatedTokens) {
        SubscriptionEntity subscription = getSubscription(tenantId);
        String yearMonth = currentYearMonth();

        long currentUsage = usageSummaryRepository
            .sumTokensByTenantIdAndYearMonth(tenantId, yearMonth)
            .orElse(0L);

        long quota = subscription.getLlmMonthlyTokenQuota();
        long remaining = quota - currentUsage;

        if (remaining < estimatedTokens) {
            log.warn("LLM token quota exceeded for tenant: {} ({}/{} used)", tenantId, currentUsage, quota);
            return QuotaCheckResult.exceeded("LLM monthly token quota exceeded",
                quota, currentUsage, estimatedTokens);
        }

        return QuotaCheckResult.allowed(remaining);
    }

    /**
     * Knowledge Source 개수 쿼터 체크.
     *
     * @param tenantId 테넌트 ID
     * @param currentCount 현재 Knowledge Source 수
     */
    public QuotaCheckResult checkKnowledgeSourceQuota(String tenantId, long currentCount) {
        SubscriptionEntity subscription = getSubscription(tenantId);
        int max = subscription.getMaxKnowledgeSources();

        if (currentCount >= max) {
            return QuotaCheckResult.exceeded("Knowledge source limit reached", max, currentCount, 1);
        }
        return QuotaCheckResult.allowed(max - currentCount);
    }

    /**
     * Workflow 개수 쿼터 체크.
     *
     * @param tenantId 테넌트 ID
     * @param currentCount 현재 Workflow 수
     */
    public QuotaCheckResult checkWorkflowQuota(String tenantId, long currentCount) {
        SubscriptionEntity subscription = getSubscription(tenantId);
        int max = subscription.getMaxWorkflows();

        if (currentCount >= max) {
            return QuotaCheckResult.exceeded("Workflow limit reached", max, currentCount, 1);
        }
        return QuotaCheckResult.allowed(max - currentCount);
    }

    /**
     * Connection 개수 쿼터 체크.
     *
     * @param tenantId 테넌트 ID
     * @param currentCount 현재 Connection 수
     */
    public QuotaCheckResult checkConnectionQuota(String tenantId, long currentCount) {
        SubscriptionEntity subscription = getSubscription(tenantId);
        int max = subscription.getMaxConnections();

        if (currentCount >= max) {
            return QuotaCheckResult.exceeded("Connection limit reached", max, currentCount, 1);
        }
        return QuotaCheckResult.allowed(max - currentCount);
    }

    /**
     * LLM 토큰 사용량 기록 (LLM 응답 완료 후 호출).
     *
     * @param tenantId 테넌트 ID
     * @param inputTokens 실제 input 토큰 수
     * @param outputTokens 실제 output 토큰 수
     */
    @Transactional(transactionManager = "masterTransactionManager")
    public void recordLLMUsage(String tenantId, int inputTokens, int outputTokens) {
        String yearMonth = currentYearMonth();
        TenantUsageSummaryEntityId pk = new TenantUsageSummaryEntityId(tenantId, yearMonth);

        TenantUsageSummaryEntity summary = usageSummaryRepository.findById(pk)
            .orElseGet(() -> {
                TenantUsageSummaryEntity newSummary = new TenantUsageSummaryEntity();
                newSummary.setPk(pk);
                return newSummary;
            });

        summary.setTotalInputTokens(summary.getTotalInputTokens() + inputTokens);
        summary.setTotalOutputTokens(summary.getTotalOutputTokens() + outputTokens);
        summary.setApiCallCount(summary.getApiCallCount() + 1);
        usageSummaryRepository.save(summary);
    }

    // ─── Private Helpers ───────────────────────────────────────────────

    private SubscriptionEntity getSubscription(String tenantId) {
        return subscriptionRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new IllegalStateException("No subscription found for tenant: " + tenantId));
    }

    private String currentYearMonth() {
        return LocalDate.now().format(YEAR_MONTH_FMT);
    }
}
