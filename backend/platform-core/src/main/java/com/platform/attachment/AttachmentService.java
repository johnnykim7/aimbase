package com.platform.attachment;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * CR-061 첨부 저장/조회/삭제/제한 검증.
 *
 * 저장 경로: StorageService 의 "widget-attachments/{sessionId}" 카테고리.
 * TTL: 세션 TTL(24h) 과 동기화. 실제 GC 는 AttachmentGcScheduler.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    static final long DEFAULT_MAX_IMAGE_BYTES = 10L * 1024 * 1024;   // BIZ-099 image
    static final long DEFAULT_MAX_PDF_BYTES   = 32L * 1024 * 1024;   // BIZ-099 pdf
    static final int  DEFAULT_MAX_PER_SESSION = 10;                  // BIZ-100
    static final long DEFAULT_TTL_SECONDS     = 24L * 3600;          // BIZ-101

    private final ChatAttachmentRepository repo;
    private final StorageService storage;
    private final MimeValidator mimeValidator;
    private final PlatformSettingsService settings;

    public AttachmentService(ChatAttachmentRepository repo,
                             StorageService storage,
                             MimeValidator mimeValidator,
                             PlatformSettingsService settings) {
        this.repo = repo;
        this.storage = storage;
        this.mimeValidator = mimeValidator;
        this.settings = settings;
    }

    /**
     * 업로드 처리. 반환값은 Controller 응답 DTO 로 직렬화.
     */
    public ChatAttachmentEntity save(String sessionId, MultipartFile file) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_MIME_UNSUPPORTED, "session_id is required");
        }
        if (file == null || file.isEmpty()) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_MIME_UNSUPPORTED, "file is empty");
        }

        // 1) 원본 바이트 전부 메모리로 — 50MB 이내 설계. checksum/StorageService 저장을 위해 재사용.
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new AttachmentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    AttachmentException.CODE_MIME_UNSUPPORTED,
                    "failed to read uploaded file: " + e.getMessage());
        }

        // 2) Magic number 기반 MIME 판정 + Content-Type 이중 검증
        byte[] head = new byte[Math.min(16, bytes.length)];
        System.arraycopy(bytes, 0, head, 0, head.length);
        String mediaType = mimeValidator.detect(head);
        mimeValidator.assertMatchesContentType(mediaType, file.getContentType());

        // 3) 크기 제한 BIZ-099
        long maxBytes = MimeValidator.isPdf(mediaType)
                ? getLong("widget.attachment.max-pdf-bytes", DEFAULT_MAX_PDF_BYTES)
                : getLong("widget.attachment.max-image-bytes", DEFAULT_MAX_IMAGE_BYTES);
        if (bytes.length > maxBytes) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_SIZE_EXCEEDED,
                    "size " + bytes.length + " exceeds limit " + maxBytes + " for " + mediaType);
        }

        // 4) 세션당 활성 개수 BIZ-100
        int maxPerSession = settings.getInt("widget.attachment.max-per-session", DEFAULT_MAX_PER_SESSION);
        long activeCount = repo.countBySessionIdAndExpiresAtAfter(sessionId, OffsetDateTime.now());
        if (activeCount >= maxPerSession) {
            throw new AttachmentException(HttpStatus.CONFLICT,
                    AttachmentException.CODE_COUNT_EXCEEDED,
                    "session has " + activeCount + " active attachments (limit " + maxPerSession + ")");
        }

        // 5) 저장
        String tenantId = TenantContext.getTenantId();
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String category = "widget-attachments/" + sessionId;
        String storagePath;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            storagePath = storage.save(tenantId, category, originalName, in);
        } catch (IOException e) {
            throw new AttachmentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    AttachmentException.CODE_MIME_UNSUPPORTED,
                    "storage write failed: " + e.getMessage());
        }

        // 6) checksum (SHA-256)
        String checksum = sha256Hex(bytes);

        // 7) PDF 페이지 수는 Phase 3 PdfTextExtractor 에서 필요 시 채움. 여기선 null.
        long ttlSeconds = getLong("widget.attachment.ttl-seconds", DEFAULT_TTL_SECONDS);

        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(UUID.randomUUID());
        e.setSessionId(sessionId);
        e.setFilename(originalName);
        e.setMediaType(mediaType);
        e.setSizeBytes(bytes.length);
        e.setStoragePath(storagePath);
        e.setChecksum(checksum);
        e.setCreatedAt(OffsetDateTime.now());
        e.setExpiresAt(OffsetDateTime.now().plusSeconds(ttlSeconds));
        ChatAttachmentEntity saved = repo.save(e);

        log.info("Attachment saved — id={} session={} media={} size={} path={}",
                saved.getId(), sessionId, mediaType, bytes.length, storagePath);
        return saved;
    }

    /**
     * 세션 소유권 검증 후 조회. ChatController 가 LLM 호출 전에 사용.
     */
    public ChatAttachmentEntity loadOwned(UUID attachmentId, String sessionId) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        ChatAttachmentEntity e = repo.findById(attachmentId)
                .orElseThrow(() -> new AttachmentException(HttpStatus.NOT_FOUND,
                        AttachmentException.CODE_NOT_FOUND,
                        "attachment not found: " + attachmentId));
        if (!e.getSessionId().equals(sessionId)) {
            throw new AttachmentException(HttpStatus.FORBIDDEN,
                    AttachmentException.CODE_OWNERSHIP_DENIED,
                    "attachment does not belong to session " + sessionId);
        }
        if (e.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new AttachmentException(HttpStatus.NOT_FOUND,
                    AttachmentException.CODE_NOT_FOUND,
                    "attachment expired: " + attachmentId);
        }
        return e;
    }

    /**
     * 파일 바이너리를 메모리로 로드. PDF base64 인라인·PDF 폴백 텍스트 추출용.
     */
    public byte[] readBytes(ChatAttachmentEntity e) {
        try (InputStream in = storage.load(e.getStoragePath())) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new AttachmentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    AttachmentException.CODE_NOT_FOUND,
                    "failed to read storage: " + ex.getMessage());
        }
    }

    /**
     * 세션 소유권 검증 후 삭제. 스토리지 → DB 순.
     */
    public void delete(UUID attachmentId, String sessionId) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        ChatAttachmentEntity e = repo.findById(attachmentId)
                .orElseThrow(() -> new AttachmentException(HttpStatus.NOT_FOUND,
                        AttachmentException.CODE_NOT_FOUND,
                        "attachment not found: " + attachmentId));
        if (!e.getSessionId().equals(sessionId)) {
            throw new AttachmentException(HttpStatus.FORBIDDEN,
                    AttachmentException.CODE_OWNERSHIP_DENIED,
                    "cannot delete attachment owned by another session");
        }
        try {
            storage.delete(e.getStoragePath());
        } catch (Exception ex) {
            log.warn("Storage delete failed for attachment={} path={} — proceeding to DB delete",
                    attachmentId, e.getStoragePath(), ex);
        }
        repo.deleteById(attachmentId);
        log.info("Attachment deleted — id={} session={}", attachmentId, sessionId);
    }

    private long getLong(String key, long defaultValue) {
        int v = settings.getInt(key, (int) Math.min(defaultValue, Integer.MAX_VALUE));
        return v;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
