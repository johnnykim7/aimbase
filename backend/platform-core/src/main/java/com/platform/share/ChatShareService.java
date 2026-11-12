package com.platform.share;

import com.platform.artifact.ArtifactService;
import com.platform.config.WorkspaceProperties;
import com.platform.document.DocModel;
import com.platform.document.DocumentRenderer;
import com.platform.document.FileMediaTypes;
import com.platform.domain.ChatShareEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ChatShareRepository;
import com.platform.session.ChatExportLabels;
import com.platform.session.ChatExportService;
import com.platform.session.ChatRejectedException;
import com.platform.session.ChatSessionOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 공유 링크(CR-182 3.5, BIZ-212, BIZ-213). 링크는 만든 순간의 사본을 가리킨다. 사본은 작업 폴더 밖
 * {@code {base}/shares/{tenant_id}/{share_id}/} 에 두어 작업 폴더 24시간 지움과 따로 산다.
 */
@Service
public class ChatShareService {

    public static final String SHARE_NOT_CONFIGURED = "share_not_configured";
    public static final String SHARE_NOT_FOUND = "share_not_found";
    public static final String FILE_NOT_FOUND = "file_not_found";
    static final String INVALID_KIND = "share_invalid:kind";
    static final String INVALID_FILE_NAME = "share_invalid:file_name";
    static final String INVALID_TTL = "share_invalid:ttl_days";
    static final String CONVERSATION_FILE = "conversation.html";
    static final String SHARES_DIR = "shares";

    /** 테넌트 id 가 폴더 이름과 공개 경로에 그대로 들어가서 글자를 좁힌다. */
    public static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    /** 32바이트를 URL 안전 base64 로, 덧붙임 없이 43자. */
    public static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final Logger log = LoggerFactory.getLogger(ChatShareService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ChatShareRepository repository;
    private final ChatSessionOwnership ownership;
    private final ChatExportService exportService;
    private final ChatExportLabels labels;
    private final DocumentRenderer renderer;
    private final ArtifactService artifactService;
    private final WorkspaceProperties workspaceProperties;
    private final ChatShareSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ChatShareService(ChatShareRepository repository, ChatSessionOwnership ownership,
                            ChatExportService exportService, ChatExportLabels labels, DocumentRenderer renderer,
                            ArtifactService artifactService, WorkspaceProperties workspaceProperties,
                            ChatShareSettings settings) {
        this(repository, ownership, exportService, labels, renderer, artifactService, workspaceProperties,
                settings, Clock.systemUTC());
    }

    ChatShareService(ChatShareRepository repository, ChatSessionOwnership ownership,
                     ChatExportService exportService, ChatExportLabels labels, DocumentRenderer renderer,
                     ArtifactService artifactService, WorkspaceProperties workspaceProperties,
                     ChatShareSettings settings, Clock clock) {
        this.repository = repository;
        this.ownership = ownership;
        this.exportService = exportService;
        this.labels = labels;
        this.renderer = renderer;
        this.artifactService = artifactService;
        this.workspaceProperties = workspaceProperties;
        this.settings = settings;
        this.clock = clock;
    }

    /** 받는 사람에게 내줄 사본 하나. */
    public record Opened(ChatShareEntity.Kind kind, String fileName, String contentType, Path path) {}

    @Transactional
    public Map<String, Object> create(String requesterRef, String tenantId, String sessionId, String kind,
                                      String fileName, Integer ttlDays) {
        if (!settings.usable()) throw new ChatRejectedException(HttpStatus.CONFLICT, SHARE_NOT_CONFIGURED);
        ChatShareEntity.Kind k = kind(kind);
        if (k == ChatShareEntity.Kind.FILE && (fileName == null || fileName.isBlank())) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, INVALID_FILE_NAME);
        }
        int days = ttl(ttlDays);
        ConversationSessionEntity session = ownership.require(requesterRef, sessionId);
        Path root = sharesRoot(tenantId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plusDays(days);
        UUID id = UUID.randomUUID();
        Path dir = root.resolve(id.toString());

        ChatShareEntity share = new ChatShareEntity();
        share.setId(id);
        share.setToken(newToken());
        share.setKind(k);
        share.setSessionId(sessionId);
        share.setCreatedBy(requesterRef);
        share.setCreatedAt(now);
        share.setExpiresAt(expiresAt);
        try {
            Files.createDirectories(dir);
            Path snapshot = k == ChatShareEntity.Kind.FILE
                    ? copyFile(sessionId, fileName, dir)
                    : writeConversation(session, expiresAt, dir, now);
            share.setFileName(k == ChatShareEntity.Kind.FILE ? snapshot.getFileName().toString() : null);
            share.setSnapshotPath(snapshot.toString());
            share.setSnapshotSize(Files.size(snapshot));
            share.setContentType(FileMediaTypes.forName(snapshot.getFileName().toString()));
            repository.save(share);
        } catch (IOException e) {
            deleteQuietly(dir);
            throw new IllegalStateException("share_write_failed", e);
        } catch (RuntimeException e) {
            deleteQuietly(dir);
            throw e;
        }
        return view(share, tenantId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String requesterRef, String tenantId, String sessionId) {
        ownership.require(requesterRef, sessionId);
        return repository.findTop20BySessionIdAndCreatedByOrderByCreatedAtDesc(sessionId, requesterRef).stream()
                .map(s -> view(s, tenantId))
                .toList();
    }

    /** 주인만 끊는다. 이미 끊긴 것은 그대로 돌려준다. */
    @Transactional
    public Map<String, Object> revoke(String requesterRef, String tenantId, String shareId) {
        ChatShareEntity share = parseId(shareId).flatMap(repository::findById)
                .filter(s -> requesterRef.equals(s.getCreatedBy()))
                .orElseThrow(() -> new ChatRejectedException(HttpStatus.NOT_FOUND, SHARE_NOT_FOUND));
        if (share.getRevokedAt() == null) cut(share, OffsetDateTime.now(clock));
        return view(share, tenantId);
    }

    /** 세션을 지울 때 그 세션의 살아 있는 링크를 모두 끊는다(3.5.4). */
    @Transactional
    public int revokeAllForSession(String sessionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ChatShareEntity> live = repository.findBySessionIdAndRevokedAtIsNull(sessionId);
        live.forEach(s -> cut(s, now));
        return live.size();
    }

    /**
     * 받는 사람 열기. 없음, 끊김, 기한 지남, 사본 없음은 모두 빈 값이다. 무엇 때문인지 밖에 밝히지 않는다.
     * 호출 전에 경로의 테넌트로 TenantContext 가 잡혀 있어야 한다.
     */
    @Transactional
    public Optional<Opened> open(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return Optional.empty();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<ChatShareEntity> found = repository.findByToken(token)
                .filter(s -> s.getRevokedAt() == null && s.getFilesPurgedAt() == null && s.getExpiresAt().isAfter(now));
        if (found.isEmpty()) return Optional.empty();
        ChatShareEntity share = found.get();
        Path path = Path.of(share.getSnapshotPath());
        if (!Files.isRegularFile(path)) return Optional.empty();
        repository.countView(share.getId(), now);
        return Optional.of(new Opened(share.getKind(), share.getFileName(), share.getContentType(), path));
    }

    /** 기한 지난 링크의 사본을 지운다. 행은 남긴다(3.5.4). */
    @Transactional
    public int purgeExpired() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ChatShareEntity> expired = repository.findByExpiresAtBeforeAndFilesPurgedAtIsNull(now);
        for (ChatShareEntity s : expired) {
            deleteQuietly(Path.of(s.getSnapshotPath()).getParent());
            s.setFilesPurgedAt(now);
        }
        return expired.size();
    }

