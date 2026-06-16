package com.platform.llm.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.ContentBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CR-113: 구조화 출력(response_schema) 단일 인터페이스 정규화.
 *
 * <p><b>문제</b>: response_schema 를 준 LLM_CALL 응답에서 {@code structured_data} 를 채우는 책임은
 * 어댑터 경계에 있어야 한다. 그러나 실측상 {@link ContentBlock.Structured} 를 만드는 어댑터는
 * AnthropicAdapter (tool_use {@code structured_output}) 하나뿐이고, 나머지 6개 어댑터
 * (OpenAI / OpenAICompatible / Bedrock / Vertex / Ollama / ClaudeCli)는 응답을
 * {@link ContentBlock.Text} 로만 내놓는다 — JSON 본문이 ```json 펜스에 싸이거나 순수 JSON 인데도
 * Structured 블록이 없어 {@code LlmCallStepExecutor.extractStructuredData()} 가 null 을 반환한다.
 * 그 결과 소비앱(WMS/OMS/MALL/bidding)이 모델마다 펜스를 직접 벗기는 코드를 복붙하게 된다.
 *
 * <p><b>해결</b>: "밑단에 어떤 LLM(Claude/GPT/Gemini/Ollama/CLI)을 써도 단일 인터페이스" 원칙대로,
 * 텍스트로 응답하는 어댑터가 응답을 조립할 때 이 정규화를 한 곳에서 통과시킨다.
 * response_schema 가 있고 응답에 Structured 블록이 없으면 Text 본문(JSON String)을 <b>객체화</b>해
 * {@link ContentBlock.Structured} 를 부착한다. 모델별 차이(```json 펜스 유무·앞뒤 설명문)는 여기
 * 한 곳의 최소 예외처리로 흡수된다. Anthropic 처럼 이미 Structured 가 있으면 그대로 통과(no-op).
 *
 * <p><b>전제</b>: 대부분 어댑터는 응답을 JSON 으로 강제(Anthropic tool_use / OpenAI json_schema
 * strict / Ollama json_object)하므로 출력은 JSON 으로 통일돼 있다. 이 클래스는 그 JSON String 을
 * 객체(Map)로 읽어들이는 책임만 진다 — JSON 외 형식(MD/key:value)으로의 변환은 하지 않는다.
 * 강제 장치가 없는 CLI 경로가 JSON 이 아닌 형식을 흔들리게 내는지는 별도 실측 대상.
 */
public final class StructuredOutputNormalizer {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputNormalizer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** ```json / ``` 등 코드 펜스. (?s) = DOTALL (개행 포함). */
    private static final Pattern FENCE = Pattern.compile("(?s)```[a-zA-Z]*\\s*(.*?)\\s*```");

    private StructuredOutputNormalizer() {}

    /**
     * 어댑터 응답 content 를 받아, response_schema 가 있으면 Structured 블록을 보강해 반환한다.
     *
     * @param content        어댑터가 조립한 원본 content 블록 목록
     * @param responseSchema LLM_CALL 의 response_schema (null/empty 면 정규화 없이 원본 반환)
     * @return Structured 블록이 보장된 content (보강 불가/불필요 시 원본 그대로)
     */
    public static List<ContentBlock> normalize(List<ContentBlock> content, Map<String, Object> responseSchema) {
        if (responseSchema == null || responseSchema.isEmpty()) {
            return content;
        }
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 이미 Structured 가 있으면(Anthropic 경로) 그대로 — 중복 부착 금지.
        boolean hasStructured = content.stream().anyMatch(b -> b instanceof ContentBlock.Structured);
        if (hasStructured) {
            return content;
        }

        String text = content.stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", (a, b) -> a + b);
        if (text == null || text.isBlank()) {
            return content;
        }

        Map<String, Object> data = parseToMap(stripFence(text));
        if (data == null) {
            // JSON 으로 객체화 못 함 — 원본 Text 유지(소비앱이 raw 로 받음).
            log.warn("response_schema 선언됐으나 응답 텍스트를 JSON 으로 객체화하지 못함 (앞 120자: {})",
                    text.length() > 120 ? text.substring(0, 120) : text);
            return content;
        }

        // 원본 블록(Text 등) 유지 + Structured 부착. extractStructuredData 는 Structured 를 우선 줍는다.
        List<ContentBlock> normalized = new ArrayList<>(content);
        normalized.add(new ContentBlock.Structured("response_schema", data));
        return normalized;
    }

    /**
     * ```json 펜스 제거 + 앞뒤 설명문이 섞인 경우 첫 '{'~마지막 '}' 구간만 추출.
     * (배열 최상위는 현재 structured_data Map 계약상 미지원 — object 만 추출.)
     */
    static String stripFence(String text) {
        String t = text.trim();
        Matcher m = FENCE.matcher(t);
        if (m.find()) {
            t = m.group(1).trim();
        }
        int objStart = t.indexOf('{');
        int objEnd = t.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return t.substring(objStart, objEnd + 1);
        }
        return t;
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** JSON 텍스트를 Map 으로 객체화한다. JSON 이 아니면 null. */
    private static Map<String, Object> parseToMap(String text) {
        try {
            return JSON.readValue(text, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }
}
