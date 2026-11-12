package com.platform.attachment.data;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** CR-183 시험용 엑셀, 워드 파일을 만든다. */
public final class DataFiles {

    private DataFiles() {}

    public static byte[] xlsx(Consumer<Workbook> fill) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill.accept(wb);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] xls(Consumer<Workbook> fill) {
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill.accept(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 시트 하나, 머리 행 다음에 rows 행. 칸 값이 Number 면 숫자 칸, 아니면 글 칸이다. */
    public static byte[] xlsxRows(String sheetName, Object[] header, int rows) {
        return xlsx(wb -> addSheet(wb, sheetName, header, rows));
    }

    public static void addSheet(Workbook wb, String sheetName, Object[] header, int rows) {
        Sheet s = wb.createSheet(sheetName);
        put(s.createRow(0), header);
        for (int r = 1; r <= rows; r++) {
            put(s.createRow(r), new Object[]{"item" + r, r});
        }
    }

    public static void put(Row row, Object[] values) {
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v == null) continue;
            if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
            else row.createCell(c).setCellValue(String.valueOf(v));
        }
    }

    public static byte[] encryptedXlsx() {
        byte[] plain = xlsxRows("s", new Object[]{"a", "b"}, 1);
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.standard);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword("secret");
            try (OutputStream os = enc.getDataStream(fs)) {
                os.write(plain);
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 문단, 표, 문단 순서의 워드 파일. */
    public static byte[] docx() {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("첫 문단");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("지점");
            table.getRow(0).getCell(1).setText("매출");
            table.getRow(1).getCell(0).setText("강남");
            table.getRow(1).getCell(1).setText("100");
            doc.createParagraph().createRun().setText("끝 문단");
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** zip 이고 본문 항목도 있지만 XML 이 깨진 워드 파일. */
    public static byte[] brokenDocx() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
