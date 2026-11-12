package com.platform.session;

import com.platform.attachment.AttachmentService;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.ChatProjectEntity;
import com.platform.domain.ChatProjectFileEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ChatProjectFileRepository;
import com.platform.repository.ChatProjectRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-178 BIZ-171, BIZ-173: 위젯 프로젝트와 프로젝트 자료. 주인이 아닌 요청은 없는 것으로 보고 404 다.
 */
@Service
public class ChatProjectService {

    private static final Logger log = LoggerFactory.getLogger(ChatProjectService.class);

    static final int NAME_MAX = 100;
    static final int DEFAULT_INSTRUCTIONS_MAX = 4000;
    static final int DEFAULT_FILES_MAX = 20;

    private final ChatProjectRepository projectRepository;
    private final ChatProjectFileRepository fileRepository;
    private final ConversationSessionRepository sessionRepository;
    private final AttachmentService attachmentService;
    private final StorageService storage;
    private final PlatformSettingsService settings;

    public ChatProjectService(ChatProjectRepository projectRepository,
                              ChatProjectFileRepository fileRepository,
                              ConversationSessionRepository sessionRepository,
                              AttachmentService attachmentService,
                              StorageService storage,
                              PlatformSettingsService settings) {
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.sessionRepository = sessionRepository;
        this.attachmentService = attachmentService;
        this.storage = storage;
        this.settings = settings;
    }

    public int instructionsMaxChars() {
        return settings.getInt("widget.project-instructions-max-chars", DEFAULT_INSTRUCTIONS_MAX);
    }

    public int filesMaxCount() {
        return settings.getInt("widget.project-files-max-count", DEFAULT_FILES_MAX);
    }

    /** 요청자의 프로젝트. 없거나 남의 것이면 404 project_not_found. */
    public ChatProjectEntity requireOwned(String owner, UUID projectId) {
        if (owner == null || projectId == null) throw projectNotFound();
        return projectRepository.findByIdAndOwnerRef(projectId, owner).orElseThrow(ChatProjectService::projectNotFound);
    }

    public ChatProjectEntity requireOwned(String owner, String projectIdText) {
        return requireOwned(owner, parseId(projectIdText, ChatProjectService::projectNotFound));
    }

