package com.platform.tool.builtin;

import com.platform.attachment.AttachmentException;
import com.platform.attachment.AttachmentKind;
import com.platform.attachment.AttachmentService;
import com.platform.attachment.data.DataTableReader;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-183 TC-CHT-097(소유), 도구 결과 모양. */
class DataQueryToolTest {

    private static final UUID FILE = UUID.randomUUID();
    private static final String SQL = "SELECT SUM(\"매출\") AS \"합계\" FROM \"t1\"";

    private AttachmentService attachments;
    private DataQueryTool tool;

    @BeforeEach
    void setUp() {
        attachments = mock(AttachmentService.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        tool = new DataQueryTool(attachments, new DataTableReader(settings), settings);
    }

    private static ChatAttachmentEntity attachment(String mediaType) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(FILE);
        e.setSessionId("s1");
        e.setFilename("매출.csv");
        e.setMediaType(mediaType);
        return e;
    }

    private ToolResult call(String sessionId) {
        return tool.execute(Map.of("file_id", FILE.toString(), "sql", SQL), ToolContext.minimal("t", sessionId));
    }

    @Test
    @DisplayName("자기 세션의 데이터 파일이면 계산 결과를 돌려준다")
    void ownFile_returnsResult() {
        ChatAttachmentEntity att = attachment(AttachmentKind.CSV.mediaType());
        when(attachments.loadOwned(FILE, "s1")).thenReturn(att);
        when(attachments.readBytes(att)).thenReturn("지점,매출\n강남,100\n종로,250\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = call("s1");

        assertThat(r.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertThat(out.get("rows")).isEqualTo(List.of(List.of(new BigDecimal("350"))));
        assertThat(out).containsKeys("columns", "truncated", "elapsed_ms");
    }

    @Test
    @DisplayName("다른 세션 파일은 file not found")
    void otherSession_fileNotFound() {
        when(attachments.hasActiveDataFile("s1")).thenReturn(true);

        when(attachments.loadOwned(FILE, "s1")).thenThrow(new AttachmentException(HttpStatus.FORBIDDEN,
                AttachmentException.CODE_OWNERSHIP_DENIED, "other session"));
        assertThat(call("s1").summary()).isEqualTo(DataQueryTool.FILE_NOT_FOUND);
    }

    @Test
    @DisplayName("보관 기간이 지나 없는 파일은 file not found")
    void expired_fileNotFound() {
        when(attachments.hasActiveDataFile("s1")).thenReturn(true);
        when(attachments.loadOwned(FILE, "s1")).thenThrow(new AttachmentException(HttpStatus.NOT_FOUND,
                AttachmentException.CODE_NOT_FOUND, "expired"));
        assertThat(call("s1").summary()).isEqualTo(DataQueryTool.FILE_NOT_FOUND);
    }

    @Test
    @DisplayName("이미지 첨부는 file not found 이고 바이트를 읽지 않는다")
    void image_fileNotFound() {
        when(attachments.hasActiveDataFile("s1")).thenReturn(true);
        when(attachments.loadOwned(FILE, "s1")).thenReturn(attachment("image/png"));

        assertThat(call("s1").summary()).isEqualTo(DataQueryTool.FILE_NOT_FOUND);
        verify(attachments, never()).readBytes(any());
    }

    @Test
    @DisplayName("세션 없는 호출은 file not found, 데이터 파일 없는 세션은 no data file")
    void noSessionOrNoDataFile() {
        assertThat(call(null).summary()).isEqualTo(DataQueryTool.FILE_NOT_FOUND);

        when(attachments.loadOwned(eq(FILE), eq("s2"))).thenThrow(new AttachmentException(HttpStatus.NOT_FOUND,
                AttachmentException.CODE_NOT_FOUND, "none"));
        when(attachments.hasActiveDataFile("s2")).thenReturn(false);
        assertThat(call("s2").summary()).isEqualTo(DataQueryTool.NO_DATA_FILE);
    }

    @Test
    @DisplayName("SELECT 가 아니면 파일을 열기 전에 오류")
    void rejectedStatement() {
        ToolResult r = tool.execute(Map.of("file_id", FILE.toString(), "sql", "DELETE FROM \"t1\""),
                ToolContext.minimal("t", "s1"));

        assertThat(r.success()).isFalse();
        verify(attachments, never()).loadOwned(any(), anyString());
    }
}
