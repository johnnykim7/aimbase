package com.platform.attachment;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-061 AttachmentService — BIZ-099(크기) / BIZ-100(개수) / 소유권 검증.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock private ChatAttachmentRepository repo;
    @Mock private StorageService storage;
    @Mock private PlatformSettingsService settings;

    private MimeValidator mimeValidator;
    private AttachmentService service;

    // 4-byte PNG magic + 20 bytes payload — 크기 검증/저장 경로 확인용
    private static final byte[] PNG_BYTES = new byte[]{
            (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    @BeforeEach
    void setUp() {
        mimeValidator = new MimeValidator();
        service = new AttachmentService(repo, storage, mimeValidator, settings);
        TenantContext.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void save_persistsEntityAndReturnsMeta() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "img.png", "image/png", PNG_BYTES);

        when(settings.getInt(eq("widget.attachment.max-image-bytes"), anyInt()))
                .thenReturn(10 * 1024 * 1024);
        when(settings.getInt(eq("widget.attachment.max-per-session"), anyInt()))
                .thenReturn(10);
        when(settings.getInt(eq("widget.attachment.ttl-seconds"), anyInt()))
                .thenReturn(24 * 3600);
        when(repo.countBySessionIdAndExpiresAtAfter(eq("s1"), any())).thenReturn(0L);
        when(storage.save(eq("tenant-a"), anyString(), eq("img.png"), any()))
                .thenReturn("widget-attachments/s1/img.png");
        when(repo.save(any(ChatAttachmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatAttachmentEntity result = service.save("s1", file);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getSessionId()).isEqualTo("s1");
        assertThat(result.getMediaType()).isEqualTo("image/png");
        assertThat(result.getSizeBytes()).isEqualTo(PNG_BYTES.length);
        assertThat(result.getStoragePath()).isEqualTo("widget-attachments/s1/img.png");
        assertThat(result.getChecksum()).hasSize(64);  // SHA-256 hex
        assertThat(result.getExpiresAt()).isAfter(OffsetDateTime.now());
        verify(repo).save(any());
    }

    @Test
    void save_rejectsOversizedImage() {
        byte[] big = new byte[12 * 1024 * 1024];  // 12MB
        // 선두에 PNG magic 넣기
        System.arraycopy(PNG_BYTES, 0, big, 0, 8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", big);

        when(settings.getInt(eq("widget.attachment.max-image-bytes"), anyInt()))
                .thenReturn(10 * 1024 * 1024);

        assertThatThrownBy(() -> service.save("s1", file))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_SIZE_EXCEEDED);

        verify(repo, never()).save(any());
        verify(storage, never()).save(anyString(), anyString(), anyString(), any());
    }

    @Test
    void save_rejectsPerSessionLimitExceeded() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "img.png", "image/png", PNG_BYTES);

        when(settings.getInt(eq("widget.attachment.max-image-bytes"), anyInt()))
                .thenReturn(10 * 1024 * 1024);
        when(settings.getInt(eq("widget.attachment.max-per-session"), anyInt()))
                .thenReturn(10);
        when(repo.countBySessionIdAndExpiresAtAfter(eq("s1"), any())).thenReturn(10L);

        assertThatThrownBy(() -> service.save("s1", file))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_COUNT_EXCEEDED);

        verify(storage, never()).save(anyString(), anyString(), anyString(), any());
    }

    @Test
    void save_rejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "x.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.save("s1", empty))
                .isInstanceOf(AttachmentException.class);
    }

    @Test
    void loadOwned_returnsEntityWhenOwnedAndNotExpired() {
        UUID id = UUID.randomUUID();
        ChatAttachmentEntity e = fixture(id, "s1", OffsetDateTime.now().plusHours(1));
        when(repo.findById(id)).thenReturn(Optional.of(e));

        assertThat(service.loadOwned(id, "s1")).isSameAs(e);
    }

    @Test
    void loadOwned_rejectsWrongSession() {
        UUID id = UUID.randomUUID();
        ChatAttachmentEntity e = fixture(id, "owner-session", OffsetDateTime.now().plusHours(1));
        when(repo.findById(id)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.loadOwned(id, "other-session"))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_OWNERSHIP_DENIED);
    }

    @Test
    void loadOwned_rejectsExpired() {
        UUID id = UUID.randomUUID();
        ChatAttachmentEntity e = fixture(id, "s1", OffsetDateTime.now().minusMinutes(1));
        when(repo.findById(id)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.loadOwned(id, "s1"))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_NOT_FOUND);
    }

    @Test
    void delete_removesStorageAndRecord() {
        UUID id = UUID.randomUUID();
        ChatAttachmentEntity e = fixture(id, "s1", OffsetDateTime.now().plusHours(1));
        when(repo.findById(id)).thenReturn(Optional.of(e));

        service.delete(id, "s1");

        verify(storage).delete("p/x");
        verify(repo).deleteById(id);
    }

    @Test
    void delete_rejectsForeignSession() {
        UUID id = UUID.randomUUID();
        ChatAttachmentEntity e = fixture(id, "owner", OffsetDateTime.now().plusHours(1));
        when(repo.findById(id)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.delete(id, "intruder"))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_OWNERSHIP_DENIED);

        verify(repo, never()).deleteById(any(UUID.class));
    }

    @Test
    void readBytes_copiesFromStorage() {
        ChatAttachmentEntity e = fixture(UUID.randomUUID(), "s1", OffsetDateTime.now().plusHours(1));
        when(storage.load("p/x")).thenReturn(new java.io.ByteArrayInputStream(PNG_BYTES));

        byte[] bytes = service.readBytes(e);

        assertThat(bytes).isEqualTo(PNG_BYTES);
    }

    // --- helpers ---

    private static ChatAttachmentEntity fixture(UUID id, String sessionId, OffsetDateTime expires) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId(sessionId);
        e.setFilename("img.png");
        e.setMediaType("image/png");
        e.setSizeBytes(24);
        e.setStoragePath("p/x");
        e.setChecksum("00".repeat(32));
        e.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        e.setExpiresAt(expires);
        return e;
    }

    @SuppressWarnings("unused") // 참조용 — 업로드 시 메모리 버퍼 확인 흐름 테스트 시 활용 가능
    private static ByteArrayOutputStream dummyBuf() {
        return new ByteArrayOutputStream();
    }

    @SuppressWarnings("unused")
    private static long anyLongVal() { return anyLong(); }
}
