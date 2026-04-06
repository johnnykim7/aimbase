package com.platform.context;

import com.platform.domain.ContextRecipeEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.repository.ContextRecipeRepository;
import com.platform.session.ContextWindowManager;
import com.platform.session.MemoryService;
import com.platform.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CR-029 (PRD-182): 컨텍스트 조립 엔진.
 *
 * OrchestratorEngine의 인라인 조립 로직을 추출하여,
 * recipe 기반 layer 정의 + budget/priority eviction + freshness + dedup을 수행.
 *
 * 품질 비교 가능성: AssemblyTrace에 조립 과정을 기록하여
 * ClaudeTool과 동일 과제를 재실행해 비교할 수 있다.
 */
@Component
public class ContextAssemblyEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblyEngine.class);
    private static final int DEFAULT_TOKEN_BUDGET = 100_000;
    private static final int OUTPUT_TOKEN_RESERVE = 20_000;

    // CR-029: 네이티브 도구 사용 지침 (system prompt)
    private static final String TOOL_USAGE_PROMPT = """
            # Using your tools
            - Do NOT guess file names or paths. Always use builtin_glob or builtin_grep first to find actual file paths, then pass those exact paths to builtin_file_read.
            - When analyzing a directory, start with builtin_workspace_snapshot or builtin_glob to get the file list, then read specific files with builtin_file_read.
            - You can call multiple tools in a single response. If the calls are independent, make them in parallel.
            - Results from previous tool calls are available in the conversation. Use the exact file paths returned by glob/grep when calling file_read.
            - file_path parameters must be absolute paths.
            - For code structure analysis, use builtin_structured_search instead of builtin_grep.
            - For document section reading, use builtin_document_section_read instead of reading the entire file.
            """;
    private static final int MAX_COMPACT_FAILURES = 3;

    private final SessionStore sessionStore;
    private final MemoryService memoryService;
    private final ContextWindowManager contextWindowManager;
    private final ContextRecipeRepository recipeRepository;

    // 세션별 연속 압축 실패 카운터 (circuit breaker)
    private final Map<String, Integer> compactFailures = new HashMap<>();

    public ContextAssemblyEngine(
            SessionStore sessionStore,
            MemoryService memoryService,
            ContextWindowManager contextWindowManager,
            ContextRecipeRepository recipeRepository) {
        this.sessionStore = sessionStore;
        this.memoryService = memoryService;
        this.contextWindowManager = contextWindowManager;
        this.recipeRepository = recipeRepository;
    }

    /**
     * 컨텍스트를 조립한다.
     *
     * @param sessionId   현재 세션
     * @param recipeId    레시피 ID (null이면 기본 assembly)
     * @param request     현재 ChatRequest
     * @return 조립 결과 (messages + trace)
     */
    public AssemblyResult assemble(String sessionId, String recipeId, ChatRequest request) {
        long start = System.currentTimeMillis();

        // 1. Recipe 해석
        ContextRecipeEntity recipe = resolveRecipe(recipeId, sessionId);
        String resolveReason = recipe != null
                ? "recipe_id=" + recipe.getId()
                : "default_assembly";

        // 2. Recipe가 없으면 기존 default assembly (하위호환)
        if (recipe == null) {
            return assembleDefault(sessionId, request, start, resolveReason);
        }

        // 3. Recipe 기반 조립
        return assembleWithRecipe(sessionId, request, recipe, start, resolveReason);
    }

    /**
     * 기존 OrchestratorEngine과 동일한 default assembly.
     * recipe가 없을 때 하위호환을 보장한다.
     */
    private AssemblyResult assembleDefault(String sessionId, ChatRequest request,
                                            long start, String resolveReason) {
        // 기존 로직: memoryContext + history + request + trim
        List<UnifiedMessage> memoryContext = memoryService.buildMemoryContext(
                sessionId, request.userId());
        List<UnifiedMessage> history = sessionStore.getMessages(sessionId);

        List<UnifiedMessage> allMessages = new ArrayList<>();
        // CR-029: 도구 사용 지침을 최상위 SYSTEM 메시지로 주입
        allMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, TOOL_USAGE_PROMPT));
        allMessages.addAll(memoryContext);
        allMessages.addAll(history);
        if (request.messages() != null) {
            allMessages.addAll(request.messages());
        }

        // 트리밍
        List<UnifiedMessage> trimmed = contextWindowManager.trim(allMessages,
                DEFAULT_TOKEN_BUDGET);

        int estimatedTokens = contextWindowManager.estimateTokens(trimmed);

        AssemblyTrace trace = new AssemblyTrace(
                null, resolveReason,
                List.of(
                        new AssembledLayer("memory", memoryContext, estimateTokens(memoryContext), 100, false),
                        new AssembledLayer("history", history, estimateTokens(history), 80, true),
                        new AssembledLayer("user_input", request.messages() != null ? request.messages() : List.of(), estimateTokens(request.messages()), 100, false)
                ),
                List.of(), List.of(),
                estimatedTokens, DEFAULT_TOKEN_BUDGET,
                System.currentTimeMillis() - start
        );

        return new AssemblyResult(trimmed, trace);
    }

    /**
     * Recipe 기반 조립: layer별 source 수집 → budget 적용 → trace 생성.
     */
    @SuppressWarnings("unchecked")
    private AssemblyResult assembleWithRecipe(String sessionId, ChatRequest request,
                                              ContextRecipeEntity recipe,
                                              long start, String resolveReason) {
        Map<String, Object> recipeData = recipe.getRecipe();
        int totalBudget = recipeData.containsKey("totalTokenBudget")
                ? ((Number) recipeData.get("totalTokenBudget")).intValue()
                : DEFAULT_TOKEN_BUDGET;
        List<Map<String, Object>> layers = (List<Map<String, Object>>) recipeData.getOrDefault("layers", List.of());

        List<AssembledLayer> assembled = new ArrayList<>();
        List<String> evicted = new ArrayList<>();
        List<String> stale = new ArrayList<>();

        for (Map<String, Object> layerDef : layers) {
            boolean enabled = Boolean.TRUE.equals(layerDef.getOrDefault("enabled", true));
            if (!enabled) continue;

            String source = (String) layerDef.get("source");
            int maxTokens = layerDef.containsKey("maxTokens")
                    ? ((Number) layerDef.get("maxTokens")).intValue() : Integer.MAX_VALUE;
            int priority = layerDef.containsKey("priority")
                    ? ((Number) layerDef.get("priority")).intValue() : 50;
            boolean evictable = Boolean.TRUE.equals(layerDef.getOrDefault("evictable", true));

            // Source Provider 호출
            List<UnifiedMessage> messages = provideSource(source, sessionId, request);
            int tokens = estimateTokens(messages);

            // 토큰 제한 적용
            if (tokens > maxTokens) {
                messages = truncateMessages(messages, maxTokens);
                tokens = maxTokens;
            }

            assembled.add(new AssembledLayer(source, messages, tokens, priority, evictable));
        }

        // Budget eviction: priority 낮은 evictable layer부터 제거
        int totalTokens = assembled.stream().mapToInt(AssembledLayer::estimatedTokens).sum();
        if (totalTokens > totalBudget) {
            assembled.sort(Comparator.comparingInt(AssembledLayer::priority));
            Iterator<AssembledLayer> it = assembled.iterator();
            while (totalTokens > totalBudget && it.hasNext()) {
                AssembledLayer layer = it.next();
                if (layer.evictable()) {
                    totalTokens -= layer.estimatedTokens();
                    evicted.add(layer.source());
                    it.remove();
                }
            }
        }

        // 최종 메시지 합치기 (priority 높은 순)
        assembled.sort(Comparator.comparingInt(AssembledLayer::priority).reversed());
        List<UnifiedMessage> finalMessages = new ArrayList<>();
        for (AssembledLayer layer : assembled) {
            finalMessages.addAll(layer.messages());
        }

        AssemblyTrace trace = new AssemblyTrace(
                recipe.getId(), resolveReason,
                assembled, evicted, stale,
                totalTokens, totalBudget,
                System.currentTimeMillis() - start
        );

        return new AssemblyResult(finalMessages, trace);
    }

    /**
     * Source Provider: source 이름에 따라 적절한 메시지를 수집.
     */
    private List<UnifiedMessage> provideSource(String source, String sessionId, ChatRequest request) {
        return switch (source) {
            case "system_policy" -> {
                List<UnifiedMessage> policyMessages = new ArrayList<>();
                // CR-029: 도구 사용 지침을 system_policy 소스 최상위에 주입
                policyMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, TOOL_USAGE_PROMPT));
                policyMessages.addAll(memoryService.buildMemoryContext(sessionId, request.userId()));
                yield policyMessages;
            }
            case "session_summary" -> {
                // 세션 요약이 있으면 SYSTEM 메시지로 주입
                var session = sessionStore.getMessages(sessionId);
                yield List.of(); // 기본: 빈 목록 (요약은 memory에서 처리)
            }
            case "recent_conversation" -> sessionStore.getMessages(sessionId);
            case "user_input" -> request.messages() != null ? request.messages() : List.of();
            // 나머지 source는 향후 Provider 추가 시 구현
            default -> {
                log.debug("Unknown context source: {}, skipping", source);
                yield List.of();
            }
        };
    }

    /**
     * Recipe 해석: explicit ID > domain default > null (default assembly).
     */
    private ContextRecipeEntity resolveRecipe(String recipeId, String sessionId) {
        if (recipeId != null && !recipeId.isBlank()) {
            return recipeRepository.findById(recipeId).orElse(null);
        }
        // 향후: session meta → domain default 체인
        return null;
    }

    /**
     * 하이브리드 토큰 추정: char / 4.
     * 향후 API 실측 값과 결합.
     */
    private int estimateTokens(List<UnifiedMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int chars = 0;
        for (UnifiedMessage msg : messages) {
            if (msg.content() != null) {
                for (var block : msg.content()) {
                    if (block instanceof com.platform.llm.model.ContentBlock.Text text) {
                        chars += text.text().length();
                    }
                }
            }
        }
        return chars / 4;
    }

    /**
     * 메시지를 maxTokens 이내로 잘라냄.
     */
    private List<UnifiedMessage> truncateMessages(List<UnifiedMessage> messages, int maxTokens) {
        List<UnifiedMessage> result = new ArrayList<>();
        int tokens = 0;
        for (UnifiedMessage msg : messages) {
            int msgTokens = estimateTokens(List.of(msg));
            if (tokens + msgTokens > maxTokens) break;
            result.add(msg);
            tokens += msgTokens;
        }
        return result;
    }

    /**
     * Auto-compact circuit breaker: 연속 3회 실패 시 압축 건너뜀.
     */
    public boolean shouldSkipCompact(String sessionId) {
        return compactFailures.getOrDefault(sessionId, 0) >= MAX_COMPACT_FAILURES;
    }

    public void recordCompactFailure(String sessionId) {
        compactFailures.merge(sessionId, 1, Integer::sum);
    }

    public void resetCompactFailure(String sessionId) {
        compactFailures.remove(sessionId);
    }
}
