package com.platform.llm;

import com.platform.domain.ConnectionGroupEntity;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.orchestrator.GenericCircuitBreaker;
import com.platform.repository.ConnectionGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 커넥션 그룹 선택기 (PRD-132, PRD-133).
 *
 * 그룹 전략(PRIORITY / ROUND_ROBIN / LEAST_USED)에 따라 커넥션을 선택하고,
 * 실패 시 그룹 내 다음 커넥션으로 자동 폴백한다.
 *
 * 커넥션별 서킷 브레이커와 사용 카운터를 관리한다.
 */
@Component
public class ConnectionGroupSelector {

    private static final Logger log = LoggerFactory.getLogger(ConnectionGroupSelector.class);

    private static final int CB_FAILURE_THRESHOLD = 3;
    private static final long CB_OPEN_DURATION_MS = 5 * 60 * 1000L;
    private static final long BASE_DELAY_MS = 500;

    private final ConnectionGroupRepository groupRepository;
    private final ConnectionAdapterFactory adapterFactory;

    private final ConcurrentHashMap<String, GenericCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> usageCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> roundRobinCounters = new ConcurrentHashMap<>();

    public ConnectionGroupSelector(ConnectionGroupRepository groupRepository,
                                    ConnectionAdapterFactory adapterFactory) {
        this.groupRepository = groupRepository;
        this.adapterFactory = adapterFactory;
    }

    /**
     * 그룹 전략에 따라 커넥션을 선택하고 LLM 호출을 실행한다.
     * 실패 시 그룹 내 다른 커넥션으로 자동 폴백한다.
     */
    public LLMResponse executeWithGroup(String groupId, LLMRequest request) {
        ConnectionGroupEntity group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Connection group not found: " + groupId));

        List<Map<String, Object>> orderedMembers = getOrderedMembers(group);

        if (orderedMembers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Connection group '" + groupId + "' has no members");
        }

        Exception lastException = null;

        for (int i = 0; i < orderedMembers.size(); i++) {
            Map<String, Object> member = orderedMembers.get(i);
            String connectionId = (String) member.get("connection_id");

            GenericCircuitBreaker cb = getCircuitBreaker(connectionId);
            if (!cb.allowRequest()) {
                log.info("Connection '{}' circuit OPEN, skipping", connectionId);
                continue;
            }

            // 폴백 시 지수 백오프 (첫 시도는 대기 없음)
            if (i > 0) {
                long delay = BASE_DELAY_MS * (1L << (i - 1));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Connection group fallback interrupted", ie);
                }
            }

            try {
                LLMAdapter adapter = adapterFactory.getAdapter(connectionId);
                String model = adapterFactory.resolveModel(connectionId, request.model());
                LLMRequest adaptedRequest = request.withModel(model);

                LLMResponse response = adapter.chat(adaptedRequest).get();
                cb.recordSuccess();
                incrementUsage(connectionId);
                log.info("Connection group '{}' → connection '{}' succeeded (attempt {})",
                        groupId, connectionId, i + 1);
                return response;
            } catch (Exception e) {
                cb.recordFailure();
                lastException = e;
                log.warn("Connection group '{}' → connection '{}' failed (attempt {}): {}",
                        groupId, connectionId, i + 1, e.getMessage());
            }
        }

        throw new RuntimeException("All connections in group '" + groupId + "' failed. Last error: "
                + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    /**
     * 전략에 따라 멤버를 정렬하여 시도 순서를 결정한다.
     */
    private List<Map<String, Object>> getOrderedMembers(ConnectionGroupEntity group) {
        List<Map<String, Object>> members = new ArrayList<>(group.getMembers());
        if (members.isEmpty()) return members;

        return switch (group.getStrategy().toUpperCase()) {
            case "ROUND_ROBIN" -> orderByRoundRobin(group.getId(), members);
            case "LEAST_USED" -> orderByLeastUsed(members);
            default -> orderByPriority(members);  // PRIORITY (default)
        };
    }

    /**
     * PRIORITY: priority 필드 오름차순 (낮을수록 먼저)
     */
    private List<Map<String, Object>> orderByPriority(List<Map<String, Object>> members) {
        members.sort(Comparator.comparingInt(m -> {
            Object p = m.get("priority");
            return p instanceof Number ? ((Number) p).intValue() : Integer.MAX_VALUE;
        }));
        return members;
    }

    /**
     * ROUND_ROBIN: 그룹별 카운터로 시작점을 순환
     */
    private List<Map<String, Object>> orderByRoundRobin(String groupId, List<Map<String, Object>> members) {
        // priority로 먼저 정렬하여 순서를 안정화
        orderByPriority(members);

        AtomicLong counter = roundRobinCounters.computeIfAbsent(groupId, k -> new AtomicLong(0));
        int startIndex = (int) (counter.getAndIncrement() % members.size());

        List<Map<String, Object>> rotated = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            rotated.add(members.get((startIndex + i) % members.size()));
        }
        return rotated;
    }

    /**
     * LEAST_USED: 누적 사용량 가장 적은 커넥션 우선
     */
    private List<Map<String, Object>> orderByLeastUsed(List<Map<String, Object>> members) {
        members.sort(Comparator.comparingLong(m -> {
            String connId = (String) m.get("connection_id");
            return getUsageCount(connId);
        }));
        return members;
    }

    private void incrementUsage(String connectionId) {
        usageCounters.computeIfAbsent(connectionId, k -> new AtomicLong(0)).incrementAndGet();
    }

    public long getUsageCount(String connectionId) {
        AtomicLong counter = usageCounters.get(connectionId);
        return counter != null ? counter.get() : 0;
    }

    public String getCircuitBreakerState(String connectionId) {
        GenericCircuitBreaker cb = circuitBreakers.get(connectionId);
        return cb != null ? cb.getState().name() : "CLOSED";
    }

    private GenericCircuitBreaker getCircuitBreaker(String connectionId) {
        return circuitBreakers.computeIfAbsent(connectionId,
                k -> new GenericCircuitBreaker("conn:" + k, CB_FAILURE_THRESHOLD, CB_OPEN_DURATION_MS));
    }

    /**
     * 그룹 변경 시 라운드로빈 카운터 초기화
     */
    public void evict(String groupId) {
        roundRobinCounters.remove(groupId);
    }

    /**
     * 그룹 전략에 따라 최적 커넥션 1개를 선택한다 (스트리밍용 — 폴백 없이).
     * 서킷 브레이커 OPEN인 커넥션은 건너뛴다.
     */
    public String selectConnection(String groupId) {
        ConnectionGroupEntity group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Connection group not found: " + groupId));

        List<Map<String, Object>> orderedMembers = getOrderedMembers(group);

        for (Map<String, Object> member : orderedMembers) {
            String connectionId = (String) member.get("connection_id");
            GenericCircuitBreaker cb = getCircuitBreaker(connectionId);
            if (cb.allowRequest()) {
                incrementUsage(connectionId);
                return connectionId;
            }
        }

        // 모든 커넥션이 OPEN → 첫 번째 멤버라도 반환
        String fallbackId = (String) group.getMembers().get(0).get("connection_id");
        log.warn("All connections in group '{}' have circuit OPEN, falling back to '{}'", groupId, fallbackId);
        return fallbackId;
    }

    /**
     * adapter별 기본 그룹을 찾아서 실행한다.
     * 기본 그룹이 없으면 null 반환 (호출측에서 폴백 처리).
     */
    public ConnectionGroupEntity findDefaultGroup(String adapter) {
        return groupRepository.findByAdapterAndIsDefaultTrue(adapter).orElse(null);
    }
}
