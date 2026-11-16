package com.platform.clitoken;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.platform.auth.UserPrincipal;
import com.platform.domain.ConnectionEntity;
import com.platform.domain.master.CliAccountTokenEntity;
import com.platform.domain.master.TenantEntity;
import com.platform.llm.adapter.ClaudeCliRunnerClient;
import com.platform.llm.adapter.ClaudeCliRunnerClient.AccountTokenReply;
import com.platform.policy.AuditLogger;
import com.platform.repository.ConnectionRepository;
import com.platform.repository.master.CliAccountTokenRepository;
import com.platform.repository.master.CliTokenExpiryNoticeRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.service.AgentEndpoint;
import com.platform.service.AgentRegistryService;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CR-196 TC-TOK-011, 012, 020 ~ 022, 025. */
class CliAccountTokenServiceTest {

    private static final String TOKEN = "sk-ant-oat01-" + "z".repeat(40);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentEndpoint EP_A = new AgentEndpoint("a1", "http://runner-a:8290", "hash");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ConnectionRepository connections;
    private AgentRegistryService agents;
    private ClaudeCliRunnerClient runner;
    private CliAccountTokenRepository tokenRepo;
    private TenantRepository tenantRepo;
    private AuditLogger audit;
    private final List<CliAccountTokenEntity> masterRows = new ArrayList<>();
    private final List<ConnectionEntity> conns = new ArrayList<>();
    private CliAccountTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connections = mock(ConnectionRepository.class);
        agents = mock(AgentRegistryService.class);
        runner = mock(ClaudeCliRunnerClient.class);
        tokenRepo = mock(CliAccountTokenRepository.class);
        tenantRepo = mock(TenantRepository.class);
        audit = mock(AuditLogger.class);

        when(connections.findAll()).thenAnswer(i -> new ArrayList<>(conns));
        when(connections.findById(anyString())).thenAnswer(i -> conns.stream()
                .filter(c -> c.getId().equals(i.getArgument(0))).findFirst());
        when(agents.resolveActiveByAgentName(anyString())).thenReturn(Optional.empty());
        when(agents.resolveActiveByAgentName("runner-a")).thenReturn(Optional.of(EP_A));
        when(tokenRepo.findByTenantId(anyString())).thenAnswer(i -> masterRows.stream()
                .filter(r -> r.getTenantId().equals(i.getArgument(0))).toList());
        when(tokenRepo.save(any())).thenAnswer(i -> {
            CliAccountTokenEntity e = i.getArgument(0);
            if (masterRows.stream().noneMatch(r -> r == e)) masterRows.add(e);
            return e;
        });
        when(tokenRepo.findAll()).thenAnswer(i -> new ArrayList<>(masterRows));
        org.mockito.Mockito.doAnswer(i -> {
            Collection<CliAccountTokenEntity> gone = i.getArgument(0);
            masterRows.removeIf(r -> gone.stream().anyMatch(g -> g == r));
            return null;
        }).when(tokenRepo).deleteAll(any(Iterable.class));

        CliAccountTokenStore store = new CliAccountTokenStore(tokenRepo, mock(CliTokenExpiryNoticeRepository.class));
        service = new CliAccountTokenService(connections, agents, runner, store, tenantRepo, audit, TIMEOUT, CLOCK);
        TenantContext.setTenantId("t1");

        conns.add(cli("c1", "runner-a", "/data/w1", "k1"));
        conns.add(cli("c2", "runner-a", "/data/w1", null));
        conns.add(cli("c3", "runner-off", "/data/w2", "k3"));
        conns.add(cli("c5", null, null, null));
        ConnectionEntity api = new ConnectionEntity();
        api.setId("c4");
        api.setAdapter("anthropic");
        api.setConfig(new HashMap<>(Map.of("agent_name", "runner-a")));
        conns.add(api);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void tc_tok_020_listGroupsFoldersKeepsOfflineValuesAndDropsGoneRows() throws Exception {
        masterRows.add(row("t1", "runner-off", "/data/w2", OffsetDateTime.parse("2027-01-01T00:00:00Z")));
        masterRows.add(row("t1", "gone", "/x", OffsetDateTime.parse("2027-01-01T00:00:00Z")));
        masterRows.add(row("t2", "other", "/y", OffsetDateTime.parse("2027-01-01T00:00:00Z")));
        when(runner.accountToken(EP_A, "/data/w1", "k1", TIMEOUT)).thenReturn(ok("tenant"));

        List<TokenRow> rows = service.listCurrentTenant();

        assertThat(rows).hasSize(2);
        TokenRow a = rows.get(0);
        assertThat(a.agentName()).isEqualTo("runner-a");
        assertThat(a.connectionIds()).containsExactly("c1", "c2");
        assertThat(a.present()).isTrue();
        assertThat(a.reachable()).isTrue();
        assertThat(a.expiresAt()).isEqualTo("2027-11-16T09:00+09:00");
        assertThat(a.daysLeft()).isEqualTo(365L);
        assertThat(a.tenantId()).isNull();

        TokenRow off = rows.get(1);
        assertThat(off.reachable()).isFalse();
        assertThat(off.present()).isTrue();
        assertThat(off.expiresAt()).isEqualTo("2027-01-01T09:00+09:00");
        assertThat(off.connectionIds()).containsExactly("c3");

        assertThat(masterRows).extracting(CliAccountTokenEntity::getAgentName)
                .containsExactlyInAnyOrder("runner-a", "runner-off", "other");
    }

