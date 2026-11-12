package com.platform.document;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** 발표 파일(PPT)을 만든다(CR-182 3.2). 16:9, 표지 한 장과 장마다 제목, 글머리, 발표자 메모. */
public final class SlideFileWriter {

    public record SlideData(String title, List<String> bullets, String notes) {}

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int MARGIN = 48;

    private SlideFileWriter() {}

    public static byte[] pptx(String title, List<SlideData> slides) {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 빈 발표 파일의 기본 틀은 4:3 자리여서, 16:9 로 넓힌 뒤 칸 위치를 다시 잡는다.
            ppt.setPageSize(new Dimension(WIDTH, HEIGHT));
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            XSLFSlideLayout cover = master.getLayout(SlideLayout.TITLE);
            XSLFSlideLayout content = master.getLayout(SlideLayout.TITLE_AND_CONTENT);

            if (title != null && !title.isBlank()) {
                XSLFSlide slide = ppt.createSlide(cover);
                XSLFTextShape head = slide.getPlaceholder(0);
                head.setText(clean(title));
                head.setAnchor(new Rectangle2D.Double(MARGIN, HEIGHT * 0.32, WIDTH - MARGIN * 2, HEIGHT * 0.22));
                XSLFTextShape sub = slide.getPlaceholder(1);
                if (sub != null) slide.removeShape(sub);
            }
            for (SlideData data : slides) {
                XSLFSlide slide = ppt.createSlide(content);
                XSLFTextShape head = slide.getPlaceholder(0);
                head.setText(clean(data.title()));
                head.setAnchor(new Rectangle2D.Double(MARGIN, 24, WIDTH - MARGIN * 2, 80));
                XSLFTextShape body = slide.getPlaceholder(1);
                List<String> bullets = data.bullets() == null ? List.of() : data.bullets();
                if (bullets.isEmpty()) {
                    if (body != null) slide.removeShape(body);
                } else if (body != null) {
                    body.setAnchor(new Rectangle2D.Double(MARGIN, 120, WIDTH - MARGIN * 2, HEIGHT - 120 - 36));
                    body.clearText();
                    for (String b : bullets) {
                        body.addNewTextParagraph().addNewTextRun().setText(clean(b));
                    }
                }
                if (data.notes() != null && !data.notes().isBlank()) {
                    XSLFNotes notes = ppt.getNotesSlide(slide);
                    for (XSLFTextShape shape : notes.getPlaceholders()) {
                        if (shape.getTextType() == Placeholder.BODY) {
                            shape.setText(clean(data.notes()));
                            break;
                        }
                    }
                }
            }
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String clean(String s) {
        return s == null ? "" : DocumentRenderer.xmlSafe(s);
    }
}
