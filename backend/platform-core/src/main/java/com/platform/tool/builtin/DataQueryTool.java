package com.platform.tool.builtin;

import com.platform.attachment.AttachmentException;
import com.platform.attachment.AttachmentKind;
import com.platform.attachment.AttachmentService;
import com.platform.attachment.data.DataQueryEngine;
import com.platform.attachment.data.DataTable;
import com.platform.attachment.data.DataTableReader;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.tool.*;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-183: 대화에 첨부한 데이터 파일의 표에 SQL 한 문장을 실행한다(T3-2 3.4, BIZ-222).
 *
 * <p>노출 등급이 없어 기본 도구 목록과 /mcp 에 나오지 않는다. 대화 요청에만 따로 붙인다(3.5).
 */
@Component
public class DataQueryTool implements EnhancedToolExecutor {

    public static final String NAME = "data_query";
    public static final String MAX_RESULT_ROWS_KEY = "widget.data.max-result-rows";
    public static final String MAX_RESULT_BYTES_KEY = "widget.data.max-result-bytes";
    public static final String QUERY_TIMEOUT_KEY = "widget.data.query-timeout-seconds";
    static final int DEFAULT_MAX_RESULT_ROWS = 500;
    static final int DEFAULT_MAX_RESULT_BYTES = 65_536;
    static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 10;
    static final String FILE_NOT_FOUND = "file not found";
    static final String NO_DATA_FILE = "no data file in this conversation";

    private static final Logger log = LoggerFactory.getLogger(DataQueryTool.class);

    private final AttachmentService attachments;
    private final DataTableReader tableReader;
    private final PlatformSettingsService settings;

    public DataQueryTool(AttachmentService attachments, DataTableReader tableReader,
                         PlatformSettingsService settings) {
        this.attachments = attachments;
        this.tableReader = tableReader;
        this.settings = settings;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                NAME,
                "Runs one SQL SELECT on the tables of a data file (Excel, CSV, TSV) attached to this conversation "
                        + "and returns the exact result. Use it for every total, average, count, ratio and ranking "
                        + "instead of calculating in your head. Tables are named t1, t2 and so on in sheet order. "
                        + "Dialect: H2 in PostgreSQL mode. Put table and column names in double quotes.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_id", Map.of("type", "string",
                                        "description", "file_id shown in the attached data file summary"),
                                "sql", Map.of("type", "string",
                                        "description", "One statement starting with SELECT or WITH, e.g. "
                                                + "SELECT \"region\", SUM(\"sales\") FROM \"t1\" GROUP BY \"region\"")
                        ),
                        "required", List.of("file_id", "sql")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative(NAME, List.of("data"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        Object fileId = input.get("file_id");
        Object sql = input.get("sql");
        if (!(fileId instanceof String f) || f.isBlank() || !(sql instanceof String s) || s.isBlank()) {
            return ToolResult.error("INVALID_INPUT: file_id and sql are required");
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;
        if (sessionId == null || sessionId.isBlank()) return ToolResult.error(FILE_NOT_FOUND);

        String statement;
        try {
            statement = DataQueryEngine.normalize(s);
        } catch (DataQueryEngine.QueryRejected e) {
            return ToolResult.error(e.getMessage());
        }

        Optional<Loaded> loaded = load(f.trim(), sessionId);
        if (loaded.isEmpty()) {
            return ToolResult.error(attachments.hasActiveDataFile(sessionId) ? FILE_NOT_FOUND : NO_DATA_FILE);
        }

        int maxRows = settings.getInt(MAX_RESULT_ROWS_KEY, DEFAULT_MAX_RESULT_ROWS);
        int maxBytes = settings.getInt(MAX_RESULT_BYTES_KEY, DEFAULT_MAX_RESULT_BYTES);
        int timeout = settings.getInt(QUERY_TIMEOUT_KEY, DEFAULT_QUERY_TIMEOUT_SECONDS);
        try {
            DataQueryEngine.Result result = DataQueryEngine.run(loaded.get().tables(), statement, maxRows,
                    maxBytes, timeout);
            String summary = "data_query " + loaded.get().filename() + ": " + result.rows().size() + " rows"
                    + (result.truncated() ? " (truncated)" : "") + ", " + result.elapsedMs() + " ms";
            return ToolResult.ok(result.toMap(), summary);
        } catch (SQLTimeoutException e) {
            return ToolResult.error("query timed out after " + timeout + " seconds");
        } catch (SQLException e) {
            return ToolResult.error("SQL error: " + firstLine(e.getMessage()));
        }
    }

    private record Loaded(String filename, List<DataTable> tables) {}

    /** 다른 세션 파일, 보관 기간이 지난 파일, 데이터 파일이 아닌 첨부는 모두 없는 파일로 본다. */
    private Optional<Loaded> load(String fileId, String sessionId) {
        UUID id;
        try {
            id = UUID.fromString(fileId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        try {
            ChatAttachmentEntity att = attachments.loadOwned(id, sessionId);
            Optional<AttachmentKind> kind = AttachmentKind.fromMediaType(att.getMediaType())
                    .filter(AttachmentKind::isData);
            if (kind.isEmpty()) return Optional.empty();
            return Optional.of(new Loaded(att.getFilename(), tableReader.read(attachments.readBytes(att), kind.get())));
        } catch (AttachmentException e) {
            log.debug("data_query file {} not usable for session {}: {}", fileId, sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** H2 오류 글은 문장 전체와 내부 코드를 덧붙인다. 모델이 고치는 데 필요한 첫 줄만 돌려준다. */
    private static String firstLine(String message) {
        if (message == null) return "unknown";
        int nl = message.indexOf('\n');
        String line = nl >= 0 ? message.substring(0, nl) : message;
        return line.length() > 500 ? line.substring(0, 500) : line;
    }
}
