package com.platform.llm;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionGroupEntity;
import com.platform.orchestrator.GenericCircuitBreaker;
import com.platform.repository.ConnectionGroupRepository;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 커넥션 그룹 선택기 (PRD-132, PRD-133, CR-161 PRD-347).
 *
 * 그룹 전략(PRIORITY / ROUND_ROBIN / LEAST_USED)에 따라 멤버 커넥션을 고르고, 고른 커넥션으로
 * 호출측의 단일 커넥션 처리를 실행한다. 넘길 실패(BIZ-128)면 다음 멤버로 다시 실행한다.
 *
 * 같은 고정 키(채팅 세션, 워크플로우 run, AGENT_CALL 자식 세션)는 앞서 성공한 멤버로 보낸다(BIZ-127).
 * 실행기 CLI 프로세스가 그 키 단위로 이어지므로, 고정이 없으면 기록상 커넥션과 실제 계정이 어긋난다.
 *
 * 차례, 사용 횟수, 서킷 브레이커, 고정은 테넌트별로 센다. 그룹 id 는 테넌트 DB 마다 따로 매겨진다.
 */
@Component
public class ConnectionGroupSelector {

    private static final Logger log = LoggerFactory.getLogger(ConnectionGroupSelector.class);

    /** cli-runner ClaudeCliWorker.ACCOUNT_LIMITED_PREFIX 와 같은 값. platform-core 는 cli-runner 를 의존하지 않는다. */
    public static final String ACCOUNT_LIMITED_PREFIX = "cli_account_limited:";
    public static final String GROUP_UNAVAILABLE_PREFIX = "connection_group_unavailable:";

    private static final Pattern RESETS_AT = Pattern.compile("cli_account_limited:resets_at=(\\d+)");
    /** 실행기에 닿지 못한 실패. 요청이 간 뒤 끊긴 것(AGENT_TIMEOUT, TURN_BROKEN_PIPE)은 다른 계정에서 같은 일을 또 하게 되므로 넘기지 않는다. */
    private static final List<String> CLI_UNREACHED_MARKERS = List.of(
            "cli_agent_offline:", "cli_routing_missing",
            "cli_runner_unreachable:AGENT_OFFLINE:", "cli_runner_unreachable:TURN_RELAY_DEAD:");

    private static final int CB_FAILURE_THRESHOLD = 3;
    private static final long CB_OPEN_DURATION_MS = 5 * 60 * 1000L;
    private static final long BASE_DELAY_MS = 500;
    private static final long PIN_SWEEP_INTERVAL_MS = 10 * 60 * 1000L;

    private final ConnectionGroupRepository groupRepository;
    private final ConnectionAdapterFactory adapterFactory;
    private final PlatformSettingsService platformSettings;
    private final Clock clock;

    private final ConcurrentHashMap<String, GenericCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> usageCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> roundRobinCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pin> pins = new ConcurrentHashMap<>();
    private volatile long lastPinSweepAt;

    private enum Failure { ACCOUNT_LIMITED, UNREACHED, OTHER }

