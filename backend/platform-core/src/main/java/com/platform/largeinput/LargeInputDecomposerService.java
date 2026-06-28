package com.platform.largeinput;

import com.platform.attachment.PdfTextExtractor;
import com.platform.rag.MCPRagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * CR-120: 결정론적 청크 분해기 — 자율주행 밖에서 문서를 32MB 안 치는 청크로 쪼갠다.
 *
 * <h3>핵심: 추정 금지, 실측 역산 (CR-117 5배 빗나감 교훈)</h3>
 * 이미지형 PDF 는 1장 크기를 추정하면 5배까지 빗나간다. 그래서 <b>첫 3장을 실제 렌더해 평균 base64
 * 크기를 역산</b>하고, budget(24MB) ÷ 평균 으로 청크당 페이지 수를 정한다.
 *
 * <h3>텍스트형 vs 이미지형</h3>
 * PdfTextExtractor 로 charsPerPage 를 계산해 ≥ 임계(200자/p)면 TEXT(텍스트 청크), 빈약하면 IMAGE(렌더).
 * MVP 는 문서 전체 단일 type — 페이지별 혼합 type 은 확장 자리.
 */
@Service
public class LargeInputDecomposerService {

    private static final Logger log = LoggerFactory.getLogger(LargeInputDecomposerService.class);

    private final MCPRagClient ragClient;
    private final PopplerPdfRenderer popplerRenderer;
    private final PdfTextExtractor pdfTextExtractor;

    /** 텍스트형 판별 임계 (자/페이지). 이 이상이면 TEXT, 미만이면 IMAGE 로 렌더. */
    @Value("${largeinput.text-chars-per-page-threshold:200}")
    private int textPerPageThreshold;

    /** 이미지형 청크 base64 예산 (바이트). 32MB 물리한계의 안전 마진 안쪽. */
    @Value("${largeinput.chunk-budget-bytes:25165824}") // 24 MB
    private long chunkBudgetBytes;

    /** 텍스트형 청크 문자 예산. */
    @Value("${largeinput.chunk-budget-chars:40000}")
    private int chunkBudgetChars;

    /** 한 청크가 가질 수 있는 최대 페이지 수 (사이드카 렌더 상한과 정렬). */
    @Value("${largeinput.max-pages-per-chunk:20}")
    private int maxPagesPerChunk;

    /** 이미지형 렌더 DPI. */
    @Value("${largeinput.image-dpi:100}")
    private int imageDpi;

    public LargeInputDecomposerService(MCPRagClient ragClient, PopplerPdfRenderer popplerRenderer,
                                       PdfTextExtractor pdfTextExtractor) {
        this.ragClient = ragClient;
        this.popplerRenderer = popplerRenderer;
        this.pdfTextExtractor = pdfTextExtractor;
    }

    /**
     * 소스를 청크 목록으로 분해.
     *
     * @param source 적재된 소스
     * @param policy OFF 면 청크 1개(분해 생략). 그 외엔 경계 계산.
     */
    public List<LargeInputChunk> decompose(LargeInputSource source, LargeInputPolicy policy) {
        // 인라인 텍스트: 문자 예산으로 분할 (페이지 개념 없음 → page 0)
        if (source.isInlineText()) {
            return decomposeInlineText(source.inlineText(), policy);
        }
        if (source.bytes() == null) {
            throw new IllegalArgumentException("CR-120: source has no bytes and no inline text");
        }
        // 여기 도달 = bytes 가 있고 inlineText 가 아닌 소스 = PDF (TextSourceLoader 는 inlineText 로 적재해
        // 위 isInlineText() 경로로 빠진다). 텍스트는 타입 무관 크기로 분해 여부 결정(LARGE_INPUT 본래 의도).
        return decomposePdf(source, policy);
    }

    // ─── 인라인 텍스트 ───────────────────────────────────────────────────────

    private List<LargeInputChunk> decomposeInlineText(String text, LargeInputPolicy policy) {
        List<LargeInputChunk> chunks = new ArrayList<>();
        if (policy == LargeInputPolicy.OFF || text.length() <= chunkBudgetChars) {
            chunks.add(new LargeInputChunk(0, 0, 0, LargeInputChunk.Type.TEXT, text, utf8Bytes(text)));
            return chunks;
        }
        int idx = 0;
        for (int start = 0; start < text.length(); start += chunkBudgetChars) {
            int end = Math.min(text.length(), start + chunkBudgetChars);
            String slice = text.substring(start, end);
            chunks.add(new LargeInputChunk(idx++, 0, 0, LargeInputChunk.Type.TEXT, slice, utf8Bytes(slice)));
        }
        return chunks;
    }

    // ─── PDF ────────────────────────────────────────────────────────────────

