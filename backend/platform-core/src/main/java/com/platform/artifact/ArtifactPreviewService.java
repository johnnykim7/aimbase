package com.platform.artifact;

import com.platform.config.PlatformSettingsService;
import com.platform.session.ChatRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CR-180: 위젯 옆 창 미리보기.
 *
 * <p>pptx, docx 는 LibreOffice 로 PDF 를 만들고 PDF 는 쪽마다 PNG 로 그린다. xlsx, csv 는 표 데이터로 읽는다.
 * 그 밖의 종류는 변환 없이 종류만 알려 주고 위젯이 내려받기 주소로 원본을 받아 그린다.
 *
 * <p>권한과 격리는 내려받기와 같다(BIZ-194). 변환 결과는 세션 workspace 의 {@code .preview/} 아래
 * 파일 이름, 수정 시각, 크기로 만든 폴더에 두므로 같은 파일은 한 번만 변환하고 파일이 바뀌면 새로 만든다(BIZ-192).
 */
@Service
public class ArtifactPreviewService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactPreviewService.class);

    static final String MAX_FILE_MB_KEY = "widget.preview-max-file-mb";
    static final String MAX_PAGES_KEY = "widget.preview-max-pages";
    static final String PAGE_WIDTH_KEY = "widget.preview-page-width-px";
    static final String TIMEOUT_KEY = "widget.preview-convert-timeout-seconds";
    static final String CONCURRENCY_KEY = "widget.preview-convert-concurrency";
    static final String SHEET_MAX_ROWS_KEY = "widget.preview-sheet-max-rows";
    static final String SHEET_MAX_COLS_KEY = "widget.preview-sheet-max-cols";

    static final String ERR_ACCESS = "artifact_access_denied";
    static final String ERR_NOT_FOUND = "artifact_not_found";
    static final String ERR_PAGE = "page_not_found";
    static final String ERR_CONVERT = "preview_failed:convert";
    static final String ERR_TIMEOUT = "preview_failed:timeout";
    static final String ERR_BUSY = "preview_busy";

    static final String REASON_TOO_LARGE = "too_large";
    static final String REASON_TOO_MANY_PAGES = "too_many_pages";
    static final String REASON_UNSUPPORTED = "unsupported";

    private static final Pattern PAGES = Pattern.compile("(?m)^Pages:\\s+(\\d+)");
    private static final Pattern PAGE_SIZE = Pattern.compile("(?m)^Page size:\\s+([\\d.]+) x ([\\d.]+)");
    private static final Pattern PAGE_ROT = Pattern.compile("(?m)^Page rot:\\s+(\\d+)");

    private final ArtifactService artifactService;
    private final PlatformSettingsService settings;
    private final SheetPreviewReader sheetReader;
    private final String sofficeBin;
    private final String pdfinfoBin;
    private final String pdftoppmBin;

    /** 같은 결과 파일을 만들고 있는 작업. 같은 파일을 동시에 부르면 한 번만 변환하고 나머지는 기다린다. */
    private final ConcurrentHashMap<Path, CompletableFuture<Void>> inflight = new ConcurrentHashMap<>();

    /** 동시 변환 자리. 설정이 바뀌면 새 크기로 바꾸고, 이미 얻은 자리는 얻은 쪽에 돌려준다. */
    private final AtomicReference<Gate> gate = new AtomicReference<>(new Gate(0, new Semaphore(0)));

    private record Gate(int size, Semaphore semaphore) {}

    public ArtifactPreviewService(ArtifactService artifactService,
                                  PlatformSettingsService settings,
                                  SheetPreviewReader sheetReader,
                                  @Value("${aimbase.preview.soffice-path:soffice}") String sofficeBin,
                                  @Value("${largeinput.poppler.pdfinfo:pdfinfo}") String pdfinfoBin,
                                  @Value("${largeinput.poppler.pdftoppm:pdftoppm}") String pdftoppmBin) {
        this.artifactService = artifactService;
        this.settings = settings;
        this.sheetReader = sheetReader;
        this.sofficeBin = sofficeBin;
        this.pdfinfoBin = pdfinfoBin;
        this.pdftoppmBin = pdftoppmBin;
    }

    /** GET /api/v1/chat/artifacts/preview 본문. 한도를 넘으면 오류가 아니라 NONE 과 이유를 준다(BIZ-191). */
    public Map<String, Object> info(String sessionId, String name) {
        Source src = resolve(sessionId, name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", src.name());
        body.put("size", src.size());
        body.put("modified_at", src.modifiedAt().toString());
        body.put("preview_kind", src.kind().name());
        if (src.kind() == PreviewKind.NONE) return none(body, REASON_UNSUPPORTED);
        if (src.size() > maxFileBytes()) return none(body, REASON_TOO_LARGE);

        if (src.kind() == PreviewKind.PAGES) {
            Deadline deadline = deadline();
            Path pdf = ensurePdf(src, deadline);
            PdfInfo pdfInfo = pdfInfo(pdf, deadline);
            if (pdfInfo.pages() > maxPages()) return none(body, REASON_TOO_MANY_PAGES);
            int width = pageWidth();
            body.put("page_count", pdfInfo.pages());
            body.put("page_width", width);
            body.put("page_height", pdfInfo.heightFor(width));
        } else if (src.kind() == PreviewKind.SHEET) {
            body.put("sheets", readSheets(src, deadline()));
        }
        return body;
    }

    /** GET /api/v1/chat/artifacts/preview/page 의 PNG 파일. page 는 1부터. */
    public Path page(String sessionId, String name, int page) {
        Source src = resolve(sessionId, name);
        if (src.kind() != PreviewKind.PAGES || src.size() > maxFileBytes()) {
            throw new ChatRejectedException(HttpStatus.NOT_FOUND, ERR_PAGE);
        }
        Deadline deadline = deadline();
        Path pdf = ensurePdf(src, deadline);
        PdfInfo pdfInfo = pdfInfo(pdf, deadline);
        if (page < 1 || page > pdfInfo.pages() || pdfInfo.pages() > maxPages()) {
            throw new ChatRejectedException(HttpStatus.NOT_FOUND, ERR_PAGE);
        }
        int width = pageWidth();
        Path png = src.cacheDir().resolve("p" + page + "-" + width + ".png");
        once(png, deadline, () -> renderPage(pdf, page, width, png, deadline));
        return png;
    }

    private Source resolve(String sessionId, String name) {
        try {
            Path path = artifactService.resolveDownloadPath(sessionId, name);
            long size = Files.size(path);
            Instant modifiedAt = Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis());
            Path cacheDir = artifactService.resolvePreviewRoot(sessionId)
                    .resolve(cacheKey(name, modifiedAt, size));
            return new Source(name, path, size, modifiedAt, PreviewKind.of(name), cacheDir);
        } catch (ArtifactService.ArtifactAccessException e) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, ERR_ACCESS);
        } catch (ArtifactService.ArtifactNotFoundException | IOException e) {
            throw new ChatRejectedException(HttpStatus.NOT_FOUND, ERR_NOT_FOUND);
        }
    }

    private Path ensurePdf(Source src, Deadline deadline) {
        if ("pdf".equals(src.ext())) return src.path();
        Path pdf = src.cacheDir().resolve("doc.pdf");
        once(pdf, deadline, () -> convertToPdf(src, pdf, deadline));
        return pdf;
    }

    private void convertToPdf(Source src, Path target, Deadline deadline) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path work = Files.createTempDirectory(target.getParent(), "work-");
        try {
            // 원본 이름(한글, 공백)이 변환기 출력 이름을 흔들지 않게 고정 이름 사본으로 변환한다.
            Path input = work.resolve("src." + src.ext());
            Files.copy(src.path(), input);
            List<String> cmd = List.of(sofficeBin, "--headless", "--norestore",
                    "-env:UserInstallation=" + work.resolve("profile").toUri(),
                    "--convert-to", "pdf", "--outdir", work.toString(), input.toString());
            PreviewProcess.Result result = PreviewProcess.run(cmd, deadline.remaining());
            Path out = work.resolve("src.pdf");
            // soffice 는 변환에 실패해도 0 으로 끝나는 때가 있어 결과 파일로 판정한다.
            if (result.exitCode() != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
                log.warn("[CR180] 변환 실패 {} exit={} output={}", src.name(), result.exitCode(), result.output());
                throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
            }
            Files.move(out, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            deleteQuietly(work);
        }
    }

    private void renderPage(Path pdf, int page, int width, Path target, Deadline deadline)
            throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        String prefix = target.getParent().resolve("render-" + UUID.randomUUID()).toString();
        Path out = Path.of(prefix + ".png");
        try {
            List<String> cmd = List.of(pdftoppmBin, "-png", "-f", String.valueOf(page), "-l", String.valueOf(page),
                    "-singlefile", "-scale-to-x", String.valueOf(width), "-scale-to-y", "-1",
                    pdf.toString(), prefix);
            PreviewProcess.Result result = PreviewProcess.run(cmd, deadline.remaining());
            if (result.exitCode() != 0 || !Files.isRegularFile(out)) {
                log.warn("[CR180] 쪽 그림 실패 {} p{} exit={} output={}", pdf, page, result.exitCode(), result.output());
                throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
            }
            Files.move(out, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private PdfInfo pdfInfo(Path pdf, Deadline deadline) {
        try {
            PreviewProcess.Result result = PreviewProcess.run(List.of(pdfinfoBin, pdf.toString()), deadline.remaining());
            Matcher pages = PAGES.matcher(result.output());
            if (result.exitCode() != 0 || !pages.find()) {
                log.warn("[CR180] 쪽 수 읽기 실패 {} exit={} output={}", pdf, result.exitCode(), result.output());
                throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
            }
            double w = 0;
            double h = 0;
            Matcher size = PAGE_SIZE.matcher(result.output());
            if (size.find()) {
                w = Double.parseDouble(size.group(1));
                h = Double.parseDouble(size.group(2));
            }
            Matcher rot = PAGE_ROT.matcher(result.output());
            if (rot.find() && Integer.parseInt(rot.group(1)) % 180 == 90) {
                double t = w;
                w = h;
                h = t;
            }
            return new PdfInfo(Integer.parseInt(pages.group(1)), w, h);
        } catch (PreviewProcess.TimeoutException e) {
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_TIMEOUT);
        } catch (IOException e) {
            log.warn("[CR180] pdfinfo 실행 실패 {}: {}", pdf, e.getMessage());
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
        }
    }

    private List<Map<String, Object>> readSheets(Source src, Deadline deadline) {
        int maxRows = settings.getInt(SHEET_MAX_ROWS_KEY, 500);
        int maxCols = settings.getInt(SHEET_MAX_COLS_KEY, 50);
        return withGate(deadline, () -> sheetReader.read(src.path(), src.ext(), maxRows, maxCols), src.name());
    }

    /**
     * target 이 없을 때만 task 로 만든다. 같은 target 을 다른 요청이 만들고 있으면 그 결과를 기다린다.
     * 만드는 쪽은 동시 변환 자리를 얻은 뒤에만 돈다.
     */
    private void once(Path target, Deadline deadline, IoTask task) {
        if (Files.isRegularFile(target)) return;
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> running = inflight.putIfAbsent(target, mine);
        if (running != null) {
            await(running, deadline);
            return;
        }
        try {
            if (!Files.isRegularFile(target)) {
                withGate(deadline, () -> {
                    task.run();
                    return null;
                }, target.getFileName().toString());
            }
            mine.complete(null);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(target, mine);
        }
    }

    private static void await(CompletableFuture<Void> running, Deadline deadline) {
        try {
            running.get(deadline.remaining().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_TIMEOUT);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ChatRejectedException rejected) {
                throw new ChatRejectedException(rejected.getStatus(), rejected.getMessage());
            }
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
        }
    }

    /** 동시 변환 자리를 얻어 task 를 돌린다. 자리를 기다리는 시간도 변환 시간 한도 안이다. */
    private <T> T withGate(Deadline deadline, IoSupplier<T> task, String label) {
        Semaphore semaphore = currentGate();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(Math.max(0, deadline.remaining().toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatRejectedException(HttpStatus.SERVICE_UNAVAILABLE, ERR_BUSY);
        }
        if (!acquired) {
            throw new ChatRejectedException(HttpStatus.SERVICE_UNAVAILABLE, ERR_BUSY);
        }
        try {
            return task.get();
        } catch (PreviewProcess.TimeoutException e) {
            log.warn("[CR180] 변환 시간 한도 초과 {}", label);
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_TIMEOUT);
        } catch (IOException e) {
            log.warn("[CR180] 변환 실패 {}: {}", label, e.getMessage());
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, ERR_CONVERT);
        } finally {
            semaphore.release();
        }
    }

    Semaphore currentGate() {
        int size = Math.max(1, settings.getInt(CONCURRENCY_KEY, 2));
        Gate current = gate.get();
        if (current.size() != size) {
            gate.compareAndSet(current, new Gate(size, new Semaphore(size)));
            current = gate.get();
        }
        return current.semaphore();
    }

    private Deadline deadline() {
        return new Deadline(System.nanoTime() + Duration.ofSeconds(settings.getInt(TIMEOUT_KEY, 90)).toNanos());
    }

    private long maxFileBytes() {
        return settings.getLong(MAX_FILE_MB_KEY, 30) * 1024L * 1024L;
    }

    private int maxPages() {
        return settings.getInt(MAX_PAGES_KEY, 300);
    }

    private int pageWidth() {
        return settings.getInt(PAGE_WIDTH_KEY, 1600);
    }

    private static Map<String, Object> none(Map<String, Object> body, String reason) {
        body.put("preview_kind", PreviewKind.NONE.name());
        body.put("reason", reason);
        return body;
    }

    static String cacheKey(String name, Instant modifiedAt, long size) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8) + "-" + modifiedAt.toEpochMilli() + "-" + size;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("[CR180] 임시 파일 삭제 실패 {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("[CR180] 임시 폴더 삭제 실패 {}: {}", dir, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException, InterruptedException;
    }

    private record Source(String name, Path path, long size, Instant modifiedAt, PreviewKind kind, Path cacheDir) {
        String ext() {
            return PreviewKind.extension(name);
        }
    }

    private record PdfInfo(int pages, double widthPt, double heightPt) {
        int heightFor(int widthPx) {
            if (widthPt <= 0 || heightPt <= 0) return 0;
            return (int) Math.round(widthPx * heightPt / widthPt);
        }
    }

    private record Deadline(long atNanos) {
        Duration remaining() {
            return Duration.ofNanos(atNanos - System.nanoTime());
        }
    }
}
