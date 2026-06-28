package com.platform.largeinput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CR-120: PDF → 페이지 JPEG 렌더를 <b>BE 가 poppler 바이너리를 직접 호출</b>해 수행한다.
 *
 * <p><b>왜 사이드카 MCP 를 안 거치나</b>: 기존엔 BE 가 사이드카(Python)에게 MCP/SSE 로
 * {@code pdf_to_images} 를 요청하고 렌더 결과를 base64(청크당 ~27MB)로 돌려받았다. 단일 SSE 세션에
 * 청크 5개가 동시 호출되면 응답 base64 가 한 세션에 몰려 180초 requestTimeout 으로 hang 했다
 * (고해상도 도면일수록 심함). 그러나 렌더 자체는 poppler(`pdftoppm`) 가 하는 일이고, 그 바이너리는
 * <b>BE 컨테이너에 이미 설치돼 있다</b>(/usr/bin/pdftoppm, Dockerfile poppler-utils). openclaude 도
 * Node 에서 {@code execFile('pdftoppm')} 를 직접 부른다(별도 서버 프로세스 없음). → BE 가 직접 exec.
 *
 * <p><b>운반 비용 0</b>: 입력=디스크 PDF 경로(작업장 공유), 렌더=BE 안 임시 디렉토리로 떨굼,
 * 출력=그 jpg 파일을 BE 가 읽어 base64(LLM 주입 직전 1회, 불가피)로 변환. BE↔사이드카 왕복·SSE·
 * 단일세션 race 가 전부 사라진다.
 *
 * <p>사이드카 {@code pdf_images.py} 의 동작을 1:1 이식: 100 DPI JPEG, pages 범위("1-5"/"3"/"10-"),
 * max_pages 상한. 응답 형식도 사이드카와 동일({@code {success, images:[{page_number, media_type, data}],
 * page_count, total_pages}})하게 맞춰 호출부가 분기 없이 교체되게 한다.
 */
