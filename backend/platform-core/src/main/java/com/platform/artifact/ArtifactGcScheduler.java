package com.platform.artifact;

import com.platform.config.WorkspaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * CR-132: CLI 산출물 TTL GC.
 *
 * <p>산출물 폴더 수명 = TTL 보관(사용자 결정). run 종료 후에도 일정 시간 다운로드 유예를 준 뒤 삭제한다.
 * CLI worker 정리 훅(CR-114/116)과 <b>독립</b>이다 — worker 는 즉시 정리하되 out/ 파일은 mtime 기준 TTL 로 남긴다.
 *
 * <p>스캔 범위는 workspace whitelist-roots 하위의 {@code out/} 디렉토리로 한정한다(경로 게이트 재사용).
 * 세션→workspace 매핑을 몰라도 파일 mtime 만으로 삭제 대상을 결정하므로 DB 조회가 필요 없다.
 */
@Component
public class ArtifactGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactGcScheduler.class);

    /** whitelist-root 아래에서 out/ 디렉토리를 찾는 최대 깊이(무한 탐색 방지). */
    private static final int MAX_DEPTH = 6;

    private final WorkspaceProperties workspaceProperties;

    /** 산출물 보관 시간(시간 단위). 기본 24h. */
    @Value("${aimbase.artifact.ttl-hours:24}")
    private long ttlHours;

    public ArtifactGcScheduler(WorkspaceProperties workspaceProperties) {
        this.workspaceProperties = workspaceProperties;
    }

    /** 매 1시간, 초기 지연 10분. mtime 이 TTL 초과한 out/ 파일 삭제. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(ttlHours));
        int deleted = 0;
        for (Path root : workspaceProperties.whitelistPaths()) {
            if (!Files.isDirectory(root)) continue;
            deleted += purgeUnderRoot(root, cutoff);
        }
        if (deleted > 0) {
            log.info("[CR132] 산출물 GC — {}개 삭제 (TTL {}h)", deleted, ttlHours);
        }
    }

    private int purgeUnderRoot(Path root, Instant cutoff) {
        int deleted = 0;
        try (Stream<Path> dirs = Files.walk(root, MAX_DEPTH)) {
            for (Path outDir : (Iterable<Path>) dirs
                    .filter(Files::isDirectory)
                    .filter(p -> ArtifactService.OUT_DIR.equals(p.getFileName().toString()))
                    ::iterator) {
                deleted += purgeOutDir(outDir, cutoff);
            }
        } catch (IOException e) {
            log.warn("[CR132] out/ 탐색 실패 root={}: {}", root, e.getMessage());
        }
        return deleted;
    }

    private int purgeOutDir(Path outDir, Instant cutoff) {
        int deleted = 0;
        try (Stream<Path> files = Files.list(outDir)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                try {
                    Instant mtime = Files.getLastModifiedTime(f).toInstant();
                    if (mtime.isBefore(cutoff)) {
                        Files.deleteIfExists(f);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("[CR132] 산출물 삭제 실패 {}: {}", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[CR132] out/ 목록 실패 {}: {}", outDir, e.getMessage());
        }
        return deleted;
    }
}
