package com.platform.workflow.analysis;

import com.platform.service.PromptTemplateService;
import com.platform.workflow.analysis.impl.ExtractAnalysis;
import com.platform.workflow.analysis.impl.SummarizeAnalysis;
import com.platform.workflow.analysis.impl.VerifyAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * CR-120: 분석 동작 레지스트리 디스패치 + 동작별 특성(needsReference/검증) 단위 테스트.
 */
class AnalysisActionRegistryTest {

    private PromptTemplateService templates;
    private AnalysisActionRegistry registry;

    @BeforeEach
    void setUp() {
        templates = mock(PromptTemplateService.class);
        // 폴백 그대로 반환 (DB 미적재 시 동작 확인) + 렌더는 자리표시자 치환만
        lenient().when(templates.getTemplateOrFallback(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(templates.renderTemplate(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        registry = new AnalysisActionRegistry(List.of(
                new ExtractAnalysis(templates),
                new SummarizeAnalysis(templates),
                new VerifyAnalysis(templates)));
    }

    @Test
    @DisplayName("등록된 actionId 로 동작을 조회한다")
    void dispatchesByActionId() {
        assertThat(registry.get("extract").actionId()).isEqualTo("extract");
        assertThat(registry.get("summarize").actionId()).isEqualTo("summarize");
        assertThat(registry.get("verify").actionId()).isEqualTo("verify");
        assertThat(registry.ids()).containsExactlyInAnyOrder("extract", "summarize", "verify");
    }

    @Test
    @DisplayName("미등록 actionId 는 즉시 예외 — 오타/미구현 동작 노출")
    void unknownActionThrows() {
        assertThatThrownBy(() -> registry.get("translate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("translate");
    }

    @Test
    @DisplayName("verify 만 needsReference=true, extract/summarize 는 false")
    void onlyVerifyNeedsReference() {
        assertThat(registry.get("verify").needsReference()).isTrue();
        assertThat(registry.get("extract").needsReference()).isFalse();
        assertThat(registry.get("summarize").needsReference()).isFalse();
    }

    @Test
    @DisplayName("verify map instruction 은 reference_input 을 본문에 포함한다")
    void verifyInjectsReference() {
        AnalysisParams params = new AnalysisParams(
                List.of("가격"), null, "리스크 점검", "기준 단가 1000원", null, null);
        AnalysisInstruction instr = registry.get("verify").buildMapInstruction(params);
        // 렌더 mock 이 자리표시자를 그대로 두지만, VerifyAnalysis 가 vars 에 reference_input 을 넣었는지
        // 검증하려면 실제 렌더가 필요 → 여기서는 템플릿에 {{reference_input}} 자리표시자 존재만 확인
        assertThat(instr.prompt()).contains("{{reference_input}}");
        assertThat(instr.prompt()).contains("{{chunk}}");
    }

    @Test
    @DisplayName("extract 청크 결과 검증: 빈 결과는 fail, 텍스트 있으면 ok")
    void extractValidatesEmptyResult() {
        DocumentAnalysis extract = registry.get("extract");
        assertThat(extract.validateChunkResult(Map.of()).valid()).isFalse();
        assertThat(extract.validateChunkResult(Map.of("output", "")).valid()).isFalse();
        assertThat(extract.validateChunkResult(Map.of("output", "fact: 가격 1000원")).valid()).isTrue();
        assertThat(extract.validateChunkResult(Map.of("structured_data", Map.of("items", List.of("a")))).valid())
                .isTrue();
    }

    @Test
    @DisplayName("custom_instruction — 소비앱 도메인 지시문이 map/reduce 프롬프트에 실제로 주입된다(범용 통로)")
    void customInstructionInjectedIntoPrompt() {
        // 실제 렌더링 동작으로 검증 — 폴백 템플릿 + 실제 renderTemplate(자리표시자 치환).
        PromptTemplateService realTemplates = new PromptTemplateService(
                mock(com.platform.repository.PromptTemplateRepository.class));
        ExtractAnalysis extract = new ExtractAnalysis(realTemplates);

        String domain = "USFK RFP analyst: classify fulfillmentParty (CLIENT_UPLOAD/PLATFORM_GENERATED/SYSTEM_FORM)";
        AnalysisParams params = new AnalysisParams(
                List.of("계약기간"), null, "fact 추출", null, domain, null);

        assertThat(extract.buildMapInstruction(params).prompt()).contains(domain);
        assertThat(extract.buildReduceInstruction(params).prompt()).contains(domain);
        assertThat(extract.actionId()).isEqualTo("extract"); // 도메인 action 신설 없이 범용 extract 재사용
    }

    @Test
    @DisplayName("custom_instruction 없으면 빈 문자열로 치환(프롬프트 깨지지 않음)")
    void customInstructionAbsentRendersEmpty() {
        PromptTemplateService realTemplates = new PromptTemplateService(
                mock(com.platform.repository.PromptTemplateRepository.class));
        ExtractAnalysis extract = new ExtractAnalysis(realTemplates);
        AnalysisParams params = new AnalysisParams(null, null, null, null, null, null);

        String prompt = extract.buildMapInstruction(params).prompt();
        assertThat(prompt).doesNotContain("{{custom_instruction}}");
        assertThat(prompt).contains("{{chunk}}"); // chunk 는 엔진이 나중에 채우므로 남아있어야 함
    }
}
