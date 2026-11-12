package com.platform.attachment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * CR-183: 확장자로 정하는 첨부 종류(T3-2 위젯 데이터 첨부, BIZ-220).
 *
 * <p>이미지와 PDF 는 지금처럼 파일 앞 바이트로 정한다. 여기 종류는 브라우저와 운영체제마다 보내는
 * Content-Type 이 달라(윈도우는 CSV 를 application/vnd.ms-excel 로 보낸다) 확장자로 정하고 내용을 확인한다.
 */
public enum AttachmentKind {

    XLSX(Group.DATA, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    XLS(Group.DATA, "application/vnd.ms-excel", "xls"),
    CSV(Group.DATA, "text/csv", "csv"),
    TSV(Group.DATA, "text/tab-separated-values", "tsv"),
    DOCX(Group.TEXT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    TEXT(Group.TEXT, "text/plain", "txt"),
    MARKDOWN(Group.TEXT, "text/markdown", "md");

    /** 데이터 파일은 표로 읽어 계산하고, 글 파일은 본문을 모델에 보낸다. */
    public enum Group { DATA, TEXT }

    public static final String MAX_DATA_BYTES_KEY = "widget.attachment.max-data-bytes";
    public static final String MAX_TEXT_BYTES_KEY = "widget.attachment.max-text-bytes";
    public static final long DEFAULT_MAX_DATA_BYTES = 10L * 1024 * 1024;
    public static final long DEFAULT_MAX_TEXT_BYTES = 10L * 1024 * 1024;

    private final Group group;
    private final String mediaType;
    private final String extension;

    AttachmentKind(Group group, String mediaType, String extension) {
        this.group = group;
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public Group group() { return group; }

    public String mediaType() { return mediaType; }

    public String extension() { return extension; }

    public boolean isData() { return group == Group.DATA; }

    public String maxBytesKey() {
        return isData() ? MAX_DATA_BYTES_KEY : MAX_TEXT_BYTES_KEY;
    }

    public long defaultMaxBytes() {
        return isData() ? DEFAULT_MAX_DATA_BYTES : DEFAULT_MAX_TEXT_BYTES;
    }

    public static Optional<AttachmentKind> fromFilename(String filename) {
        if (filename == null) return Optional.empty();
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return Optional.empty();
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(k -> k.extension.equals(ext)).findFirst();
    }

    /** 저장된 media_type 으로 종류를 찾는다. 이미지, PDF 는 없다. */
    public static Optional<AttachmentKind> fromMediaType(String mediaType) {
        if (mediaType == null) return Optional.empty();
        return Arrays.stream(values()).filter(k -> k.mediaType.equals(mediaType)).findFirst();
    }

    public static boolean isDataMediaType(String mediaType) {
        return fromMediaType(mediaType).map(AttachmentKind::isData).orElse(false);
    }

    public static List<String> dataMediaTypes() {
        return Arrays.stream(values()).filter(AttachmentKind::isData).map(AttachmentKind::mediaType).toList();
    }

    /** 화면 파일 선택 창과 안내에 쓰는 확장자 목록(이미지, PDF 포함). */
    public static List<String> acceptedExtensions() {
        List<String> all = new java.util.ArrayList<>(List.of("png", "jpg", "jpeg", "gif", "webp", "pdf"));
        Arrays.stream(values()).map(AttachmentKind::extension).forEach(all::add);
        return List.copyOf(all);
    }
}
