package com.platform.workflow.step;

import com.platform.agent.AgentOrchestrator;
import com.platform.agent.SubagentRequest;
import com.platform.agent.SubagentResult;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-030 PRD-210: AGENT_CALL 스텝 실행기.
 *
 * 워크플로우 DAG에서 서브에이전트 실행을 담당한다.
 *
 * <h3>config 형식 (단일 에이전트)</h3>
 * <pre>{@code
 * {
 *   "description": "코드 리뷰 에이전트",
 *   "prompt": "{{input.code}} 를 리뷰해줘",
 *   "model": "claude-sonnet",
 *   "connection_id": "conn-1",
 *   "isolation": "NONE" | "WORKTREE",
 *   "timeout_ms": 120000
 * }
 * }</pre>
 *
 * <h3>config 형식 (멀티 에이전트)</h3>
 * <pre>{@code
 * {
 *   "agents": [
 *     { "description": "에이전트1", "prompt": "...", ... },
 *     { "description": "에이전트2", "prompt": "...", ... }
 *   ],
 *   "execution": "parallel" | "sequential"
 * }
 * }</pre>
 */
@Component
public class AgentCallStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentCallStepExecutor.class);

    private final AgentOrchestrator agentOrchestrator;

    public AgentCallStepExecutor(@org.springframework.context.annotation.Lazy AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.AGENT_CALL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = context.resolveMap(step.config());
        long startMs = System.currentTimeMillis();

        // 멀티 에이전트 모드
        if (config.containsKey("agents")) {
            return executeMultiAgent(config, context, step.id(), startMs);
        }

        // 단일 에이전트 모드
        return executeSingleAgent(config, context, step.id(), startMs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeSingleAgent(Map<String, Object> config,
                                                    StepContext context, String stepId, long startMs) {
        SubagentRequest request = buildRequest(config, context, stepId);
        SubagentResult result = agentOrchestrator.runSingle(request);
        // FAILED/TIMEOUT 은 Exception 으로 승격해야 WorkflowEngine.executeWithRetry 가 retry/failed 처리한다.
        // 그대로 두면 result map 만 채우고 정상 return → status=completed 가짜 성공.
        if (result.status() == SubagentResult.Status.FAILED || result.status() == SubagentResult.Status.TIMEOUT) {
            String reason = result.error() != null ? result.error() : result.status().name();
            throw new RuntimeException("AGENT_CALL failed: " + reason);
        }
        return toResultMap(result, startMs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeMultiAgent(Map<String, Object> config,
                                                   StepContext context, String stepId, long startMs) {
        List<Map<String, Object>> agentConfigs = (List<Map<String, Object>>) config.get("agents");
        String execution = (String) config.getOrDefault("execution", "parallel");

        List<SubagentRequest> requests = new ArrayList<>();
        for (Map<String, Object> agentConfig : agentConfigs) {
            requests.add(buildRequest(agentConfig, context, stepId));
        }

        AgentOrchestrator.OrchestratedResult orchestrated;
        if ("sequential".equalsIgnoreCase(execution)) {
            orchestrated = agentOrchestrator.runSequential(requests);
        } else {
            orchestrated = agentOrchestrator.runParallel(requests);
        }

        long durationMs = System.currentTimeMillis() - startMs;

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("output", orchestrated.mergedOutput());
        resultMap.put("execution", execution);
        resultMap.put("agent_count", requests.size());
        resultMap.put("success_count", orchestrated.successCount());
        resultMap.put("fail_count", orchestrated.failCount());
        resultMap.put("all_succeeded", orchestrated.allSucceeded());

        // 개별 에이전트 결과
        List<Map<String, Object>> agentResults = new ArrayList<>();
        for (SubagentResult r : orchestrated.results()) {
            agentResults.add(toResultMap(r, startMs));
        }
        resultMap.put("agents", agentResults);

        if (orchestrated.totalUsage() != null) {
            resultMap.put("input_tokens", orchestrated.totalUsage().inputTokens());
            resultMap.put("output_tokens", orchestrated.totalUsage().outputTokens());
        }
        resultMap.put("_durationMs", durationMs);

        return resultMap;
    }

    private SubagentRequest buildRequest(Map<String, Object> config, StepContext context, String stepId) {
        String description = (String) config.getOrDefault("description", "workflow-agent");
        String prompt = (String) config.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("AGENT_CALL step requires 'prompt' in config");
        }

        String model = (String) config.get("model");
        String connectionId = (String) config.get("connection_id");
        String isolationStr = (String) config.getOrDefault("isolation", "NONE");
        SubagentRequest.IsolationMode isolation = SubagentRequest.IsolationMode.valueOf(
                isolationStr.toUpperCase());

        long timeoutMs = config.containsKey("timeout_ms")
                ? ((Number) config.get("timeout_ms")).longValue()
                : 120_000L;

        // CR-102: 워크플로우 run/step 연결 키 전파 — 서브에이전트 내부 도구 루프 이벤트를 run 타임라인에 적재
        return new SubagentRequest(
                description, prompt, model, connectionId,
                isolation, false, timeoutMs,
                config, context.sessionId(), com.platform.agent.AgentType.GENERAL,
                context.workflowRunId(), stepId);
    }

    private Map<String, Object> toResultMap(SubagentResult result, long startMs) {
        Map<String, Object> map = new HashMap<>();
        map.put("output", result.output() != null ? result.output() : "");
        map.put("subagent_run_id", result.subagentRunId());
        map.put("session_id", result.sessionId());
        map.put("status", result.status().name());
        map.put("exit_code", result.exitCode());

        if (result.structuredData() != null) {
            map.put("structured_data", result.structuredData());
        }
        if (result.usage() != null) {
            map.put("input_tokens", result.usage().inputTokens());
            map.put("output_tokens", result.usage().outputTokens());
        }
        if (result.worktreePath() != null) {
            map.put("worktree_path", result.worktreePath());
            map.put("branch_name", result.branchName());
        }
        if (result.error() != null) {
            map.put("error", result.error());
        }
        map.put("_durationMs", result.durationMs());
        return map;
    }
}
