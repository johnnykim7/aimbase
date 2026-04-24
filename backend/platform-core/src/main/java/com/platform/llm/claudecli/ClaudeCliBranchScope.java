package com.platform.llm.claudecli;

/**
 * CR-050: 병렬 브랜치 컨텍스트 마커 (ThreadLocal).
 *
 * {@link com.platform.workflow.step.ParallelStepExecutor} 가 각 서브스텝 실행 스레드에 세팅하면,
 * {@link com.platform.llm.adapter.ClaudeCliLlmAdapter} 가 이를 감지해 메인 워커 대신
 * fork-session 워커를 사용한다 — prompt prefix 캐시 재사용으로 병렬 브랜치 비용 절감.
 *
 * <p>현재 스레드에만 적용되며 Virtual Thread 내부에서도 자식 스레드로 전파되지 않는다
 * (병렬 브랜치 경계는 의도적으로 명확히 유지).
 *
 * <p>사용:
 * <pre>
 *   try (var scope = ClaudeCliBranchScope.open("run-1", "branch-A")) {
 *       engine.executeStepById(subStepId, context);   // 이 안쪽 LLM_CALL 은 fork 워커 사용
 *   }
 * </pre>
 */
public final class ClaudeCliBranchScope implements AutoCloseable {

    private static final ThreadLocal<ClaudeCliBranchScope> CURRENT = new ThreadLocal<>();

    private final String parentRunId;
    private final String branchKey;
    private final ClaudeCliBranchScope previous;

    private ClaudeCliBranchScope(String parentRunId, String branchKey, ClaudeCliBranchScope previous) {
        this.parentRunId = parentRunId;
        this.branchKey = branchKey;
        this.previous = previous;
    }

    /** 새 스코프 열기 — try-with-resources 로 사용. 중첩 호출도 이전 스코프를 보존해서 안전하게 복원. */
    public static ClaudeCliBranchScope open(String parentRunId, String branchKey) {
        ClaudeCliBranchScope prev = CURRENT.get();
        ClaudeCliBranchScope s = new ClaudeCliBranchScope(parentRunId, branchKey, prev);
        CURRENT.set(s);
        return s;
    }

    public static ClaudeCliBranchScope current() {
        return CURRENT.get();
    }

    public String parentRunId() { return parentRunId; }
    public String branchKey() { return branchKey; }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