    private List<LargeInputChunk> decomposePdf(LargeInputSource source, LargeInputPolicy policy) {
        byte[] bytes = source.bytes();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        // CR-120: 작업장 공유 경로가 있으면 사이드카 호출을 file_path 로(전체 PDF base64 전송 생략).
        // 없으면(attachment 바이트 등) base64. PdfSource(byte[]) 는 그대로 두되 사이드카 운반만 경로화.
        String filePath = source.filePath();
        int totalPages = source.totalPages() != null ? source.totalPages() : fetchPageCount(base64, filePath);
        if (totalPages <= 0) totalPages = 1; // 페이지 수 불명 → 단일 청크 폴백

        // 텍스트/이미지 판별. 작업장 경로가 있으면 BE 가 poppler(pdftotext)로 직접 판별한다.
        //   사이드카 parse_document(+OCR 폴백)는 무거운 PDF(35MB)에서 360초를 허비했다(실측). pdftotext 는
        //   같은 파일을 0.3초에 처리(샘플 N페이지만). 판별엔 charsPerPage 만 필요하므로 OCR 폴백 불필요
        //   (스캔이면 텍스트 0 → IMAGE → 비전 렌더로 처리). file_path 없으면(attachment 바이트) 기존 폴백.
        double charsPerPage;
        if (filePath != null && !filePath.isBlank()) {
            String sampleText = popplerRenderer.extractTextSample(filePath);
            int denom = Math.min(popplerRenderer.textSamplePages(), Math.max(1, totalPages));
            charsPerPage = (double) sampleText.length() / denom;
        } else {
            // 폴백(디스크 파일 부재): 기존 사이드카 기반 PdfTextExtractor.
            PdfTextExtractor.ExtractResult sample = pdfTextExtractor.extract(bytes);
            int sampledPages = sample.pages() != null && sample.pages() > 0 ? sample.pages() : totalPages;
            charsPerPage = (double) sample.text().length() / Math.max(1, sampledPages);
        }
        boolean isText = charsPerPage >= textPerPageThreshold;

        log.info("CR-120 decompose: totalPages={}, charsPerPage={}, type={}, policy={}, viaPath={}",
                totalPages, String.format("%.0f", charsPerPage), isText ? "TEXT" : "IMAGE", policy,
                filePath != null && !filePath.isBlank());

        if (policy == LargeInputPolicy.OFF) {
            return List.of(singleChunk(source, totalPages, isText, base64, filePath));
        }
        return isText
                ? decomposeTextPdf(base64, filePath, totalPages)
                : decomposeImagePdf(base64, filePath, totalPages);
    }

    /** 텍스트형: pdftotext(작업장 경로) 또는 parse_document 로 전문 추출 후 char 예산으로 페이지 경계 분할. */
    private List<LargeInputChunk> decomposeTextPdf(String base64, String filePath, int totalPages) {
        String fullText = parseFullText(base64, filePath);
        // 페이지별 경계 정보가 사이드카에서 안 오므로 char 예산 슬라이스 + 균등 페이지 안분(메타용)으로 근사.
        List<LargeInputChunk> chunks = new ArrayList<>();
        if (fullText.length() <= chunkBudgetChars) {
            chunks.add(new LargeInputChunk(0, 1, totalPages, LargeInputChunk.Type.TEXT,
                    fullText, utf8Bytes(fullText)));
            return chunks;
        }
        int numChunks = (int) Math.ceil((double) fullText.length() / chunkBudgetChars);
        int pagesPerChunk = Math.max(1, (int) Math.ceil((double) totalPages / numChunks));
        int idx = 0;
        for (int start = 0; start < fullText.length(); start += chunkBudgetChars) {
            int end = Math.min(fullText.length(), start + chunkBudgetChars);
            String slice = fullText.substring(start, end);
            int pageStart = Math.min(totalPages, idx * pagesPerChunk + 1);
            int pageEnd = Math.min(totalPages, (idx + 1) * pagesPerChunk);
            chunks.add(new LargeInputChunk(idx, pageStart, Math.max(pageStart, pageEnd),
                    LargeInputChunk.Type.TEXT, slice, utf8Bytes(slice)));
            idx++;
        }
        return chunks;
    }

    /**
     * 이미지형: 첫 3장 샘플 렌더 → 평균 base64/p 역산 → budget ÷ 평균 = 청크당 페이지 수.
     * CR-117 의 5배 빗나감(1장 추정)을 첫 3장 실측 평균으로 해소.
     */
    private List<LargeInputChunk> decomposeImagePdf(String base64, String filePath, int totalPages) {
        double avgBase64PerPage = sampleAvgBase64PerPage(base64, filePath, totalPages);
        int pagesPerChunk = (int) Math.floor(chunkBudgetBytes / Math.max(1.0, avgBase64PerPage));
        pagesPerChunk = clamp(pagesPerChunk, 1, maxPagesPerChunk);

        log.info("CR-120 image decompose: avgBase64/p={}B, budget={}B → pagesPerChunk={}",
                String.format("%.0f", avgBase64PerPage), chunkBudgetBytes, pagesPerChunk);

        List<LargeInputChunk> chunks = new ArrayList<>();
        int idx = 0;
        for (int pageStart = 1; pageStart <= totalPages; pageStart += pagesPerChunk) {
            int pageEnd = Math.min(totalPages, pageStart + pagesPerChunk - 1);
            long estBytes = (long) (avgBase64PerPage * (pageEnd - pageStart + 1));
            chunks.add(new LargeInputChunk(idx++, pageStart, pageEnd,
                    LargeInputChunk.Type.IMAGE, null, estBytes));
        }
        return chunks;
    }

