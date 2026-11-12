package com.platform.chatdoc;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CR-181 PRD-480: 대화 요청의 document_edit 칸. target 은 CR-180 옆 창 대상과 같은 모양이다.
 *
 * @param target      고칠 문서
 * @param baseVersion 옆 창에 보이는 판. 없으면 마지막 판
 * @param selection   옆 창에서 선택한 글(PRD-483). 없거나 빈 글이면 문서 전체를 고친다
 */
public record DocumentEditRequest(
        Target target,
        @JsonProperty("base_version") Integer baseVersion,
        Selection selection
) {
    /** 위젯이 옆 창에서 받은 선택한 글. 고칠 범위를 알려 주는 자료일 뿐이다. */
    public record Selection(String text) {}

    /**
     * @param kind   file 또는 block
     * @param name   file 의 작업 폴더 out/ 아래 파일 이름
     * @param turnId block 이 나온 답 번호
     * @param index  block 의 그 답 안 코드 블록 순번(0부터)
     * @param lang   block 언어 html, svg
     * @param source block 원문. 처음 고칠 때만 1판으로 쓴다
     */
    public record Target(
            String kind,
            String name,
            @JsonProperty("turn_id") String turnId,
            Integer index,
            String lang,
            String source
    ) {}
}
