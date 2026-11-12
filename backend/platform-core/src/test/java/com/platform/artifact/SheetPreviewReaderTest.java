package com.platform.artifact;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-180 TC-CHT-062, 068: 표 미리보기 읽기. */
class SheetPreviewReaderTest {

    @TempDir Path dir;

    private final SheetPreviewReader reader = new SheetPreviewReader();

    // TC-CHT-062
    @Test
    @SuppressWarnings("unchecked")
    void xlsx_evaluates_formulas_formats_dates_and_truncates_at_limits() throws IOException {
        Path file = dir.resolve("매출.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet first = wb.createSheet("1월");
            Row r0 = first.createRow(0);
            r0.createCell(0).setCellValue(2);
            r0.createCell(1).setCellValue(3);
            r0.createCell(2).setCellFormula("A1*B1");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            var dateCell = first.createRow(1).createCell(0);
            dateCell.setCellValue(LocalDate.of(2026, 11, 13));
            dateCell.setCellStyle(dateStyle);

            Sheet big = wb.createSheet("큰 시트");
            for (int r = 0; r < 600; r++) {
                Row row = big.createRow(r);
                for (int c = 0; c < 60; c++) row.createCell(c).setCellValue(r + "-" + c);
            }
            try (OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }

        List<Map<String, Object>> sheets = reader.read(file, "xlsx", 500, 50);

        assertThat(sheets).hasSize(2);
        Map<String, Object> s1 = sheets.get(0);
        List<List<String>> rows1 = (List<List<String>>) s1.get("rows");
        assertThat(s1).containsEntry("name", "1월").containsEntry("truncated", false);
        assertThat(rows1.get(0)).containsExactly("2", "3", "6");
        assertThat(rows1.get(1).get(0)).isEqualTo("2026-11-13");

        Map<String, Object> s2 = sheets.get(1);
        List<List<String>> rows2 = (List<List<String>>) s2.get("rows");
        assertThat(s2).containsEntry("row_count", 600).containsEntry("col_count", 60).containsEntry("truncated", true);
        assertThat(rows2).hasSize(500);
        assertThat(rows2).allSatisfy(r -> assertThat(r).hasSize(50));
        assertThat(rows2.get(499).get(49)).isEqualTo("499-49");
    }

    // TC-CHT-068
    @Test
    @SuppressWarnings("unchecked")
    void csv_reads_utf8_bom_cp949_and_quoted_commas_and_newlines() throws IOException {
        Path bom = dir.resolve("bom.csv");
        byte[] body = "지점,매출\n강남,1200\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Files.write(bom, withBom);

        Path legacy = dir.resolve("legacy.csv");
        Files.write(legacy, "지점,매출\r\n서초,900\r\n".getBytes(Charset.forName("MS949")));

        Path quoted = dir.resolve("quoted.csv");
        Files.writeString(quoted, "\"서울, 강남\",\"첫 줄\n둘째 줄\",\"따옴표 \"\"안\"\"\"\n끝,,\n");

        Map<String, Object> a = reader.readCsv(bom, 500, 50);
        assertThat((List<List<String>>) a.get("rows")).containsExactly(List.of("지점", "매출"), List.of("강남", "1200"));
        assertThat(a).containsEntry("name", "bom").containsEntry("row_count", 2).containsEntry("col_count", 2);

        Map<String, Object> b = reader.readCsv(legacy, 500, 50);
        assertThat((List<List<String>>) b.get("rows")).containsExactly(List.of("지점", "매출"), List.of("서초", "900"));

        Map<String, Object> c = reader.readCsv(quoted, 500, 50);
        assertThat((List<List<String>>) c.get("rows")).containsExactly(
                List.of("서울, 강남", "첫 줄\n둘째 줄", "따옴표 \"안\""),
                List.of("끝", "", ""));
        assertThat(c).containsEntry("row_count", 2).containsEntry("col_count", 3).containsEntry("truncated", false);
    }
}
