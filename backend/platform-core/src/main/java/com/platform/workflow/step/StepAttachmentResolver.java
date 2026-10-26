package com.platform.workflow.step;

import com.platform.attachment.AttachmentService;
import com.platform.attachment.MimeValidator;
import com.platform.attachment.PdfTextExtractor;
import com.platform.attachment.PdfVisionResolver;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.llm.adapter.AdapterCapability;
import com.platform.llm.model.ContentBlock;
import com.platform.workflow.StepContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-159: 워크플로우 단계 config 의 {@code attachments} 를 모델에 보낼 그림, 문서 블록으로 바꾼다.
 *
 * <p>첨부는 채팅 첨부 API 로 올린 것을 실행 세션 소유로만 읽는다(LARGE_INPUT source_file 과 같은 규칙).
 * PDF 는 채팅 첨부와 같은 판정기({@link PdfVisionResolver})를 써서 연결이 받는 형태로 보낸다.
 */
@Component
public class StepAttachmentResolver {

    private static final float JPEG_QUALITY = 0.85f;

    private final AttachmentService attachmentService;
    private final PdfVisionResolver pdfVisionResolver;
    private final PdfTextExtractor pdfTextExtractor;
    private final int imageMaxEdge;

    public StepAttachmentResolver(AttachmentService attachmentService,
                                  PdfVisionResolver pdfVisionResolver,
                                  PdfTextExtractor pdfTextExtractor,
                                  @Value("${aimbase.workflow.attachment.image-max-edge:1568}") int imageMaxEdge) {
        this.attachmentService = attachmentService;
        this.pdfVisionResolver = pdfVisionResolver;
        this.pdfTextExtractor = pdfTextExtractor;
        this.imageMaxEdge = imageMaxEdge;
    }

    /**
     * @param blocks  사용자 메시지의 프롬프트 글 뒤에 붙일 블록
     * @param summary 실행 기록에 남길 첨부 목록(파일 내용 없음). 첨부가 없으면 빈 글
     */
    public record Resolved(List<ContentBlock> blocks, String summary) {
        public static final Resolved EMPTY = new Resolved(List.of(), "");

        public boolean isEmpty() { return blocks.isEmpty(); }
    }

    /**
     * @param ref config {@code attachments} 원값. {@code {{input.files}}} 같은 참조나 목록.
     *            참조가 비면(그 입력을 주지 않은 실행) 첨부 없음으로 본다. 같은 워크플로우를 글 원문에도 쓰기 위함.
     */
    public Resolved resolve(String stepId, Object ref, StepContext context, AdapterCapability capability) {
        List<UUID> ids = attachmentIds(stepId, context.resolveObject(ref));
        if (ids.isEmpty()) return Resolved.EMPTY;

        List<ContentBlock> blocks = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (UUID id : ids) {
            ChatAttachmentEntity att = attachmentService.loadOwned(id, context.sessionId());
            byte[] bytes = attachmentService.readBytes(att);
            String mediaType = att.getMediaType();
            blocks.add(new ContentBlock.Text("[첨부: " + att.getFilename() + "]"));
            String sent;
            if (MimeValidator.isImage(mediaType)) {
                if (!capability.supportsImage()) {
                    throw new IllegalStateException("LLM_CALL step '" + stepId
                            + "': 이 연결은 그림 입력을 받지 않는다 (" + att.getFilename() + ")");
                }
                ImageOut image = imageBlock(bytes, mediaType);
                blocks.add(image.block());
                sent = image.resized() ? "그림(줄임 " + image.block().mediaType() + ")" : "그림";
            } else if (MimeValidator.isPdf(mediaType)) {
                sent = addPdf(stepId, att, bytes, capability, blocks);
            } else {
                throw new IllegalArgumentException("LLM_CALL step '" + stepId
                        + "': 첨부 형식을 보낼 수 없다: " + mediaType + " (" + att.getFilename() + ")");
            }
            summary.append("[첨부] ").append(att.getFilename()).append(' ').append(mediaType)
                    .append(' ').append(bytes.length).append("B, ").append(sent).append('\n');
        }
        return new Resolved(List.copyOf(blocks), summary.toString().trim());
    }

