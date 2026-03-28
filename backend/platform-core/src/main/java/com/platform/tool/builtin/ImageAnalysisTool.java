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
 * 이미지 분석 내장 도구 (CR-019).
 *
 * 멀티모달 LLM(Claude Vision, GPT-4V)을 호출하여 실제 이미지 분석 수행.
 */
@Component
public class ImageAnalysisTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ImageAnalysisTool.class);

    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final ConnectionRepository connectionRepository;

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "analyze_image",
            "이미지를 분석하여 설명, 텍스트 추출, 데이터 인식 등을 수행합니다.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "image_base64", Map.of(
                                    "type", "string",
                                    "description", "base64 인코딩된 이미지 (PNG, JPG 등)"
                            ),
                            "prompt", Map.of(
                                    "type", "string",
                                    "description", "분석 요청 (예: '이 이미지에서 텍스트를 추출해주세요')"
                            ),
                            "media_type", Map.of(
                                    "type", "string",
                                    "description", "이미지 MIME 타입 (기본: image/png)"
                            )
                    ),
                    "required", List.of("image_base64", "prompt")
            )
    );

    public ImageAnalysisTool(ConnectionAdapterFactory connectionAdapterFactory,
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
        String imageBase64 = String.valueOf(input.getOrDefault("image_base64", ""));
        String prompt = String.valueOf(input.getOrDefault("prompt", ""));
        String mediaType = String.valueOf(input.getOrDefault("media_type", "image/png"));

        if (imageBase64.isEmpty() || prompt.isEmpty()) {
            return "{\"success\": false, \"error\": \"image_base64 and prompt are required\"}";
        }

        log.info("analyze_image: prompt='{}', image_size={} chars", prompt, imageBase64.length());

        try {
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
                            UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM,
                                    "You are an image analysis expert. Analyze the image and respond to the user's request precisely."),
                            new UnifiedMessage(UnifiedMessage.Role.USER, List.of(
                                    ContentBlock.Image.ofBase64(mediaType, imageBase64),
                                    new ContentBlock.Text(prompt)
                            ))
                    )
            );

            LLMResponse response = adapter.chat(request).get();

            String analysis = response.textContent().trim();

            log.info("analyze_image completed: result_length={}", analysis.length());

            return "{\"success\": true"
                    + ", \"analysis\": " + jsonEscape(analysis)
                    + ", \"media_type\": \"" + mediaType + "\""
                    + ", \"image_size_bytes\": " + (imageBase64.length() * 3 / 4) + "}";

        } catch (Exception e) {
            log.error("analyze_image failed: {}", e.getMessage());
            return "{\"success\": false, \"error\": " + jsonEscape(e.getMessage()) + "}";
        }
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
