package com.platform.session;

import com.platform.domain.ToolExecutionLogEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ToolExecutionLogRepository;
import com.platform.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CR-031 PRD-211: Post-Compact Recovery — 압축 후 컨텍스트 복구.
 *
 * ContextWindowManager의 압축(Level 2~4) 실행 후 손실된 컨텍스트를 자동 복구한다.
 * <ul>
 *   <li>ToolExecutionLog에서 최근 참조 파일 top-5 추출 → 요약 재주입</li>
 *   <li>LONG_TERM 메모리 재확인 → system message 갱신</li>
 *   <li>활성 MCP 도구 스키마 요약 재주입</li>
 * </ul>
 *
 * 토큰 예산: 파일당 5,000 토큰, 전체 50,000 토큰 이내.
 */
@Service
public class PostCompactRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PostCompactRecoveryService.class);

    static final int MAX_FILES_TO_RESTORE = 5;
    static final int TOKENS_PER_FILE = 5_000;
    static final int TOTAL_TOKEN_BUDGET = 50_000;
    static final int TOKENS_PER_CHAR = 4;

    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final MemoryService memoryService;
    private final ToolRegistry toolRegistry;

    public PostCompactRecoveryService(ToolExecutionLogRepository toolExecutionLogRepository,
                                      MemoryService memoryService,
                                      ToolRegistry toolRegistry) {
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 압축 후 컨텍스트를 복구한다.
     *
     * @param sessionId        세션 ID
     * @param userId           사용자 ID (nullable)
     * @param compactedMessages 압축된 메시지 리스트
     * @param strategy         사용된 압축 전략
     * @return 복구 메시지가 삽입된 결과 리스트
     */
    public RecoveryResult recover(String sessionId, String userId,
                                  List<UnifiedMessage> compactedMessages,
                                  CompactionStrategy strategy) {
        if (sessionId == null || strategy == CompactionStrategy.SNIP) {
            return new RecoveryResult(compactedMessages, 0, 0, 0);
        }

        log.info("Post-Compact Recovery 시작: sessionId={}, strategy={}", sessionId, strategy);

        int budgetRemaining = TOTAL_TOKEN_BUDGET;
        List<UnifiedMessage> recoveryMessages = new ArrayList<>();

        // 1. 최근 참조 파일 복구
        int restoredFiles = 0;
        try {
            List<ToolExecutionLogEntity> recentFiles =
                    toolExecutionLogRepository.findRecentFileReferences(sessionId, MAX_FILES_TO_RESTORE);

            for (ToolExecutionLogEntity fileRef : recentFiles) {
                if (budgetRemaining <= 0) break;

                String fileSummary = buildFileSummary(fileRef);
                int tokens = estimateTokens(fileSummary);

                if (tokens > TOKENS_PER_FILE) {
                    fileSummary = truncateToTokens(fileSummary, TOKENS_PER_FILE);
                    tokens = TOKENS_PER_FILE;
                }

                if (tokens <= budgetRemaining) {
                    recoveryMessages.add(UnifiedMessage.ofText(
                            UnifiedMessage.Role.SYSTEM,
                            "[복구: 최근 참조 파일] " + fileSummary));
                    budgetRemaining -= tokens;
                    restoredFiles++;
                }
            }
        } catch (Exception e) {
            log.warn("파일 참조 복구 실패 (무시): {}", e.getMessage());
        }

        // 2. LONG_TERM 메모리 재주입
        int restoredMemories = 0;
        if (userId != null && budgetRemaining > 0) {
            try {
                List<UnifiedMessage> memoryContext =
                        memoryService.buildMemoryContext(sessionId, userId);

                for (UnifiedMessage mem : memoryContext) {
                    int tokens = estimateMessageTokens(mem);
                    if (tokens <= budgetRemaining) {
                        recoveryMessages.add(mem);
                        budgetRemaining -= tokens;
                        restoredMemories++;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("메모리 복구 실패 (무시): {}", e.getMessage());
            }
        }

        // 3. 활성 도구 스키마 요약 재주입
        int restoredToolSchemas = 0;
        if (budgetRemaining > 1000) {
            try {
                String toolSummary = toolRegistry.getToolDefs().stream()
                        .map(td -> "- " + td.name() + ": " + td.description())
                        .collect(Collectors.joining("\n"));

                if (!toolSummary.isBlank()) {
                    int tokens = estimateTokens(toolSummary);
                    if (tokens > budgetRemaining) {
                        toolSummary = truncateToTokens(toolSummary, budgetRemaining);
                        tokens = estimateTokens(toolSummary);
                    }
                    recoveryMessages.add(UnifiedMessage.ofText(
                            UnifiedMessage.Role.SYSTEM,
                            "[복구: 사용 가능한 도구]\n" + toolSummary));
                    budgetRemaining -= tokens;
                    restoredToolSchemas = 1;
                }
            } catch (Exception e) {
                log.warn("도구 스키마 복구 실패 (무시): {}", e.getMessage());
            }
        }

        // 복구 메시지를 compactedMessages의 SYSTEM 메시지 뒤에 삽입
        List<UnifiedMessage> result = insertRecoveryMessages(compactedMessages, recoveryMessages);

        int totalRestoredTokens = TOTAL_TOKEN_BUDGET - budgetRemaining;
        log.info("Post-Compact Recovery 완료: 파일 {}개, 메모리 {}개, 도구 스키마 {}개, 복구 토큰 {}",
                restoredFiles, restoredMemories, restoredToolSchemas, totalRestoredTokens);

        return new RecoveryResult(result, restoredFiles, restoredMemories, totalRestoredTokens);
    }

    private String buildFileSummary(ToolExecutionLogEntity fileRef) {
        StringBuilder sb = new StringBuilder();
        sb.append(fileRef.getToolName());

        if (fileRef.getInputSummary() != null) {
            sb.append(" — ").append(fileRef.getInputSummary());
        }

        if (fileRef.getOutputSummary() != null && !fileRef.getOutputSummary().isBlank()) {
            sb.append("\n").append(fileRef.getOutputSummary());
        }

        return sb.toString();
    }

    private List<UnifiedMessage> insertRecoveryMessages(
            List<UnifiedMessage> compactedMessages,
            List<UnifiedMessage> recoveryMessages) {
        if (recoveryMessages.isEmpty()) return compactedMessages;

        List<UnifiedMessage> result = new ArrayList<>();
        boolean recoveryInserted = false;

        for (UnifiedMessage msg : compactedMessages) {
            result.add(msg);
            // 마지막 SYSTEM 메시지 뒤에 복구 메시지 삽입
            if (!recoveryInserted && msg.role() == UnifiedMessage.Role.SYSTEM) {
                // 다음 메시지가 SYSTEM이 아니면 여기에 삽입
                int nextIdx = compactedMessages.indexOf(msg) + 1;
                if (nextIdx >= compactedMessages.size()
                        || compactedMessages.get(nextIdx).role() != UnifiedMessage.Role.SYSTEM) {
                    result.addAll(recoveryMessages);
                    recoveryInserted = true;
                }
            }
        }

        if (!recoveryInserted) {
            // SYSTEM 메시지가 없으면 맨 앞에 삽입
            result.addAll(0, recoveryMessages);
        }

        return result;
    }

    private int estimateTokens(String text) {
        return text.length() / TOKENS_PER_CHAR + 4;
    }

    private int estimateMessageTokens(UnifiedMessage msg) {
        return msg.content().stream()
                .mapToInt(block -> {
                    if (block instanceof ContentBlock.Text t) {
                        return t.text().length() / TOKENS_PER_CHAR + 4;
                    }
                    return 10;
                })
                .sum();
    }

    private String truncateToTokens(String text, int maxTokens) {
        int maxChars = maxTokens * TOKENS_PER_CHAR;
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n...(truncated)";
    }

    /**
     * 복구 결과.
     */
    public record RecoveryResult(
            List<UnifiedMessage> messages,
            int restoredFileCount,
            int restoredMemoryCount,
            int totalTokensRestored
    ) {}
}
