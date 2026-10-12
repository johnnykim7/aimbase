package com.platform.skill;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** CR-147: 바이트 상한으로 UTF-8 을 자를 때 글자 중간에서 끊지 않는다. */
final class Utf8 {

    private Utf8() {}

    static String truncate(byte[] bytes, int maxBytes) {
        int limit = Math.min(bytes.length, maxBytes);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        CharBuffer out = CharBuffer.allocate(limit);
        decoder.decode(ByteBuffer.wrap(bytes, 0, limit), out, true);
        out.flip();
        return out.toString();
    }
}