    private static List<UUID> attachmentIds(String stepId, Object resolved) {
        if (resolved == null) return List.of();
        List<?> items = resolved instanceof List<?> list ? list : List.of(resolved);
        List<UUID> ids = new ArrayList<>();
        for (Object item : items) {
            Object raw = item instanceof Map<?, ?> m ? m.get("attachment_id") : item;
            if (raw == null || raw.toString().isBlank()) continue;
            try {
                ids.add(UUID.fromString(raw.toString().trim()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("LLM_CALL step '" + stepId
                        + "': attachments 항목이 첨부 번호가 아니다: " + raw);
            }
        }
        return ids;
    }

    private String addPdf(String stepId, ChatAttachmentEntity att, byte[] bytes,
                          AdapterCapability capability, List<ContentBlock> blocks) {
        PdfVisionResolver.PdfVisionResult vision =
                pdfVisionResolver.resolve(bytes, capability.supportsPdf(), capability.supportsImage());
        switch (vision.mode()) {
            case INLINE_PDF -> {
                blocks.add(ContentBlock.Document.ofBase64(att.getMediaType(), vision.pdfBase64(), att.getFilename()));
                return "PDF 통째";
            }
            // 첨부 경로는 쪽 수 가드를 쓰지 않아 NEEDS_SPLIT 이 오지 않는다. 와도 받은 쪽 그림을 보낸다.
            case PAGE_IMAGES, NEEDS_SPLIT -> {
                if (vision.note() != null) {
                    blocks.add(new ContentBlock.Text("[첨부 PDF: " + att.getFilename() + ", " + vision.note() + "]"));
                }
                for (PdfVisionResolver.PageImage page : vision.images()) {
                    blocks.add(ContentBlock.Image.ofBase64(page.mediaType(), page.base64()));
                }
                return "쪽 그림 " + vision.images().size() + "장";
            }
            default -> {
                // 쪽 그림을 못 만들면 글로 대신한다. 스캔 PDF 는 글이 비므로 빈 원문으로 성공시키지 않는다.
                PdfTextExtractor.ExtractResult extracted = pdfTextExtractor.extract(bytes);
                if (extracted.isEmpty()) {
                    throw new IllegalStateException("LLM_CALL step '" + stepId + "': PDF 를 그림으로도 글로도 읽지 못했다 ("
                            + att.getFilename() + ", " + vision.note() + ")");
                }
                blocks.add(new ContentBlock.Text(extracted.text()));
                return "뽑은 글(" + vision.note() + ")";
            }
        }
    }

    /**
     * 긴 변이 상한보다 크면 줄인다. 모델도 그 크기로 줄여 읽으므로 잃는 것이 없고, 큰 휴대폰 사진이
     * 그림 한 장 한도(5MB)에 걸리지 않는다. 다시 쓰면 EXIF 가 빠지므로 회전 표시를 픽셀에 먼저 반영한다.
     */
    ImageOut imageBlock(byte[] bytes, String mediaType) {
        ImageOut original = new ImageOut(
                ContentBlock.Image.ofBase64(mediaType, Base64.getEncoder().encodeToString(bytes)), false);
        BufferedImage source = imageMaxEdge > 0 ? readImage(bytes) : null;
        if (source == null || Math.max(source.getWidth(), source.getHeight()) <= imageMaxEdge) {
            return original;
        }
        int orientation = MimeValidator.JPEG.equals(mediaType) ? jpegOrientation(bytes) : 1;
        BufferedImage scaled = applyOrientation(downscale(source, imageMaxEdge), orientation);
        boolean alpha = scaled.getColorModel().hasAlpha();
        try {
            byte[] out = alpha ? writePng(scaled) : writeJpeg(scaled);
            return new ImageOut(ContentBlock.Image.ofBase64(alpha ? MimeValidator.PNG : MimeValidator.JPEG,
                    Base64.getEncoder().encodeToString(out)), true);
        } catch (IOException e) {
            return original;
        }
    }

    record ImageOut(ContentBlock.Image block, boolean resized) {}

    private static BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 한 번에 크게 줄이면 글자가 뭉개져 절반씩 줄인 뒤 마지막에 맞춘다. */
    static BufferedImage downscale(BufferedImage source, int maxEdge) {
        boolean alpha = source.getColorModel().hasAlpha();
        double scale = (double) maxEdge / Math.max(source.getWidth(), source.getHeight());
        int targetW = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        do {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            current = redraw(current, w, h, alpha);
        } while (w != targetW || h != targetH);
        return current;
    }

    private static BufferedImage redraw(BufferedImage src, int w, int h, boolean alpha) {
        BufferedImage dst = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
            }
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** EXIF Orientation(1~8) 대로 픽셀을 돌린다. 저장 픽셀 (x, y) 가 화면 어디에 오는지의 아핀 변환. */
    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation < 2 || orientation > 8) return img;
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w);
        };
        boolean swap = orientation >= 5;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, img.getType());
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(img, t, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** JPEG APP1 Exif 의 0th IFD 에서 Orientation(0x0112) 을 읽는다. 없거나 깨졌으면 1. */
    static int jpegOrientation(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
        int i = 2;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xFF) != 0xFF) return 1;
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) return 1;
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > b.length) return 1;
            if (marker == 0xE1 && len >= 16 && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i'
                    && b[i + 7] == 'f' && b[i + 8] == 0 && b[i + 9] == 0) {
                return tiffOrientation(b, i + 10, i + 2 + len);
            }
            i += 2 + len;
        }
        return 1;
    }

    private static int tiffOrientation(byte[] b, int start, int end) {
        if (start + 8 > end) return 1;
        boolean le;
        if (b[start] == 'I' && b[start + 1] == 'I') le = true;
        else if (b[start] == 'M' && b[start + 1] == 'M') le = false;
        else return 1;
        long ifd = start + u32(b, start + 4, le);
        if (ifd + 2 > end) return 1;
        int entries = u16(b, (int) ifd, le);
        for (int k = 0; k < entries; k++) {
            int e = (int) ifd + 2 + k * 12;
            if (e + 12 > end) return 1;
            if (u16(b, e, le) == 0x0112) {
                int v = u16(b, e + 8, le);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int i, boolean le) {
        return le ? (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)
                : ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static long u32(byte[] b, int i, boolean le) {
        return le ? (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24)
                : ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16) | ((b[i + 2] & 0xFFL) << 8) | (b[i + 3] & 0xFFL);
    }

    private static byte[] writePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] writeJpeg(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
