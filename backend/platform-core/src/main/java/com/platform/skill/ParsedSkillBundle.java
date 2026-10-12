package com.platform.skill;

import java.util.List;
import java.util.Map;

/**
 * CR-147: 올리기 검사를 통과한 묶음. 경로는 SKILL.md 가 있는 폴더 기준이다.
 *
 * @param topFolder 벗겨낸 최상위 폴더 이름. 맨 위에 SKILL.md 가 있었으면 null
 */
public record ParsedSkillBundle(
        String name,
        String description,
        Map<String, Object> frontmatter,
        String instructions,
        List<File> files,
        long totalBytes,
        String contentSha256,
        List<Map<String, Object>> warnings,
        String topFolder
) {
    public record File(String path, byte[] content, String sha256, boolean isText) {}
}
