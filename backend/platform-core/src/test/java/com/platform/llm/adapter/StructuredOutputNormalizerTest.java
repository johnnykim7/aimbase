package com.platform.llm.adapter;

import com.platform.llm.model.ContentBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-113: 구조화 출력 단일 인터페이스 정규화 단위 테스트.
 *
 * <p>핵심 계약: response_schema 가 있고 응답에 Structured 블록이 없으면 — 펜스든 순수 JSON 이든
 * 앞뒤 설명문이 섞였든 — Structured 블록을 보강해 어댑터 종류와 무관하게 동일 출력을 만든다.
 */
class StructuredOutputNormalizerTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string")));

    private Map<String, Object> structuredData(List<ContentBlock> content) {
        return content.stream()
                .filter(b -> b instanceof ContentBlock.Structured)
                .map(b -> ((ContentBlock.Structured) b).data())
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("```json 펜스로 감싼 응답 → Structured 부착")
    void fencedJson() {
        var content = List.<ContentBlock>of(
                new ContentBlock.Text("```json\n{\"name\": \"aimbase\"}\n```"));
        var result = StructuredOutputNormalizer.normalize(content, SCHEMA);
        assertThat(structuredData(result)).containsEntry("name", "aimbase");
    }

    @Test
    @DisplayName("펜스 없는 순수 JSON 응답 → Structured 부착")
    void rawJson() {
        var content = List.<ContentBlock>of(new ContentBlock.Text("{\"name\": \"gpt\"}"));
        var result = StructuredOutputNormalizer.normalize(content, SCHEMA);
        assertThat(structuredData(result)).containsEntry("name", "gpt");
    }

    @Test
    @DisplayName("앞뒤 설명문이 섞인 응답 → 첫 '{'~마지막 '}' 추출해 Structured 부착")
    void jsonWithSurroundingText() {
        var content = List.<ContentBlock>of(new ContentBlock.Text(
                "Here is the result:\n{\"name\": \"ollama\"}\nHope this helps."));
        var result = StructuredOutputNormalizer.normalize(content, SCHEMA);
        assertThat(structuredData(result)).containsEntry("name", "ollama");
    }

    @Test
    @DisplayName("response_schema 없으면 정규화하지 않음 (자유 텍스트 보호)")
    void noSchemaNoNormalize() {
        var content = List.<ContentBlock>of(new ContentBlock.Text("{\"name\": \"x\"}"));
        var result = StructuredOutputNormalizer.normalize(content, null);
        assertThat(structuredData(result)).isNull();
        assertThat(result).isSameAs(content);
    }

    @Test
    @DisplayName("이미 Structured 가 있으면(Anthropic 경로) 그대로 통과 — 중복 부착 안 함")
    void alreadyStructuredIsNoOp() {
        var content = List.<ContentBlock>of(
                new ContentBlock.Structured("structured_output", Map.of("name", "claude")));
        var result = StructuredOutputNormalizer.normalize(content, SCHEMA);
        assertThat(result).isSameAs(content);
        assertThat(result.stream().filter(b -> b instanceof ContentBlock.Structured).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("schema 는 있으나 JSON 이 아닌 산문 → 원본 Text 유지 (파싱 실패 안전)")
    void nonJsonProseKeepsOriginal() {
        var content = List.<ContentBlock>of(new ContentBlock.Text(
                "I cannot answer that. This is just a sentence with no structure."));
        var result = StructuredOutputNormalizer.normalize(content, SCHEMA);
        assertThat(structuredData(result)).isNull();
        assertThat(result).isSameAs(content);
    }

    @Test
    @DisplayName("빈/공백 텍스트 → 원본 그대로")
    void blankTextKeepsOriginal() {
        var content = List.<ContentBlock>of(new ContentBlock.Text("   "));
        var result = StructuredOutputNormalizer.normalize(content, SCHEMA);
        assertThat(result).isSameAs(content);
    }

    @Test
    @DisplayName("Text + 보강된 Structured 가 공존 — extractStructuredData 가 Structured 우선 줍음")
    void textAndStructuredCoexist() {
        var content = List.<ContentBlock>of(new ContentBlock.Text("{\"name\": \"a\"}"));
        var result = StructuredOutputNormalizer.normalize(content, SCHEMA);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(ContentBlock.Text.class);
        assertThat(result.get(1)).isInstanceOf(ContentBlock.Structured.class);
    }
}
