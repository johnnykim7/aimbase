package com.platform.attachment;

import com.platform.domain.ChatAttachmentEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantDataSourceManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-061 AttachmentGcScheduler — 만료 건만 삭제, 스토리지 실패해도 DB 정리 진행.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentGcSchedulerTest {

    @Mock private ChatAttachmentRepository repo;
    @Mock private StorageService storage;
    @Mock private TenantDataSourceManager tenantDataSourceManager;

    private AttachmentGcScheduler scheduler;

    @BeforeEach
    void setUp() {
        // CR-122: GC 는 캐시된 테넌트를 순회한다. 기존 케이스는 테넌트 1개 기준으로 동작 동일.
        when(tenantDataSourceManager.getAllCachedDataSources())
                .thenReturn(Map.of("tenant-a", mock(HikariDataSource.class)));
        scheduler = new AttachmentGcScheduler(repo, storage, tenantDataSourceManager);
    }

    @Test
    void purgeExpired_noop_whenNoExpired() {
        when(repo.findTop100ByExpiresAtBefore(any())).thenReturn(List.of());

        scheduler.purgeExpired();

        verify(storage, never()).delete(any());
        verify(repo, never()).deleteById(any(UUID.class));
    }

    @Test
    void purgeExpired_deletesStorageThenDb() {
        ChatAttachmentEntity a = fixture(UUID.randomUUID(), "/p/a");
        ChatAttachmentEntity b = fixture(UUID.randomUUID(), "/p/b");
        when(repo.findTop100ByExpiresAtBefore(any())).thenReturn(List.of(a, b));

        scheduler.purgeExpired();

        verify(storage).delete("/p/a");
        verify(storage).delete("/p/b");
        verify(repo).deleteById(a.getId());
        verify(repo).deleteById(b.getId());
    }

    @Test
    void purgeExpired_continuesWhenStorageFails() {
        ChatAttachmentEntity a = fixture(UUID.randomUUID(), "/p/a");
        ChatAttachmentEntity b = fixture(UUID.randomUUID(), "/p/b");
        when(repo.findTop100ByExpiresAtBefore(any())).thenReturn(List.of(a, b));
        when(storage.delete("/p/a")).thenThrow(new RuntimeException("S3 down"));

        scheduler.purgeExpired();

        // storage 는 a 실패했지만 b 는 시도, DB 정리는 둘 다 진행
        verify(storage).delete("/p/b");
        verify(repo, times(2)).deleteById(any(UUID.class));
    }

    /**
     * CR-122 회귀 가드: chat_attachments 는 테넌트 테이블이므로 캐시된 테넌트마다 조회해야 한다.
     * 이전엔 테넌트 컨텍스트 없이 1회만 조회해 master DB 로 라우팅됐고 매번 실패했다.
     */
    @Test
    void purgeExpired_iteratesEveryCachedTenant() {
        when(tenantDataSourceManager.getAllCachedDataSources()).thenReturn(Map.of(
                "tenant-a", mock(HikariDataSource.class),
                "tenant-b", mock(HikariDataSource.class),
                "tenant-c", mock(HikariDataSource.class)));
        when(repo.findTop100ByExpiresAtBefore(any())).thenReturn(List.of());

        scheduler.purgeExpired();

        verify(repo, times(3)).findTop100ByExpiresAtBefore(any());
    }

    /** CR-122: 한 테넌트에서 실패해도 나머지 테넌트 GC 는 계속된다. */
    @Test
    void purgeExpired_continuesWhenOneTenantFails() {
        when(tenantDataSourceManager.getAllCachedDataSources()).thenReturn(Map.of(
                "tenant-a", mock(HikariDataSource.class),
                "tenant-b", mock(HikariDataSource.class)));
        when(repo.findTop100ByExpiresAtBefore(any()))
                .thenThrow(new RuntimeException("relation does not exist"))
                .thenReturn(List.of());

        scheduler.purgeExpired();

        verify(repo, times(2)).findTop100ByExpiresAtBefore(any());
    }

    private static ChatAttachmentEntity fixture(UUID id, String path) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId("s");
        e.setFilename("f.pdf");
        e.setMediaType("application/pdf");
        e.setSizeBytes(100);
        e.setStoragePath(path);
        e.setChecksum("0".repeat(64));
        e.setCreatedAt(OffsetDateTime.now().minusDays(2));
        e.setExpiresAt(OffsetDateTime.now().minusHours(1));
        return e;
    }
}
