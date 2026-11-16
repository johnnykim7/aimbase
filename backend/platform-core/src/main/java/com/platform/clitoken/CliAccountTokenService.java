package com.platform.clitoken;

import com.platform.auth.UserPrincipal;
import com.platform.domain.ConnectionEntity;
import com.platform.domain.master.CliAccountTokenEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.ClaudeCliAdapter;
import com.platform.llm.adapter.ClaudeCliRunnerClient;
import com.platform.llm.adapter.ClaudeCliRunnerClient.AccountTokenReply;
import com.platform.policy.AuditLogger;
import com.platform.repository.ConnectionRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.service.AgentEndpoint;
import com.platform.service.AgentRegistryService;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * CR-196 PRD-646, PRD-647: 테넌트 서버 실행기 계정 폴더의 1년 토큰을 실행기에 묻고 넣는다.
 *
 * <p>토큰은 연결이 아니라 실행기 계정 폴더에 붙는다. 그래서 연결을 (agent_name, config_dir) 로 묶어 폴더마다 한 번 묻는다.
 * 넣을 때 받은 값은 실행기에 넘기기만 하고 기록, 감사, DB, 오류 문구 어디에도 남기지 않는다(BIZ-289).
 */
@Service
public class CliAccountTokenService {

    private static final Logger log = LoggerFactory.getLogger(CliAccountTokenService.class);

    /** BIZ-284: 남은 날과 알림 시각을 세는 기준 시간대. */
    public static final String ZONE_ID = "Asia/Seoul";
    static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    public static final String BY_TENANT = "tenant";
    public static final String BY_PLATFORM = "platform";
    public static final String ERR_NOT_SERVER_RUNNER = "account_token_not_server_runner";
    public static final String ERR_OFFLINE = "cli_runner_unreachable:AGENT_OFFLINE:";

    private static final String CLI_ADAPTER = "anthropic-cli";

    /** 같은 실행기 계정 폴더를 쓰는 연결의 묶음 열쇠. */
    public record Folder(String agentName, String configDir) {}

    private final ConnectionRepository connections;
    private final AgentRegistryService agents;
    private final ClaudeCliRunnerClient runner;
    private final CliAccountTokenStore store;
    private final TenantRepository tenants;
    private final AuditLogger audit;
    private final Duration queryTimeout;
    private final Clock clock;

    @Autowired
    public CliAccountTokenService(ConnectionRepository connections, AgentRegistryService agents,
                                  ClaudeCliRunnerClient runner, CliAccountTokenStore store,
                                  TenantRepository tenants, AuditLogger audit,
                                  @Value("${aimbase.cli-token.query-timeout:5s}") Duration queryTimeout) {
        this(connections, agents, runner, store, tenants, audit, queryTimeout, Clock.system(ZONE));
    }

    CliAccountTokenService(ConnectionRepository connections, AgentRegistryService agents,
                           ClaudeCliRunnerClient runner, CliAccountTokenStore store,
                           TenantRepository tenants, AuditLogger audit, Duration queryTimeout, Clock clock) {
        this.connections = connections;
        this.agents = agents;
        this.runner = runner;
        this.store = store;
        this.tenants = tenants;
        this.audit = audit;
        this.queryTimeout = queryTimeout;
        this.clock = clock;
    }

    /** 4.3-1: 이 테넌트의 실행기 계정 폴더마다 묻고 Master 에 적는다. 연결이 없어진 폴더의 줄은 지운다. */
    public List<TokenRow> listCurrentTenant() {
        String tenantId = TenantContext.getTenantId();
        Map<Folder, List<ConnectionEntity>> groups = group(connections.findAll());
        List<CliAccountTokenStore.FolderState> states = probeAll(groups);
        List<CliAccountTokenEntity> saved = store.saveTenant(tenantId, states, true, OffsetDateTime.now(clock));
        return saved.stream().map(e -> toRow(e, null, false)).toList();
    }

    /** 4.3-2: 그 연결이 쓰는 계정 폴더 하나. 실행기가 꺼져 있으면 앞서 확인한 값을 reachable=false 로 준다. */
    public TokenRow getForConnection(String connectionId) {
        ConnectionEntity conn = findConnection(connectionId);
        Folder folder = requireServerRunner(conn);
        Map<Folder, List<ConnectionEntity>> one = Map.of(folder, sameFolder(folder));
        List<CliAccountTokenStore.FolderState> states = probeAll(one);
        CliAccountTokenEntity saved = store.saveTenant(TenantContext.getTenantId(), states, false,
                OffsetDateTime.now(clock)).get(0);
        return toRow(saved, null, false);
    }

