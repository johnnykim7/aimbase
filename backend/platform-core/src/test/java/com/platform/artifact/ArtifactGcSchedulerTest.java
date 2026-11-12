package com.platform.artifact;

import com.platform.config.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-180 TC-CHT-067: 미리보기 캐시 정리. */
class ArtifactGcSchedulerTest {

    @TempDir Path root;

    @Test
    void expired_preview_folders_are_removed_with_the_same_ttl_as_artifacts() throws IOException {
        Path ws = root.resolve("ws");
        Path oldCache = Files.createDirectories(ws.resolve(".preview/aaaa-1-10"));
        Files.writeString(oldCache.resolve("doc.pdf"), "pdf");
        Files.writeString(oldCache.resolve("p1-1600.png"), "png");
        Path freshCache = Files.createDirectories(ws.resolve(".preview/bbbb-2-20"));
        Files.writeString(freshCache.resolve("doc.pdf"), "pdf");
        Path oldArtifact = Files.writeString(Files.createDirectories(ws.resolve("out")).resolve("old.pptx"), "deck");

        Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));
        Files.setLastModifiedTime(oldCache, FileTime.from(twoDaysAgo));
        Files.setLastModifiedTime(oldArtifact, FileTime.from(twoDaysAgo));

        WorkspaceProperties props = new WorkspaceProperties();
        props.setWhitelistRoots(List.of(root.toString()));
        int deleted = new ArtifactGcScheduler(props).purgeUnderRoot(root, Instant.now().minus(Duration.ofHours(24)));

        assertThat(deleted).isEqualTo(2);
        assertThat(oldCache).doesNotExist();
        assertThat(oldArtifact).doesNotExist();
        assertThat(freshCache.resolve("doc.pdf")).exists();
    }
}
