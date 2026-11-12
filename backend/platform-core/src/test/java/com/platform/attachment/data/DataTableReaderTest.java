package com.platform.attachment.data;

import com.platform.attachment.AttachmentException;
import com.platform.attachment.AttachmentKind;
import com.platform.config.PlatformSettingsService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** CR-183 TC-CHT-090(종류별 읽기), TC-CHT-091(열어 보기 검사), TC-CHT-092(표로 읽기). */
class DataTableReaderTest {

    private PlatformSettingsService settings;
    private DataTableReader reader;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        reader = new DataTableReader(settings);
    }

    @Test
    @DisplayName("CSV 는 UTF-8 과 CP949 를 같은 표로 읽는다")
    void csv_utf8AndCp949() {
        String text = "지점,매출\n강남,1000\n부산,2000\n";
        for (Charset cs : List.of(StandardCharsets.UTF_8, Charset.forName("MS949"))) {
            List<DataTable> tables = reader.read(text.getBytes(cs), AttachmentKind.CSV);
            assertThat(tables).hasSize(1);
            DataTable t = tables.get(0);
            assertThat(t.name()).isEqualTo("t1");
            assertThat(t.columns()).extracting(DataTable.Column::name).containsExactly("지점", "매출");
            assertThat(t.columns().get(1).type()).isEqualTo(DataTable.ColumnType.NUMBER);
            assertThat(t.rows()).hasSize(2);
            assertThat(t.rows().get(0)[0]).isEqualTo("강남");
        }
    }

    @Test
    @DisplayName("TSV 는 탭으로 나눈다")
    void tsv() {
        List<DataTable> tables = reader.read("a\tb\n1\t2\n".getBytes(StandardCharsets.UTF_8), AttachmentKind.TSV);
        assertThat(tables.get(0).columns()).extracting(DataTable.Column::name).containsExactly("a", "b");
    }

    @Test
    @DisplayName("xlsx 는 시트마다 표 하나, xls 도 같다")
    void xlsxAndXls_sheetPerTable() {
        byte[] xlsx = DataFiles.xlsx(wb -> {
            DataFiles.addSheet(wb, "서울", new Object[]{"품목", "수량"}, 3);
            DataFiles.addSheet(wb, "부산", new Object[]{"품목", "수량"}, 2);
        });
        List<DataTable> tables = reader.read(xlsx, AttachmentKind.XLSX);
        assertThat(tables).extracting(DataTable::name).containsExactly("t1", "t2");
        assertThat(tables).extracting(DataTable::sheet).containsExactly("서울", "부산");
        assertThat(tables.get(1).rows()).hasSize(2);

        byte[] xls = DataFiles.xls(wb -> DataFiles.addSheet(wb, "s", new Object[]{"품목", "수량"}, 4));
        DataTable t = reader.read(xls, AttachmentKind.XLS).get(0);
        assertThat(t.rows()).hasSize(4);
        assertThat(t.columns().get(1).type()).isEqualTo(DataTable.ColumnType.NUMBER);
    }

    @Test
    @DisplayName("빈 머리 칸은 c열번호, 같은 이름은 _2, 수식은 저장된 결과, 병합은 왼쪽 위만, 날짜 칸은 DATE")
    void xlsx_readingRules() {
        byte[] bytes = DataFiles.xlsx(wb -> {
            Sheet s = wb.createSheet("s");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Row h = s.createRow(0);
            DataFiles.put(h, new Object[]{"매출", "매출", null, "일자", "지역"});
            for (int r = 1; r <= 2; r++) {
                Row row = s.createRow(r);
                row.createCell(0).setCellValue(r * 10);
                row.createCell(1).setCellFormula("A" + (r + 1) + "*2");
                row.createCell(2).setCellValue("x");
                var date = row.createCell(3);
                date.setCellValue(LocalDate.of(2026, 11, r));
                date.setCellStyle(dateStyle);
            }
            s.getRow(1).createCell(4).setCellValue("수도권");
            s.addMergedRegion(new CellRangeAddress(1, 2, 4, 4));
        });

        DataTable t = reader.read(bytes, AttachmentKind.XLSX).get(0);

        assertThat(t.columns()).extracting(DataTable.Column::name)
                .containsExactly("매출", "매출_2", "c3", "일자", "지역");
        assertThat(t.rows().get(1)[1]).isEqualTo(new BigDecimal("40"));
        assertThat(t.columns().get(3).type()).isEqualTo(DataTable.ColumnType.DATE);
        assertThat(t.rows().get(0)[3]).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(t.rows().get(0)[4]).isEqualTo("수도권");
        assertThat(t.rows().get(1)[4]).isNull();
    }

    @Test
    @DisplayName("쉼표 숫자 글은 숫자, 퍼센트 글은 글, 빈 행은 뺀다")
    void csv_numberTextAndEmptyRows() {
        String text = "금액,비율\n\"1,234\",12%\n,\n\"5,000.5\",3%\n";
        DataTable t = reader.read(text.getBytes(StandardCharsets.UTF_8), AttachmentKind.CSV).get(0);

        assertThat(t.columns().get(0).type()).isEqualTo(DataTable.ColumnType.NUMBER);
        assertThat(t.columns().get(1).type()).isEqualTo(DataTable.ColumnType.TEXT);
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(0)[0]).isEqualTo(new BigDecimal("1234"));
        assertThat(t.rows().get(1)[0]).isEqualTo(new BigDecimal("5000.5"));
    }

    @Test
    @DisplayName("암호 xlsx 는 FG-ATT-4003")
    void encrypted() {
        assertThatThrownBy(() -> reader.read(DataFiles.encryptedXlsx(), AttachmentKind.XLSX))
                .isInstanceOf(AttachmentException.class)
                .extracting("code").isEqualTo(AttachmentException.CODE_ENCRYPTED);
    }

    @Test
    @DisplayName("행 상한 + 1 은 FG-ATT-4005, 열 상한 + 1 은 FG-ATT-4006, 상한 값을 함께 준다")
    void rowAndColumnLimits() {
        lenient().when(settings.getInt(eq(DataTableReader.MAX_ROWS_KEY), anyInt())).thenReturn(3);
        lenient().when(settings.getInt(eq(DataTableReader.MAX_COLUMNS_KEY), anyInt())).thenReturn(2);

        byte[] rows = "a\n1\n2\n3\n4\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.read(rows, AttachmentKind.CSV))
                .isInstanceOfSatisfying(AttachmentException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AttachmentException.CODE_ROWS_EXCEEDED);
                    assertThat(e.getLimit()).isEqualTo(3L);
                });
        assertThat(reader.read("a\n1\n2\n3\n".getBytes(StandardCharsets.UTF_8), AttachmentKind.CSV).get(0).rows())
                .hasSize(3);

        byte[] cols = "a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.read(cols, AttachmentKind.CSV))
                .isInstanceOfSatisfying(AttachmentException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AttachmentException.CODE_COLUMNS_EXCEEDED);
                    assertThat(e.getLimit()).isEqualTo(2L);
                });
    }

    @Test
    @DisplayName("이름만 xlsx 인 PNG 는 FG-ATT-4221")
    void pngNamedXlsx() {
        byte[] png = Arrays.copyOf(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 64);
        assertThatThrownBy(() -> reader.read(png, AttachmentKind.XLSX))
                .isInstanceOf(AttachmentException.class)
                .extracting("code").isEqualTo(AttachmentException.CODE_MIME_MISMATCH);
    }
}
