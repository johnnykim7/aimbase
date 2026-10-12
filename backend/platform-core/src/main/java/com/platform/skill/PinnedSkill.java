package com.platform.skill;

import java.util.List;

/**
 * CR-147: 단계 config.skills 항목 하나를 판까지 고정한 것. files 는 지시문에 미리 붙일 묶음 안 파일이다.
 */
public record PinnedSkill(ResolvedSkill skill, List<String> files) {

    public PinnedSkill {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
