package com.platform.tool.builtin;

import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.repository.ConnectionRepository;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 번역 내장 도구 (CR-019).
 *
 * ConnectionAdapterFactory를 통해 등록된 LLM 연결로 실제 번역 수행.
 */
@Component
public class TranslationTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TranslationTool.class);

    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final ConnectionRepository connectionRepository;

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "translate_text",
            "텍스트를 다른 언어로 번역합니다.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "text", Map.of(
                                    "type", "string",
                                    "description", "번역할 텍스트"
                            ),
                            "source_lang", Map.of(
                                    "type", "string",
                                    "description", "원본 언어 (auto = 자동 감지, ko, en, ja, zh 등)"
                            ),
                            "target_lang", Map.of(
                                    "type", "string",
                                    "description", "대상 언어 (ko, en, ja, zh 등)"
                            )
                    ),
                    "required", List.of("text", "target_lang")
            )
    );

    public TranslationTool(ConnectionAdapterFactory connectionAdapterFactory,
                           ConnectionRepository connectionRepository) {
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.connectionRepository = connectionRepository;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String text = String.valueOf(input.getOrDefault("text", ""));
        String sourceLang = String.valueOf(input.getOrDefault("source_lang", "auto"));
        String targetLang = String.valueOf(input.getOrDefault("target_lang", ""));

        if (text.isEmpty() || targetLang.isEmpty()) {
            return "{\"success\": false, \"error\": \"text and target_lang are required\"}";
        }

        log.info("translate_text: {} → {}, text_length={}", sourceLang, targetLang, text.length());

        try {
            String systemPrompt = "You are a professional translator. "
                    + "Translate the given text to " + langName(targetLang) + ". "
                    + "Output ONLY the translated text, nothing else. No explanations, no quotes.";

            String userPrompt = text;
            if (!"auto".equals(sourceLang)) {
                userPrompt = "[Source language: " + langName(sourceLang) + "]\n\n" + text;
            }

            // LLM 타입 연결 중 활성 상태인 것을 우선 사용
            var conn = connectionRepository.findByType("llm").stream()
                    .filter(c -> !"inactive".equals(c.getStatus()))
                    .findFirst()
                    .or(() -> connectionRepository.findAll().stream().findFirst())
                    .orElseThrow(() -> new RuntimeException("No LLM connection available"));

            LLMAdapter adapter = connectionAdapterFactory.getAdapter(conn.getId());
            String model = connectionAdapterFactory.resolveModel(conn.getId(), null);

            LLMRequest request = new LLMRequest(
                    model,
                    List.of(
                            UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, systemPrompt),
                            UnifiedMessage.ofText(UnifiedMessage.Role.USER, userPrompt)
                    )
            );

            LLMResponse response = adapter.chat(request).get();

            String translated = response.textContent().trim();

            log.info("translate_text completed: {} → {}, result_length={}",
                    sourceLang, targetLang, translated.length());

            return "{\"success\": true"
                    + ", \"translated_text\": " + jsonEscape(translated)
                    + ", \"source_lang\": \"" + sourceLang + "\""
                    + ", \"target_lang\": \"" + targetLang + "\""
                    + ", \"original_length\": " + text.length()
                    + ", \"translated_length\": " + translated.length() + "}";

        } catch (Exception e) {
            log.error("translate_text failed: {}", e.getMessage());
            return "{\"success\": false, \"error\": " + jsonEscape(e.getMessage()) + "}";
        }
    }

    private String langName(String code) {
        return switch (code.toLowerCase()) {
            case "ko" -> "Korean";
            case "en" -> "English";
            case "ja" -> "Japanese";
            case "zh" -> "Chinese";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "pt" -> "Portuguese";
            case "vi" -> "Vietnamese";
            case "th" -> "Thai";
            default -> code;
        };
    }

    private String jsonEscape(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
