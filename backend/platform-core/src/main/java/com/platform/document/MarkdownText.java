package com.platform.document;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;

import java.util.Locale;

final class MarkdownText {

    private MarkdownText() {}

    /** 마디 아래 글자만 이어 붙인다(그림 대체 글, 표 칸, 제목 글). */
    static String plain(Node node) {
        StringBuilder sb = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                sb.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                sb.append(code.getLiteral());
            }

            @Override
            public void visit(HtmlInline html) {
                sb.append(html.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak br) {
                sb.append(' ');
            }

            @Override
            public void visit(HardLineBreak br) {
                sb.append('\n');
            }
        });
        return sb.toString();
    }

    /**
     * 문서에 링크로 남겨도 되는 주소. 웹과 메일만 둔다. 파일 주소나 스크립트 주소가 PDF, 워드, 공유 화면에서
     * 눌리지 않게 한다.
     */
    static boolean isSafeLink(String url) {
        if (url == null) return false;
        String u = url.strip().toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:");
    }
}
