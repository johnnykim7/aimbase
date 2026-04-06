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

    public LlmCallStepExecutor(ModelRouter modelRouter, ConnectionAdapterFactory connectionAdapterFactory) {
        this.modelRouter = modelRouter;
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

        String partPrompt = "전체 작업 중 아래 범위만 생성하세요.\n\n" +
                "=== 범위 ===\n" + scope + "\n\n" +
                "=== 원본 요청 ===\n" + prompt + "\n\n" +
                "JSON 형식으로만 응답하세요.";

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

        String planSystem = "당신은 대규모 JSON 생성 작업을 분할하는 전문가입니다. " +
                "작업을 독립적으로 생성 가능한 파트로 나누세요.";

        String planPrompt = "다음 작업의 출력이 너무 커서 한 번에 생성할 수 없습니다.\n" +
                "독립적으로 생성 가능한 파트로 분할 계획을 세우세요.\n\n" +
                "=== 원본 시스템 프롬프트 ===\n" + (system != null ? system : "(없음)") + "\n\n" +
                "=== 원본 요청 ===\n" + prompt + "\n\n" +
                "=== 출력 스키마 ===\n" + schemaStr + "\n\n" +
                "규칙:\n" +
                "- 각 파트는 최대 3000 토큰 이내로 생성 가능해야 합니다\n" +
                "- 파트 수는 2~" + SPLIT_MAX_PARTS + "개\n" +
                "- 각 파트의 scope는 구체적이고 명확해야 합니다\n" +
                "- 나중에 파트별 결과를 합쳐서 최종 스키마를 완성할 수 있어야 합니다";

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

        String mergeSystem = "당신은 JSON 조각을 하나의 완전한 구조로 병합하는 전문가입니다. " +
                "주어진 스키마에 정확히 맞는 하나의 JSON을 생성하세요.";

        String mergePrompt = "다음 조각들을 아래 스키마에 맞게 하나의 완전한 JSON으로 합치세요.\n\n" +
                "=== 목표 스키마 ===\n" + schemaStr + "\n\n" +
                "=== 조각들 ===\n" + fragments +
                "규칙:\n" +
                "- 모든 조각의 내용을 빠짐없이 포함하세요\n" +
                "- 스키마에 맞는 구조로 재배치하세요\n" +
                "- 중복은 제거하되 내용은 누락하지 마세요";

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
