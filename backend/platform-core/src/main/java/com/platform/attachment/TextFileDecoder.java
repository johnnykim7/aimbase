package com.platform.attachment;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * CR-183: 글 파일 판정과 해석(BIZ-220).
 *
 * <p>앞 8KB 에 NUL 바이트가 없어야 글 파일이다. UTF-8(BOM 허용)로 해석되면 UTF-8, 아니면 CP949 로 해석되면
 * CP949 다. 엑셀에서 저장한 한글 CSV 가 CP949 다. 둘 다 아니면 글 파일이 아니다.
 */
public final class TextFileDecoder {

    static final int SNIFF_BYTES = 8 * 1024;
    private static final Charset CP949 = Charset.forName("x-windows-949");

    private TextFileDecoder() {}

    /** 글 파일이면 해석한 본문, 아니면 null. */
    public static String decode(byte[] bytes) {
        if (bytes == null) return null;
        int sniff = Math.min(bytes.length, SNIFF_BYTES);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) return null;
        }
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        String utf8 = strictDecode(bytes, offset, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8;
        return strictDecode(bytes, 0, CP949);
    }

    private static boolean hasUtf8Bom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }

    private static String strictDecode(byte[] bytes, int offset, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
