package com.platform.session;

import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 대화 요약 생성기 (PRD-126).
 *
 * 이전 대화 메시지를 저가 모델(Haiku)로 요약하여 컨텍스트를 압축한다.
 * SYSTEM 메시지는 요약 대상에서 제외.
 * 요약 실패 시 null 반환 (호출자가 기존 트리밍 방식으로 폴백).
 */
@Component
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);
    private static final String SUMMARY_MODEL = "anthropic/claude-haiku-4-5";
    private static final String SUMMARY_PROMPT =
            "아래 대화 내용을 간결하게 요약해라. " +
            "핵심 결정사항, 사용자 요청, 중요한 맥락을 보존하되 불필요한 반복은 제거. " +
            "한국어로 작성하고, 500자 이내로 요약.";

    private final LLMAdapterRegistry adapterRegistry;

    public ConversationSummarizer(LLMAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    /**
     * 메시지 목록을 요약한다.
     * SYSTEM 메시지는 제외하고 USER/ASSISTANT 대화만 요약.
     *
     * @return 요약 텍스트, 실패 시 null
     */
    public String summarize(List<UnifiedMessage> messagesToSummarize) {
        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
            return null;
        }

        // SYSTEM 제외, 대화 내용만 텍스트로 변환
        String conversationText = messagesToSummarize.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .map(this::messageToText)
                .collect(Collectors.joining("\n"));

        if (conversationText.isBlank()) {
            return null;
        }

        try {
            LLMAdapter adapter = adapterRegistry.getAdapter(SUMMARY_MODEL);

            List<UnifiedMessage> messages = new ArrayList<>();
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, SUMMARY_PROMPT));
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, conversationText));

            LLMRequest request = new LLMRequest(SUMMARY_MODEL, messages);
            LLMResponse response = adapter.chat(request).get();

            String summary = extractText(response);
            log.info("대화 요약 생성: 원본 {}자 → 요약 {}자",
                    conversationText.length(), summary != null ? summary.length() : 0);
            return summary;

        } catch (Exception e) {
            log.warn("대화 요약 실패 (기존 트리밍으로 폴백): {}", e.getMessage());
            return null;
        }
    }

    private String messageToText(UnifiedMessage msg) {
        String role = switch (msg.role()) {
            case USER -> "사용자";
            case ASSISTANT -> "어시스턴트";
            default -> msg.role().name();
        };
        String text = msg.content().stream()
                .filter(cb -> cb instanceof ContentBlock.Text)
                .map(cb -> ((ContentBlock.Text) cb).text())
                .collect(Collectors.joining(" "));
        return role + ": " + text;
    }

    private String extractText(LLMResponse response) {
        if (response == null || response.content() == null) return null;
        return response.content().stream()
                .filter(cb -> cb instanceof ContentBlock.Text)
                .map(cb -> ((ContentBlock.Text) cb).text())
                .collect(Collectors.joining("\n"));
    }
}
