package com.platform.llm;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionGroupEntity;
import com.platform.repository.ConnectionGroupRepository;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-161 TC-CG-101~110: 그룹 공통 실행(고정, 넘길 실패 분류, 테넌트별). */
class ConnectionGroupSelectorTest {

    private static final String GROUP = "grp-cli";

    private ConnectionGroupRepository groupRepository;
    private ConnectionAdapterFactory adapterFactory;
    private MutableClock clock;
    private ConnectionGroupSelector selector;
    private ConnectionGroupEntity group;

    @BeforeEach
    void setUp() {
        groupRepository = mock(ConnectionGroupRepository.class);
        adapterFactory = mock(ConnectionAdapterFactory.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        clock = new MutableClock(Instant.now());
        selector = new ConnectionGroupSelector(groupRepository, adapterFactory, settings, clock);

        group = group("ROUND_ROBIN", "w01", "w02");
        when(groupRepository.findById(GROUP)).thenReturn(Optional.of(group));
        when(adapterFactory.isCliConnection(anyString())).thenReturn(true);
        TenantContext.setTenantId("bp_wes");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void tc_cg_101_pinnedMemberFirst() {
        String first = selector.execute(GROUP, "s1", id -> id);
        String second = selector.execute(GROUP, "s1", id -> id);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void tc_cg_102_noPinKeyFollowsStrategy() {
        String first = selector.execute(GROUP, null, id -> id);
        String second = selector.execute(GROUP, null, id -> id);
        assertThat(List.of(first, second)).containsExactlyInAnyOrder("w01", "w02");
    }

    @Test
    void tc_cg_103_accountLimitMovesPinAndExcludesMember() {
        long resetsAt = clock.instant().plus(Duration.ofHours(1)).getEpochSecond();
        List<String> calls = new ArrayList<>();
        String used = selector.execute(GROUP, "s1", id -> {
            calls.add(id);
            if (id.equals("w01")) {
                throw new RuntimeException("cli_account_limited:resets_at=" + resetsAt + ":type=seven_day:You've hit your limit");
            }
            return id;
        });
        assertThat(used).isEqualTo("w02");
        assertThat(calls).containsExactly("w01", "w02");
        assertThat(selector.getCircuitBreakerState("w01")).isEqualTo("OPEN");

        calls.clear();
        assertThat(run("s2", calls)).isEqualTo("w02");
        assertThat(run("s1", calls)).isEqualTo("w02");
        assertThat(run("s3", calls)).isEqualTo("w02");
        assertThat(calls).doesNotContain("w01");
    }

    @Test
    void tc_cg_104_limitMarkerInsideStreamErrorChain() {
        RuntimeException wrapped = new RuntimeException("LLM call failed: x",
                new RuntimeException("cli_runner_unreachable:RuntimeException:Runner stream error: "
                        + "cli_account_limited:resets_at=" + clock.instant().plusSeconds(600).getEpochSecond() + ":type=five_hour:"));
        String used = selector.execute(GROUP, "s1", id -> {
            if (id.equals("w01")) throw wrapped;
            return id;
        });
        assertThat(used).isEqualTo("w02");
        assertThat(selector.getCircuitBreakerState("w01")).isEqualTo("OPEN");
    }

    @Test
    void tc_cg_105_unreachedRunnerFailsOverAndCountsFailure() {
        group.setStrategy("PRIORITY");
        List<String> markers = List.of(
                "cli_runner_unreachable:AGENT_OFFLINE:connection refused",
                "cli_agent_offline:agent-name=cli-runner-bp-wes",
                "cli_runner_unreachable:TURN_RELAY_DEAD:relay gone");
        for (String marker : markers) {
            assertThat(selector.getCircuitBreakerState("w01")).isEqualTo("CLOSED");
            String used = selector.execute(GROUP, null, id -> {
                if (id.equals("w01")) throw new RuntimeException(marker);
                return id;
            });
            assertThat(used).isEqualTo("w02");
        }
        assertThat(selector.getCircuitBreakerState("w01")).isEqualTo("OPEN");
    }

    @Test
    void tc_cg_106_failureAfterRequestReachedIsRethrownAndPinKept() {
        selector.execute(GROUP, "s1", id -> id);
        String pinned = selector.execute(GROUP, "s1", id -> id);

        List<String> calls = new ArrayList<>();
        RuntimeException timeout = new RuntimeException("cli_runner_unreachable:AGENT_TIMEOUT:request timed out");
        assertThatThrownBy(() -> selector.execute(GROUP, "s1", id -> { calls.add(id); throw timeout; }))
                .isSameAs(timeout);
        assertThat(calls).containsExactly(pinned);

        RuntimeException structured = new RuntimeException("structured output failed after 2 retries");
        assertThatThrownBy(() -> selector.execute(GROUP, "s1", id -> { throw structured; }))
                .isSameAs(structured);
        assertThat(run("s1", calls)).isEqualTo(pinned);
    }

    @Test
    void tc_cg_107_apiMemberFailsOverOnAnyError() {
        when(adapterFactory.isCliConnection(anyString())).thenReturn(false);
        String used = selector.execute(GROUP, "s1", id -> {
            if (id.equals("w01")) throw new RuntimeException("529 overloaded");
            return id;
        });
        assertThat(used).isEqualTo("w02");
    }

    @Test
    void tc_cg_108_allMembersLimitedReportsEarliestTime() {
        Instant early = clock.instant().plus(Duration.ofMinutes(30));
        Instant late = clock.instant().plus(Duration.ofHours(3));
        assertThatThrownBy(() -> selector.execute(GROUP, "s1", id -> {
            Instant at = id.equals("w01") ? late : early;
            throw new RuntimeException("cli_account_limited:resets_at=" + at.getEpochSecond() + ":type=seven_day:");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("connection_group_unavailable:retry_after=" + Instant.ofEpochSecond(early.getEpochSecond()));

        assertThatThrownBy(() -> selector.execute(GROUP, "s9", id -> id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("connection_group_unavailable:");
    }

    @Test
    void tc_cg_109_tenantsDoNotShareState() {
        long resetsAt = clock.instant().plus(Duration.ofHours(1)).getEpochSecond();
        selector.execute(GROUP, "s1", id -> {
            if (id.equals("w01")) throw new RuntimeException("cli_account_limited:resets_at=" + resetsAt + ":type=seven_day:");
            return id;
        });
        assertThat(selector.getCircuitBreakerState("w01")).isEqualTo("OPEN");

        TenantContext.setTenantId("bp_office");
        assertThat(selector.getCircuitBreakerState("w01")).isEqualTo("CLOSED");
        List<String> calls = new ArrayList<>();
        selector.execute(GROUP, "s1", id -> { calls.add(id); return id; });
        selector.execute(GROUP, "s2", id -> { calls.add(id); return id; });
        assertThat(calls).contains("w01");
    }

    @Test
    void tc_cg_110_pinExpiresAfterSessionTtl() {
        List<String> calls = new ArrayList<>();
        assertThat(run("s1", calls)).isEqualTo("w01");
        assertThat(run("s1", calls)).isEqualTo("w01");
        assertThat(run("s1", calls)).isEqualTo("w01");

        // 차례는 w02 로 와 있으므로 고정이 남아 있으면 w01, 지워졌으면 w02 가 나온다.
        clock.advance(Duration.ofHours(25));
        assertThat(run("s1", calls)).isEqualTo("w02");
    }

    @Test
    void memberConnectionIdsListsGroupMembers() {
        assertThat(selector.memberConnectionIds(GROUP)).containsExactly("w01", "w02");
    }

    private String run(String pinKey, List<String> calls) {
        return selector.execute(GROUP, pinKey, id -> { calls.add(id); return id; });
    }

    private static ConnectionGroupEntity group(String strategy, String... members) {
        ConnectionGroupEntity g = new ConnectionGroupEntity();
        g.setId(GROUP);
        g.setName(GROUP);
        g.setAdapter("anthropic-cli");
        g.setStrategy(strategy);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < members.length; i++) {
            list.add(Map.of("connection_id", members[i], "priority", i + 1, "weight", 1));
        }
        g.setMembers(list);
        return g;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
