package com.platform.artifact;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-180: xlsx, csv 를 옆 창 표 미리보기용 데이터로 읽는다(BIZ-193).
 * 시트마다 앞 행과 열만 주고, 전체 크기와 잘렸는지를 함께 준다.
 */
@Component
public class SheetPreviewReader {

    /** 한국어 엑셀이 csv 로 저장할 때 쓰는 인코딩. UTF-8 로 읽히지 않는 파일에만 쓴다. */
    private static final Charset LEGACY_KOREAN = Charset.forName("MS949");

    public List<Map<String, Object>> read(Path file, String ext, int maxRows, int maxCols) throws IOException {
        return "csv".equals(ext)
                ? List.of(readCsv(file, maxRows, maxCols))
                : readXlsx(file, maxRows, maxCols);
    }

    List<Map<String, Object>> readXlsx(Path file, int maxRows, int maxCols) throws IOException {
        List<Map<String, Object>> sheets = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file.toFile(), null, true)) {
            // 파이썬 openpyxl 로 만든 파일은 수식 칸에 계산 값을 남기지 않는다. 서버가 계산해 보여 준다.
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();
            for (Sheet sheet : wb) {
                int rowCount = sheet.getLastRowNum() + 1;
                int colCount = 0;
                for (Row row : sheet) {
                    colCount = Math.max(colCount, row.getLastCellNum());
                }
                int width = Math.min(colCount, maxCols);
                int height = Math.min(rowCount, maxRows);
                List<List<String>> rows = new ArrayList<>(height);
                for (int r = 0; r < height; r++) {
                    Row row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>(width);
                    for (int c = 0; c < width; c++) {
                        Cell cell = row == null ? null : row.getCell(c);
                        cells.add(cell == null ? "" : cellText(cell, formatter, evaluator));
                    }
                    rows.add(cells);
                }
                sheets.add(sheet(sheet.getSheetName(), rows, rowCount, colCount, maxRows, maxCols));
            }
        }
        return sheets;
    }

    private static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            // 계산할 수 없는 수식(외부 파일 참조, 지원하지 않는 함수)은 수식 글을 그대로 보인다.
            return "=" + cell.getCellFormula();
        }
    }

    Map<String, Object> readCsv(Path file, int maxRows, int maxCols) throws IOException {
        String text = decode(Files.readAllBytes(file));
        List<List<String>> rows = new ArrayList<>();
        int rowCount = 0;
        int colCount = 0;
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean pending = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"' && field.isEmpty()) {
                inQuotes = true;
                pending = true;
            } else if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
                pending = true;
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                current.add(field.toString());
                field.setLength(0);
                colCount = Math.max(colCount, current.size());
                if (rowCount < maxRows) rows.add(current);
                rowCount++;
                current = new ArrayList<>();
                pending = false;
            } else {
                field.append(ch);
                pending = true;
            }
        }
        if (pending || !field.isEmpty()) {
            current.add(field.toString());
            colCount = Math.max(colCount, current.size());
            if (rowCount < maxRows) rows.add(current);
            rowCount++;
        }
        int width = Math.min(colCount, maxCols);
        List<List<String>> fitted = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>(width);
            for (int c = 0; c < width; c++) cells.add(c < row.size() ? row.get(c) : "");
            fitted.add(cells);
        }
        return sheet(baseName(file), fitted, rowCount, colCount, maxRows, maxCols);
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, LEGACY_KOREAN);
        }
    }

    private static Map<String, Object> sheet(String name, List<List<String>> rows,
                                             int rowCount, int colCount, int maxRows, int maxCols) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("rows", rows);
        m.put("row_count", rowCount);
        m.put("col_count", colCount);
        m.put("truncated", rowCount > maxRows || colCount > maxCols);
        return m;
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
