package com.platform.largeinput;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-120: 누락 검증 — full_coverage(결정론적 강제) + 실패 청크 정직 표기(CR-119 철학).
 *
 * <p>"빠짐없이 전수" 가 핵심이므로 completed == total && 모든 청크 ok 여야 full_coverage=true.
 * 미충족이면 숨기지 않고 coverage_report 에 실패 청크의 page_range/사유를 남긴다.
 */
@Service
public class CoverageVerifier {

    /** 청크별 처리 결과 1건 — 검증 입력. */
    public record ChunkOutcome(LargeInputChunk chunk, boolean ok, String failureReason) {}

    /**
     * @return coverage_report Map (job.coverage_report 에 저장 + full_coverage 키로 강제 판정)
     */
    public Map<String, Object> verify(List<ChunkOutcome> outcomes) {
        int total = outcomes.size();
        int completed = 0;
        List<Map<String, Object>> failedChunks = new ArrayList<>();
        for (ChunkOutcome o : outcomes) {
            if (o.ok()) {
                completed++;
            } else {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("index", o.chunk().chunkIndex());
                f.put("page_range", o.chunk().pageRange());
                f.put("reason", o.failureReason() != null ? o.failureReason() : "unknown");
                failedChunks.add(f);
            }
        }
        int failed = total - completed;
        boolean fullCoverage = total > 0 && failed == 0;
        double ratio = total == 0 ? 0.0 : (double) completed / total;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("total_chunks", total);
        report.put("completed", completed);
        report.put("failed", failed);
        report.put("failed_chunks", failedChunks);
        report.put("coverage_ratio", Math.round(ratio * 10000.0) / 10000.0);
        report.put("full_coverage", fullCoverage);
        return report;
    }

    public boolean isFullCoverage(Map<String, Object> report) {
        return Boolean.TRUE.equals(report.get("full_coverage"));
    }
}
