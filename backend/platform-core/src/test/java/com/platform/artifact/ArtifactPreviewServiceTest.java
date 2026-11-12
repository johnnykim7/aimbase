package com.platform.artifact;

import com.platform.api.ArtifactController;
import com.platform.chatdoc.ChatDocumentService;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.repository.ChatDocumentRepository;
import com.platform.repository.ChatDocumentVersionRepository;
import com.platform.storage.StorageService;
import com.platform.config.WorkspaceProperties;
import com.platform.session.ChatRejectedException;
import com.platform.session.SessionStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-180 TC-CHT-061, 063 ~ 066, 069: 옆 창 미리보기 서버. */
class ArtifactPreviewServiceTest {

    private static final String SESSION = "s-1";

    @TempDir Path root;

    private Path workspace;
    private Path out;
    private Path counter;
    private final Map<String, Long> numbers = new HashMap<>();
    private SessionStore sessionStore;
    private ArtifactService artifactService;
    private PlatformSettingsService settings;
    private ChatDocumentRepository documentRepo;
    private ChatDocumentVersionRepository versionRepo;
    private StorageService storage;
    private ChatDocumentService chatDocuments;

    @BeforeEach
    void setUp() throws IOException {
        workspace = root.resolve("ws");
        out = Files.createDirectories(workspace.resolve("out"));
        counter = root.resolve("count.txt");
        sessionStore = mock(SessionStore.class);
        when(sessionStore.getWorkspaceRef(SESSION)).thenReturn(workspace.toString());
        WorkspaceProperties props = new WorkspaceProperties();
        props.setWhitelistRoots(List.of(root.toString()));
        artifactService = new ArtifactService(sessionStore, props);
        documentRepo = mock(ChatDocumentRepository.class);
        versionRepo = mock(ChatDocumentVersionRepository.class);
        storage = mock(StorageService.class);
        chatDocuments = new ChatDocumentService(documentRepo, versionRepo, storage, artifactService);
        settings = mock(PlatformSettingsService.class);
        numbers.put(ArtifactPreviewService.MAX_FILE_MB_KEY, 30L);
        numbers.put(ArtifactPreviewService.MAX_PAGES_KEY, 300L);
        numbers.put(ArtifactPreviewService.PAGE_WIDTH_KEY, 1600L);
        numbers.put(ArtifactPreviewService.TIMEOUT_KEY, 30L);
        numbers.put(ArtifactPreviewService.CONCURRENCY_KEY, 2L);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv ->
                numbers.containsKey(inv.<String>getArgument(0))
                        ? numbers.get(inv.<String>getArgument(0)).intValue() : inv.<Integer>getArgument(1));
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv ->
                numbers.getOrDefault(inv.<String>getArgument(0), inv.<Long>getArgument(1)));
    }

    // TC-CHT-061
    @Test
    void isolation_rejects_escape_subfolder_other_tenant_outside_whitelist_and_missing_file() throws IOException {
        ArtifactPreviewService service = service(fakeSoffice("exit 1"));
        Files.createDirectories(out.resolve("sub"));
        writePdf(out.resolve("sub/x.pdf"), 1);

        assertRejected(() -> service.info(SESSION, "../x.pptx"), HttpStatus.FORBIDDEN, "artifact_access_denied");
        assertRejected(() -> service.info(SESSION, "sub/x.pdf"), HttpStatus.FORBIDDEN, "artifact_access_denied");
        assertRejected(() -> service.info("other-tenant", "x.pptx"), HttpStatus.NOT_FOUND, "artifact_not_found");
        when(sessionStore.getWorkspaceRef("outside")).thenReturn("/var/elsewhere");
        assertRejected(() -> service.info("outside", "x.pptx"), HttpStatus.FORBIDDEN, "artifact_access_denied");
        assertRejected(() -> service.info(SESSION, "none.pptx"), HttpStatus.NOT_FOUND, "artifact_not_found");
        assertThat(counter).doesNotExist();
    }

    // TC-CHT-063
    @Test
    void conversion_runs_once_per_file_version_and_once_for_concurrent_requests() throws Exception {
        assumePoppler();
        Path fixture = writePdf(root.resolve("fixture.pdf"), 3);
        ArtifactPreviewService service = service(fakeSoffice("sleep 0.5\ncp '" + fixture + "' \"$outdir/src.pdf\""));
        Path pptx = Files.writeString(out.resolve("제안서.pptx"), "deck");

        Map<String, Object> first = service.info(SESSION, "제안서.pptx");
        service.info(SESSION, "제안서.pptx");
        assertThat(first).containsEntry("preview_kind", "PAGES").containsEntry("page_count", 3)
                .containsEntry("page_width", 1600).containsEntry("page_height", 2263);
        assertThat(runs()).isEqualTo(1);

        Files.setLastModifiedTime(pptx, FileTime.from(Instant.now().plusSeconds(5)));
        service.info(SESSION, "제안서.pptx");
        assertThat(runs()).isEqualTo(2);

        Files.writeString(out.resolve("other.docx"), "doc");
        CompletableFuture<Map<String, Object>> a = CompletableFuture.supplyAsync(() -> service.info(SESSION, "other.docx"));
        CompletableFuture<Map<String, Object>> b = CompletableFuture.supplyAsync(() -> service.info(SESSION, "other.docx"));
        assertThat(a.get(20, TimeUnit.SECONDS).get("page_count")).isEqualTo(3);
        assertThat(b.get(20, TimeUnit.SECONDS).get("page_count")).isEqualTo(3);
        assertThat(runs()).isEqualTo(3);
    }

    // TC-CHT-064
    @Test
    void failed_conversion_is_convert_error() throws IOException {
        ArtifactPreviewService service = service(fakeSoffice("exit 1"));
        Files.writeString(out.resolve("bad.pptx"), "deck");

        assertRejected(() -> service.info(SESSION, "bad.pptx"),
                HttpStatus.UNPROCESSABLE_ENTITY, "preview_failed:convert");
        assertThat(workDirs()).isEmpty();
    }

    // TC-CHT-064
    @Test
    void slow_conversion_times_out_and_leaves_no_process_or_profile() throws Exception {
        numbers.put(ArtifactPreviewService.TIMEOUT_KEY, 1L);
        Path pidFile = root.resolve("child.pid");
        ArtifactPreviewService service = service(fakeSoffice(
                "mkdir -p \"$profile\"\nsleep 30 &\necho $! > '" + pidFile + "'\nwait"));
        Files.writeString(out.resolve("slow.pptx"), "deck");

        assertRejected(() -> service.info(SESSION, "slow.pptx"),
                HttpStatus.UNPROCESSABLE_ENTITY, "preview_failed:timeout");
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle.of(pid).ifPresent(h -> h.onExit().orTimeout(5, TimeUnit.SECONDS).join());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(workDirs()).isEmpty();
    }

    // TC-CHT-065
    @Test
    void limits_give_none_with_reason_and_full_gate_gives_busy() throws Exception {
        ArtifactPreviewService service = service(fakeSoffice("exit 1"));
        try (RandomAccessFile big = new RandomAccessFile(out.resolve("big.pptx").toFile(), "rw")) {
            big.setLength(31L * 1024 * 1024);
        }
        assertThat(service.info(SESSION, "big.pptx"))
                .containsEntry("preview_kind", "NONE").containsEntry("reason", "too_large");
        assertThat(counter).doesNotExist();

        numbers.put(ArtifactPreviewService.CONCURRENCY_KEY, 1L);
        numbers.put(ArtifactPreviewService.TIMEOUT_KEY, 1L);
        Files.writeString(out.resolve("wait.pptx"), "deck");
        Semaphore gate = service.currentGate();
        gate.acquire();
        try {
            assertRejected(() -> service.info(SESSION, "wait.pptx"), HttpStatus.SERVICE_UNAVAILABLE, "preview_busy");
        } finally {
            gate.release();
        }
        assertThat(counter).doesNotExist();
    }

    // TC-CHT-065
    @Test
    void too_many_pages_gives_none() throws IOException {
        assumePoppler();
        ArtifactPreviewService service = service(fakeSoffice("exit 1"));
        writePdf(out.resolve("long.pdf"), 301);

        assertThat(service.info(SESSION, "long.pdf"))
                .containsEntry("preview_kind", "NONE").containsEntry("reason", "too_many_pages");
    }

    // TC-CHT-066
    @Test
    void page_png_is_configured_width_out_of_range_is_404_and_second_call_is_cached() throws Exception {
        assumePoppler();
        ArtifactPreviewService service = service(fakeSoffice("exit 1"));
        writePdf(out.resolve("three.pdf"), 3);

        Path png = service.page(SESSION, "three.pdf", 2);
        BufferedImage image = ImageIO.read(png.toFile());
        assertThat(image.getWidth()).isEqualTo(1600);
        FileTime drawn = Files.getLastModifiedTime(png);

        assertRejected(() -> service.page(SESSION, "three.pdf", 0), HttpStatus.NOT_FOUND, "page_not_found");
        assertRejected(() -> service.page(SESSION, "three.pdf", 4), HttpStatus.NOT_FOUND, "page_not_found");

        Thread.sleep(20);
        Path again = service.page(SESSION, "three.pdf", 2);
        assertThat(again).isEqualTo(png);
        assertThat(Files.getLastModifiedTime(again)).isEqualTo(drawn);
        assertThat(png).startsWith(workspace.resolve(".preview"));
    }

    // TC-CHT-069
    @Test
    void done_event_items_carry_modified_at_and_preview_kind() throws IOException {
        Files.writeString(out.resolve("a.pptx"), "deck");
        Files.writeString(out.resolve("b.csv"), "x,y");
        Files.setLastModifiedTime(out.resolve("a.pptx"), FileTime.from(Instant.parse("2026-11-13T05:12:40Z")));

        List<Map<String, Object>> items = artifactService.listArtifacts(SESSION).stream()
                .map(a -> ArtifactController.artifactItem(SESSION, a))
                .toList();

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(m -> assertThat(m)
                .containsKeys("name", "size", "download_url", "modified_at", "preview_kind"));
        Map<String, Object> pptx = items.stream().filter(m -> "a.pptx".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(pptx).containsEntry("modified_at", "2026-11-13T05:12:40Z").containsEntry("preview_kind", "PAGES");
        Map<String, Object> csv = items.stream().filter(m -> "b.csv".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(csv).containsEntry("preview_kind", "SHEET");
    }

    private ArtifactPreviewService service(Path soffice) {
        return new ArtifactPreviewService(artifactService, chatDocuments, settings, new SheetPreviewReader(),
                soffice.toString(), Objects.requireNonNullElse(binary("pdfinfo"), "pdfinfo"),
                Objects.requireNonNullElse(binary("pdftoppm"), "pdftoppm"));
    }

    /** 실제 soffice 대신 쓰는 변환기. 부를 때마다 횟수 파일에 한 줄 더하고 body 를 실행한다. */
    private Path fakeSoffice(String body) throws IOException {
        Path script = root.resolve("soffice-" + System.nanoTime() + ".sh");
        String text = "#!/bin/sh\n"
                + "echo run >> '" + counter + "'\n"
                + "outdir=''; profile=''; prev=''\n"
                + "for a in \"$@\"; do\n"
                + "  [ \"$prev\" = \"--outdir\" ] && outdir=\"$a\"\n"
                + "  case \"$a\" in -env:UserInstallation=file://*) profile=\"${a#-env:UserInstallation=file://}\";; esac\n"
                + "  prev=\"$a\"\n"
                + "done\n"
                + body + "\n";
        Files.writeString(script, text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private int runs() throws IOException {
        return Files.exists(counter) ? Files.readAllLines(counter).size() : 0;
    }

    private List<Path> workDirs() throws IOException {
        Path preview = workspace.resolve(".preview");
        if (!Files.exists(preview)) return List.of();
        try (Stream<Path> s = Files.walk(preview)) {
            return s.filter(p -> p.getFileName().toString().startsWith("work-")).toList();
        }
    }

    private static Path writePdf(Path file, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(file.toFile());
        }
        return file;
    }

    // CR-181 TC-CHT-078
    @Test
    void has_source_follows_create_file_source() throws IOException {
        ArtifactPreviewService service = service(fakeSoffice("exit 1"));
        Files.writeString(out.resolve("a.txt"), "hello");
        assertThat(service.info(SESSION, "a.txt")).containsEntry("has_source", false);

        Files.createDirectories(out.resolve(".source"));
        Files.writeString(out.resolve(".source/a.txt.json"), "{}");
        assertThat(service.info(SESSION, "a.txt")).containsEntry("has_source", true);
    }

    // CR-181 TC-CHT-078
    @Test
    void version_previews_stored_copy_not_current_out_file() throws IOException {
        ArtifactPreviewService service = service(fakeSoffice("exit 1"));
        Files.writeString(out.resolve("a.txt"), "current");
        ChatDocumentEntity doc = new ChatDocumentEntity();
        doc.setId(UUID.randomUUID());
        doc.setSessionId(SESSION);
        doc.setKind(ChatDocumentEntity.Kind.FILE);
        doc.setName("a.txt");
        ChatDocumentVersionEntity v1 = new ChatDocumentVersionEntity();
        v1.setDocumentId(doc.getId());
        v1.setVersion(1);
        v1.setStoragePath("p1");
        v1.setSourceStoragePath("s1");
        v1.setSizeBytes(3);
        v1.setSha256("0123456789abcdef0123");
        when(documentRepo.findFile(SESSION, "a.txt")).thenReturn(Optional.of(doc));
        when(versionRepo.findLive(doc.getId(), 1)).thenReturn(Optional.of(v1));
        when(versionRepo.findLive(doc.getId(), 9)).thenReturn(Optional.empty());
        when(storage.load("p1")).thenAnswer(inv -> new ByteArrayInputStream("old".getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> info = service.info(SESSION, "a.txt", 1);

        assertThat(info).containsEntry("size", 3L).containsEntry("has_source", true)
                .containsEntry("preview_kind", "TEXT");
        Path copy = workspace.resolve(".preview")
                .resolve(ArtifactPreviewService.versionCacheKey("a.txt", v1.getSha256())).resolve("src.txt");
        assertThat(copy).hasContent("old");
        assertThat(out.resolve("a.txt")).hasContent("current");
        assertRejected(() -> service.info(SESSION, "a.txt", 9), HttpStatus.NOT_FOUND, "artifact_not_found");
        assertRejected(() -> service.info(SESSION, "b.txt", 1), HttpStatus.NOT_FOUND, "artifact_not_found");
    }

    private static void assertRejected(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ChatRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(code);
                });
    }

    private static void assumePoppler() {
        assumeTrue(binary("pdfinfo") != null && binary("pdftoppm") != null, "poppler 가 없어 건너뛴다");
    }

    private static String binary(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            Path p = Path.of(dir, name);
            if (Files.isExecutable(p)) return p.toString();
        }
        for (String dir : List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")) {
            Path p = Path.of(dir, name);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }
}