    /** 첫 maxSample(3) 장 렌더해 평균 base64 길이 역산. 실패 시 보수적 큰 값(1장=청크). */
    private double sampleAvgBase64PerPage(String base64, String filePath, int totalPages) {
        int sampleCount = Math.min(3, totalPages);
        try {
            // CR-120: file_path 있으면 BE 가 poppler 직접 호출(사이드카 왕복·base64응답·세션race 제거).
            Map<String, Object> r = (filePath != null && !filePath.isBlank())
                    ? popplerRenderer.pdfToImages(filePath, "1-" + sampleCount, imageDpi, sampleCount)
                    : ragClient.pdfToImages(base64, "1-" + sampleCount, imageDpi, sampleCount);
            if (Boolean.TRUE.equals(r.get("success")) && r.get("images") instanceof List<?> images
                    && !images.isEmpty()) {
                long total = 0;
                int counted = 0;
                for (Object o : images) {
                    if (o instanceof Map<?, ?> img && img.get("data") instanceof String data) {
                        total += data.length();
                        counted++;
                    }
                }
                if (counted > 0) return (double) total / counted;
            }
        } catch (Exception e) {
            log.warn("CR-120: sample render failed — fall back to 1 page/chunk: {}", e.getMessage());
        }
        // 폴백: 예산 = 1장 → pagesPerChunk=1 (가장 보수적)
        return chunkBudgetBytes;
    }

    private LargeInputChunk singleChunk(LargeInputSource source, int totalPages, boolean isText,
                                        String base64, String filePath) {
        if (isText) {
            String full = parseFullText(base64, filePath);
            return new LargeInputChunk(0, 1, totalPages, LargeInputChunk.Type.TEXT, full, utf8Bytes(full));
        }
        return new LargeInputChunk(0, 1, totalPages, LargeInputChunk.Type.IMAGE, null, source.bytes().length);
    }

    // ─── 사이드카 호출 헬퍼 ────────────────────────────────────────────────────

    private String parseFullText(String base64, String filePath) {
        // CR-120: 작업장 경로가 있으면 BE 가 poppler pdftotext 로 전체 텍스트를 직접 뽑는다(사이드카 0).
        //   기존 사이드카 parse_document(+OCR)는 무거운 PDF 에서 360초 timeout 후 빈 텍스트였다(body[5] 18분).
        if (filePath != null && !filePath.isBlank()) {
            String full = popplerRenderer.extractFullText(filePath);
            if (full != null && !full.isBlank()) return full;
            // pdftotext 가 빈 결과(스캔 PDF 등)면 텍스트형으로 잘못 온 것 — 빈 문자열 반환(상위가 처리).
            return full != null ? full : "";
        }
        // 폴백(디스크 파일 부재): 사이드카 parse_document → PdfTextExtractor.
        try {
            Map<String, Object> r = ragClient.parseDocument(base64, "pdf");
            if (r.get("content") instanceof String s && !s.isBlank()) return s;
        } catch (Exception e) {
            log.warn("CR-120: parse_document failed — using PdfTextExtractor(절단가능): {}", e.getMessage());
        }
        return pdfTextExtractor.extract(Base64.getDecoder().decode(base64)).text();
    }

    private int fetchPageCount(String base64, String filePath) {
        try {
            // CR-120: file_path 있으면 BE 가 pdfinfo 직접 호출(사이드카 왕복 제거).
            Map<String, Object> r = (filePath != null && !filePath.isBlank())
                    ? popplerRenderer.pageCount(filePath)
                    : ragClient.pdfPageCount(base64);
            if (Boolean.TRUE.equals(r.get("success")) && r.get("page_count") instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception e) {
            log.warn("CR-120: pdf_page_count failed: {}", e.getMessage());
        }
        return 0;
    }

    private static long utf8Bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // 테스트 주입용 (Value 필드 setter)
    void configureForTest(int textThreshold, long budgetBytes, int budgetChars, int maxPages, int dpi) {
        this.textPerPageThreshold = textThreshold;
        this.chunkBudgetBytes = budgetBytes;
        this.chunkBudgetChars = budgetChars;
        this.maxPagesPerChunk = maxPages;
        this.imageDpi = dpi;
    }
}
