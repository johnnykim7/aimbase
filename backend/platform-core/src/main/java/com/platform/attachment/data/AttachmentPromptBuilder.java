package com.platform.attachment.data;

import com.platform.attachment.AttachmentKind;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.service.PromptTemplateService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CR-183: 워드, 텍스트 첨부와 데이터 파일 첨부를 모델에 보낼 글 블록으로 만든다(T3-2, BIZ-221).
 */
@Component
public class AttachmentPromptBuilder {

    public static final String MAX_TEXT_CHARS_KEY = "widget.attachment.max-text-chars";
    public static final String INLINE_ROWS_KEY = "widget.data.inline-rows";
    public static final String PREVIEW_ROWS_KEY = "widget.data.preview-rows";
    public static final String DATA_GUIDE_TEMPLATE = "widget.data.guide";
    static final int DEFAULT_MAX_TEXT_CHARS = 100_000;
    static final int DEFAULT_INLINE_ROWS = 200;
    static final int DEFAULT_PREVIEW_ROWS = 20;
    /** 결과 칸 글 상한과 같게 둔다(BIZ-222). */
    static final int MAX_CELL_CHARS = 200;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PlatformSettingsService settings;
    private final DataTableReader tableReader;
    private final PromptTemplateService templates;

    public AttachmentPromptBuilder(PlatformSettingsService settings, DataTableReader tableReader,
                                   PromptTemplateService templates) {
        this.settings = settings;
        this.tableReader = tableReader;
        this.templates = templates;
    }

    /** 워드, 텍스트: 첫 줄 이름, 다음 줄부터 본문. 상한을 넘으면 앞부분만 싣는다. */
    public String documentText(ChatAttachmentEntity att, byte[] bytes, AttachmentKind kind) {
        String body = DocumentTextReader.read(bytes, kind);
        int max = settings.getInt(MAX_TEXT_CHARS_KEY, DEFAULT_MAX_TEXT_CHARS);
        StringBuilder out = new StringBuilder("[첨부 문서: ").append(att.getFilename()).append("]\n");
        if (body.length() > max) {
            out.append(body, 0, max)
                    .append("\n[앞 ").append(String.format("%,d", max)).append("자만 보냄, 전체 ")
                    .append(String.format("%,d", body.length())).append("자]");
        } else {
            out.append(body);
        }
        return out.toString();
    }

    /**
     * 데이터 파일 요약. 표마다 이름, 행 수, 열 이름과 형, 앞 행을 싣는다.
     *
     * @param withGuide 계산 도구가 붙는 연결일 때만 계산 안내 틀을 끝에 붙인다
     */
    public String dataSummary(ChatAttachmentEntity att, byte[] bytes, AttachmentKind kind, boolean withGuide) {
        List<DataTable> tables = tableReader.read(bytes, kind);
        int totalRows = tables.stream().mapToInt(t -> t.rows().size()).sum();
        boolean inline = totalRows <= settings.getInt(INLINE_ROWS_KEY, DEFAULT_INLINE_ROWS);
        int preview = settings.getInt(PREVIEW_ROWS_KEY, DEFAULT_PREVIEW_ROWS);

        StringBuilder out = new StringBuilder("[첨부 데이터 파일: ").append(att.getFilename())
                .append(", file_id ").append(att.getId()).append("]\n");
        if (tables.isEmpty()) {
            out.append("표 없음 (모든 시트가 비어 있다)\n");
        }
        for (DataTable table : tables) {
            out.append("표 ").append(table.name());
            if (table.sheet() != null) out.append(" (시트 \"").append(table.sheet()).append("\")");
            out.append(": ").append(String.format("%,d", table.rows().size())).append("행\n");
            List<String> cols = new ArrayList<>();
            for (DataTable.Column c : table.columns()) cols.add("\"" + c.name() + "\" " + c.type().label());
            out.append("  열: ").append(String.join(", ", cols)).append('\n');
            int shown = inline ? table.rows().size() : Math.min(preview, table.rows().size());
            if (shown == 0) continue;
            out.append(inline ? "  전체 행:\n" : "  앞 " + shown + "행:\n");
            List<String> header = table.columns().stream().map(c -> DocumentTextReader.markdownCell(c.name())).toList();
            out.append("  | ").append(String.join(" | ", header)).append(" |\n");
            out.append("  |").append(" --- |".repeat(header.size())).append('\n');
            for (int r = 0; r < shown; r++) {
                Object[] row = table.rows().get(r);
                List<String> cells = new ArrayList<>(row.length);
                for (Object v : row) cells.add(DocumentTextReader.markdownCell(display(v)));
                out.append("  | ").append(String.join(" | ", cells)).append(" |\n");
            }
        }
        if (withGuide) {
            String guide = templates.getTemplate(DATA_GUIDE_TEMPLATE);
            if (guide != null && !guide.isBlank()) out.append('\n').append(guide.strip());
        }
        return out.toString().stripTrailing();
    }

    static String display(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof BigDecimal n) s = n.toPlainString();
        else if (v instanceof LocalDateTime t) s = t.format(TIMESTAMP);
        else s = v.toString();
        return s.length() > MAX_CELL_CHARS ? s.substring(0, MAX_CELL_CHARS) : s;
    }
}
