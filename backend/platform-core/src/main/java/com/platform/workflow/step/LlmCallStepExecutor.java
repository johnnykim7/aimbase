package com.platform.workflow.step;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM_CALL 스텝 실행기.
 *
 * 토큰 초과 자동 처리 전략:
 *   Phase 1: 일반 호출 (4096)
 *   Phase 2: 에스컬레이션 (8192) — 살짝 넘는 경우
 *   Phase 3: 자동분할 — 크게 넘는 경우
 *     3-a: 분할 계획 호출 → 파트 목록
 *     3-b: 파트별 실행 (각 4096)
 *     3-c: 취합 호출 → 최종 결과
 *
 * 소비앱은 분할 여부를 모름 — response_schema에 맞는 완성된 JSON만 받음.
 */
@Component
public class LlmCallStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmCallStepExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int INITIAL_MAX_TOKENS = 4096;
    private static final int ESCALATION_MAX_TOKENS = 8192;
    private static final int SPLIT_PART_MAX_TOKENS = 4096;
    private static final int SPLIT_MAX_PARTS = 10;

    private final ModelRouter modelRouter;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final com.platform.service.PromptTemplateService promptTemplateService;

    public LlmCallStepExecutor(ModelRouter modelRouter, ConnectionAdapterFactory connectionAdapterFactory,
                                com.platform.service.PromptTemplateService promptTemplateService) {
        this.modelRouter = modelRouter;
        this.promptTemplateService = promptTemplateService;
        this.connectionAdapterFactory = connectionAdapterFactory;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.LLM_CALL;
    }

    @Override
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();

        String model = config.containsKey("model") ? (String) config.get("model") : "auto";
        String connectionId = (String) config.get("connection_id");

        // CR-029: workflow step runtime 필드 (향후 RuntimeAdapter 분기 지점)
        String runtime = (String) config.getOrDefault("runtime", "llm_api");
        String runtimeMode = (String) config.getOrDefault("runtime_mode", "stateless");
        log.debug("Step '{}': runtime={}, mode={}", step.id(), runtime, runtimeMode);
        String promptTemplate = (String) config.getOrDefault("prompt", "");
        String systemTemplate = (String) config.get("system");
        Integer configCeiling = config.containsKey("max_tokens")
                ? ((Number) config.get("max_tokens")).intValue() : null;

        // 변수 치환
        String prompt = context.resolve(promptTemplate);
        String system = systemTemplate != null ? context.resolve(systemTemplate) : null;

        // connection_id가 있으면 ConnectionAdapterFactory 사용, 없으면 ModelRouter 폴백
        LLMAdapter adapter;
        String resolvedModel;
        if (connectionId != null && !connectionId.isBlank()) {
            adapter = connectionAdapterFactory.getAdapter(connectionId);
            resolvedModel = connectionAdapterFactory.resolveModel(connectionId, model);
        } else {
            resolvedModel = modelRouter.resolveModelId(model);
            adapter = modelRouter.route(new LLMRequest(resolvedModel, List.of()));
        }

        // CR-007: response_schema
        @SuppressWarnings("unchecked")
        Map<String, Object> responseSchema = config.containsKey("response_schema")
                ? (Map<String, Object>) config.get("response_schema") : null;

        // CR-030: step config에서 Extended Thinking 설정 추출
        Boolean extThinking = config.containsKey("extended_thinking")
                ? Boolean.valueOf(config.get("extended_thinking").toString()) : null;
        Integer thinkingBudget = config.containsKey("thinking_budget_tokens")
                ? ((Number) config.get("thinking_budget_tokens")).intValue() : null;

        // ── Phase 1: 일반 호출 ──
        int phase1Tokens = configCeiling != null ? Math.min(INITIAL_MAX_TOKENS, configCeiling) : INITIAL_MAX_TOKENS;
        LLMResponse response = callLlm(adapter, resolvedModel, system, prompt, responseSchema,
                phase1Tokens, context, extThinking, thinkingBudget);

        if (response.finishReason() != LLMResponse.FinishReason.MAX_TOKENS) {
            return buildResult(response, resolvedModel);
        }

        log.warn("LLM_CALL step '{}': Phase 1 응답 잘림 (max_tokens={}, output_tokens={}). 에스컬레이션 시도...",
                step.id(), phase1Tokens, response.usage().outputTokens());

        // ── Phase 2: 에스컬레이션 (1회) ──
        int phase2Tokens = configCeiling != null ? Math.min(ESCALATION_MAX_TOKENS, configCeiling) : ESCALATION_MAX_TOKENS;
        if (phase2Tokens > phase1Tokens) {
            response = callLlm(adapter, resolvedModel, system, prompt, responseSchema, phase2Tokens, context);

            if (response.finishReason() != LLMResponse.FinishReason.MAX_TOKENS) {
                log.info("LLM_CALL step '{}': 에스컬레이션 성공 (max_tokens={})", step.id(), phase2Tokens);
                return buildResult(response, resolvedModel);
            }

            log.warn("LLM_CALL step '{}': Phase 2 에스컬레이션도 잘림 (max_tokens={}, output_tokens={})",
                    step.id(), phase2Tokens, response.usage().outputTokens());
        }

        // ── Phase 3: 자동분할 ──
        if (responseSchema == null || responseSchema.isEmpty()) {
            throw new RuntimeException(
                    "LLM_CALL step '" + step.id() + "': 응답이 max_tokens에서 잘렸으나 " +
                    "response_schema가 없어 자동분할을 수행할 수 없습니다. " +
                    "step config에 max_tokens를 늘리거나 스텝을 수동 분할하세요.");
        }

        log.info("LLM_CALL step '{}': 자동분할 모드 진입", step.id());
        return executeAutoSplit(step.id(), adapter, resolvedModel, system, prompt, responseSchema, context);
    }

    // ═══════════════════════════════════════════════
    // 자동분할 (Phase 3)
    // ═══════════════════════════════════════════════

    private Map<String, Object> executeAutoSplit(
            String stepId, LLMAdapter adapter, String resolvedModel,
            String system, String prompt, Map<String, Object> responseSchema, StepContext context) {

        // 3-a. 분할 계획 호출
        int[] planTokenUsage = new int[2]; // [inputTokens, outputTokens]
        List<Map<String, Object>> parts = planSplit(stepId, adapter, resolvedModel, system, prompt,
                responseSchema, context, planTokenUsage);
        log.info("LLM_CALL step '{}': {}개 파트로 분할 계획 수립", stepId, parts.size());

        // 3-b. 파트별 실행 (실패 시 1회 재시도)
        List<String> partResults = new ArrayList<>();
        int totalInputTokens = planTokenUsage[0];
        int totalOutputTokens = planTokenUsage[1];

        for (int i = 0; i < parts.size(); i++) {
            Map<String, Object> part = parts.get(i);
            String scope = (String) part.getOrDefault("scope", part.getOrDefault("description", "파트 " + (i + 1)));

            LLMResponse partResponse = executePart(stepId, adapter, resolvedModel, system, prompt,
                    scope, i, parts.size(), context);

            String partOutput = partResponse.textContent();
            partResults.add(partOutput);
            totalInputTokens += partResponse.usage().inputTokens();
            totalOutputTokens += partResponse.usage().outputTokens();

            log.info("LLM_CALL step '{}': 파트 {}/{} 완료 ({}자)", stepId, i + 1, parts.size(), partOutput.length());
        }

        // 3-c. 취합 호출
        Map<String, Object> mergedResult = mergeResults(stepId, adapter, resolvedModel,
                partResults, responseSchema, context);

        totalInputTokens += (int) mergedResult.getOrDefault("_merge_input_tokens", 0);
        totalOutputTokens += (int) mergedResult.getOrDefault("_merge_output_tokens", 0);
        mergedResult.remove("_merge_input_tokens");
        mergedResult.remove("_merge_output_tokens");

        log.info("LLM_CALL step '{}': 자동분할 완료 ({}개 파트, 총 input={}, output={})",
                stepId, parts.size(), totalInputTokens, totalOutputTokens);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", "");
        result.put("structured_data", mergedResult);
        result.put("model", resolvedModel);
        result.put("input_tokens", totalInputTokens);
        result.put("output_tokens", totalOutputTokens);
        result.put("_auto_split", true);
        result.put("_split_parts", parts.size());
        return result;
    }

    /**
     * 3-a. 분할 계획: LLM에게 작업을 N개 파트로 나누도록 요청
     */
    /**
     * 3-b. 파트 실행: 실패 시 1회 재시도
     */
    private LLMResponse executePart(
            String stepId, LLMAdapter adapter, String resolvedModel,
            String system, String prompt, String scope,
            int partIndex, int totalParts, StepContext context) {

        // CR-036: DB 외부화
        String partTemplate = promptTemplateService.getTemplateOrFallback("workflow.split.part_prompt",
                "Generate only the following scope from the overall task.\n\n=== Scope ===\n{{scope}}\n\n=== Original Request ===\n{{prompt}}\n\nRespond in JSON format only.");
        String partPrompt = promptTemplateService.renderTemplate(partTemplate, Map.of("scope", scope, "prompt", prompt));

        for (int attempt = 1; attempt <= 2; attempt++) {
            LLMResponse partResponse = callLlm(adapter, resolvedModel, system, partPrompt,
                    null, SPLIT_PART_MAX_TOKENS, context);

            if (partResponse.finishReason() != LLMResponse.FinishReason.MAX_TOKENS) {
                return partResponse;
            }

            if (attempt == 1) {
                log.warn("LLM_CALL step '{}': 파트 {}/{} 잘림, 1회 재시도...",
                        stepId, partIndex + 1, totalParts);
            } else {
                throw new RuntimeException(
                        "LLM_CALL step '" + stepId + "': 자동분할 파트 " + (partIndex + 1) + "/" + totalParts +
                        " 재시도 후에도 잘림. 워크플로우 스텝을 수동으로 분할하세요.");
            }
        }

        throw new IllegalStateException("unreachable");
    }

    /**
     * 3-a. 분할 계획: LLM에게 작업을 N개 파트로 나누도록 요청
     */
    private List<Map<String, Object>> planSplit(
            String stepId, LLMAdapter adapter, String resolvedModel,
            String system, String prompt, Map<String, Object> responseSchema,
            StepContext context, int[] tokenUsageOut) {

        String schemaStr;
        try {
            schemaStr = MAPPER.writeValueAsString(responseSchema);
        } catch (Exception e) {
            schemaStr = responseSchema.toString();
        }

        // CR-036: DB 외부화
        String planSystem = promptTemplateService.getTemplateOrFallback("workflow.split.plan_system",
                "You are an expert at splitting large JSON generation tasks. Divide the task into independently generable parts.");

        String planTemplate = promptTemplateService.getTemplateOrFallback("workflow.split.plan_prompt",
                "The output of the following task is too large to generate at once.\nCreate a split plan into independently generable parts.\n\n=== Original System Prompt ===\n{{system}}\n\n=== Original Request ===\n{{prompt}}\n\n=== Output Schema ===\n{{schema}}\n\nRules:\n- Each part must be generable within 3000 tokens\n- Number of parts: 2 to {{max_parts}}\n- Each part's scope must be specific and clear\n- Part results must be mergeable into the final schema");
        String planPrompt = promptTemplateService.renderTemplate(planTemplate, Map.of(
                "system", system != null ? system : "(none)",
                "prompt", prompt,
                "schema", schemaStr,
                "max_parts", String.valueOf(SPLIT_MAX_PARTS)));

        Map<String, Object> planSchema = Map.of(
                "type", "object",
                "required", List.of("parts"),
                "properties", Map.of(
                        "parts", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "required", List.of("part_number", "scope"),
                                        "properties", Map.of(
                                                "part_number", Map.of("type", "integer"),
                                                "scope", Map.of("type", "string"),
                                                "description", Map.of("type", "string")
                                        )
                                )
                        )
                )
        );

        LLMResponse planResponse = callLlm(adapter, resolvedModel, planSystem, planPrompt,
                planSchema, SPLIT_PART_MAX_TOKENS, context);

        // 분할 계획 호출 토큰 집계
        tokenUsageOut[0] = planResponse.usage().inputTokens();
        tokenUsageOut[1] = planResponse.usage().outputTokens();

        // structured_data에서 parts 추출
        Map<String, Object> planData = extractStructuredData(planResponse);
        if (planData == null || !planData.containsKey("parts")) {
            throw new RuntimeException(
                    "LLM_CALL step '" + stepId + "': 자동분할 계획 생성 실패 — parts 배열을 받지 못했습니다.");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) planData.get("parts");

        if (parts.isEmpty()) {
            throw new RuntimeException(
                    "LLM_CALL step '" + stepId + "': 자동분할 계획이 빈 배열입니다.");
        }
        if (parts.size() > SPLIT_MAX_PARTS) {
            log.warn("LLM_CALL step '{}': 분할 계획이 {}개 → {}개로 제한", stepId, parts.size(), SPLIT_MAX_PARTS);
            parts = parts.subList(0, SPLIT_MAX_PARTS);
        }

        return parts;
    }

    /**
     * 3-c. 취합: 파트별 결과를 원본 schema에 맞게 병합
     */
    private Map<String, Object> mergeResults(
            String stepId, LLMAdapter adapter, String resolvedModel,
            List<String> partResults, Map<String, Object> responseSchema, StepContext context) {

        String schemaStr;
        try {
            schemaStr = MAPPER.writeValueAsString(responseSchema);
        } catch (Exception e) {
            schemaStr = responseSchema.toString();
        }

        StringBuilder fragments = new StringBuilder();
        for (int i = 0; i < partResults.size(); i++) {
            fragments.append("=== 파트 ").append(i + 1).append(" ===\n");
            fragments.append(partResults.get(i)).append("\n\n");
        }

        String mergeSystem = promptTemplateService.getTemplateOrFallback("workflow.split.merge_system",
                "You are an expert at merging JSON fragments into a single complete structure. Generate one JSON that exactly matches the given schema.");

        String mergeTemplate = promptTemplateService.getTemplateOrFallback("workflow.split.merge_prompt",
                "Merge the following fragments into one complete JSON according to the schema below.\n\n=== Target Schema ===\n{{schema}}\n\n=== Fragments ===\n{{fragments}}\n\nRules:\n- Include all content from every fragment without omission\n- Rearrange into the structure matching the schema\n- Remove duplicates but do not omit any content");
        String mergePrompt = promptTemplateService.renderTemplate(mergeTemplate, Map.of(
                "schema", schemaStr,
                "fragments", fragments.toString()));

        // 취합: 4096 시도 → 잘리면 8192 에스컬레이션
        LLMResponse mergeResponse = callLlm(adapter, resolvedModel, mergeSystem, mergePrompt,
                responseSchema, SPLIT_PART_MAX_TOKENS, context);

        if (mergeResponse.finishReason() == LLMResponse.FinishReason.MAX_TOKENS) {
            log.warn("LLM_CALL step '{}': 취합 잘림 (max_tokens={}), 에스컬레이션...",
                    stepId, SPLIT_PART_MAX_TOKENS);
            mergeResponse = callLlm(adapter, resolvedModel, mergeSystem, mergePrompt,
                    responseSchema, ESCALATION_MAX_TOKENS, context);
        }

        if (mergeResponse.finishReason() == LLMResponse.FinishReason.MAX_TOKENS) {
            throw new RuntimeException(
                    "LLM_CALL step '" + stepId + "': 자동분할 취합 에스컬레이션 후에도 잘림. " +
                    "파트가 너무 많거나 각 파트 결과가 큽니다. 워크플로우 스텝을 수동으로 분할하세요.");
        }

        Map<String, Object> merged = extractStructuredData(mergeResponse);
        if (merged == null) {
            // structured_data 없으면 text 응답을 JSON 파싱 시도
            String text = mergeResponse.textContent();
            try {
                merged = MAPPER.readValue(text, new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException(
                        "LLM_CALL step '" + stepId + "': 자동분할 취합 결과를 JSON으로 파싱할 수 없습니다: " + e.getMessage());
            }
        }

        merged = new LinkedHashMap<>(merged);
        merged.put("_merge_input_tokens", mergeResponse.usage().inputTokens());
        merged.put("_merge_output_tokens", mergeResponse.usage().outputTokens());
        return merged;
    }

    // ═══════════════════════════════════════════════
    // 공통 유틸리티
    // ═══════════════════════════════════════════════

    /**
     * LLM 단일 호출 — 어댑터/모델/메시지/토큰을 받아 호출
     */
    /** 하위 호환: thinking 설정 없는 호출 */
    private LLMResponse callLlm(LLMAdapter adapter, String resolvedModel,
                                 String system, String prompt,
                                 Map<String, Object> responseSchema,
                                 int maxTokens, StepContext context) {
        return callLlm(adapter, resolvedModel, system, prompt, responseSchema,
                maxTokens, context, null, null);
    }

    private LLMResponse callLlm(LLMAdapter adapter, String resolvedModel,
                                 String system, String prompt,
                                 Map<String, Object> responseSchema,
                                 int maxTokens, StepContext context,
                                 Boolean extendedThinking, Integer thinkingBudgetTokens) {
        List<UnifiedMessage> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, system));
        }
        messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, prompt));

        ModelConfig modelConfig = new ModelConfig(null, maxTokens, null, null,
                extendedThinking, thinkingBudgetTokens);
        LLMRequest request = new LLMRequest(resolvedModel, messages, null,
                modelConfig, false, context.workflowRunId(), null, responseSchema);

        try {
            return adapter.chat(request).get();
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * LLMResponse에서 structured_data 추출
     */
    private Map<String, Object> extractStructuredData(LLMResponse response) {
        return response.content().stream()
                .filter(b -> b instanceof ContentBlock.Structured)
                .map(b -> ((ContentBlock.Structured) b).data())
                .findFirst().orElse(null);
    }

    /**
     * LLMResponse → 표준 결과 Map 변환
     */
    private Map<String, Object> buildResult(LLMResponse response, String resolvedModel) {
        String output = response.textContent();
        Map<String, Object> structuredData = extractStructuredData(response);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", output);
        if (structuredData != null) {
            result.put("structured_data", structuredData);
        }
        result.put("model", resolvedModel);
        result.put("input_tokens", response.usage().inputTokens());
        result.put("output_tokens", response.usage().outputTokens());
        return result;
    }
}
