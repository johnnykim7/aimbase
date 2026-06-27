package com.platform.largeinput;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-120: 결정론적으로 분해된 청크 1개의 메타.
 *
 * <p>청크는 "내용"이 아니라 "경계+추출방법"만 들고 다닌다 — 실제 내용(텍스트/페이지 이미지)은
 * 엔진이 map 단계에서 그때 추출/렌더한다(큰 base64 를 job JSONB 에 통째 적재하지 않기 위함).
 *
 * @param chunkIndex 0-based 인덱스
 * @param pageStart  1-based 시작 페이지 (텍스트형/이미지형 공통, 페이지 경계 유지). 인라인 텍스트는 0.
 * @param pageEnd    1-based 끝 페이지 (포함). 인라인 텍스트는 0.
 * @param type       TEXT(parse_document 텍스트 청크) | IMAGE(pdf_to_images 렌더)
 * @param text       TEXT 청크의 추출 텍스트 (이미지형은 null — map 단계에서 렌더)
 * @param estBytes   추정 바이트(base64 기준, 이미지형 경계 역산 결과). 디버깅·검증용.
 */
public record LargeInputChunk(
        int chunkIndex,
        int pageStart,
        int pageEnd,
        Type type,
        String text,
        long estBytes
) {
    public enum Type { TEXT, IMAGE }

    /** 1-based "start-end" 페이지 범위 문자열 (coverage_report 출처 표기 + pdf_to_images pages 인자). */
    public String pageRange() {
        if (pageStart <= 0) return "inline";
        return pageStart == pageEnd ? String.valueOf(pageStart) : pageStart + "-" + pageEnd;
    }

    /** job.chunks JSONB 직렬화용 Map (내용 text 는 길 수 있어 제외 — 메타만). */
    public Map<String, Object> toMeta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunk_index", chunkIndex);
        m.put("page_start", pageStart);
        m.put("page_end", pageEnd);
        m.put("page_range", pageRange());
        m.put("type", type.name());
        m.put("est_bytes", estBytes);
        return m;
    }
}
