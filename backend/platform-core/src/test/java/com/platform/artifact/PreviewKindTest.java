package com.platform.artifact;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-180 TC-CHT-060: 미리보기 종류. */
class PreviewKindTest {

    @ParameterizedTest
    @CsvSource({
            "a.pptx, PAGES", "a.docx, PAGES", "a.pdf, PAGES",
            "a.xlsx, SHEET", "a.csv, SHEET",
            "a.html, HTML", "a.svg, SVG",
            "a.png, IMAGE", "a.jpg, IMAGE",
            "a.md, MARKDOWN", "a.txt, TEXT", "a.json, TEXT",
            "제안서.PPTX, PAGES",
            "a.zip, NONE", "noext, NONE"
    })
    void kind_follows_extension(String name, PreviewKind expected) {
        assertThat(PreviewKind.of(name)).isEqualTo(expected);
    }
}