    @Test
    void tc_tok_011_012_asksEachFolderOnceAndSkipsOfflineAndPcRunner() throws Exception {
        when(runner.accountToken(any(), any(), any(), any())).thenReturn(ok("tenant"));

        service.listCurrentTenant();

        verify(runner, times(1)).accountToken(any(), any(), any(), any());
        verify(runner).accountToken(EP_A, "/data/w1", "k1", TIMEOUT);
    }

    @Test
    void tc_tok_020_runnerErrorOrNetworkFailureIsUnreachable() throws Exception {
        when(runner.accountToken(any(), any(), any(), any())).thenThrow(new ConnectException("refused"));

        List<TokenRow> rows = service.listCurrentTenant();

        assertThat(rows.get(0).reachable()).isFalse();
        assertThat(rows.get(0).present()).isFalse();
    }

    @Test
    void tc_tok_021_tenantAdminPutsWithByTenant() throws Exception {
        when(runner.putAccountToken(EP_A, "/data/w1", TOKEN, "tenant", "k1")).thenReturn(ok("tenant"));
        UserPrincipal admin = new UserPrincipal("u1", "admin@t1.com", "t1", "admin", Map.of());

        TokenRow row = service.putForConnection("c2", TOKEN, admin, "10.0.0.1");

        assertThat(row.present()).isTrue();
        assertThat(row.connectionIds()).containsExactly("c1", "c2");
        ArgumentCaptor<Map<String, Object>> detail = detailCaptor();
        verify(audit).log(eq("cli_account_token_put"), eq("connection:c2"), eq("u1"), isNull(), detail.capture(),
                eq("10.0.0.1"));
        assertThat(detail.getValue()).containsEntry("by", "tenant").containsEntry("actor", "admin@t1.com")
                .containsEntry("expires_at", "2027-11-16T00:00Z");
    }

    @Test
    void tc_tok_021_impersonationPutsWithByPlatformAndAuditsPlatformAdmin() throws Exception {
        when(runner.putAccountToken(EP_A, "/data/w1", TOKEN, "platform", "k1")).thenReturn(ok("platform"));
        UserPrincipal sa = new UserPrincipal("sa-1", "ops@aimbase.io", "t1", "super_admin", Map.of(), true);

        TokenRow row = service.putForConnection("c1", TOKEN, sa, "10.0.0.2");

        assertThat(row.managedBy()).isEqualTo("platform");
        ArgumentCaptor<Map<String, Object>> detail = detailCaptor();
        verify(audit).log(eq("cli_account_token_put"), eq("connection:c1"), eq("sa-1"), isNull(), detail.capture(),
                eq("10.0.0.2"));
        assertThat(detail.getValue()).containsEntry("by", "platform").containsEntry("actor", "ops@aimbase.io");
    }

