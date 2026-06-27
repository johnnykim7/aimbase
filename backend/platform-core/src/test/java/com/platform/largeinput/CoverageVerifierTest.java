package com.platform.largeinput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-120: 누락 검증 판정 — full_coverage 결정론 + 실패 청크 정직 표기(CR-119) 단위 테스트.
 */
class CoverageVerifierTest {

    private final CoverageVerifier verifier = new CoverageVerifier();

    private LargeInputChunk chunk(int idx, int start, int end) {
        return new LargeInputChunk(idx, start, end, LargeInputChunk.Type.IMAGE, null, 0);
    }

    @Test
    @DisplayName("모든 청크 성공이면 full_coverage=true, ratio=1.0")
    void fullCoverageWhenAllOk() {
        var report = verifier.verify(List.of(
                new CoverageVerifier.ChunkOutcome(chunk(0, 1, 10), true, null),
                new CoverageVerifier.ChunkOutcome(chunk(1, 11, 20), true, null)));
        assertThat(verifier.isFullCoverage(report)).isTrue();
        assertThat(report.get("coverage_ratio")).isEqualTo(1.0);
        assertThat(report.get("completed")).isEqualTo(2);
        assertThat(report.get("failed")).isEqualTo(0);
        assertThat((List<?>) report.get("failed_chunks")).isEmpty();
    }

    @Test
    @DisplayName("일부 실패면 full_coverage=false + 실패 청크 page_range/사유를 숨기지 않는다")
    void partialCoverageReportsFailedChunks() {
        var report = verifier.verify(List.of(
                new CoverageVerifier.ChunkOutcome(chunk(0, 1, 10), true, null),
                new CoverageVerifier.ChunkOutcome(chunk(1, 11, 20), false, "empty_response_after_retry"),
                new CoverageVerifier.ChunkOutcome(chunk(2, 21, 30), true, null)));

        assertThat(verifier.isFullCoverage(report)).isFalse();
        assertThat(report.get("total_chunks")).isEqualTo(3);
        assertThat(report.get("completed")).isEqualTo(2);
        assertThat(report.get("failed")).isEqualTo(1);
        assertThat((Double) report.get("coverage_ratio")).isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.001));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) report.get("failed_chunks");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0)).containsEntry("index", 1)
                .containsEntry("page_range", "11-20")
                .containsEntry("reason", "empty_response_after_retry");
    }

    @Test
    @DisplayName("빈 입력은 full_coverage=false (전수 0건은 완주 아님)")
    void emptyIsNotFullCoverage() {
        var report = verifier.verify(List.of());
        assertThat(verifier.isFullCoverage(report)).isFalse();
        assertThat(report.get("total_chunks")).isEqualTo(0);
    }
}
