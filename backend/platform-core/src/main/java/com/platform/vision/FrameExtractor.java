package com.platform.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * CR-137: 영상 → 프레임 JPEG N장 추출. BE 가 ffmpeg 바이너리를 직접 호출한다.
 *
 * <p><b>왜 BE 직접 exec 인가</b>: CR-120 에서 poppler 렌더를 사이드카 MCP 경유 → BE 직접 exec 로
 * 바꿔 83분→14분으로 줄인 선례({@link com.platform.largeinput.PopplerPdfRenderer})를 그대로 따른다.
 * 프레임 추출은 ffmpeg 바이너리가 하는 일이고, 그 바이너리는 BE 컨테이너에 설치돼 있다
 * (Dockerfile ffmpeg). 사이드카를 거치면 base64 왕복·SSE 단일세션 race 만 늘어난다.
 * (참고: transcribe-sidecar 에도 ffmpeg 가 있지만 그건 사용자 맥에서 도는 오디오 PCM 디코드 전용이라
 * 운영 서버에서는 쓸 수 없다.)
 *
 * <p><b>왜 {@code -vf fps=} 가 아니라 프레임별 {@code -ss} seek 인가</b>: fps 필터는 디코드 순서에
 * 의존해 키프레임 간격이 성긴 영상(모바일 촬영본이 특히 그렇다)에서 샘플 간격이 틀어진다.
 * "영상 전체에서 고르게 N장"이 목적이므로 타임스탬프를 직접 계산해 개별 seek 한다.
 * {@code -ss} 를 {@code -i} <b>앞에</b> 두는 것이 중요하다 — 입력 seek 이라 즉시 점프한다.
 * 뒤에 두면 출력 seek 이라 앞부분을 전부 디코드하며 훑어 긴 영상에서 매우 느려진다.
 */
