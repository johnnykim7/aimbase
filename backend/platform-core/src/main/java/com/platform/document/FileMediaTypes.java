package com.platform.document;

import java.util.Locale;

/** 파일 이름 확장자로 내려줄 형식(Content-Type)을 고른다. 산출물 다운로드와 공유 사본이 같이 쓴다. */
public final class FileMediaTypes {

    private FileMediaTypes() {}

    public static String forName(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pdf" -> "application/pdf";
            case "csv" -> "text/csv; charset=UTF-8";
            case "json" -> "application/json";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "html" -> "text/html; charset=UTF-8";
            case "md" -> "text/markdown; charset=UTF-8";
            case "txt" -> "text/plain; charset=UTF-8";
            default -> "application/octet-stream";
        };
    }
}