    public List<Map<String, Object>> list(String owner) {
        List<ChatProjectEntity> projects = projectRepository.findByOwnerRef(owner);
        if (projects.isEmpty()) return List.of();

        Map<UUID, Long> fileCounts = new HashMap<>();
        for (Object[] row : projectRepository.countFilesByProject(projects.stream().map(ChatProjectEntity::getId).toList())) {
            fileCounts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<String, Object[]> sessionStats = new HashMap<>();
        for (Object[] row : sessionRepository.summarizeByChatProject(owner)) {
            sessionStats.put((String) row[0], row);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatProjectEntity p : projects) {
            Object[] stats = sessionStats.get(p.getId().toString());
            long sessionCount = stats != null ? ((Number) stats[1]).longValue() : 0L;
            OffsetDateTime lastSession = stats != null ? toOffset(stats[2]) : null;
            OffsetDateTime lastActive = lastSession != null && lastSession.isAfter(p.getUpdatedAt())
                    ? lastSession : p.getUpdatedAt();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId().toString());
            row.put("name", p.getName());
            row.put("has_instructions", p.hasInstructions());
            row.put("file_count", fileCounts.getOrDefault(p.getId(), 0L));
            row.put("session_count", sessionCount);
            row.put("last_active_at", lastActive);
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (OffsetDateTime) r.get("last_active_at")).reversed());
        return rows;
    }

    public Map<String, Object> get(String owner, String projectIdText) {
        ChatProjectEntity p = requireOwned(owner, projectIdText);
        Map<String, Object> body = projectBody(p);
        body.put("files", files(p.getId()));
        return body;
    }

    @Transactional
    public Map<String, Object> create(String owner, Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        ChatProjectEntity p = new ChatProjectEntity();
        p.setId(UUID.randomUUID());
        p.setOwnerRef(owner);
        p.setName(validName(body.get("name")));
        p.setInstructions(validInstructions(body.get("instructions")));
        OffsetDateTime now = OffsetDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return projectBody(projectRepository.save(p));
    }

    /** 준 칸만 바꾼다. instructions 가 null 이면 지시문을 비운다. */
    @Transactional
    public Map<String, Object> update(String owner, String projectIdText, Map<String, Object> request) {
        ChatProjectEntity p = requireOwned(owner, projectIdText);
        Map<String, Object> body = request != null ? request : Map.of();
        if (body.containsKey("name")) p.setName(validName(body.get("name")));
        if (body.containsKey("instructions")) p.setInstructions(validInstructions(body.get("instructions")));
        p.setUpdatedAt(OffsetDateTime.now());
        return projectBody(projectRepository.save(p));
    }

    /** 대화는 지우지 않고 프로젝트에서만 빠진다(외래키 ON DELETE SET NULL). 자료는 저장소 파일까지 지운다. */
    @Transactional
    public void delete(String owner, String projectIdText) {
        ChatProjectEntity p = requireOwned(owner, projectIdText);
        for (ChatProjectFileEntity f : fileRepository.findByProjectIdOrderByCreatedAtAsc(p.getId())) {
            deleteStorageQuietly(f);
        }
        projectRepository.delete(p);
    }

    public List<Map<String, Object>> listFiles(String owner, String projectIdText) {
        return files(requireOwned(owner, projectIdText).getId());
    }

    @Transactional
    public Map<String, Object> uploadFile(String owner, String projectIdText, MultipartFile file) {
        ChatProjectEntity p = requireOwned(owner, projectIdText);
        int maxCount = filesMaxCount();
        if (fileRepository.countByProjectId(p.getId()) >= maxCount) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, "project_files_full");
        }
        byte[] bytes = attachmentService.readUpload(file);
        String mediaType = attachmentService.validate(bytes, file.getOriginalFilename(), file.getContentType());
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";

        String storagePath;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            storagePath = storage.save(TenantContext.getTenantId(), "chat-project-files/" + p.getId(), filename, in);
        } catch (IOException e) {
            throw new IllegalStateException("project file storage write failed: " + e.getMessage(), e);
        }

        ChatProjectFileEntity f = new ChatProjectFileEntity();
        f.setId(UUID.randomUUID());
        f.setProjectId(p.getId());
        f.setFilename(filename);
        f.setMediaType(mediaType);
        f.setSizeBytes(bytes.length);
        f.setStoragePath(storagePath);
        f.setChecksum(AttachmentService.sha256Hex(bytes));
        f.setCreatedAt(OffsetDateTime.now());
        ChatProjectFileEntity saved = fileRepository.save(f);
        p.setUpdatedAt(OffsetDateTime.now());
        projectRepository.save(p);
        log.info("Chat project file saved: project={} file={} media={} size={}",
                p.getId(), saved.getId(), mediaType, bytes.length);
        return fileBody(saved);
    }

    @Transactional
    public void deleteFile(String owner, String projectIdText, String fileIdText) {
        ChatProjectEntity p = requireOwned(owner, projectIdText);
        ChatProjectFileEntity f = requireFile(p, fileIdText);
        deleteStorageQuietly(f);
        fileRepository.delete(f);
        p.setUpdatedAt(OffsetDateTime.now());
        projectRepository.save(p);
    }

    /**
     * 자료를 복사해 그 세션의 새 첨부를 만든다. 첨부는 첨부 규칙(만료 포함)을 따르고 자료 원본은 남는다.
     * 세션이 아직 없으면(새 대화 첫 질문 전) 그대로 붙인다.
     */
    public ChatAttachmentEntity attachFile(String owner, String projectIdText, String fileIdText, String sessionId) {
        ChatProjectEntity p = requireOwned(owner, projectIdText);
        ChatProjectFileEntity f = requireFile(p, fileIdText);
        if (sessionId == null || sessionId.isBlank()) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "session_id_required");
        }
        sessionRepository.findBySessionIdIncludingDeleted(sessionId).ifPresent(session -> {
            if (session.getDeletedAt() != null || !owner.equals(session.getUserId())) {
                throw new ChatRejectedException(HttpStatus.NOT_FOUND, "session_not_found");
            }
        });
        byte[] bytes;
        try (InputStream in = storage.load(f.getStoragePath())) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("project file read failed: " + e.getMessage(), e);
        }
        return attachmentService.saveForSession(sessionId, f.getFilename(), f.getMediaType(), bytes);
    }

    private ChatProjectFileEntity requireFile(ChatProjectEntity p, String fileIdText) {
        UUID fileId = parseId(fileIdText, ChatProjectService::fileNotFound);
        return fileRepository.findById(fileId)
                .filter(f -> f.getProjectId().equals(p.getId()))
                .orElseThrow(ChatProjectService::fileNotFound);
    }

    private List<Map<String, Object>> files(UUID projectId) {
        return fileRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream().map(ChatProjectService::fileBody).toList();
    }

    private void deleteStorageQuietly(ChatProjectFileEntity f) {
        try {
            storage.delete(f.getStoragePath());
        } catch (Exception e) {
            log.warn("Chat project file storage delete failed: file={} path={} ({})",
                    f.getId(), f.getStoragePath(), e.getMessage());
        }
    }

    private String validName(Object value) {
        String name = value instanceof String s ? s.trim() : "";
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "project_invalid:name");
        }
        return name;
    }

    private String validInstructions(Object value) {
        if (value == null) return null;
        if (!(value instanceof String s)) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "project_invalid:instructions");
        }
        if (s.length() > instructionsMaxChars()) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "project_invalid:instructions");
        }
        return s.isBlank() ? null : s;
    }

    private static Map<String, Object> projectBody(ChatProjectEntity p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.getId().toString());
        body.put("name", p.getName());
        body.put("instructions", p.getInstructions());
        body.put("has_instructions", p.hasInstructions());
        body.put("created_at", p.getCreatedAt());
        body.put("updated_at", p.getUpdatedAt());
        return body;
    }

    static Map<String, Object> fileBody(ChatProjectFileEntity f) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file_id", f.getId().toString());
        body.put("filename", f.getFilename());
        body.put("media_type", f.getMediaType());
        body.put("size_bytes", f.getSizeBytes());
        body.put("pages", f.getPages());
        body.put("created_at", f.getCreatedAt());
        return body;
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.time.Instant i) return i.atOffset(ZoneOffset.UTC);
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }

    private static UUID parseId(String text, java.util.function.Supplier<ChatRejectedException> onInvalid) {
        if (text == null || text.isBlank()) throw onInvalid.get();
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            throw onInvalid.get();
        }
    }

    static ChatRejectedException projectNotFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, "project_not_found");
    }

    private static ChatRejectedException fileNotFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, "file_not_found");
    }
}
