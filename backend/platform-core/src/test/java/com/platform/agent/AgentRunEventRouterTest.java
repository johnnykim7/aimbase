package com.platform.agent;

import com.platform.orchestrator.stream.StreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-070 Phase B: AgentRunEventRouter 단위 테스트.
 */
class AgentRunEventRouterTest {

    private AgentRunEventRouter router;

    @BeforeEach
    void setUp() {
        router = new AgentRunEventRouter();
    }

    @Test
    void register_returns_unique_runId_with_run_prefix() {
        String r1 = router.register("tenant-a", ev -> {});
        String r2 = router.register("tenant-a", ev -> {});
        assertThat(r1).startsWith("run-");
        assertThat(r2).startsWith("run-");
        assertThat(r1).isNotEqualTo(r2);
        assertThat(router.size()).isEqualTo(2);
    }

    @Test
    void dispatch_delivers_event_to_registered_sink() {
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register("tenant-a", received::add);

        boolean ok = router.dispatch(runId, "tenant-a", new StreamEvent.TextDelta("hello"));

        assertThat(ok).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(StreamEvent.TextDelta.class);
    }

    @Test
    void dispatch_unknown_runId_returns_false() {
        boolean ok = router.dispatch("run-nonexistent", "tenant-a", new StreamEvent.TextDelta("x"));
        assertThat(ok).isFalse();
    }

    @Test
    void dispatch_tenant_mismatch_is_rejected() {
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register("tenant-a", received::add);

        boolean ok = router.dispatch(runId, "tenant-b", new StreamEvent.TextDelta("hello"));

        assertThat(ok).isFalse();
        assertThat(received).isEmpty();
    }

    @Test
    void dispatch_with_null_tenants_skips_check() {
        // 멀티테넌시 비활성 환경 — 양쪽 null 이면 통과
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register(null, received::add);

        boolean ok = router.dispatch(runId, null, new StreamEvent.TextDelta("x"));

        assertThat(ok).isTrue();
        assertThat(received).hasSize(1);
    }

    @Test
    void unregister_removes_runId() {
        String runId = router.register("tenant-a", ev -> {});
        assertThat(router.size()).isEqualTo(1);

        router.unregister(runId);
        assertThat(router.size()).isZero();

        boolean ok = router.dispatch(runId, "tenant-a", new StreamEvent.TextDelta("x"));
        assertThat(ok).isFalse();
    }

    @Test
    void sink_exception_is_swallowed_returning_false() {
        String runId = router.register("tenant-a", ev -> {
            throw new RuntimeException("sink boom");
        });

        boolean ok = router.dispatch(runId, "tenant-a", new StreamEvent.TextDelta("x"));

        assertThat(ok).isFalse();
        assertThat(router.size()).isEqualTo(1);
    }

    @Test
    void multiple_events_in_order_to_same_runId() {
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register("tenant-a", received::add);

        router.dispatch(runId, "tenant-a", new StreamEvent.TextDelta("first"));
        router.dispatch(runId, "tenant-a", new StreamEvent.ToolUseStart("t1", "Read", java.util.Map.of()));
        router.dispatch(runId, "tenant-a", new StreamEvent.ToolResultEvent("t1", "ok", false));
        router.dispatch(runId, "tenant-a", new StreamEvent.TextDelta("done"));

        assertThat(received).hasSize(4);
        assertThat(received.get(0)).isInstanceOf(StreamEvent.TextDelta.class);
        assertThat(received.get(1)).isInstanceOf(StreamEvent.ToolUseStart.class);
        assertThat(received.get(2)).isInstanceOf(StreamEvent.ToolResultEvent.class);
        assertThat(received.get(3)).isInstanceOf(StreamEvent.TextDelta.class);
    }
}