@Component
public class PopplerPdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(PopplerPdfRenderer.class);

    /** pdftoppm 출력 파일명 패턴: <prefix>-NN.jpg (자릿수는 총페이지에 따라 가변). */
    private static final Pattern PAGE_FILE = Pattern.compile(".*-(\\d+)\\.jpg$");
    /** pdfinfo "Pages:  N" 라인. */
    private static final Pattern PDFINFO_PAGES = Pattern.compile("(?m)^Pages:\\s+(\\d+)");

    @Value("${largeinput.poppler.pdftoppm:pdftoppm}")
    private String pdftoppmBin;

    @Value("${largeinput.poppler.pdfinfo:pdfinfo}")
    private String pdfinfoBin;

    @Value("${largeinput.poppler.pdftotext:pdftotext}")
    private String pdftotextBin;

    /** 판별용 텍스트 추출 시 읽을 첫 N 페이지(전체 추출 불필요 — charsPerPage 추정엔 샘플로 충분). */
    @Value("${largeinput.poppler.text-sample-pages:5}")
    private int textSamplePages;

    /** 렌더 1건 timeout(초). 사이드카 6초 실측이나 대용량 여유. */
    @Value("${largeinput.poppler.render-timeout-seconds:120}")
    private int renderTimeoutSeconds;

    /**
     * 동시 렌더 상한. pdftoppm 은 CPU 바운드라 과도한 병렬은 이득이 없고 메모리만 먹는다.
     * run 내 청크 병렬(max_parallel)과 별개로 호스트 전역 렌더 부하를 제한한다.
     */
    @Value("${largeinput.poppler.max-concurrent-renders:4}")
    private int maxConcurrentRenders;

    private Semaphore renderGate;

    private synchronized Semaphore gate() {
        if (renderGate == null) renderGate = new Semaphore(Math.max(1, maxConcurrentRenders));
        return renderGate;
    }

    /**
     * PDF 파일의 총 페이지 수 (pdfinfo). 실패 시 {@code {success:false}}.
     * 사이드카 {@code pdf_page_count} 응답과 동일 형식.
     */
    public Map<String, Object> pageCount(String pdfPath) {
        try {
            ProcResult r = exec(List.of(pdfinfoBin, pdfPath), renderTimeoutSeconds);
            if (r.exitCode != 0) {
                log.warn("CR-120 poppler pdfinfo failed (exit={}): {}", r.exitCode, r.stderr);
                return Map.of("success", false, "error", "pdfinfo_failed: " + r.stderr);
            }
            Matcher m = PDFINFO_PAGES.matcher(r.stdout);
            if (m.find()) {
                return Map.of("success", true, "page_count", Integer.parseInt(m.group(1)));
            }
            return Map.of("success", false, "error", "page_count_not_found");
        } catch (Exception e) {
            log.warn("CR-120 poppler pdfinfo error: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * CR-120: TEXT/IMAGE 판별용 텍스트 추출 — BE 가 poppler {@code pdftotext} 를 직접 호출.
     *
     * <p><b>왜 사이드카 parse_document 를 안 쓰나</b>: 기존 판별은 {@code PdfTextExtractor}(사이드카
     * parse_document + OCR 폴백)를 빌려 썼는데, 무거운 PDF(35MB)면 parse_document 가 base64 전송 +
     * unstructured 처리로 180초 timeout → OCR 폴백 또 180초 = 판별에만 360초를 허비했다(실측). 게다가
     * CR-120 판별은 charsPerPage 만 알면 되므로 OCR 폴백 자체가 불필요(스캔이면 어차피 IMAGE→비전 처리).
     * pdftotext 는 같은 35MB 를 <b>0.3초</b>에 처리(실측). 작업장 공유 경로를 직접 읽어 운반도 0.
     *
     * <p>첫 {@code textSamplePages} 페이지만 추출한다(charsPerPage 추정엔 샘플로 충분, 645p 같은 대형도 빠름).
     *
     * @param pdfPath 작업장 PDF 절대경로
     * @return 추출 텍스트(실패 시 빈 문자열 — 호출부가 charsPerPage=0 → IMAGE 로 판정)
     */
    public String extractTextSample(String pdfPath) {
        try {
            ProcResult r = exec(List.of(pdftotextBin, "-l", String.valueOf(Math.max(1, textSamplePages)),
                    pdfPath, "-"), renderTimeoutSeconds);
            if (r.exitCode != 0) {
                log.warn("CR-120 poppler pdftotext failed (exit={}): {}", r.exitCode, r.stderr);
                return "";
            }
            return r.stdout != null ? r.stdout : "";
        } catch (Exception e) {
            log.warn("CR-120 poppler pdftotext error: {}", e.getMessage());
            return "";
        }
    }

    /** 판별 샘플 페이지 수(호출부가 charsPerPage 계산 시 분모로 사용). */
    public int textSamplePages() {
        return Math.max(1, textSamplePages);
    }

    /**
     * CR-120: TEXT 형 PDF 전문 추출 — BE 가 poppler {@code pdftotext} 로 전체 페이지 텍스트를 직접 뽑는다.
     *
     * <p>기존 {@code parseFullText} 는 사이드카 parse_document(+OCR 폴백)를 base64 로 호출 → 무거운
     * PDF 면 360초 timeout 후 빈 텍스트(실측 body[5] 18분 허비). pdftotext 는 전체 추출도 빠르다.
     * 페이지 제한 없이(-l 생략) 전체를 뽑되, 너무 크면 호출부가 청크 예산으로 분할한다.
     *
     * @param pdfPath 작업장 PDF 절대경로
     * @return 전체 텍스트(실패 시 빈 문자열)
     */
    public String extractFullText(String pdfPath) {
        try {
            ProcResult r = exec(List.of(pdftotextBin, pdfPath, "-"), renderTimeoutSeconds);
            if (r.exitCode != 0) {
                log.warn("CR-120 poppler pdftotext(full) failed (exit={}): {}", r.exitCode, r.stderr);
                return "";
            }
            return r.stdout != null ? r.stdout : "";
        } catch (Exception e) {
            log.warn("CR-120 poppler pdftotext(full) error: {}", e.getMessage());
            return "";
        }
    }

    /**
     * PDF 페이지 범위를 JPEG 로 렌더해 base64 목록 반환. 사이드카 {@code pdf_to_images} 와 동일 형식.
     *
     * @param pdfPath  렌더할 PDF 절대경로(디스크, 작업장 공유)
     * @param pages    1-indexed 범위("1-5"/"3"/"10-"). null/빈값=전체(maxPages 상한)
     * @param dpi      렌더 해상도(기본 100, openclaude pdftoppm -r 100 동일)
     * @param maxPages 한 번에 변환할 최대 페이지 수(폭주 방어)
     * @return {success, images:[{page_number, media_type, data(base64)}], page_count, dpi} 또는 {success:false, error}
     */
    public Map<String, Object> pdfToImages(String pdfPath, String pages, int dpi, int maxPages) {
        int[] range;
        try {
            range = resolveRange(pages, maxPages);
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
        int firstPage = range[0];
        int lastPage = range[1];

        Path outDir = null;
        boolean acquired = false;
        try {
            gate().acquire();
            acquired = true;
            outDir = Files.createTempDirectory("cr120-pdf-");
            String prefix = outDir.resolve("page").toString();

            // openclaude extractPDFPages 와 동일: pdftoppm -jpeg -r <dpi> -f <first> -l <last> <pdf> <prefix>
            List<String> cmd = new ArrayList<>(List.of(
                    pdftoppmBin, "-jpeg", "-r", String.valueOf(dpi),
                    "-f", String.valueOf(firstPage), "-l", String.valueOf(lastPage),
                    pdfPath, prefix));
            ProcResult r = exec(cmd, renderTimeoutSeconds);
            if (r.exitCode != 0) {
                String err = classifyPdftoppmError(r.stderr);
                log.warn("CR-120 poppler pdftoppm failed (exit={}, {}): {}", r.exitCode, err, r.stderr);
                return Map.of("success", false, "error", err);
            }

            List<Map<String, Object>> images = collectImages(outDir);
            if (images.isEmpty()) {
                return Map.of("success", false, "error", "no_pages_rendered");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("images", images);
            out.put("page_count", images.size());
            out.put("dpi", dpi);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("success", false, "error", "render_interrupted");
        } catch (IOException e) {
            log.warn("CR-120 poppler render IO error: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        } finally {
            if (acquired) gate().release();
            if (outDir != null) deleteQuietly(outDir);
        }
    }

    /** 출력 디렉토리의 page-NN.jpg 들을 페이지 번호 오름차순으로 읽어 base64 변환. */
    private List<Map<String, Object>> collectImages(Path outDir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(outDir)) {
            files = s.filter(p -> PAGE_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(PopplerPdfRenderer::pageNum))
                    .toList();
        }
        List<Map<String, Object>> images = new ArrayList<>(files.size());
        for (Path f : files) {
            byte[] bytes = Files.readAllBytes(f);
            Map<String, Object> img = new LinkedHashMap<>();
            img.put("page_number", pageNum(f));
            img.put("media_type", "image/jpeg");
            img.put("data", Base64.getEncoder().encodeToString(bytes));
            images.add(img);
        }
        return images;
    }

    private static int pageNum(Path p) {
        Matcher m = PAGE_FILE.matcher(p.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /**
     * pages 문자열 → [firstPage, lastPage] (1-indexed). 사이드카 parse_page_range + max_pages 상한 이식.
     * null/빈값 = 전체(1..maxPages). 잘못된 입력은 IllegalArgumentException.
     */
    static int[] resolveRange(String pages, int maxPages) {
        if (pages == null || pages.isBlank()) {
            return new int[]{1, maxPages};
        }
        String t = pages.trim();
        int first;
        int last;
        if (t.endsWith("-")) {
            first = parsePositive(t.substring(0, t.length() - 1), pages);
            last = first + maxPages - 1;
        } else if (!t.contains("-")) {
            first = parsePositive(t, pages);
            last = first;
        } else {
            int dash = t.indexOf('-');
            first = parsePositive(t.substring(0, dash), pages);
            last = parsePositive(t.substring(dash + 1), pages);
            if (last < first) throw new IllegalArgumentException("invalid page range: " + pages);
        }
        // 요청 범위가 max_pages 초과면 잘라낸다(사이드카 동형).
        if (last - first + 1 > maxPages) last = first + maxPages - 1;
        return new int[]{first, last};
    }

    private static int parsePositive(String s, String orig) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 1) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid page range: " + orig);
        }
    }

    private static String classifyPdftoppmError(String stderr) {
        String s = stderr == null ? "" : stderr.toLowerCase();
        if (s.contains("password")) return "password_protected";
        if (s.contains("damaged") || s.contains("corrupt") || s.contains("invalid")) return "corrupted";
        return "pdftoppm_failed: " + stderr;
    }

    private ProcResult exec(List<String> cmd, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process proc = pb.start();
        // stdout/stderr 동시 소진(버퍼 풀 데드락 방지). pdfinfo 는 stdout, pdftoppm 은 파일출력+stderr.
        byte[] stdout = proc.getInputStream().readAllBytes();
        byte[] stderr = proc.getErrorStream().readAllBytes();
        boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            throw new IOException("process timeout after " + timeoutSeconds + "s: " + cmd.get(0));
        }
        return new ProcResult(proc.exitValue(), new String(stdout), new String(stderr));
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) { /* best-effort */ }
            });
        } catch (IOException ignore) { /* best-effort */ }
    }

    private record ProcResult(int exitCode, String stdout, String stderr) {}
}
