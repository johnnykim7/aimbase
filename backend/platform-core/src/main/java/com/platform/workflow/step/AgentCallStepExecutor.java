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
        SubagentRequest request = buildRequest(config, context, stepId, true);
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
            // CR-106: 멀티 에이전트는 한 step 에 여러 agent 가 같은 stepId 공유 → 결정적 키 충돌 위험.
            // timeout retry 멱등화는 단일 AGENT_CALL 장기작업 대상이므로 멀티 경로는 비활성(enableResume=false).
            requests.add(buildRequest(agentConfig, context, stepId, false));
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

    private SubagentRequest buildRequest(Map<String, Object> config, StepContext context, String stepId,
                                         boolean enableResume) {
        String description = (String) config.getOrDefault("description", "workflow-agent");
        String prompt = (String) config.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("AGENT_CALL step requires 'prompt' in config");
        }

        // CR-117: 직전 attempt 가 "Request too large (32MB)" 류로 실패했으면, 한 turn 에 PDF 페이지를
        // 너무 많이 누적해 읽은 것이다(진범). 재시도 프롬프트에 "더 잘게 읽어라" 힌트를 앞에 붙인다.
        // 미리 정밀 분할하지 않고(추정은 빗나감), 에러가 알려주면 그때 적응적으로 줄인다 — 사용자 결정.
        if (isPayloadTooLargeFailure(context.previousAttemptFailure())) {
            prompt = TOO_LARGE_RETRY_HINT + "\n\n" + prompt;
            log.info("AGENT_CALL step '{}' (run={}): previous attempt hit 32MB limit — injecting smaller-read hint",
                    stepId, context.workflowRunId());
        }

        String model = (String) config.get("model");
        String connectionId = (String) config.get("connection_id");
        String isolationStr = (String) config.getOrDefault("isolation", "NONE");
        SubagentRequest.IsolationMode isolation = SubagentRequest.IsolationMode.valueOf(
                isolationStr.toUpperCase());

        long timeoutMs = config.containsKey("timeout_ms")
                ? ((Number) config.get("timeout_ms")).longValue()
                : 120_000L;

        // CR-106: 직전 attempt 가 turn timeout 류로 실패했으면, 이 step·run 의 결정적 childSessionId 를
        // resumeSessionId 로 넘긴다 → 같은 CLI run_id 로 Worker 재사용 + --resume 이어하기(작업장 누적 위 계속).
        // timeout 류가 아니면 null → SubagentRunner 가 새 세션 발급(깨끗이 재시도). 첫 시도도 null.
        String resumeSessionId = null;
        if (enableResume && isTurnTimeoutFailure(context.previousAttemptFailure())) {
            resumeSessionId = deterministicSessionId(context.workflowRunId(), stepId);
            log.info("AGENT_CALL step '{}' (run={}): previous attempt timed out — resuming same CLI session '{}'",
                    stepId, context.workflowRunId(), resumeSessionId);
        }

        // CR-117: 빈응답=실패 판정 토글. 기본 true(extract_facts 류 fact 추출은 텍스트를 내야 함).
        // config.require_text_output=false 면 도구만 쓰고 끝내는 에이전트 허용(빈응답 통과).
        // JSON boolean(false) 또는 템플릿 치환으로 들어온 문자열("false") 둘 다 false 로 인식.
        boolean requireTextOutput = !isFalsey(config.get("require_text_output"));

        // CR-102: 워크플로우 run/step 연결 키 전파 — 서브에이전트 내부 도구 루프 이벤트를 run 타임라인에 적재
        // CR-107 후속: 부모 run 의 workspacePath 전파 → 서브에이전트가 TOOL_CALL(download_file) 이 쓴
        // run 격리 workspace 를 본다(새 childSessionId 의 tenant/project 폴백 단절 해소).
        return new SubagentRequest(
                description, prompt, model, connectionId,
                isolation, false, timeoutMs,
                config, context.sessionId(), com.platform.agent.AgentType.GENERAL,
                context.workflowRunId(), stepId, resumeSessionId, context.workspacePath(),
                requireTextOutput);
    }

    /**
     * CR-106: 직전 실패 메시지가 turn timeout 류인지 판정.
     * AgentCallStepExecutor.executeSingleAgent 가 TIMEOUT status 를
     * {@code "AGENT_CALL failed: <reason>"} 로 승격(reason 에 "turn timeout after Ns" 보존)하므로
     * 메시지에 "timeout" 이 포함되면 timeout 류로 본다.
     */
    static boolean isTurnTimeoutFailure(String failureMessage) {
        if (failureMessage == null) return false;
        String lower = failureMessage.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out");
    }

    /**
     * CR-117: 직전 실패가 Anthropic API "Request too large (max 32MB)" 류인지 판정.
     * CLI 가 이 에러를 {@code is_error:true} result 로 둔갑 발행하고(CR-112), ClaudeCliWorker 가
     * 예외로 승격 → AgentCallStepExecutor 가 "AGENT_CALL failed: ...too large..." 로 전파한다.
     * 한 turn 에 PDF 페이지를 과다 누적해 32MB 를 넘긴 경우 → 재시도 시 "더 잘게 읽어라" 힌트 주입.
     */
    static boolean isPayloadTooLargeFailure(String failureMessage) {
        if (failureMessage == null) return false;
        String lower = failureMessage.toLowerCase();
        return lower.contains("too large") || lower.contains("32mb") || lower.contains("request_too_large");
    }

    /** CR-117: 32MB 재시도 시 프롬프트 앞에 붙이는 힌트 — 한 turn 누적을 줄여 32MB 회피. */
    static final String TOO_LARGE_RETRY_HINT =
            "[재시도 안내] 직전 시도에서 한 번에 너무 많은 PDF 페이지를 읽어 요청이 32MB 한계를 초과해 실패했습니다. "
            + "이번에는 한 turn(한 응답)에 PDF 를 5~10페이지씩만 읽으세요. "
            + "한 범위를 읽고 → 핵심을 메모한 뒤 → 다음 범위를 읽는 식으로 진행하고, "
            + "여러 페이지 범위를 한 turn 에 몰아서 읽지 마세요. 마지막 페이지까지 가되 누적은 작게 유지하세요.";

    /** CR-117: JSON boolean false 또는 문자열 "false"(템플릿 치환 결과) 를 false 로 인식. null/그 외=false 아님. */
    static boolean isFalsey(Object v) {
        if (v instanceof Boolean b) return !b;
        if (v instanceof String s) return "false".equalsIgnoreCase(s.trim());
        return false;
    }

    /**
     * CR-106: (workflowRunId, stepId) 로 결정적 childSessionId 파생.
     * 같은 step 의 retry 는 항상 같은 값 → Pool 이 살아있는 Worker 재사용 + CLI --resume.
     * workflowRunId 가 null(비워크플로우 경로)이면 멱등화 대상 아님 — 호출 측에서 timeout 류일 때만 호출.
     */
    static String deterministicSessionId(String workflowRunId, String stepId) {
        String seed = (workflowRunId != null ? workflowRunId : "no-run") + ":" + stepId;
        return "subagent-" + java.util.UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
