package com.platform.attachment;

import com.platform.attachment.data.DataTableReader;
import com.platform.attachment.data.DocumentTextReader;
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

    static final String MAX_IMAGE_BYTES_KEY = "widget.attachment.max-image-bytes";
    static final String MAX_PDF_BYTES_KEY = "widget.attachment.max-pdf-bytes";
    static final long DEFAULT_MAX_IMAGE_BYTES = 10L * 1024 * 1024;   // BIZ-099 image
    static final long DEFAULT_MAX_PDF_BYTES   = 32L * 1024 * 1024;   // BIZ-099 pdf
    static final int  DEFAULT_MAX_PER_SESSION = 10;                  // BIZ-100
    static final long DEFAULT_TTL_SECONDS     = 24L * 3600;          // BIZ-101

    private final ChatAttachmentRepository repo;
    private final StorageService storage;
    private final MimeValidator mimeValidator;
    private final PlatformSettingsService settings;
    private final DataTableReader tableReader;

    public AttachmentService(ChatAttachmentRepository repo,
                             StorageService storage,
                             MimeValidator mimeValidator,
                             PlatformSettingsService settings,
                             DataTableReader tableReader) {
        this.repo = repo;
        this.storage = storage;
        this.mimeValidator = mimeValidator;
        this.settings = settings;
        this.tableReader = tableReader;
    }

    /**
     * 업로드 처리. 반환값은 Controller 응답 DTO 로 직렬화.
     */
    public ChatAttachmentEntity save(String sessionId, MultipartFile file) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_MIME_UNSUPPORTED, "session_id is required");
        }
        byte[] bytes = readUpload(file);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String mediaType = validate(bytes, originalName, file.getContentType());
        return saveForSession(sessionId, originalName, mediaType, bytes);
    }

    /** CR-178: 올린 파일을 메모리로 읽는다. 50MB 이내 설계. checksum 과 저장에 다시 쓴다. */
    public byte[] readUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_MIME_UNSUPPORTED, "file is empty");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new AttachmentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    AttachmentException.CODE_MIME_UNSUPPORTED,
                    "failed to read uploaded file: " + e.getMessage());
        }
    }

    /**
     * 종류와 크기 검사. 판정한 미디어 종류를 돌려준다. CR-178 프로젝트 자료도 같은 검사를 쓴다(BIZ-173).
     */
    public String validate(byte[] bytes, String filename, String declaredContentType) {
        java.util.Optional<AttachmentKind> kind = AttachmentKind.fromFilename(filename);
        if (kind.isPresent()) {
            // CR-183: 엑셀, CSV, TSV, 워드, 텍스트는 확장자로 종류를 정한다. 운영체제마다 Content-Type 이 달라
            // 머리글은 대조하지 않는다. 크기를 먼저 보고, 열어서 내용과 암호, 행과 열 상한을 본다(BIZ-220).
            AttachmentKind k = kind.get();
            assertSize(bytes.length, getLong(k.maxBytesKey(), k.defaultMaxBytes()), k.mediaType());
            if (k.isData()) {
                tableReader.read(bytes, k);
            } else {
                DocumentTextReader.read(bytes, k);
            }
            return k.mediaType();
        }

        // Magic number 기반 MIME 판정 + Content-Type 이중 검증
        byte[] head = new byte[Math.min(16, bytes.length)];
        System.arraycopy(bytes, 0, head, 0, head.length);
        String mediaType = mimeValidator.detect(head);
        mimeValidator.assertMatchesContentType(mediaType, declaredContentType);

        // CR-137: MimeValidator 가 영상 4종(MP4/MOV/WEBM/AVI)도 인식하게 확장됐지만,
        // 채팅 첨부 경로는 이미지·PDF 전용을 유지한다. 영상은 프레임 추출·job 이 필요하므로
        // POST /api/v1/vision-jobs 로 보내야 한다. 여기서 막지 않으면 영상이
        // 이미지 상한(10MB)에 걸리거나 Vision 블록 조립에서 조용히 깨진다.
        if (MimeValidator.isVideo(mediaType)) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_MIME_UNSUPPORTED,
                    "video is not supported on chat attachments — use POST /api/v1/vision-jobs");
        }

        // 크기 제한 BIZ-099
        long maxBytes = MimeValidator.isPdf(mediaType)
                ? getLong(MAX_PDF_BYTES_KEY, DEFAULT_MAX_PDF_BYTES)
                : getLong(MAX_IMAGE_BYTES_KEY, DEFAULT_MAX_IMAGE_BYTES);
        assertSize(bytes.length, maxBytes, mediaType);
        return mediaType;
    }

    /**
     * 검사를 마친 파일을 세션 첨부로 저장한다. CR-178 프로젝트 자료 붙이기도 이 길로 새 첨부를 만든다.
     */
    public ChatAttachmentEntity saveForSession(String sessionId, String originalName, String mediaType, byte[] bytes) {
        // 세션당 활성 개수 BIZ-100
        int maxPerSession = settings.getInt("widget.attachment.max-per-session", DEFAULT_MAX_PER_SESSION);
        long activeCount = repo.countBySessionIdAndExpiresAtAfter(sessionId, OffsetDateTime.now());
        if (activeCount >= maxPerSession) {
            throw new AttachmentException(HttpStatus.CONFLICT,
                    AttachmentException.CODE_COUNT_EXCEEDED,
                    "session has " + activeCount + " active attachments (limit " + maxPerSession + ")");
        }

        String tenantId = TenantContext.getTenantId();
        String category = "widget-attachments/" + sessionId;
        String storagePath;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            storagePath = storage.save(tenantId, category, originalName, in);
        } catch (IOException e) {
            throw new AttachmentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    AttachmentException.CODE_MIME_UNSUPPORTED,
                    "storage write failed: " + e.getMessage());
        }

        String checksum = sha256Hex(bytes);

        // PDF 페이지 수는 Phase 3 PdfTextExtractor 에서 필요 시 채움. 여기선 null.
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

    /** CR-183: 위젯이 「최대 N」 을 보이도록 상한을 싣는다(BIZ-220). */
    private static void assertSize(long size, long maxBytes, String mediaType) {
        if (size > maxBytes) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_SIZE_EXCEEDED,
                    "size " + size + " exceeds limit " + maxBytes + " for " + mediaType, maxBytes);
        }
    }

    /**
     * CR-183: 세션에 보관 중인 데이터 파일이 있는지. API 연결이 계산 도구를 실을지 정한다(BIZ-222).
     */
    public boolean hasActiveDataFile(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return repo.existsBySessionIdAndMediaTypeInAndExpiresAtAfter(
                sessionId, AttachmentKind.dataMediaTypes(), OffsetDateTime.now());
    }

    /** 위젯 설정의 첨부 크기 상한(T3-2 widget-settings attachments.max_bytes). 올리기 검사와 같은 설정을 읽는다. */
    public java.util.Map<String, Long> maxBytesByGroup() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        out.put("image", getLong(MAX_IMAGE_BYTES_KEY, DEFAULT_MAX_IMAGE_BYTES));
        out.put("pdf", getLong(MAX_PDF_BYTES_KEY, DEFAULT_MAX_PDF_BYTES));
        out.put("data", getLong(AttachmentKind.MAX_DATA_BYTES_KEY, AttachmentKind.DEFAULT_MAX_DATA_BYTES));
        out.put("text", getLong(AttachmentKind.MAX_TEXT_BYTES_KEY, AttachmentKind.DEFAULT_MAX_TEXT_BYTES));
        return out;
    }

    private long getLong(String key, long defaultValue) {
        int v = settings.getInt(key, (int) Math.min(defaultValue, Integer.MAX_VALUE));
        return v;
    }

    public static String sha256Hex(byte[] data) {
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
