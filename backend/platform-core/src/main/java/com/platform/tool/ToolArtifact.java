package com.platform.tool;

/**
 * CR-029: 도구 실행으로 생성된 부산물 (파일, 검색 결과 등).
 */
public record ToolArtifact(
        String type,    // e.g., "file_content", "search_result", "glob_match"
        String ref,     // e.g., file path
        Object data     // actual content or metadata
) {}
