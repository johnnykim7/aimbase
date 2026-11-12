package com.platform.document;

import java.util.List;

/**
 * 그릴 문서 한 장. 대화 내보내기는 메시지마다 칸을 나눠, 한 메시지의 닫히지 않은 코드 블록이
 * 다음 메시지를 삼키지 않게 한다.
 *
 * @param title   맨 위 제목. 없으면 null
 * @param notice  제목 위 안내 한 줄(공유한 대화와 기한). 없으면 null
 * @param meta    제목 아래 회색 줄들(시각, 모델, 메시지 수)
 * @param sections 본문 칸. heading 이 null 이면 머리 없이 마크다운만 그린다
 */
public record DocModel(String title, String notice, List<String> meta, List<Section> sections) {

    public record Section(String heading, String markdown) {}

    public DocModel {
        meta = meta == null ? List.of() : List.copyOf(meta);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public static DocModel single(String title, String markdown) {
        return new DocModel(title, null, List.of(), List.of(new Section(null, markdown)));
    }

    public DocModel withNotice(String text) {
        return new DocModel(title, text, meta, sections);
    }
}