    /**
     * 4.3-3: 그 연결의 실행기에 토큰을 넘겨 계정 폴더에 적게 한다. 임퍼소네이션한 플랫폼 관리자면 by=platform 이다.
     *
     * @param token 화면에서 받은 값. 실행기에 넘기는 것 말고는 쓰지 않는다
     */
    public TokenRow putForConnection(String connectionId, String token, UserPrincipal principal, String ip) {
        ConnectionEntity conn = findConnection(connectionId);
        Folder folder = requireServerRunner(conn);
        List<ConnectionEntity> members = sameFolder(folder);
        AgentEndpoint endpoint = agents.resolveActiveByAgentName(folder.agentName())
                .orElseThrow(() -> new CliTokenApiException(HttpStatus.SERVICE_UNAVAILABLE, ERR_OFFLINE));
        String by = principal != null && principal.isImpersonating() ? BY_PLATFORM : BY_TENANT;

        AccountTokenReply reply;
        try {
            reply = runner.putAccountToken(endpoint, folder.configDir(), token, by, runnerKey(members));
        } catch (IOException e) {
            String reason = ClaudeCliAdapter.classifyRunnerFailure(e);
            log.warn("[CR-196] 1년 토큰 넣기 실행기 호출 실패: agent={}, dir={}, {}", folder.agentName(),
                    folder.configDir(), reason);
            throw new CliTokenApiException(HttpStatus.SERVICE_UNAVAILABLE, "cli_runner_unreachable:" + reason + ":");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliTokenApiException(HttpStatus.SERVICE_UNAVAILABLE, "cli_runner_unreachable:INTERRUPTED:");
        }
        if (!reply.ok()) {
            String code = reply.error();
            log.warn("[CR-196] 1년 토큰 넣기 거절: agent={}, dir={}, status={}, code={}", folder.agentName(),
                    folder.configDir(), reply.status(), code);
            if ((reply.status() == 400 || reply.status() == 409) && code != null) {
                throw new CliTokenApiException(HttpStatus.valueOf(reply.status()), code);
            }
            throw new CliTokenApiException(HttpStatus.BAD_GATEWAY, "cli_runner_error:" + reply.status());
        }

        CliAccountTokenStore.FolderState state = stateOf(folder, members, reply.body());
        CliAccountTokenEntity saved = store.saveTenant(TenantContext.getTenantId(), List.of(state), false,
                OffsetDateTime.now(clock)).get(0);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("by", by);
        detail.put("actor", principal != null ? principal.getEmail() : null);
        detail.put("agent_name", folder.agentName());
        detail.put("config_dir", folder.configDir());
        detail.put("expires_at", saved.getExpiresAt() != null ? saved.getExpiresAt().toString() : null);
        audit.log("cli_account_token_put", "connection:" + connectionId,
                principal != null ? principal.getId() : null, null, detail, ip);
        log.info("[CR-196] 1년 토큰 넣음: connection={}, agent={}, dir={}, by={}, expires_at={}", connectionId,
                folder.agentName(), folder.configDir(), by, saved.getExpiresAt());
        return toRow(saved, null, false);
    }

    /** 4.4-1: Master 에 적힌 모든 테넌트의 줄. 실행기에 묻지 않는다(BIZ-024). */
    public List<TokenRow> listAllTenants() {
        Map<String, String> names = new LinkedHashMap<>();
        tenants.findAll().forEach(t -> names.put(t.getId(), t.getName()));
        return store.findAll().stream()
                .sorted(Comparator.comparing(CliAccountTokenEntity::getTenantId)
                        .thenComparing(CliAccountTokenEntity::getAgentName)
                        .thenComparing(CliAccountTokenEntity::getConfigDir))
                .map(e -> toRow(e, names.get(e.getTenantId()), true))
                .toList();
    }

