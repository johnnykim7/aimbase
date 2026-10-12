package com.platform.skill;

import com.platform.domain.SkillBundleFileBase;
import com.platform.domain.SkillBundleVersionBase;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * CR-147: 고정한 판의 파일을 run 작업장 .skills/&lt;이름&gt;/v&lt;판&gt;/ 에 푼다(T3-17 3.4 skill_run 처리 2).
 * 다 쓴 뒤 표시 파일을 두고, 표시가 같은 판을 가리키면 다시 쓰지 않는다.
 */
@Component
public class SkillWorkspaceInstaller {

    static final String MARKER = ".complete";
    /** 병렬 에이전트가 같은 묶음을 동시에 풀 때만 겹치므로 폴더별 잠금 대신 고정 개수로 나눈다. */
    private static final int LOCK_STRIPES = 64;

    private final SkillCatalog catalog;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public SkillWorkspaceInstaller(SkillCatalog catalog) {
        this.catalog = catalog;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public Path install(ResolvedSkill skill, Path workspace) {
        Path dir = workspace.resolve(skill.workspaceDir()).normalize();
        SkillBundleStore<?, ?, ?> store = catalog.store(skill.scope());
        SkillBundleVersionBase version = store.findVersionById(skill.versionId())
                .orElseThrow(() -> new SkillNotFoundException(
                        "BIZ-120: 스킬 " + skill.name() + " 판 " + skill.version() + " 이 없습니다"));
        // 같은 이름이 테넌트 전용과 플랫폼 공용에 다 있으면 위치가 겹치므로 범위까지 표시에 넣는다.
        String stamp = skill.scope().value() + ":" + version.getContentSha256();

        ReentrantLock lock = locks[Math.floorMod(dir.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            Path marker = dir.resolve(MARKER);
            if (Files.isRegularFile(marker) && stamp.equals(Files.readString(marker).strip())) {
                return dir;
            }
            if (Files.exists(dir)) {
                deleteTree(dir);
            }
            for (SkillBundleFileBase file : store.loadFiles(skill.versionId())) {
                Path target = dir.resolve(file.getPath()).normalize();
                if (!target.startsWith(dir)) {
                    throw new IllegalStateException("BIZ-118: 묶음 밖을 가리키는 파일 경로: " + file.getPath());
                }
                Files.createDirectories(target.getParent());
                Files.write(target, file.getContent());
            }
            Files.writeString(marker, stamp);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("스킬 묶음을 작업장에 풀지 못했습니다: " + dir, e);
        } finally {
            lock.unlock();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
