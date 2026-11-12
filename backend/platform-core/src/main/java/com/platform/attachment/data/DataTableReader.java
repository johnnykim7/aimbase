package com.platform.attachment.data;

import com.platform.attachment.AttachmentException;
import com.platform.attachment.AttachmentKind;
import com.platform.attachment.TextFileDecoder;
import com.platform.config.PlatformSettingsService;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CR-183: 데이터 파일(xlsx, xls, csv, tsv)을 표로 읽는다(T3-2, BIZ-221).
 *
 * <p>올릴 때(상한 검사), 요약을 만들 때, data_query 가 계산할 때 같은 규칙으로 읽는다.
 * xlsx 는 시트 XML 을 흘려 읽는다. 통째로 여는 방식은 10MB 파일에도 수백 MB 를 쓴다.
 */
@Component
public class DataTableReader {

    public static final String MAX_ROWS_KEY = "widget.data.max-rows";
    public static final String MAX_COLUMNS_KEY = "widget.data.max-columns";
    static final int DEFAULT_MAX_ROWS = 100_000;
    static final int DEFAULT_MAX_COLUMNS = 200;
    /** H2 이름 길이 상한(256)보다 짧게 자른다. 머리 칸에 문장이 들어 있는 시트가 있다. */
    static final int MAX_COLUMN_NAME_CHARS = 100;

    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?");
    private static final Pattern DATE = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{4})-(\\d{1,2})-(\\d{1,2})[ T](\\d{1,2}):(\\d{2})(:(\\d{2})(\\.\\d{1,9})?)?");
    private static final DateTimeFormatter EXCEL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter EXCEL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter EXCEL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final MathContext EXCEL_PRECISION = new MathContext(15);

    private final PlatformSettingsService settings;

    public DataTableReader(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public List<DataTable> read(byte[] bytes, AttachmentKind kind) {
        int maxRows = settings.getInt(MAX_ROWS_KEY, DEFAULT_MAX_ROWS);
        int maxColumns = settings.getInt(MAX_COLUMNS_KEY, DEFAULT_MAX_COLUMNS);
        List<RawSheet> sheets = switch (kind) {
            case XLSX -> readXlsx(bytes, maxRows, maxColumns);
            case XLS -> readXls(bytes, maxRows, maxColumns);
            case CSV -> List.of(readDelimited(bytes, ',', maxRows, maxColumns));
            case TSV -> List.of(readDelimited(bytes, '\t', maxRows, maxColumns));
            default -> throw new IllegalArgumentException("not a data file: " + kind);
        };
        List<DataTable> tables = new ArrayList<>();
        for (RawSheet sheet : sheets) {
            if (sheet.isEmpty()) continue;
            tables.add(build("t" + (tables.size() + 1), sheet));
        }
        return tables;
    }

    private static RawSheet readDelimited(byte[] bytes, char delimiter, int maxRows, int maxColumns) {
        String text = TextFileDecoder.decode(bytes);
        if (text == null) {
            throw new AttachmentException(HttpStatus.UNPROCESSABLE_ENTITY, AttachmentException.CODE_MIME_MISMATCH,
                    "file is not UTF-8 or CP949 text");
        }
        RawSheet sheet = new RawSheet(null, maxRows, maxColumns);
        DelimitedTextParser.parse(text, delimiter, sheet);
        return sheet;
    }

    private static List<RawSheet> readXlsx(byte[] bytes, int maxRows, int maxColumns) {
        OfficeFiles.assertOoxml(bytes, "xl/workbook.xml");
        List<RawSheet> sheets = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(bytes))) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (it.hasNext()) {
                try (InputStream sheetXml = it.next()) {
                    RawSheet sheet = new RawSheet(it.getSheetName(), maxRows, maxColumns);
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(
                            styles, null, strings, new XlsxRows(sheet), new RawValueFormatter(), false));
                    parser.parse(new InputSource(sheetXml));
                    sheets.add(sheet);
                }
            }
        } catch (AttachmentException e) {
            throw e;
        } catch (Exception e) {
            throw OfficeFiles.unreadable(e.getClass().getSimpleName());
        }
        return sheets;
    }

    private static List<RawSheet> readXls(byte[] bytes, int maxRows, int maxColumns) {
        OfficeFiles.assertXls(bytes);
        List<RawSheet> sheets = new ArrayList<>();
        RawValueFormatter formatter = new RawValueFormatter();
        try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet s : workbook) {
                RawSheet sheet = new RawSheet(s.getSheetName(), maxRows, maxColumns);
                for (Row row : s) {
                    sheet.startRow();
                    for (Cell cell : row) {
                        sheet.cell(cell.getColumnIndex(), xlsValue(cell, formatter));
                    }
                    sheet.endRow();
                }
                sheets.add(sheet);
            }
        } catch (AttachmentException e) {
            throw e;
        } catch (EncryptedDocumentException e) {
            throw OfficeFiles.encrypted();
        } catch (Exception e) {
            throw OfficeFiles.unreadable(e.getClass().getSimpleName());
        }
        return sheets;
    }

    private static String xlsValue(Cell cell, RawValueFormatter formatter) {
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> {
                CellStyle style = cell.getCellStyle();
                yield formatter.formatRawCellContents(cell.getNumericCellValue(),
                        style.getDataFormat(), style.getDataFormatString());
            }
            case STRING -> cell.getRichStringCellValue().getString();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            default -> null;
        };
    }

    /** xlsx 시트 처리기의 칸을 RawSheet 로 옮긴다. 칸 주소가 없으면 앞 칸 다음 자리다. */
    private static final class XlsxRows implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final RawSheet sheet;
        private int lastColumn = -1;

        XlsxRows(RawSheet sheet) { this.sheet = sheet; }

        @Override
        public void startRow(int rowNum) {
            lastColumn = -1;
            sheet.startRow();
        }

        @Override
        public void endRow(int rowNum) {
            sheet.endRow();
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = cellReference != null ? new CellReference(cellReference).getCol() : lastColumn + 1;
            lastColumn = column;
            // 오류 칸(#DIV/0! 등)은 빈 칸으로 본다. 한 칸 때문에 숫자 열이 글 열이 되면 합계를 못 낸다.
            if (formattedValue != null && formattedValue.startsWith("ERROR:#")) return;
            sheet.cell(column, formattedValue);
        }
    }

    /**
     * 엑셀 숫자 칸을 화면 서식 대신 값 그대로 글로 바꾼다. 날짜 서식이면 날짜 글이다.
     * 엑셀은 유효 숫자 15자리까지만 보이므로 그 자리에서 맞춘다(0.1+0.2 가 0.3).
     */
    static final class RawValueFormatter extends DataFormatter {
        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString,
                                            boolean use1904Windowing) {
            if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                if (value < 1) {
                    return DateUtil.getLocalDateTime(value, use1904Windowing).format(EXCEL_TIME);
                }
                LocalDateTime dt = DateUtil.getLocalDateTime(value, use1904Windowing, true);
                return dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                        ? dt.format(EXCEL_DATE) : dt.format(EXCEL_TIMESTAMP);
            }
            return plainNumber(value);
        }
    }

    static String plainNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return String.valueOf(value);
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return new BigDecimal(value, EXCEL_PRECISION).stripTrailingZeros().toPlainString();
    }

    // ─── 형 정하기 ───

    static DataTable build(String name, RawSheet sheet) {
        List<List<String>> raw = sheet.rows();
        List<String> header = raw.get(0);
        int width = sheet.width();
        List<String> names = columnNames(header, width);
        List<List<String>> body = raw.subList(1, raw.size());

        List<DataTable.Column> columns = new ArrayList<>(width);
        for (int c = 0; c < width; c++) {
            columns.add(new DataTable.Column(names.get(c), inferType(body, c)));
        }
        List<Object[]> rows = new ArrayList<>(body.size());
        for (List<String> r : body) {
            Object[] values = new Object[width];
            for (int c = 0; c < width; c++) {
                String v = c < r.size() ? r.get(c) : null;
                values[c] = v == null ? null : convert(v, columns.get(c).type());
            }
            rows.add(values);
        }
        return new DataTable(name, sheet.sheetName(), List.copyOf(columns), rows);
    }

    static List<String> columnNames(List<String> header, int width) {
        List<String> names = new ArrayList<>(width);
        Set<String> used = new HashSet<>();
        for (int c = 0; c < width; c++) {
            String h = c < header.size() ? header.get(c) : null;
            String base = h == null || h.isBlank() ? "c" + (c + 1) : h.replaceAll("\\s+", " ").strip();
            if (base.length() > MAX_COLUMN_NAME_CHARS) base = base.substring(0, MAX_COLUMN_NAME_CHARS);
            String candidate = base;
            int n = 2;
            while (!used.add(candidate)) {
                candidate = base + "_" + n++;
            }
            names.add(candidate);
        }
        return names;
    }

    static DataTable.ColumnType inferType(List<List<String>> body, int column) {
        boolean any = false;
        boolean allNumber = true;
        boolean allDate = true;
        boolean anyTime = false;
        for (List<String> r : body) {
            String v = column < r.size() ? r.get(column) : null;
            if (v == null) continue;
            any = true;
            if (allNumber && toNumber(v) == null) allNumber = false;
            if (allDate) {
                if (TIMESTAMP.matcher(v).matches() && toTimestamp(v) != null) {
                    anyTime = true;
                } else if (!(DATE.matcher(v).matches() && toDate(v) != null)) {
                    allDate = false;
                }
            }
            if (!allNumber && !allDate) break;
        }
        if (!any) return DataTable.ColumnType.TEXT;
        if (allNumber) return DataTable.ColumnType.NUMBER;
        if (allDate) return anyTime ? DataTable.ColumnType.TIMESTAMP : DataTable.ColumnType.DATE;
        return DataTable.ColumnType.TEXT;
    }

    static Object convert(String v, DataTable.ColumnType type) {
        return switch (type) {
            case NUMBER -> toNumber(v);
            case DATE -> toDate(v);
            case TIMESTAMP -> {
                LocalDateTime t = toTimestamp(v);
                if (t != null) yield t;
                LocalDate d = toDate(v);
                yield d != null ? d.atStartOfDay() : null;
            }
            case TEXT -> v;
        };
    }

    static BigDecimal toNumber(String v) {
        String s = v;
        if (GROUPED_NUMBER.matcher(s).matches()) {
            s = s.replace(",", "");
        } else if (!PLAIN_NUMBER.matcher(s).matches()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate toDate(String v) {
        var m = DATE.matcher(v);
        if (!m.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static LocalDateTime toTimestamp(String v) {
        var m = TIMESTAMP.matcher(v);
        if (!m.matches()) return null;
        try {
            int second = m.group(7) != null ? Integer.parseInt(m.group(7)) : 0;
            int nano = 0;
            if (m.group(8) != null) {
                String frac = (m.group(8).substring(1) + "000000000").substring(0, 9);
                nano = Integer.parseInt(frac);
            }
            return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    second, nano);
        } catch (java.time.DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}
