package com.platform.workflow.analysis;

import java.util.List;
import java.util.Map;

/**
 * CR-120: 분석 동작에 전달되는 사용자 지정 파라미터 (LARGE_INPUT config 에서 해석).
 *
 * @param focusAreas        집중 영역 (예: 가격/납기/위약금). null/빈 허용.
 * @param outputSchema      청크 결과 구조 — LLM_CALL response_schema 로 변환. null 허용(자유 텍스트).
 * @param analysisGoal      분석 목적 서술. null 허용.
 * @param referenceInput    verify 등 needsReference 동작의 비교 기준. needsReference=true 면 필수.
 * @param customInstruction 소비앱이 map/reduce 프롬프트에 끼워넣는 <b>범용 도메인 지시문</b>.
 *                          엔진/동작은 내용을 모르고 자리표시자에 그대로 주입만 한다(소비앱 도메인을
 *                          플랫폼에 박지 않기 위한 통로 — 특정 도메인 action 신설 대신 이 필드 사용). null 허용.
 * @param extra             동작별 추가 파라미터 (확장 자리).
 */
public record AnalysisParams(
        List<String> focusAreas,
        Map<String, Object> outputSchema,
        String analysisGoal,
        String referenceInput,
        String customInstruction,
        Map<String, Object> extra
) {
    public String focusAreasJoined() {
        return focusAreas == null || focusAreas.isEmpty() ? "" : String.join(", ", focusAreas);
    }

    public String analysisGoalOrEmpty() {
        return analysisGoal == null ? "" : analysisGoal;
    }

    public String referenceInputOrEmpty() {
        return referenceInput == null ? "" : referenceInput;
    }

    public String customInstructionOrEmpty() {
        return customInstruction == null ? "" : customInstruction;
    }
}
