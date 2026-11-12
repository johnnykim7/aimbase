package com.platform.document;

import java.util.ArrayList;
import java.util.List;

/** PDF 시험은 한글 글꼴이 있어야 돈다. 운영 기본 폴더, 환경변수, 맥 로컬 글꼴 폴더를 차례로 본다. */
public final class TestFonts {

    private TestFonts() {}

    public static DocumentFonts find() {
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("AIMBASE_DOCUMENT_PDF_FONT_DIR");
        if (env != null && !env.isBlank()) candidates.add(env);
        candidates.add("/usr/share/fonts/truetype/nanum");
        candidates.add(System.getProperty("user.home") + "/Library/Fonts");
        for (String dir : candidates) {
            DocumentFonts fonts = new DocumentFonts(dir);
            if (fonts.available()) return fonts;
        }
        return new DocumentFonts(null);
    }
}