@Component
public class FrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractor.class);

    @Value("${vision.ffmpeg.ffmpeg-bin:ffmpeg}")
    private String ffmpegBin;

    @Value("${vision.ffmpeg.ffprobe-bin:ffprobe}")
    private String ffprobeBin;

    /** 프레임 1장 추출 timeout(초). 입력 seek 이라 보통 1초 내에 끝난다. */
    @Value("${vision.ffmpeg.frame-timeout-seconds:60}")
    private int frameTimeoutSeconds;

    /** ffprobe timeout(초). 메타데이터만 읽으므로 짧아도 된다. */
    @Value("${vision.ffmpeg.probe-timeout-seconds:30}")
    private int probeTimeoutSeconds;

    /** JPEG 품질(2=고품질~31=저품질). VLM 판독 정확도와 base64 크기의 균형점. */
    @Value("${vision.ffmpeg.jpeg-quality:2}")
    private int jpegQuality;

    /**
     * 프레임 긴 변 상한(px). 원본 해상도를 그대로 넣으면 토큰만 폭증한다.
     *
     * <p>실측(2026-08-06): 1080x1920 폰 영상 6장 = 16,227 토큰 → LM Studio 32B(context 8192)
     * 가 400 으로 거부. 768px 로 줄이면 같은 6장이 2,511 토큰(1/6.5)이 되고 판독은 정상.
     * 선행 하네스 cvilite 도 같은 이유로 {@code FRAME_MAX_EDGE=768} 을 쓴다
     * ("검수 판정엔 768px이면 충분, 원본 풀해상도는 토큰만 폭증").
     *
     * <p>축소만 하고 확대는 하지 않는다({@code force_original_aspect_ratio=decrease} +
     * {@code min(..,iw/ih)}). 0 이하면 리사이즈를 끄고 원본 그대로 추출한다.
     */
    @Value("${vision.ffmpeg.frame-max-edge:768}")
    private int frameMaxEdge;

    /**
     * 동시 추출 상한. ffmpeg 는 CPU 바운드라 과도한 병렬은 이득 없이 메모리만 먹는다.
     * PopplerPdfRenderer 와 같은 이유의 게이트.
     */
    @Value("${vision.ffmpeg.max-concurrent:2}")
    private int maxConcurrent;

    private Semaphore gate;

    private synchronized Semaphore gate() {
        if (gate == null) gate = new Semaphore(Math.max(1, maxConcurrent));
        return gate;
    }

    /**
     * 영상 길이(초)를 ffprobe 로 읽는다.
     *
     * @return 길이(초). 읽지 못하면 -1 (호출부가 균등 분할 대신 순차 추출로 폴백할 수 있게)
     */
    public double probeDuration(Path video) {
        try {
            ProcResult r = exec(List.of(ffprobeBin,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    video.toString()), probeTimeoutSeconds);
            if (r.exitCode != 0) {
                log.warn("CR-137 ffprobe failed (exit={}): {}", r.exitCode, r.stderr);
                return -1;
            }
            String s = r.stdout == null ? "" : r.stdout.trim();
            if (s.isEmpty() || "N/A".equalsIgnoreCase(s)) return -1;
            double d = Double.parseDouble(s);
            return d > 0 ? d : -1;
        } catch (NumberFormatException e) {
            log.warn("CR-137 ffprobe duration parse failed: {}", e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            log.warn("CR-137 ffprobe error: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 영상에서 프레임 {@code frameCount} 장을 균등 간격으로 뽑아 {@code outDir} 에 JPEG 로 저장한다.
     *
     * <p>샘플 지점은 {@code duration * (i + 0.5) / N} — 구간 중앙이다. {@code i / N} 을 쓰면
     * 첫 프레임이 0.0초가 되는데, 촬영 시작 순간은 흔들리거나 검은 화면인 경우가 많아 판독 가치가 낮다.
     *
     * @param video      원본 영상 경로
     * @param frameCount 뽑을 프레임 수 (BIZ-112: 1~20, 기본 6)
     * @param outDir     출력 디렉토리 (호출부가 생성·정리 책임)
     * @return 실제로 생성된 프레임 파일들(파일명 f0.jpg, f1.jpg… 오름차순). 한 장도 못 뽑으면 빈 목록
     */
    public List<Path> extract(Path video, int frameCount, Path outDir) throws IOException {
        int n = Math.max(1, frameCount);
        double duration = probeDuration(video);

        boolean acquired = false;
        try {
            gate().acquire();
            acquired = true;
            Files.createDirectories(outDir);

            List<Path> frames = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                // duration 을 못 읽으면(-1) seek 없이 맨 앞 1장만 — 손상 영상 방어
                if (duration <= 0 && i > 0) break;
                double ts = duration > 0 ? duration * (i + 0.5) / n : 0;

                Path out = outDir.resolve("f" + i + ".jpg");
                List<String> cmd = new ArrayList<>(List.of(
                        ffmpegBin,
                        "-nostdin",                       // 파이프에서 stdin 을 물지 않게(hang 방지)
                        "-ss", formatTs(ts),              // ★ -i 앞: 입력 seek (뒤에 두면 전체 디코드)
                        "-i", video.toString(),
                        "-frames:v", "1",
                        "-q:v", String.valueOf(jpegQuality)));
                String vf = scaleFilter(frameMaxEdge);
                if (vf != null) {
                    cmd.add("-vf");
                    cmd.add(vf);
                }
                cmd.add("-y");                            // 덮어쓰기(재시도 대비)
                cmd.add(out.toString());

                ProcResult r = exec(cmd, frameTimeoutSeconds);
                if (r.exitCode != 0 || !Files.exists(out) || Files.size(out) == 0) {
                    // 개별 프레임 실패는 치명적이지 않다 — 영상 끝부분 seek 이 EOF 를 넘는 경우가 흔하다.
                    // 뽑힌 것만으로 판독을 진행한다.
                    log.warn("CR-137 frame {} extract failed (exit={}, ts={}): {}",
                            i, r.exitCode, ts, truncate(r.stderr));
                    Files.deleteIfExists(out);
                    continue;
                }
                frames.add(out);
            }

            if (frames.isEmpty()) {
                log.warn("CR-137 no frames extracted from {} (duration={})", video.getFileName(), duration);
            } else {
                log.info("CR-137 extracted {}/{} frames from {} (duration={}s)",
                        frames.size(), n, video.getFileName(), duration);
            }
            return frames;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("frame extraction interrupted", e);
        } finally {
            if (acquired) gate().release();
        }
    }

    /** ffmpeg -ss 는 초 단위 소수를 받는다. 로케일 무관하게 점(.) 소수점으로 찍는다. */
    static String formatTs(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, seconds));
    }

    /**
     * 긴 변을 {@code maxEdge} 로 제한하는 {@code -vf} 필터를 만든다. 원본이 이미 작으면
     * {@code min()} 이 원본 크기를 골라 확대하지 않는다(업스케일은 토큰만 늘고 정보는 안 는다).
     *
     * @param maxEdge 긴 변 상한(px). 0 이하면 리사이즈 비활성 → {@code null} 반환.
     * @return ffmpeg scale 필터 문자열, 또는 비활성 시 {@code null}
     */
    static String scaleFilter(int maxEdge) {
        if (maxEdge <= 0) return null;
        return String.format(java.util.Locale.ROOT,
                "scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease",
                maxEdge, maxEdge);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }

    private ProcResult exec(List<String> cmd, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process proc = pb.start();
        // stdout/stderr 를 모두 소진해야 파이프 버퍼가 차서 생기는 데드락을 피한다
        // (ffmpeg 는 진행 로그를 stderr 로 계속 뱉는다).
        byte[] stdout = proc.getInputStream().readAllBytes();
        byte[] stderr = proc.getErrorStream().readAllBytes();
        boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            throw new IOException("process timeout after " + timeoutSeconds + "s: " + cmd.get(0));
        }
        return new ProcResult(proc.exitValue(), new String(stdout), new String(stderr));
    }

    /** 디렉토리 재귀 삭제(best-effort). job 정리에서 호출. */
    public static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) { /* best-effort */ }
            });
        } catch (IOException ignore) { /* best-effort */ }
    }

    private record ProcResult(int exitCode, String stdout, String stderr) {}
}
