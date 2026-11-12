package com.platform.artifact;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CR-180: 옆 창 미리보기 종류. 위젯은 이 값만 보고 그리는 방법을 고른다.
 */
public enum PreviewKind {
    PAGES(Set.of("pptx", "docx", "pdf")),
    SHEET(Set.of("xlsx", "csv")),
    HTML(Set.of("html")),
    SVG(Set.of("svg")),
    IMAGE(Set.of("png", "jpg", "jpeg", "gif", "webp")),
    MARKDOWN(Set.of("md")),
    TEXT(Set.of("txt", "json")),
    NONE(Set.of());

    private static final Map<String, PreviewKind> BY_EXT;

    static {
        Map<String, PreviewKind> m = new java.util.HashMap<>();
        for (PreviewKind k : values()) {
            for (String ext : k.exts) m.put(ext, k);
        }
        BY_EXT = Map.copyOf(m);
    }

    private final Set<String> exts;

    PreviewKind(Set<String> exts) {
        this.exts = exts;
    }

    public static PreviewKind of(String fileName) {
        String ext = extension(fileName);
        return ext == null ? NONE : BY_EXT.getOrDefault(ext, NONE);
    }

    /** 소문자 확장자. 없으면 null. */
    public static String extension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
