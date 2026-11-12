package com.platform.attachment.data;

import com.platform.attachment.AttachmentKind;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-183 TC-CHT-093 모델에 보내는 모양. */
class AttachmentPromptBuilderTest {

    private static final String GUIDE = "Use data_query for every number.";

    private PlatformSettingsService settings;
    private AttachmentPromptBuilder builder;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        PromptTemplateService templates = mock(PromptTemplateService.class);
        lenient().when(templates.getTemplate(AttachmentPromptBuilder.DATA_GUIDE_TEMPLATE)).thenReturn(GUIDE);
        builder = new AttachmentPromptBuilder(settings, new DataTableReader(settings), templates);
    }

    private static ChatAttachmentEntity att(String name) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(UUID.fromString("3f2a9c1e-0000-0000-0000-000000000001"));
        e.setFilename(name);
        return e;
    }

    @Test
    @DisplayName("150행 표 둘이면 표마다 앞 20행과 계산 안내")
    void twoLargeTables_previewRows() {
        byte[] xlsx = DataFiles.xlsx(wb -> {
            DataFiles.addSheet(wb, "서울", new Object[]{"품목", "수량"}, 150);
            DataFiles.addSheet(wb, "부산", new Object[]{"품목", "수량"}, 150);
        });

        String text = builder.dataSummary(att("매출.xlsx"), xlsx, AttachmentKind.XLSX, true);

        assertThat(text).startsWith("[첨부 데이터 파일: 매출.xlsx, file_id 3f2a9c1e-0000-0000-0000-000000000001]");
        assertThat(text).contains("표 t1 (시트 \"서울\"): 150행", "표 t2 (시트 \"부산\"): 150행");
        assertThat(text).contains("\"품목\" 글, \"수량\" 숫자");
        assertThat(text.split("앞 20행:", -1)).hasSize(3);
        assertThat(text).contains("| item20 | 20 |").doesNotContain("| item21 | 21 |");
        assertThat(text).endsWith(GUIDE);
    }

    @Test
    @DisplayName("100행 표 하나면 전체 행, 도구가 없는 연결이면 안내를 붙이지 않는다")
    void smallTable_allRows() {
        StringBuilder csv = new StringBuilder("품목,수량\n");
        for (int i = 1; i <= 100; i++) csv.append("item").append(i).append(',').append(i).append('\n');
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        String text = builder.dataSummary(att("a.csv"), bytes, AttachmentKind.CSV, true);
        assertThat(text).contains("전체 행:", "| item100 | 100 |").endsWith(GUIDE);

        assertThat(builder.dataSummary(att("a.csv"), bytes, AttachmentKind.CSV, false)).doesNotContain(GUIDE);
    }

    @Test
    @DisplayName("워드는 문단 순서대로, 표는 마크다운 표")
    void docx_paragraphsAndTable() {
        String text = builder.documentText(att("보고.docx"), DataFiles.docx(), AttachmentKind.DOCX);

        assertThat(text).startsWith("[첨부 문서: 보고.docx]\n첫 문단");
        assertThat(text).contains("| 지점 | 매출 |\n| --- | --- |\n| 강남 | 100 |");
        assertThat(text.indexOf("첫 문단")).isLessThan(text.indexOf("| 지점"));
        assertThat(text.indexOf("| 강남")).isLessThan(text.indexOf("끝 문단"));
    }

    @Test
    @DisplayName("글자 상한을 넘는 텍스트는 앞부분과 생략 줄")
    void longText_truncated() {
        when(settings.getInt(eq(AttachmentPromptBuilder.MAX_TEXT_CHARS_KEY), anyInt())).thenReturn(10);
        byte[] bytes = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);

        String text = builder.documentText(att("a.txt"), bytes, AttachmentKind.TEXT);

        assertThat(text).isEqualTo("[첨부 문서: a.txt]\n0123456789\n[앞 10자만 보냄, 전체 20자]");
    }
}