    /** Asia/Seoul 날짜로 센 남은 날. 오늘이 기한 날이면 0, 지났으면 음수다. */
    long daysLeft(OffsetDateTime expiresAt) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        return ChronoUnit.DAYS.between(today, expiresAt.atZoneSameInstant(ZONE).toLocalDate());
    }

    /** 서버 실행기 연결이면 그 계정 폴더. 사용자 PC 실행기(agent_name 없음)와 CLI 아닌 연결은 빈 값이다. */
    public static Optional<Folder> folderOf(ConnectionEntity c) {
        if (c == null || !CLI_ADAPTER.equals(ConnectionAdapterFactory.normalizeAdapterType(c.getAdapter()))) {
            return Optional.empty();
        }
        Map<String, Object> cfg = c.getConfig();
        if (cfg == null) return Optional.empty();
        String agent = text(cfg.get("agent_name"));
        if (agent.isEmpty()) return Optional.empty();
        return Optional.of(new Folder(agent, text(cfg.get("config_dir"))));
    }

    static Map<Folder, List<ConnectionEntity>> group(List<ConnectionEntity> all) {
        Map<Folder, List<ConnectionEntity>> groups = new LinkedHashMap<>();
        for (ConnectionEntity c : all) {
            folderOf(c).ifPresent(f -> groups.computeIfAbsent(f, k -> new ArrayList<>()).add(c));
        }
        return groups;
    }

    /**
     * 폴더마다 실행기에 함께 묻는다. 실행기 주소는 테넌트 DB 에서 찾으므로 이 스레드에서 먼저 정하고,
     * DB 를 쓰지 않는 HTTP 호출만 따로 띄운다.
     */
    private List<CliAccountTokenStore.FolderState> probeAll(Map<Folder, List<ConnectionEntity>> groups) {
        List<Folder> folders = new ArrayList<>(groups.keySet());
        List<Optional<AgentEndpoint>> endpoints = folders.stream()
                .map(f -> agents.resolveActiveByAgentName(f.agentName())).toList();
        List<CliAccountTokenStore.FolderState> states = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> answers = new ArrayList<>();
            for (int i = 0; i < folders.size(); i++) {
                Folder f = folders.get(i);
                Optional<AgentEndpoint> ep = endpoints.get(i);
                String key = runnerKey(groups.get(f));
                answers.add(ep.isEmpty() ? null : pool.submit(() -> ask(ep.get(), f, key)));
            }
            for (int i = 0; i < folders.size(); i++) {
                Folder f = folders.get(i);
                Map<String, Object> body = answers.get(i) == null ? null : await(answers.get(i), f);
                states.add(body == null ? unreachable(f, groups.get(f)) : stateOf(f, groups.get(f), body));
            }
        }
        return states;
    }

    private Map<String, Object> ask(AgentEndpoint ep, Folder f, String key) throws IOException, InterruptedException {
        AccountTokenReply reply = runner.accountToken(ep, f.configDir(), key, queryTimeout);
        if (!reply.ok()) {
            log.warn("[CR-196] 1년 토큰 조회 응답 {}: agent={}, dir={}, code={}", reply.status(), f.agentName(),
                    f.configDir(), reply.error());
            return null;
        }
        return reply.body();
    }

    private Map<String, Object> await(Future<Map<String, Object>> answer, Folder f) {
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("[CR-196] 1년 토큰 조회 실패: agent={}, dir={}, {}", f.agentName(), f.configDir(),
                    ClaudeCliAdapter.classifyRunnerFailure(e.getCause() != null ? e.getCause() : e));
            return null;
        }
    }

    private static CliAccountTokenStore.FolderState stateOf(Folder f, List<ConnectionEntity> members,
                                                           Map<String, Object> body) {
        String managedBy = BY_PLATFORM.equals(text(body.get("managed_by"))) ? BY_PLATFORM : BY_TENANT;
        return new CliAccountTokenStore.FolderState(f.agentName(), f.configDir(), ids(members), true, managedBy,
                Boolean.TRUE.equals(body.get("present")), time(body.get("issued_at")), time(body.get("expires_at")));
    }

    private static CliAccountTokenStore.FolderState unreachable(Folder f, List<ConnectionEntity> members) {
        return new CliAccountTokenStore.FolderState(f.agentName(), f.configDir(), ids(members), false, BY_TENANT,
                false, null, null);
    }

    private TokenRow toRow(CliAccountTokenEntity e, String tenantName, boolean platform) {
        return new TokenRow(
                platform ? e.getTenantId() : null,
                platform ? tenantName : null,
                e.getAgentName(), e.getConfigDir(), e.getManagedBy(), e.isPresent(),
                seoul(e.getIssuedAt()), seoul(e.getExpiresAt()),
                e.isPresent() && e.getExpiresAt() != null ? daysLeft(e.getExpiresAt()) : null,
                e.isReachable(), e.getConnectionIds(),
                platform ? seoul(e.getCheckedAt()) : null);
    }

    private ConnectionEntity findConnection(String id) {
        return connections.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found: " + id));
    }

    private static Folder requireServerRunner(ConnectionEntity conn) {
        return folderOf(conn)
                .orElseThrow(() -> new CliTokenApiException(HttpStatus.BAD_REQUEST, ERR_NOT_SERVER_RUNNER));
    }

    private List<ConnectionEntity> sameFolder(Folder folder) {
        return group(connections.findAll()).getOrDefault(folder, List.of());
    }

    /** 같은 폴더 연결 가운데 실행기 호출 키를 적은 첫 연결의 키. */
    private static String runnerKey(List<ConnectionEntity> members) {
        for (ConnectionEntity c : members) {
            String k = c.getConfig() != null ? text(c.getConfig().get("runner_api_key")) : "";
            if (!k.isEmpty()) return k;
        }
        return null;
    }

    private static List<String> ids(List<ConnectionEntity> members) {
        return members.stream().map(ConnectionEntity::getId).sorted().toList();
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    private static OffsetDateTime time(Object o) {
        String s = text(o);
        if (s.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String seoul(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toOffsetDateTime().toString();
    }
}
