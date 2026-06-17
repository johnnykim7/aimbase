package com.platform.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-116: 진행 중인 워크플로우 run 이 띄운 CLI worker(AGENT_CALL) 를 역추적하기 위한 인메모리 레지스트리.
 *
 * <p><b>배경</b>: 강제 취소(force cancel)는 hang 한 CLI worker 를 즉시 kill 해야 하는데,
 * worker pool key 는 {@code childSessionId}(=subagent-&lt;UUID&gt;, 매 AGENT_CALL 랜덤)이고
 * WorkflowEngine 은 {@code workflowRunId} 만 안다. 둘 사이 매핑이 없어 "이 run 의 worker 가 어느 것인지"
 * 를 알 수 없었다(CR-114 가 지적한 불일치). 본 레지스트리가 그 다리를 놓는다.
 *
 * <p><b>수명</b>: SubagentRunner 가 AGENT_CALL 실행 직전 {@link #register} 하고, 정상/실패/취소
 * 종료 시 {@link #unregister} 한다(CR-114 cleanup 과 같은 자리). 한 run 이 동시에 여러 AGENT_CALL 을
 * 가질 수 있어 값은 Set 이다.
 *
 * <p><b>경계</b>: WorkflowEngine#cancelRequests 와 동일하게 같은 JVM 인스턴스 로컬이다. 멀티노드에서
 * 다른 인스턴스가 실행 중인 run 의 worker 는 잡히지 않는다(force 도 협조적 표식을 함께 남겨 폴백).
 */
@Component
public class ActiveCliWorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveCliWorkerRegistry.class);

    /** workflowRunId → 그 run 이 현재 띄운 CLI worker 들 (childSessionId + 라우팅용 connectionId). */
    private final Map<String, Set<WorkerRef>> byRun = new ConcurrentHashMap<>();

    /** CLI worker 한 개의 kill 에 필요한 최소 정보. */
    public record WorkerRef(String childSessionId, String connectionId) {}

    /** AGENT_CALL 시작 시 등록. workflowRunId/childSessionId 가 없으면(비워크플로우 경로) 무동작. */
    public void register(String workflowRunId, String childSessionId, String connectionId) {
        if (workflowRunId == null || workflowRunId.isBlank()
                || childSessionId == null || childSessionId.isBlank()) {
            return;
        }
        byRun.computeIfAbsent(workflowRunId, k -> ConcurrentHashMap.newKeySet())
                .add(new WorkerRef(childSessionId, connectionId));
        log.debug("CR-116: registered CLI worker run={} childSession={}", workflowRunId, childSessionId);
    }

    /** AGENT_CALL 종료 시 해제. 마지막 worker 가 빠지면 run 키 자체를 제거(누수 방지). */
    public void unregister(String workflowRunId, String childSessionId) {
        if (workflowRunId == null || childSessionId == null) return;
        Set<WorkerRef> set = byRun.get(workflowRunId);
        if (set == null) return;
        set.removeIf(w -> childSessionId.equals(w.childSessionId()));
        if (set.isEmpty()) byRun.remove(workflowRunId);
        log.debug("CR-116: unregistered CLI worker run={} childSession={}", workflowRunId, childSessionId);
    }

    /** 해당 run 이 현재 띄운 worker 목록(force kill 대상). 없거나 null 이면 빈 리스트. */
    public List<WorkerRef> workersOf(String workflowRunId) {
        if (workflowRunId == null) return List.of();
        Set<WorkerRef> set = byRun.get(workflowRunId);
        return set == null ? List.of() : List.copyOf(set);
    }
}
