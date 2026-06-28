package com.platform.workflow.analysis;

import java.util.Map;

/**
 * CR-120: 대용량 문서 분석 "동작" — 무한 확장의 중심 인터페이스.
 *
 * <p>대용량 문서를 1) 읽고 2) 분석하는 행위는 공통이고, 갈리는 건 3) 무엇을 만드느냐다.
 * 추출(extract)/요약(summarize)/검증(verify)/생성/분류/번역… 유형은 5개든 50개든 정해진 수가 없다.
 * <b>엔진(LargeInputStepExecutor)은 유형을 모르고, 유형이 늘어나도 엔진 코드는 안 바뀐다.</b>
 *
 * <p>새 유형 추가 = 이 인터페이스 구현 클래스 1개(@Component) + DB 프롬프트 row 2개(소스에 박지 않음, CR-036)
 * + (선택) UI 드롭다운 1개. 엔진/레지스트리/인터페이스는 무수정 → Spring 이 {@code List<DocumentAnalysis>}
 * 로 자동 수집해 {@link AnalysisActionRegistry} 가 자동 등록.
 *
 * <p>유형 중 <b>검증·대조(verify)만 구조적으로 특수</b>: "틀렸다"고 하려면 비교 기준이 있어야 하므로
 * {@link #needsReference()}=true → reference_input 을 매 청크에 같이 주입한다. 나머지는 "문서+instruction"이면 충분.
 */
public interface DocumentAnalysis {

    /** 레지스트리 디스패치 키 (LARGE_INPUT config 의 {@code analysis_action} 값). 예: "extract". */
    String actionId();

    /**
     * 단일 청크 map 단계 지시 생성. {@code {{chunk}}} 자리표시자는 엔진이 마지막에 채운다.
     * 프롬프트 본문은 DB(prompt_templates)에서 가져오되, 미적재 시 폴백 상수로 동작한다.
     */
    AnalysisInstruction buildMapInstruction(AnalysisParams params);

    /**
     * 계층 Reduce 단계 지시 생성. {@code {{fragments}}} 자리표시자는 엔진이 묶음마다 채운다.
     */
    AnalysisInstruction buildReduceInstruction(AnalysisParams params);

    /** verify 처럼 비교 기준 입력이 필수면 true. 기본 false. */
    default boolean needsReference() { return false; }

    /**
     * CR-120: "수집형" 동작이면 true — 청크 결과를 코드로 병합(LLM 0회)하고 마지막에 1회만 정리한다.
     *
     * <p>extract 처럼 결과가 <b>배열 누적</b>(fact 목록)인 동작은, 청크별 결과를 LLM 으로 "통합"할 필요가
     * 없다 — 그냥 이어붙이면 된다(청크는 페이지 범위가 안 겹쳐 중복도 거의 없음). 기존 계층 LLM reduce 는
     * body 하나에 reduce 호출이 십수 번 발생해 reduce 가 map 의 3.7배까지 걸렸다(실측 body[2] map 262초 vs
     * reduce 973초). 수집형은 {@link #mergeStructured}/{@link #mergeText} 로 코드 병합 후 1회 정리만 한다.
     *
     * <p>summarize(요약)/verify(판정 종합)는 false — 합치면 더 줄이거나 종합해야 하므로 LLM reduce 가 본질.
     */
    default boolean collectionReduce() { return false; }

    /**
     * 수집형({@link #collectionReduce()}=true)일 때 청크 structured 결과들을 코드로 병합한다.
     * 예: extract 는 각 청크의 {@code {facts:[...]}} 의 facts 배열을 이어붙여 {@code {facts:[전체]}} 반환.
     * 기본 구현은 빈 Map(수집형 아닌 동작은 호출되지 않음).
     */
    default Map<String, Object> mergeStructured(java.util.List<Map<String, Object>> chunkStructured) {
        return Map.of();
    }

    /**
     * 청크 처리 결과(LLM 구조화/텍스트 출력) 동작별 검증. 기본 통과.
     * 위반 시 엔진이 재시도 후 청크 FAILED 로 표시(CR-119).
     */
    default ValidationResult validateChunkResult(Map<String, Object> chunkResult) {
        return ValidationResult.ok();
    }
}
