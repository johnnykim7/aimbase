package com.platform.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CR-180: 미리보기 변환기(soffice, pdfinfo, pdftoppm) 실행.
 *
 * <p>출력은 파이프가 아니라 임시 파일로 받는다. 파이프를 끝까지 읽은 뒤 기다리면 출력을 닫지 않는 변환기에서
 * 시간 한도가 듣지 않는다. 시간 한도를 넘기면 자식 프로세스까지 끝낸다. soffice 는 실행 스크립트가
 * 실제 변환 프로세스를 따로 띄우므로 부모만 끝내면 변환이 남는다(BIZ-191).
 */
final class PreviewProcess {

    /** 오류 기록에 남길 출력 상한. 변환기 출력 전체를 메모리에 올리지 않는다. */
    private static final int OUTPUT_LIMIT_BYTES = 64 * 1024;

    private PreviewProcess() {}

    record Result(int exitCode, String output) {}

    static final class TimeoutException extends IOException {
        TimeoutException(String message) { super(message); }
    }

    static Result run(List<String> cmd, Duration timeout) throws IOException, InterruptedException {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new TimeoutException("no time left before start: " + cmd.get(0));
        }
        Path out = Files.createTempFile("aimbase-preview-", ".log");
        try {
            Process proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile())
                    .start();
            boolean done;
            try {
                done = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                killTree(proc);
                throw e;
            }
            if (!done) {
                killTree(proc);
                throw new TimeoutException("timeout after " + timeout.toSeconds() + "s: " + cmd.get(0));
            }
            return new Result(proc.exitValue(), readHead(out));
        } finally {
            Files.deleteIfExists(out);
        }
    }

    static void killTree(Process proc) {
        List<ProcessHandle> children = proc.descendants().toList();
        children.forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
        try {
            proc.waitFor(5, TimeUnit.SECONDS);
            for (ProcessHandle child : children) {
                child.onExit().get(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignore) {
            // 끝나기를 기다리는 것은 정리 확인용이다. 못 기다려도 강제 종료 신호는 이미 갔다.
        }
    }

    private static String readHead(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(OUTPUT_LIMIT_BYTES), StandardCharsets.UTF_8);
        }
    }
}