    private static final class Pin {
        final String connectionId;
        volatile long lastUsedAt;
        Pin(String connectionId, long now) {
            this.connectionId = connectionId;
            this.lastUsedAt = now;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConnectionGroupSelector(ConnectionGroupRepository groupRepository,
                                    ConnectionAdapterFactory adapterFactory,
                                    PlatformSettingsService platformSettings) {
        this(groupRepository, adapterFactory, platformSettings, Clock.systemUTC());
    }

    ConnectionGroupSelector(ConnectionGroupRepository groupRepository,
                            ConnectionAdapterFactory adapterFactory,
                            PlatformSettingsService platformSettings,
                            Clock clock) {
        this.groupRepository = groupRepository;
        this.adapterFactory = adapterFactory;
        this.platformSettings = platformSettings;
        this.clock = clock;
        this.lastPinSweepAt = clock.millis();
    }

    public <T> T execute(String groupId, String pinKey, Function<String, T> call) {
        return execute(groupId, pinKey, call, () -> true);
    }

    /**
     * 그룹에서 멤버를 골라 {@code call} 을 실행한다.
     *
     * @param pinKey          같은 멤버로 보낼 단위. null 이면 고정 없이 전략 순서
     * @param call            커넥션 id 하나로 한 번 실행하는 단일 커넥션 처리
     * @param failoverAllowed 실패 시점에 다음 멤버로 넘겨도 되는지. 실시간 채팅은 답 이벤트를 보낸 뒤 false
     */
    public <T> T execute(String groupId, String pinKey, Function<String, T> call, BooleanSupplier failoverAllowed) {
        String tenant = tenantKey();
        ConnectionGroupEntity group = loadGroup(groupId);
        sweepPinsIfDue();
        List<String> candidates = candidates(tenant, group, pinKey);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Connection group '" + groupId + "' has no members");
        }

        RuntimeException last = null;
        int attempt = 0;
        for (String connectionId : candidates) {
            GenericCircuitBreaker cb = getCircuitBreaker(tenant, connectionId);
            if (!cb.allowRequest()) {
                log.info("Connection group '{}' member '{}' unavailable until {}, skipping",
                        groupId, connectionId, cb.getOpenUntil());
                continue;
            }
            if (attempt > 0) backoff(attempt);
            attempt++;
            try {
                T result = call.apply(connectionId);
                cb.recordSuccess();
                incrementUsage(tenant, connectionId);
                pin(tenant, groupId, pinKey, connectionId);
                log.info("Connection group '{}' member '{}' succeeded (attempt {})", groupId, connectionId, attempt);
                return result;
            } catch (RuntimeException e) {
                Failure failure = classify(connectionId, e);
                if (failure == Failure.OTHER) {
                    throw e;
                }
                if (failure == Failure.ACCOUNT_LIMITED) {
                    Instant until = parseResetsAt(e);
                    cb.openUntil(until != null ? until : clock.instant().plusMillis(CB_OPEN_DURATION_MS));
                } else {
                    cb.recordFailure();
                }
                unpin(tenant, groupId, pinKey, connectionId);
                log.warn("Connection group '{}' member '{}' failed with {} (attempt {}): {}",
                        groupId, connectionId, failure, attempt, e.getMessage());
                if (!failoverAllowed.getAsBoolean()) {
                    throw e;
                }
                last = e;
            }
        }

        Instant retryAfter = earliestAvailable(tenant, candidates);
        throw new IllegalStateException(GROUP_UNAVAILABLE_PREFIX + "retry_after=" + retryAfter
                + ": no available member in group '" + groupId + "'"
                + (last != null ? ". Last error: " + last.getMessage() : ""), last);
    }