    private void cut(ChatShareEntity share, OffsetDateTime now) {
        share.setRevokedAt(now);
        if (share.getFilesPurgedAt() == null) {
            deleteQuietly(Path.of(share.getSnapshotPath()).getParent());
            share.setFilesPurgedAt(now);
        }
    }

    private Path writeConversation(ConversationSessionEntity session, OffsetDateTime expiresAt, Path dir,
                                   OffsetDateTime now) throws IOException {
        ZoneId zone = exportService.zone(null);
        Map<String, String> l = labels.current();
        String notice = l.get(ChatExportLabels.SHARED) + " (" + l.get(ChatExportLabels.EXPIRES) + " "
                + ChatExportService.shown(expiresAt, zone) + ")";
        DocModel doc = exportService.conversation(session, zone, now).withNotice(notice);
        Path target = dir.resolve(CONVERSATION_FILE);
        Files.writeString(target, renderer.html(doc), StandardCharsets.UTF_8);
        return target;
    }

    private Path copyFile(String sessionId, String fileName, Path dir) throws IOException {
        Path source;
        try {
            source = artifactService.resolveDownloadPath(sessionId, fileName);
        } catch (ArtifactService.ArtifactNotFoundException | ArtifactService.ArtifactAccessException e) {
            throw new ChatRejectedException(HttpStatus.NOT_FOUND, FILE_NOT_FOUND);
        }
        Path target = dir.resolve(source.getFileName().toString()).normalize();
        if (!target.getParent().equals(dir)) throw new ChatRejectedException(HttpStatus.NOT_FOUND, FILE_NOT_FOUND);
        return Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path sharesRoot(String tenantId) {
        String base = workspaceProperties.getBase();
        if (tenantId == null || !TENANT_ID.matcher(tenantId).matches() || base == null || base.isBlank()) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, SHARE_NOT_CONFIGURED);
        }
        Path shares = Path.of(base, SHARES_DIR).toAbsolutePath().normalize();
        Path root = shares.resolve(tenantId).normalize();
        if (!root.getParent().equals(shares)) throw new ChatRejectedException(HttpStatus.CONFLICT, SHARE_NOT_CONFIGURED);
        return root;
    }

    private int ttl(Integer requested) {
        if (requested == null) return settings.ttlDays();
        if (requested < 1 || requested > settings.maxTtlDays()) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, INVALID_TTL);
        }
        return requested;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ChatShareEntity.Kind kind(String kind) {
        try {
            return ChatShareEntity.Kind.valueOf(kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, INVALID_KIND);
        }
    }

    private static Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Map<String, Object> view(ChatShareEntity s, String tenantId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String status = s.getRevokedAt() != null ? "REVOKED"
                : (s.getExpiresAt().isAfter(now) && s.getFilesPurgedAt() == null ? "ACTIVE" : "EXPIRED");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("share_id", s.getId().toString());
        m.put("url", settings.publicBaseUrl() + "/share/" + tenantId + "/" + s.getToken());
        m.put("kind", s.getKind().name());
        m.put("file_name", s.getFileName());
        m.put("status", status);
        m.put("view_count", s.getViewCount());
        m.put("expires_at", s.getExpiresAt().format(ISO));
        m.put("created_at", s.getCreatedAt().format(ISO));
        m.put("revoked_at", s.getRevokedAt() == null ? null : s.getRevokedAt().format(ISO));
        return m;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Share snapshot delete failed: {} ({})", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Share snapshot walk failed: {} ({})", dir, e.getMessage());
        }
    }
}