    @Test
    void tc_tok_021_refusals() throws Exception {
        UserPrincipal admin = new UserPrincipal("u1", "admin@t1.com", "t1", "admin", Map.of());

        assertThatThrownBy(() -> service.putForConnection("c5", TOKEN, admin, null))
                .isInstanceOf(CliTokenApiException.class).hasMessage("account_token_not_server_runner")
                .satisfies(e -> assertThat(((CliTokenApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.putForConnection("c4", TOKEN, admin, null))
                .hasMessage("account_token_not_server_runner");
        assertThatThrownBy(() -> service.putForConnection("c3", TOKEN, admin, null))
                .hasMessage("cli_runner_unreachable:AGENT_OFFLINE:")
                .satisfies(e -> assertThat(((CliTokenApiException) e).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> service.putForConnection("nope", TOKEN, admin, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Connection not found: nope");

        when(runner.putAccountToken(any(), any(), any(), any(), any()))
                .thenReturn(new AccountTokenReply(400, Map.of("error", "account_token_rejected")))
                .thenReturn(new AccountTokenReply(409, Map.of("error", "account_token_platform_managed")))
                .thenReturn(new AccountTokenReply(401, Map.of()));
        assertThatThrownBy(() -> service.putForConnection("c1", TOKEN, admin, null))
                .hasMessage("account_token_rejected")
                .satisfies(e -> assertThat(((CliTokenApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.putForConnection("c1", TOKEN, admin, null))
                .hasMessage("account_token_platform_managed")
                .satisfies(e -> assertThat(((CliTokenApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.putForConnection("c1", TOKEN, admin, null))
                .hasMessage("cli_runner_error:401");
        verify(audit, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void tc_tok_022_tokenValueIsNowhereAfterPut() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CliAccountTokenService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            when(runner.putAccountToken(any(), any(), any(), any(), any())).thenReturn(ok("tenant"));
            UserPrincipal admin = new UserPrincipal("u1", "admin@t1.com", "t1", "admin", Map.of());

            TokenRow row = service.putForConnection("c1", TOKEN, admin, null);
            when(runner.putAccountToken(any(), any(), any(), any(), any()))
                    .thenThrow(new ConnectException("refused"));
            assertThatThrownBy(() -> service.putForConnection("c1", TOKEN, admin, null))
                    .hasMessage("cli_runner_unreachable:AGENT_OFFLINE:");

            ArgumentCaptor<Map<String, Object>> detail = detailCaptor();
            verify(audit).log(any(), any(), any(), any(), detail.capture(), any());
            assertThat(detail.getValue().toString()).doesNotContain(TOKEN);
            assertThat(row.toString()).doesNotContain(TOKEN);
            assertThat(logs.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(TOKEN));
            for (CliAccountTokenEntity e : masterRows) {
                assertThat(List.of(e.getAgentName(), e.getConfigDir(), e.getManagedBy(),
                        e.getConnectionIds().toString())).noneMatch(v -> v.contains(TOKEN));
            }
            assertThat(conns).allSatisfy(c -> assertThat(String.valueOf(c.getConfig())).doesNotContain(TOKEN));
            verify(connections, never()).save(any());
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void tc_tok_025_platformListReadsMasterOnlyWithTenantNames() {
        masterRows.add(row("t2", "runner-b", "/data/w9", OffsetDateTime.parse("2026-11-20T00:00:00Z")));
        masterRows.add(row("t1", "runner-a", "/data/w1", OffsetDateTime.parse("2026-11-01T00:00:00Z")));
        when(tenantRepo.findAll()).thenReturn(List.of(tenant("t1", "물류 A"), tenant("t2", "물류 B")));

        List<TokenRow> rows = service.listAllTenants();

        assertThat(rows).extracting(TokenRow::tenantId).containsExactly("t1", "t2");
        assertThat(rows).extracting(TokenRow::tenantName).containsExactly("물류 A", "물류 B");
        assertThat(rows.get(0).checkedAt()).isEqualTo("2026-11-16T09:00+09:00");
        assertThat(rows.get(0).daysLeft()).isEqualTo(-15L);
        assertThat(rows.get(1).daysLeft()).isEqualTo(4L);
        verifyNoInteractions(runner, agents, connections);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> detailCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private static AccountTokenReply ok(String managedBy) {
        return new AccountTokenReply(200, Map.of("config_dir", "/data/w1", "present", true,
                "issued_at", "2026-11-16T00:00Z", "expires_at", "2027-11-16T00:00Z", "managed_by", managedBy));
    }

    private static ConnectionEntity cli(String id, String agent, String dir, String key) {
        ConnectionEntity c = new ConnectionEntity();
        c.setId(id);
        c.setAdapter("anthropic-cli");
        Map<String, Object> cfg = new HashMap<>();
        if (agent != null) cfg.put("agent_name", agent);
        if (dir != null) cfg.put("config_dir", dir);
        if (key != null) cfg.put("runner_api_key", key);
        c.setConfig(cfg);
        return c;
    }

    private static CliAccountTokenEntity row(String tenant, String agent, String dir, OffsetDateTime expires) {
        CliAccountTokenEntity e = new CliAccountTokenEntity();
        e.setTenantId(tenant);
        e.setAgentName(agent);
        e.setConfigDir(dir);
        e.setManagedBy("tenant");
        e.setPresent(true);
        e.setIssuedAt(expires.minusDays(365));
        e.setExpiresAt(expires);
        e.setConnectionIds(new ArrayList<>(List.of("old")));
        e.setCheckedAt(OffsetDateTime.now(CLOCK));
        return e;
    }

    private static TenantEntity tenant(String id, String name) {
        TenantEntity t = new TenantEntity();
        t.setId(id);
        t.setName(name);
        return t;
    }
}
