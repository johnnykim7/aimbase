package com.platform.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-116: run→CLI worker 레지스트리 단위 테스트. */
@DisplayName("ActiveCliWorkerRegistry")
class ActiveCliWorkerRegistryTest {

    private final ActiveCliWorkerRegistry registry = new ActiveCliWorkerRegistry();

    @Test
    @DisplayName("register 후 workersOf 로 (childSession, conn) 회수")
    void registerThenLookup() {
        registry.register("run-1", "subagent-a", "conn-1");
        registry.register("run-1", "subagent-b", "conn-1");

        var workers = registry.workersOf("run-1");
        assertThat(workers).hasSize(2);
        assertThat(workers).extracting(ActiveCliWorkerRegistry.WorkerRef::childSessionId)
                .containsExactlyInAnyOrder("subagent-a", "subagent-b");
    }

    @Test
    @DisplayName("unregister 로 해당 worker 만 제거, 마지막 제거 시 run 키 자체 비움")
    void unregisterRemovesAndCleansEmptyRun() {
        registry.register("run-1", "subagent-a", "conn-1");
        registry.register("run-1", "subagent-b", "conn-1");

        registry.unregister("run-1", "subagent-a");
        assertThat(registry.workersOf("run-1"))
                .extracting(ActiveCliWorkerRegistry.WorkerRef::childSessionId)
                .containsExactly("subagent-b");

        registry.unregister("run-1", "subagent-b");
        assertThat(registry.workersOf("run-1")).isEmpty(); // 키 자체 제거 → 빈 리스트
    }

    @Test
    @DisplayName("run 간 격리 — 다른 run 의 worker 는 섞이지 않음")
    void runsAreIsolated() {
        registry.register("run-1", "subagent-a", "conn-1");
        registry.register("run-2", "subagent-c", "conn-2");

        assertThat(registry.workersOf("run-1"))
                .extracting(ActiveCliWorkerRegistry.WorkerRef::childSessionId).containsExactly("subagent-a");
        assertThat(registry.workersOf("run-2"))
                .extracting(ActiveCliWorkerRegistry.WorkerRef::childSessionId).containsExactly("subagent-c");
    }

    @Test
    @DisplayName("workflowRunId/childSessionId 가 null/blank 면 register 무동작 (비워크플로우 경로)")
    void blankInputsNoop() {
        registry.register(null, "subagent-a", "conn-1");
        registry.register("", "subagent-a", "conn-1");
        registry.register("run-1", null, "conn-1");
        registry.register("run-1", " ", "conn-1");

        assertThat(registry.workersOf("run-1")).isEmpty();
        assertThat(registry.workersOf(null)).isEmpty();
    }

    @Test
    @DisplayName("미등록 run 의 workersOf → 빈 리스트 (NPE 없음)")
    void unknownRunEmpty() {
        assertThat(registry.workersOf("nope")).isEmpty();
    }
}
