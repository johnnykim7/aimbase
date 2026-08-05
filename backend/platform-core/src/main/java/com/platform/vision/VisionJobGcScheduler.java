package com.platform.vision;

import com.platform.domain.VisionJobEntity;
import com.platform.repository.VisionJobRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * CR-137: 영상 판독 job 산출물 GC (BIZ-114).
 *
 * <p>원본 영상은 재판독·감사를 위해 판독 후에도 남긴다. 다만 100MB 급 파일이라
 * 무한정 쌓이면 디스크가 찬다 — TTL(기본 24h) 이 지난 완료/실패 job 의 원본과
 * 작업 디렉토리를 지운다. job 레코드 자체는 이력으로 남긴다(결과 텍스트는 작다).
 *
 * <p>CR-061 {@code AttachmentGcScheduler} 와 같은 구조 — 캐시된 테넌트 DataSource 를
 * 순회하며 각 테넌트 컨텍스트에서 수행한다(BIZ-003).
 */
@Component
public class VisionJobGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(VisionJobGcScheduler.class);

    private final VisionJobRepository repo;
    private final TenantDataSourceManager tenantDataSourceManager;

    /** 산출물 보존 시간(초). 기본 24h — 첨부 TTL 정책과 맞춘다. */
    @Value("${vision.artifact-ttl-seconds:86400}")
    private long ttlSeconds;

    @Value("${vision.work-dir:/data/aimbase/vision-jobs}")
    private String workDirRoot;

    public VisionJobGcScheduler(VisionJobRepository repo,
                                TenantDataSourceManager tenantDataSourceManager) {
        this.repo = repo;
        this.tenantDataSourceManager = tenantDataSourceManager;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void purgeExpired() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                purgeForCurrentTenant();
            } catch (Exception e) {
                log.warn("[CR-137] vision job GC failed for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void purgeForCurrentTenant() {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(ttlSeconds);
        List<VisionJobEntity> expired = repo.findTop100ByFinishedAtBefore(threshold);
        if (expired.isEmpty()) return;

        int cleaned = 0;
        for (VisionJobEntity j : expired) {
            // 이미 정리된 job 은 건너뛴다(sourcePath 를 비워 표시한다).
            if (j.getSourcePath() == null) continue;
            try {
                // 원본이 담긴 스테이징 디렉토리째 삭제한다
                // (컨트롤러가 {workDir}/{tenant}/{uuid}/source.mp4 로 저장한다).
                Path source = Path.of(j.getSourcePath());
                Path dir = source.getParent();
                if (dir != null && dir.startsWith(Path.of(workDirRoot))) {
                    FrameExtractor.deleteQuietly(dir);
                } else {
                    log.warn("[CR-137] GC 대상이 작업 디렉토리 밖 — 건너뜀: {}", j.getSourcePath());
                }
                // 프레임 디렉토리는 판독 직후 이미 지워지지만, 실패 경로 대비로 한 번 더.
                if (j.getFramesPath() != null) {
                    Path frames = Path.of(j.getFramesPath());
                    if (frames.startsWith(Path.of(workDirRoot))) {
                        FrameExtractor.deleteQuietly(frames);
                    }
                }
                j.setSourcePath(null);
                j.setFramesPath(null);
                repo.save(j);
                cleaned++;
            } catch (Exception e) {
                log.warn("[CR-137] vision job GC — 파일 삭제 실패 job_id={}: {}",
                        j.getJobId(), e.getMessage());
            }
        }
        if (cleaned > 0) {
            log.info("[CR-137] vision job GC: {}건 산출물 정리 (TTL {}s)", cleaned, ttlSeconds);
        }
    }
}
