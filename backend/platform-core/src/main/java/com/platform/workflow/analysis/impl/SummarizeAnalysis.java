package com.platform.workflow.analysis.impl;

import com.platform.service.PromptTemplateService;
import com.platform.workflow.analysis.AnalysisInstruction;
import com.platform.workflow.analysis.AnalysisParams;
import com.platform.workflow.analysis.DocumentAnalysis;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * CR-120: 요약(summarize) 동작 — 내용 압축 (공고문 개요 등). needsReference=false, 동작별 무검증
 * (엔진의 빈응답 검증만 적용).
 */
@Component
public class SummarizeAnalysis implements DocumentAnalysis {

    static final String MAP_KEY = "largeinput.summarize.map";
    static final String REDUCE_KEY = "largeinput.summarize.reduce";

    static final String MAP_FALLBACK = """
            Summarize ONE fragment of a large document concisely but completely — \
            capture every key point in this fragment, omit nothing important. \
            Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
            {{custom_instruction}}

            Fragment:
            {{chunk}}""";

    static final String REDUCE_FALLBACK = """
            Combine these fragment summaries into one coherent summary of the whole document. \
            Preserve all distinct points, remove redundancy, keep it faithful. \
            Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
            {{custom_instruction}}

            Fragment summaries:
            {{fragments}}""";

    private final PromptTemplateService templates;

    public SummarizeAnalysis(PromptTemplateService templates) {
        this.templates = templates;
    }

    @Override
    public String actionId() { return "summarize"; }

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

    private Map<String, Object> vars(AnalysisParams p) {
        Map<String, Object> v = new HashMap<>();
        v.put("focus_areas", p.focusAreasJoined());
        v.put("analysis_goal", p.analysisGoalOrEmpty());
        v.put("custom_instruction", p.customInstructionOrEmpty());
        return v;
    }
}
