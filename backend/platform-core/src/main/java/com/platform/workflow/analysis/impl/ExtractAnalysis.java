package com.platform.workflow.analysis.impl;

import com.platform.service.PromptTemplateService;
import com.platform.workflow.analysis.AnalysisInstruction;
import com.platform.workflow.analysis.AnalysisParams;
import com.platform.workflow.analysis.DocumentAnalysis;
import com.platform.workflow.analysis.ValidationResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * CR-120: 추출(extract) 동작 — 정해진 항목을 있는 그대로 뽑음 (fact 추출, 금액/일정/제출서류 등).
 * needsReference=false. 빈 출력은 엔진의 require_text_output 검증이 잡으므로 여기선 통과.
 */
@Component
public class ExtractAnalysis implements DocumentAnalysis {

    static final String MAP_KEY = "largeinput.extract.map";
    static final String REDUCE_KEY = "largeinput.extract.reduce";

    static final String MAP_FALLBACK = """
            You are extracting facts from ONE fragment of a large document. \
            Extract every relevant item exhaustively — do not summarize or skip. \
            Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
            Each extracted item MUST cite its source location (page range of this fragment) in a "source_ref" field.
            If the fragment contains nothing relevant, return an empty list — do not invent.
            {{custom_instruction}}

            Fragment:
            {{chunk}}""";

    static final String REDUCE_FALLBACK = """
            You are consolidating extracted items from multiple fragments of one document. \
            Merge duplicates, preserve every distinct item and its source_ref, and keep the union complete. \
            Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
            {{custom_instruction}}

            Fragments' extracted items:
            {{fragments}}""";

    private final PromptTemplateService templates;

    public ExtractAnalysis(PromptTemplateService templates) {
        this.templates = templates;
    }

    @Override
    public String actionId() { return "extract"; }

    /**
     * 추출은 "배열 누적"이라 청크 결과를 LLM 으로 통합할 필요가 없다 — 코드로 이어붙이고 마지막 1회만 정리.
     * 기존 계층 LLM reduce 는 body 하나에 reduce 호출이 십수 번 발생해 reduce 가 map 의 3.7배(973초)까지
     * 걸렸다(실측). collectionReduce=true 로 코드 병합 경로를 쓴다.
     */
    @Override
    public boolean collectionReduce() { return true; }

    /**
     * 청크별 structured 결과의 <b>배열 필드</b>를 키별로 이어붙인다.
     * extract 출력은 {@code {facts:[...]}} 형태(output_schema 가 정한 키) — 각 청크의 같은 키 List 를 concat.
     * 배열 아닌 스칼라 필드는 첫 청크 값 보존(문서 단위 메타 가정). 빈/널 청크는 건너뜀.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> mergeStructured(java.util.List<Map<String, Object>> chunkStructured) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        for (Map<String, Object> chunk : chunkStructured) {
            if (chunk == null || chunk.isEmpty()) continue;
            for (Map.Entry<String, Object> e : chunk.entrySet()) {
                Object val = e.getValue();
                if (val instanceof java.util.List<?> list) {
                    ((java.util.List<Object>) merged.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()))
                            .addAll(list);
                } else {
                    merged.putIfAbsent(e.getKey(), val); // 스칼라 메타는 첫 값 보존
                }
            }
        }
        return merged;
    }

    @Override
    public AnalysisInstruction buildMapInstruction(AnalysisParams params) {
        String tpl = templates.getTemplateOrFallback(MAP_KEY, MAP_FALLBACK);
        return new AnalysisInstruction(null, templates.renderTemplate(tpl, vars(params)));
    }

    @Override
    public AnalysisInstruction buildReduceInstruction(AnalysisParams params) {
        String tpl = templates.getTemplateOrFallback(REDUCE_KEY, REDUCE_FALLBACK);
        return new AnalysisInstruction(null, templates.renderTemplate(tpl, vars(params)));
    }

    /**
     * 추출은 source_ref 출처 부착을 요구 — 결과가 비면 청크 재시도/FAILED 로 정직화(엔진 require_text_output 과 협력).
     * 구조화 결과가 비었는지만 본다(스키마 강제는 best-effort, 여기선 빈응답만 fail).
     */
    @Override
    public ValidationResult validateChunkResult(Map<String, Object> chunkResult) {
        if (chunkResult == null || chunkResult.isEmpty()) {
            return ValidationResult.fail("extract: empty chunk result");
        }
        Object structured = chunkResult.get("structured_data");
        Object text = chunkResult.get("output");
        boolean hasStructured = structured != null
                && !(structured instanceof Map<?, ?> m && m.isEmpty());
        boolean hasText = text != null && !text.toString().isBlank();
        if (!hasStructured && !hasText) {
            return ValidationResult.fail("extract: no structured_data and empty output");
        }
        return ValidationResult.ok();
    }

    private Map<String, Object> vars(AnalysisParams p) {
        Map<String, Object> v = new HashMap<>();
        v.put("focus_areas", p.focusAreasJoined());
        v.put("analysis_goal", p.analysisGoalOrEmpty());
        v.put("custom_instruction", p.customInstructionOrEmpty());
        return v;
    }
}
