package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CR-031 PRD-213: Extract Memories 자동화.
 *
 * 대화 5턴(10메시지) 이상 완료 시 비동기(Virtual Thread)로 Haiku를 호출하여
 * 향후 재사용할 핵심 정보를 자동 추출한다.
 *
 * <ul>
 *   <li>4가지 타입: task-specific, person context, pattern/best practice, personal preferences</li>
 *   <li>기존 LONG_TERM 메모리와 content 비교하여 중복 방지</li>
 *   <li>fire-and-forget 비동기 — 대화 흐름 미영향</li>
 *   <li>턴 기반 throttle — 동일 세션 연속 추출 방지</li>
 * </ul>
 */
@Service
public class MemoryAutoExtractService {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoExtractService.class);

    static final int MIN_MESSAGES_FOR_EXTRACTION = 10; // 5턴 = 10메시지
    private static final String EXTRACT_MODEL = "anthropic/claude-haiku-4-5";
    private static final String EXTRACT_PROMPT = """
            아래 대화에서 향후 재사용할 핵심 정보를 추출하라.
            각 항목은 한 줄로, 아래 4가지 타입 중 하나로 분류하여 출력:

            - [task] 작업 관련 사실 (예: "프로젝트 X는 Spring Boot 3.4 사용")
            - [person] 사용자 정보 (예: "사용자는 시니어 Java 개발자")
            - [pattern] 패턴/모범사례 (예: "이 팀은 PR 전에 반드시 테스트 실행")
            - [preference] 개인 선호 (예: "한국어 응답 선호")

            최대 5개. 이미 알려진 일반 상식은 제외. 없으면 "없음" 출력.
            """;

    private final LLMAdapterRegistry adapterRegistry;
    private final ConversationMemoryRepository memoryRepository;
    private final MemoryService memoryService;

    // 세션별 마지막 추출 시점 (중복 추출 방지)
    private final Map<String, Long> lastExtractionTime = new ConcurrentHashMap<>();
    private static final long THROTTLE_MS = 60_000; // 1분 이내 재추출 방지

    public MemoryAutoExtractService(LLMAdapterRegistry adapterRegistry,
                                     ConversationMemoryRepository memoryRepository,
                                     MemoryService memoryService) {
        this.adapterRegistry = adapterRegistry;
        this.memoryRepository = memoryRepository;
        this.memoryService = memoryService;
    }

    /**
     * 대화 종료 시 메모리 자동 추출을 시도한다.
     * 조건 미충족 시 조용히 스킵. fire-and-forget 비동기 실행.
     */
    public void extractIfEligible(String sessionId, String userId,
                                   List<UnifiedMessage> messages) {
        if (sessionId == null || userId == null) return;
        if (messages == null || messages.size() < MIN_MESSAGES_FOR_EXTRACTION) return;

        // throttle 체크
        Long lastTime = lastExtractionTime.get(sessionId);
        if (lastTime != null && System.currentTimeMillis() - lastTime < THROTTLE_MS) {
            log.debug("메모리 추출 스킵 — throttle (sessionId={})", sessionId);
            return;
        }

        lastExtractionTime.put(sessionId, System.currentTimeMillis());

        // fire-and-forget 비동기
        Thread.startVirtualThread(() -> {
            try {
                List<String> extracted = extractMemories(messages);
                if (extracted.isEmpty()) {
                    log.debug("추출된 메모리 없음 (sessionId={})", sessionId);
                    return;
                }

                int newCount = 0;
                int updateCount = 0;

                List<ConversationMemoryEntity> existingMemories =
                        memoryRepository.findByUserIdAndMemoryTypeOrderByCreatedAtDesc(
                                userId, MemoryLayer.LONG_TERM.name());

                for (String memory : extracted) {
                    // 단순 content 비교로 중복 체크 (cosine 대체)
                    boolean isDuplicate = existingMemories.stream()
                            .anyMatch(existing -> isSimilar(existing.getContent(), memory));

                    if (!isDuplicate) {
                        memoryService.save(null, userId, MemoryLayer.LONG_TERM, memory);
                        newCount++;
                    } else {
                        updateCount++;
                    }
                }

                log.info("메모리 자동 추출 완료: sessionId={}, 추출 {}개, 신규 {}개, 중복 {}개",
                        sessionId, extracted.size(), newCount, updateCount);

            } catch (Exception e) {
                log.warn("메모리 자동 추출 실패 (무시): sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        });
    }

    /**
     * Haiku로 대화에서 핵심 정보를 추출한다.
     */
    List<String> extractMemories(List<UnifiedMessage> messages) {
        String conversationText = messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .map(m -> {
                    String role = m.role() == UnifiedMessage.Role.USER ? "사용자" : "어시스턴트";
                    String text = m.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .collect(Collectors.joining(" "));
                    return role + ": " + text;
                })
                .collect(Collectors.joining("\n"));

        if (conversationText.isBlank()) return List.of();

        // 토큰 제한: 대화가 너무 길면 최근 부분만
        if (conversationText.length() > 20_000) {
            conversationText = conversationText.substring(conversationText.length() - 20_000);
        }

        try {
            LLMAdapter adapter = adapterRegistry.getAdapter(EXTRACT_MODEL);
            List<UnifiedMessage> promptMessages = List.of(
                    UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, EXTRACT_PROMPT),
                    UnifiedMessage.ofText(UnifiedMessage.Role.USER, conversationText));

            LLMResponse response = adapter.chat(new LLMRequest(EXTRACT_MODEL, promptMessages)).get();

            String resultText = response.content().stream()
                    .filter(b -> b instanceof ContentBlock.Text)
                    .map(b -> ((ContentBlock.Text) b).text())
                    .collect(Collectors.joining("\n"));

            if (resultText.isBlank() || resultText.trim().equals("없음")) {
                return List.of();
            }

            // 각 줄을 개별 메모리로 파싱
            return resultText.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .filter(line -> line.startsWith("-") || line.startsWith("["))
                    .map(line -> line.startsWith("- ") ? line.substring(2) : line)
                    .limit(5)
                    .toList();

        } catch (Exception e) {
            log.warn("Haiku 메모리 추출 호출 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 단순 문자열 유사도 비교.
     * 정확한 cosine similarity 대신, 포함 관계 + Jaccard 유사도로 간이 판정.
     */
    static boolean isSimilar(String existing, String candidate) {
        if (existing == null || candidate == null) return false;
        String e = existing.toLowerCase().trim();
        String c = candidate.toLowerCase().trim();

        // 한쪽이 다른 쪽을 포함하면 중복
        if (e.contains(c) || c.contains(e)) return true;

        // 단어 기반 Jaccard 유사도
        var eWords = java.util.Set.of(e.split("\\s+"));
        var cWords = java.util.Set.of(c.split("\\s+"));

        long intersection = eWords.stream().filter(cWords::contains).count();
        long union = eWords.size() + cWords.size() - intersection;

        return union > 0 && (double) intersection / union >= 0.7;
    }
}
