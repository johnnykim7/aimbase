package com.platform.attachment.data;

import com.platform.attachment.AttachmentException;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CR-183: 엑셀, 워드 파일이 확장자대로의 내용인지와 암호 설정 여부를 본다(BIZ-220).
 *
 * <p>암호를 건 xlsx, docx 는 zip 이 아니라 OLE 파일 안에 EncryptionInfo 와 암호화된 본문을 담는다.
 */
final class OfficeFiles {

    private static final String ENCRYPTION_INFO = "EncryptionInfo";

    private OfficeFiles() {}

    /** xlsx, docx: zip 머리이고 안에 그 종류의 본문 항목이 있어야 한다. */
    static void assertOoxml(byte[] bytes, String requiredEntry) {
        FileMagic magic = magic(bytes);
        if (magic == FileMagic.OLE2) {
            if (hasOleEntry(bytes, ENCRYPTION_INFO)) {
                throw encrypted();
            }
            throw mismatch("expected an Office Open XML file but found an OLE file");
        }
        if (magic != FileMagic.OOXML || !hasZipEntry(bytes, requiredEntry)) {
            throw mismatch("file content does not match its extension (missing " + requiredEntry + ")");
        }
    }

    /** xls: OLE 머리이고 통합 문서 항목이 있어야 한다. */
    static void assertXls(byte[] bytes) {
        if (magic(bytes) != FileMagic.OLE2) {
            throw mismatch("expected an Excel 97-2003 workbook");
        }
        if (hasOleEntry(bytes, ENCRYPTION_INFO)) {
            throw encrypted();
        }
        if (!hasOleEntry(bytes, "Workbook") && !hasOleEntry(bytes, "Book")) {
            throw mismatch("OLE file has no workbook");
        }
    }

    static AttachmentException encrypted() {
        return new AttachmentException(HttpStatus.BAD_REQUEST, AttachmentException.CODE_ENCRYPTED,
                "password protected files cannot be opened");
    }

    static AttachmentException unreadable(String reason) {
        return new AttachmentException(HttpStatus.BAD_REQUEST, AttachmentException.CODE_UNREADABLE,
                "file cannot be opened: " + reason);
    }

    static AttachmentException mismatch(String reason) {
        return new AttachmentException(HttpStatus.UNPROCESSABLE_ENTITY, AttachmentException.CODE_MIME_MISMATCH, reason);
    }

    private static FileMagic magic(byte[] bytes) {
        try {
            return FileMagic.valueOf(bytes);
        } catch (RuntimeException e) {
            return FileMagic.UNKNOWN;
        }
    }

    private static boolean hasZipEntry(byte[] bytes, String name) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return true;
            }
            return false;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hasOleEntry(byte[] bytes, String name) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            DirectoryNode root = fs.getRoot();
            return root.hasEntry(name);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