    /** 그룹의 멤버 커넥션 id. 어느 멤버가 돌지 미리 모르는 곳(강제 취소용 실행기 정리 등록)이 쓴다. */
    public List<String> memberConnectionIds(String groupId) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> m : loadGroup(groupId).getMembers()) {
            Object id = m.get("connection_id");
            if (id instanceof String s && !s.isBlank()) ids.add(s);
        }
        return ids;
    }

    /** 고정 멤버가 아직 고를 수 있으면 맨 앞, 나머지는 전략 순서. */
    private List<String> candidates(String tenant, ConnectionGroupEntity group, String pinKey) {
        List<String> ordered = new ArrayList<>();
        for (Map<String, Object> m : getOrderedMembers(tenant, group)) {
            Object id = m.get("connection_id");
            if (id instanceof String s && !s.isBlank()) ordered.add(s);
        }
        Pin pin = pinKey != null ? pins.get(pinMapKey(tenant, group.getId(), pinKey)) : null;
        if (pin != null && ordered.remove(pin.connectionId)) {
            ordered.add(0, pin.connectionId);
        }
        return ordered;
    }

    private Failure classify(String connectionId, Throwable e) {
        String chain = messageChain(e);
        if (chain.contains(ACCOUNT_LIMITED_PREFIX)) return Failure.ACCOUNT_LIMITED;
        if (!adapterFactory.isCliConnection(connectionId)) return Failure.UNREACHED; // CR-015: API 멤버는 모든 실패에서 넘긴다
        for (String marker : CLI_UNREACHED_MARKERS) {
            if (chain.contains(marker)) return Failure.UNREACHED;
        }
        return Failure.OTHER;
    }

    /** 실시간 경로는 실행기 오류가 cli_runner_unreachable 와 "LLM call failed" 로 겹겹이 감싸여 오므로 사슬 전체를 본다. */
    private static String messageChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.getMessage() != null) sb.append(cur.getMessage()).append('\n');
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private Instant parseResetsAt(Throwable e) {
        Matcher m = RESETS_AT.matcher(messageChain(e));
        if (!m.find()) return null;
        try {
            Instant t = Instant.ofEpochSecond(Long.parseLong(m.group(1)));
            return t.isAfter(clock.instant()) ? t : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Instant earliestAvailable(String tenant, List<String> candidates) {
        Instant now = clock.instant();
        Instant earliest = null;
        for (String connectionId : candidates) {
            GenericCircuitBreaker cb = getCircuitBreaker(tenant, connectionId);
            Instant until = cb.getState() == GenericCircuitBreaker.State.OPEN && cb.getOpenUntil() != null
                    ? cb.getOpenUntil() : now;
            if (earliest == null || until.isBefore(earliest)) earliest = until;
        }
        return earliest != null ? earliest : now;
    }

    private static void backoff(int attempt) {
        long delay = BASE_DELAY_MS * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Connection group fallback interrupted", ie);
        }
    }

    private ConnectionGroupEntity loadGroup(String groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Connection group not found: " + groupId));
    }

    /**
     * 전략에 따라 멤버를 정렬하여 시도 순서를 결정한다.
     */
    private List<Map<String, Object>> getOrderedMembers(String tenant, ConnectionGroupEntity group) {
        List<Map<String, Object>> members = new ArrayList<>(group.getMembers());
        if (members.isEmpty()) return members;

        return switch (group.getStrategy().toUpperCase()) {
            case "ROUND_ROBIN" -> orderByRoundRobin(tenant + "|" + group.getId(), members);
            case "LEAST_USED" -> orderByLeastUsed(tenant, members);
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
    private List<Map<String, Object>> orderByRoundRobin(String counterKey, List<Map<String, Object>> members) {
        // priority로 먼저 정렬하여 순서를 안정화
        orderByPriority(members);

        AtomicLong counter = roundRobinCounters.computeIfAbsent(counterKey, k -> new AtomicLong(0));
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
    private List<Map<String, Object>> orderByLeastUsed(String tenant, List<Map<String, Object>> members) {
        members.sort(Comparator.comparingLong(m -> {
            String connId = (String) m.get("connection_id");
            return usage(tenant, connId);
        }));
        return members;
    }

    private void pin(String tenant, String groupId, String pinKey, String connectionId) {
        if (pinKey == null || pinKey.isBlank()) return;
        String key = pinMapKey(tenant, groupId, pinKey);
        Pin existing = pins.get(key);
        if (existing != null && existing.connectionId.equals(connectionId)) {
            existing.lastUsedAt = clock.millis();
        } else {
            pins.put(key, new Pin(connectionId, clock.millis()));
        }
    }

    private void unpin(String tenant, String groupId, String pinKey, String connectionId) {
        if (pinKey == null || pinKey.isBlank()) return;
        pins.computeIfPresent(pinMapKey(tenant, groupId, pinKey),
                (k, p) -> p.connectionId.equals(connectionId) ? null : p);
    }

    /** 고정은 마지막 사용 뒤 세션 TTL(BIZ-002, 런타임 설정 session.session-ttl-hours)이 지나면 지운다. */
    private void sweepPinsIfDue() {
        long now = clock.millis();
        if (now - lastPinSweepAt < PIN_SWEEP_INTERVAL_MS) return;
        lastPinSweepAt = now;
        long ttlMs = Duration.ofHours(platformSettings.getInt("session.session-ttl-hours", 24)).toMillis();
        pins.entrySet().removeIf(e -> now - e.getValue().lastUsedAt > ttlMs);
    }

    private static String pinMapKey(String tenant, String groupId, String pinKey) {
        return tenant + "|" + groupId + "|" + pinKey;
    }

    private static String tenantKey() {
        String tenantId = TenantContext.getTenantId();
        return tenantId != null ? tenantId : "_";
    }

    private void incrementUsage(String tenant, String connectionId) {
        usageCounters.computeIfAbsent(tenant + "|" + connectionId, k -> new AtomicLong(0)).incrementAndGet();
    }

    private long usage(String tenant, String connectionId) {
        AtomicLong counter = usageCounters.get(tenant + "|" + connectionId);
        return counter != null ? counter.get() : 0;
    }

    public long getUsageCount(String connectionId) {
        return usage(tenantKey(), connectionId);
    }

    public String getCircuitBreakerState(String connectionId) {
        GenericCircuitBreaker cb = circuitBreakers.get(tenantKey() + "|" + connectionId);
        return cb != null ? cb.getState().name() : "CLOSED";
    }

    private GenericCircuitBreaker getCircuitBreaker(String tenant, String connectionId) {
        return circuitBreakers.computeIfAbsent(tenant + "|" + connectionId,
                k -> new GenericCircuitBreaker("conn:" + k, CB_FAILURE_THRESHOLD, CB_OPEN_DURATION_MS));
    }

    /**
     * 그룹 변경 시 라운드로빈 카운터와 고정 초기화
     */
    public void evict(String groupId) {
        String prefix = tenantKey() + "|" + groupId;
        roundRobinCounters.remove(prefix);
        pins.keySet().removeIf(k -> k.startsWith(prefix + "|"));
    }

    /**
     * adapter별 기본 그룹을 찾아서 실행한다.
     * 기본 그룹이 없으면 null 반환 (호출측에서 폴백 처리).
     */
    public ConnectionGroupEntity findDefaultGroup(String adapter) {
        return groupRepository.findByAdapterAndIsDefaultTrue(adapter).orElse(null);
    }
}
