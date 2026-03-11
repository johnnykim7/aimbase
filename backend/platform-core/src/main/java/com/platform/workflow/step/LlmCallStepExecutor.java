package com.platform.workflow.step;

import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM_CALL 스텝 실행기.
 *
 * config 형식:
 * {
 *   "model": "anthropic/claude-sonnet-4-6",   // 선택 (기본: auto)
 *   "prompt": "{{input.question}}에 답하시오",  // 사용자 메시지 (변수 치환 지원)
 *   "system": "당신은 도움이 되는 AI입니다."     // 선택: 시스템 메시지
 * }
 */
@Component
public class LlmCallStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmCallStepExecutor.class);

    private final ModelRouter modelRouter;

    public LlmCallStepExecutor(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.LLM_CALL;
    }

    @Override
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();

        String model = config.containsKey("model") ? (String) config.get("model") : "auto";
        String promptTemplate = (String) config.getOrDefault("prompt", "");
        String systemTemplate = (String) config.get("system");

        // 변수 치환
        String prompt = context.resolve(promptTemplate);
        String system = systemTemplate != null ? context.resolve(systemTemplate) : null;

        String resolvedModel = modelRouter.resolveModelId(model);
        LLMAdapter adapter = modelRouter.route(new LLMRequest(resolvedModel, List.of()));

        // 메시지 구성
        java.util.List<UnifiedMessage> messages = new java.util.ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, system));
        }
        messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, prompt));

        // LLM 호출 (tool use 없이 단순 호출)
        LLMRequest llmRequest = new LLMRequest(resolvedModel, messages, null,
                ModelConfig.defaults(), false, context.workflowRunId());
        LLMResponse response;
        try {
            response = adapter.chat(llmRequest).get();
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed in step '" + step.id() + "': " + e.getMessage(), e);
        }

        String output = response.textContent();
        log.debug("LLM_CALL step '{}' completed: {} chars", step.id(), output.length());

        return Map.of(
                "output", output,
                "model", resolvedModel,
                "input_tokens", response.usage().inputTokens(),
                "output_tokens", response.usage().outputTokens()
        );
    }
}
