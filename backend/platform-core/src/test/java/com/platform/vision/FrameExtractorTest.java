package com.platform.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CR-137 FrameExtractor — 영상 프레임 추출.
 *
 * <p>ffmpeg 가 없는 환경(CI 등)에서는 실제 추출 테스트를 건너뛴다({@code assumeTrue}).
 * 순수 계산 로직(타임스탬프 포맷)은 바이너리 없이도 항상 검증한다.
 */
class FrameExtractorTest {

    /** cvilite 선행 실증 샘플. 없으면 해당 테스트는 skip. */
    private static final Path SAMPLE =
            Path.of("/Users/sykim/Documents/GitHub/bp-platform/cvilite/samples/sample1.mp4");

    private FrameExtractor newExtractor() {
        FrameExtractor fx = new FrameExtractor();
        ReflectionTestUtils.setField(fx, "ffmpegBin", "ffmpeg");
        ReflectionTestUtils.setField(fx, "ffprobeBin", "ffprobe");
        ReflectionTestUtils.setField(fx, "frameTimeoutSeconds", 60);
        ReflectionTestUtils.setField(fx, "probeTimeoutSeconds", 30);
        ReflectionTestUtils.setField(fx, "jpegQuality", 2);
        ReflectionTestUtils.setField(fx, "maxConcurrent", 2);
        return fx;
    }

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            p.getInputStream().readAllBytes();
            p.getErrorStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 순수 로직 (바이너리 불필요) ───────────────────────────

    /** ffmpeg -ss 는 점(.) 소수점만 받는다 — 로케일이 콤마인 환경에서도 깨지면 안 된다. */
    @Test
    void formatTs_usesDotDecimalRegardlessOfLocale() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY); // 콤마 소수점 로케일
            assertThat(FrameExtractor.formatTs(1.5)).isEqualTo("1.500");
            assertThat(FrameExtractor.formatTs(12.3456)).isEqualTo("12.346");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    void formatTs_clampsNegativeToZero() {
        assertThat(FrameExtractor.formatTs(-3.0)).isEqualTo("0.000");
    }

    // ── 실제 ffmpeg 실행 ─────────────────────────────────────

    @Test
    void probeDuration_readsRealVideo() {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");
        assumeTrue(Files.exists(SAMPLE), "cvilite sample1.mp4 not present");

        double d = newExtractor().probeDuration(SAMPLE);
        assertThat(d).isGreaterThan(0);
    }

    @Test
    void probeDuration_returnsMinusOneForNonVideo(@TempDir Path tmp) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");

        Path junk = tmp.resolve("not-a-video.mp4");
        Files.writeString(junk, "this is not a video");
        assertThat(newExtractor().probeDuration(junk)).isEqualTo(-1);
    }

    /** 기본 6프레임 — cvilite preview/f0~f5.jpg 와 같은 개수. */
    @Test
    void extract_producesRequestedFrameCount(@TempDir Path tmp) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");
        assumeTrue(Files.exists(SAMPLE), "cvilite sample1.mp4 not present");

        List<Path> frames = newExtractor().extract(SAMPLE, 6, tmp.resolve("frames"));

        assertThat(frames).hasSize(6);
        for (Path f : frames) {
            assertThat(Files.exists(f)).isTrue();
            assertThat(Files.size(f)).isGreaterThan(1000); // 빈 파일/헤더만 있는 파일 배제
            // JPEG 매직넘버 FF D8 FF
            byte[] head = new byte[3];
            try (var in = Files.newInputStream(f)) {
                assertThat(in.read(head)).isEqualTo(3);
            }
            assertThat(head[0]).isEqualTo((byte) 0xFF);
            assertThat(head[1]).isEqualTo((byte) 0xD8);
            assertThat(head[2]).isEqualTo((byte) 0xFF);
        }
    }

    /** 프레임 수 조절 (BIZ-112: 1~20). */
    @Test
    void extract_honorsCustomFrameCount(@TempDir Path tmp) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");
        assumeTrue(Files.exists(SAMPLE), "cvilite sample1.mp4 not present");

        assertThat(newExtractor().extract(SAMPLE, 3, tmp.resolve("f3"))).hasSize(3);
        assertThat(newExtractor().extract(SAMPLE, 1, tmp.resolve("f1"))).hasSize(1);
    }

    /**
     * 프레임은 서로 다른 시점이어야 한다 — 전부 같은 바이트면 seek 이 안 먹은 것이다
     * ({@code -ss} 를 {@code -i} 뒤에 두는 실수를 잡는 회귀 테스트).
     */
    @Test
    void extract_framesAreDistinctTimestamps(@TempDir Path tmp) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");
        assumeTrue(Files.exists(SAMPLE), "cvilite sample1.mp4 not present");

        List<Path> frames = newExtractor().extract(SAMPLE, 4, tmp.resolve("frames"));
        assumeTrue(frames.size() >= 2, "need at least 2 frames to compare");

        byte[] first = Files.readAllBytes(frames.get(0));
        byte[] last = Files.readAllBytes(frames.get(frames.size() - 1));
        assertThat(first).isNotEqualTo(last);
    }

    /** 손상 파일이면 예외로 죽지 않고 빈 목록을 반환해야 job 이 FAILED 로 정상 종료된다. */
    @Test
    void extract_corruptedVideoReturnsEmpty(@TempDir Path tmp) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");

        Path junk = tmp.resolve("corrupt.mp4");
        Files.writeString(junk, "not a real video at all");

        assertThat(newExtractor().extract(junk, 6, tmp.resolve("out"))).isEmpty();
    }

    @Test
    void deleteQuietly_removesNestedDirs(@TempDir Path tmp) throws Exception {
        Path nested = tmp.resolve("a/b/c");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("x.jpg"), "x");

        FrameExtractor.deleteQuietly(tmp.resolve("a"));
        assertThat(Files.exists(tmp.resolve("a"))).isFalse();
    }

    @Test
    void deleteQuietly_toleratesMissingDir() {
        FrameExtractor.deleteQuietly(Path.of("/nonexistent/path/xyz"));
        FrameExtractor.deleteQuietly(null);
    }
}
