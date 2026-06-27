package com.platform.workflow.analysis;

/**
 * CR-120: 분석 동작이 엔진에 건네는 단일 청크 처리 지시.
 *
 * <p>{@code system}/{@code prompt} 모두 {@code {{chunk}}}(또는 {@code {{fragments}}}) 자리표시자를
 * 아직 채우지 않은 상태로 온다 — 엔진(LargeInputStepExecutor)이 마지막에 청크 내용/멀티모달 블록을
 * 주입한다. 동작은 "유형 고유의 지시"만 책임지고, 엔진은 청크 운반만 책임진다(엔진 유형 무지).
 */
public record AnalysisInstruction(String system, String prompt) {}
