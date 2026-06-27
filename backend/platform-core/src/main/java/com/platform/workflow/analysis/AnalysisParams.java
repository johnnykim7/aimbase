package com.platform.workflow.analysis;

import java.util.List;
import java.util.Map;

/**
 * CR-120: 분석 동작에 전달되는 사용자 지정 파라미터 (LARGE_INPUT config 에서 해석).
 *
 * @param focusAreas     집중 영역 (예: 가격/납기/위약금). null/빈 허용.
 * @param outputSchema   청크 결과 구조 — LLM_CALL response_schema 로 변환. null 허용(자유 텍스트).
 * @param analysisGoal   분석 목적 서술. null 허용.
 * @param referenceInput verify 등 needsReference 동작의 비교 기준. needsReference=true 면 필수.
 * @param extra          동작별 추가 파라미터 (확장 자리).
 */
public record AnalysisParams(
        List<String> focusAreas,
        Map<String, Object> outputSchema,
        String analysisGoal,
        String referenceInput,
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
}
