package com.platform.largeinput;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * CR-120: 텍스트 소스 로더 (text/plain, text/markdown 등).
 *
 * <p>운영 워크플로우가 {@code description.txt} 같은 텍스트 파일을 {@code source_file} 경로로 LARGE_INPUT
 * 에 넘기면, 바이트를 UTF-8 문자열로 읽어 {@code inlineText} 로 싣는다. 그러면 분해기가
 * {@link LargeInputDecomposerService#decompose}에서 {@code isInlineText()} 경로로 가서
 * "작으면(≤ chunk-budget-chars) 1청크, 크면 문자 예산으로 분할"한다 — 즉 <b>타입이 아니라 크기로</b>
 * 분해 여부가 결정된다(LARGE_INPUT 의 본래 의도). 텍스트 <i>파일</i>이 인라인 텍스트와 달리 로더가 없어
 * "no SourceLoader for mime text/plain" 으로 깨지던 비대칭 결함을 해소한다.
 *
 * <p>{@code bytes} 는 totalPages 개념이 없으므로 null page, inlineText 만 채운다.
 */
@Component
public class TextSourceLoader implements SourceLoader {

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return false;
        String m = mimeType.toLowerCase();
        // text/* 전반 + 흔한 평문 계열(json/xml/csv 등은 text 로 안 잡힐 수 있어 명시).
        return m.startsWith("text/")
                || m.contains("plain")
                || m.contains("markdown")
                || m.contains("csv")
                || m.contains("json")
                || m.contains("xml");
    }

    @Override
    public LargeInputSource load(String sourceId, String mimeType, byte[] bytes) {
        String text = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
        // inlineText 로 실어 분해기의 텍스트 경로(크기 기준 분해)를 타게 한다. bytes/totalPages 는 불필요.
        return new LargeInputSource(sourceId, mimeType, null, text, null);
    }
}
