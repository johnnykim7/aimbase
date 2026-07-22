package com.platform.workflow;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-121: 워크플로우 run 의 협조적 중지 표식을 보관하는 공유 레지스트리.
 *
 * <p>기존에는 {@link WorkflowEngine} 의 private Set 에만 있어 스텝 실행기에서 취소 여부를 볼 수 없었다.
 * LARGE_INPUT(자체 청크 병렬 루프) 같은 장시간 스텝이 진행 중 취소를 감지해 남은 청크를 건너뛰려면
 * 실행기도 같은 표식을 조회해야 하므로 별도 빈으로 분리한다.
 *
 * <p>{@link WorkflowEngine} 는 스텝 경계에서, 실행기는 청크 경계에서 {@link #isCancelRequested(String)} 를
 * 검사한다. 단일 노드 메모리 표식이며(멀티노드 강제 kill 은 worker registry 경유), best-effort 다.
 */
@Component
public class WorkflowCancellationRegistry {

    private final Set<String> cancelRequests = ConcurrentHashMap.newKeySet();

    /** runId 에 중지 요청을 건다. 이미 걸려 있으면 false. */
    public boolean request(String runId) {
        return cancelRequests.add(runId);
    }

    /** runId 에 중지 요청이 걸려 있는지. */
    public boolean isCancelRequested(String runId) {
        return cancelRequests.contains(runId);
    }

    /** run 종료 시 표식 정리(메모리 누수 방지). */
    public void clear(String runId) {
        cancelRequests.remove(runId);
    }
}
