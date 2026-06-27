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
 * CR-120: 검증·대조(verify) 동작 — 기준과 대조해 틀린/빠진 곳을 찾음 (계약 리스크, 누락 조항 탐지).
 *
 * <p>유형 중 유일하게 구조적으로 특수: "틀렸다"고 하려면 비교 기준이 있어야 하므로
 * {@link #needsReference()}=true → 엔진이 reference_input 을 매 청크에 같이 주입한다.
 */
@Component
public class VerifyAnalysis implements DocumentAnalysis {

    static final String MAP_KEY = "largeinput.verify.map";
    static final String REDUCE_KEY = "largeinput.verify.reduce";

    static final String MAP_FALLBACK = """
            You are verifying ONE fragment of a document against a reference standard. \
            Compare the fragment to the reference and report every discrepancy: \
            wrong values, missing clauses, contradictions. \
            For each finding give a verdict (MATCH | MISMATCH | MISSING) and cite the fragment's location. \
            Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
            {{custom_instruction}}

            Reference standard:
            {{reference_input}}

            Fragment:
            {{chunk}}""";

    static final String REDUCE_FALLBACK = """
            Consolidate verification findings from all fragments against the reference standard. \
            Keep every distinct discrepancy with its verdict and location, merge duplicates, \
            and produce an overall compliance assessment. \
            Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
            {{custom_instruction}}

            Reference standard:
            {{reference_input}}

            Fragment findings:
            {{fragments}}""";

    private final PromptTemplateService templates;

    public VerifyAnalysis(PromptTemplateService templates) {
        this.templates = templates;
    }

    @Override
    public String actionId() { return "verify"; }

    @Override
    public boolean needsReference() { return true; }

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

    @Override
    public ValidationResult validateChunkResult(Map<String, Object> chunkResult) {
        if (chunkResult == null || chunkResult.isEmpty()) {
            return ValidationResult.fail("verify: empty chunk result");
        }
        Object structured = chunkResult.get("structured_data");
        Object text = chunkResult.get("output");
        boolean hasStructured = structured != null
                && !(structured instanceof Map<?, ?> m && m.isEmpty());
        boolean hasText = text != null && !text.toString().isBlank();
        if (!hasStructured && !hasText) {
            return ValidationResult.fail("verify: no verdict produced (empty result)");
        }
        return ValidationResult.ok();
    }

    private Map<String, Object> vars(AnalysisParams p) {
        Map<String, Object> v = new HashMap<>();
        v.put("focus_areas", p.focusAreasJoined());
        v.put("analysis_goal", p.analysisGoalOrEmpty());
        v.put("reference_input", p.referenceInputOrEmpty());
        v.put("custom_instruction", p.customInstructionOrEmpty());
        return v;
    }
}
