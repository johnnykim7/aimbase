package com.platform.session;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.domain.ChatGeneratedImageEntity;
import com.platform.domain.ChatProjectEntity;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.policy.AuditLogger;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.repository.ChatDocumentRepository;
import com.platform.repository.ChatDocumentVersionRepository;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.ChatProjectFileRepository;
import com.platform.repository.ChatProjectRepository;
import com.platform.repository.ChatShareRepository;
import com.platform.repository.ConversationMemoryRepository;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.repository.MessageFeedbackRepository;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.SessionBriefRepository;
import com.platform.repository.ToolExecutionLogRepository;
import com.platform.repository.ToolResultStorageRepository;
import com.platform.repository.TraceRepository;
import com.platform.repository.WidgetUserPreferenceRepository;
import com.platform.share.ChatShareService;
import com.platform.storage.StorageService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * CR-204 PRD-650, PRD-651: 대화 완전 삭제.
 *
 * <p>대화 한 건은 파일을 먼저 지우고 DB 는 한 트랜잭션으로 지운다(BIZ-292). 파일이 남으면 행도 남겨 실패로 센다.
 * 반대 순서면 행이 사라진 파일을 다시 찾을 수 없다. 조건(BIZ-294)은 합집합이고 한 번에 상한만큼만 지운다.
 * 사용자 자료(BIZ-293)는 그 조건의 대화가 다 지워진 호출에서 한 번 지운다.</p>
 */
@Service
public class ConversationPurgeService {

    private static final Logger log = LoggerFactory.getLogger(ConversationPurgeService.class);

    public static final String RETENTION_DAYS_KEY = "chat.purge.retention-days";
    public static final int RETENTION_DAYS_DEFAULT = 30;
    public static final String BATCH_LIMIT_KEY = "chat.purge.batch-limit";
    public static final int BATCH_LIMIT_DEFAULT = 200;
    public static final String AUDIT_ACTION = "conversation_purge";
    /** LIKE 접두어의 이스케이프 글자. 백슬래시는 JPQL 과 SQL 에서 뜻이 갈려 쓰지 않는다. */
    public static final char LIKE_ESCAPE = '!';
    private static final int FAILED_IDS_SHOWN = 20;

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatGeneratedImageRepository imageRepository;
    private final ChatDocumentRepository documentRepository;
    private final ChatDocumentVersionRepository versionRepository;
    private final ChatShareRepository shareRepository;
    private final MessageFeedbackRepository feedbackRepository;
    private final SessionBriefRepository briefRepository;
    private final ConversationMemoryRepository memoryRepository;
    private final ToolResultStorageRepository toolResultRepository;
    private final PendingApprovalRepository approvalRepository;
    private final TraceRepository traceRepository;
    private final ToolExecutionLogRepository toolLogRepository;
    private final WidgetUserPreferenceRepository preferenceRepository;
    private final ChatProjectRepository projectRepository;
    private final ChatProjectFileRepository projectFileRepository;
    private final ChatProjectService projectService;
    private final ChatShareService shareService;
    private final StorageService storage;
    private final CancellationRegistry cancellationRegistry;
    private final SessionStore sessionStore;
    private final AuditLogger auditLogger;
    private final PlatformSettingsService settings;
    private final TransactionTemplate transactionTemplate;

    public ConversationPurgeService(ConversationSessionRepository sessionRepository,
                                    ConversationMessageRepository messageRepository,
                                    ChatAttachmentRepository attachmentRepository,
                                    ChatGeneratedImageRepository imageRepository,
                                    ChatDocumentRepository documentRepository,
                                    ChatDocumentVersionRepository versionRepository,
                                    ChatShareRepository shareRepository,
                                    MessageFeedbackRepository feedbackRepository,
                                    SessionBriefRepository briefRepository,
                                    ConversationMemoryRepository memoryRepository,
                                    ToolResultStorageRepository toolResultRepository,
                                    PendingApprovalRepository approvalRepository,
                                    TraceRepository traceRepository,
                                    ToolExecutionLogRepository toolLogRepository,
                                    WidgetUserPreferenceRepository preferenceRepository,
                                    ChatProjectRepository projectRepository,
                                    ChatProjectFileRepository projectFileRepository,
                                    ChatProjectService projectService,
                                    ChatShareService shareService,
                                    StorageService storage,
                                    CancellationRegistry cancellationRegistry,
                                    SessionStore sessionStore,
                                    AuditLogger auditLogger,
                                    PlatformSettingsService settings,
                                    TransactionTemplate transactionTemplate) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.imageRepository = imageRepository;
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.shareRepository = shareRepository;
        this.feedbackRepository = feedbackRepository;
        this.briefRepository = briefRepository;
        this.memoryRepository = memoryRepository;
        this.toolResultRepository = toolResultRepository;
        this.approvalRepository = approvalRepository;
        this.traceRepository = traceRepository;
        this.toolLogRepository = toolLogRepository;
        this.preferenceRepository = preferenceRepository;
        this.projectRepository = projectRepository;
        this.projectFileRepository = projectFileRepository;
        this.projectService = projectService;
        this.shareService = shareService;
        this.storage = storage;
        this.cancellationRegistry = cancellationRegistry;
        this.sessionStore = sessionStore;
        this.auditLogger = auditLogger;
        this.settings = settings;
        this.transactionTemplate = transactionTemplate;
    }

    /** API 한 번. 조건은 검증된 것이어야 한다. */
    public ConversationPurgeResult purge(ConversationPurgeRequest request, String callerId) {
        Specification<ConversationSessionEntity> spec = criteria(request);
        long matched = sessionRepository.count(spec);
        if (request.isDryRun()) {
            ConversationPurgeResult.Users users = request.hasUserCriteria()
                    ? new ConversationPurgeResult.Users(collectUserRefs(request).size(), 0, 0, 0, 0, 0, 0)
                    : null;
            return new ConversationPurgeResult(true, false,
                    new ConversationPurgeResult.Sessions(matched, 0, matched, 0, List.of()), null, null, users);
        }

        int limit = Math.min(request.limit() == null ? batchLimit() : request.limit(), batchLimit());
        Counters c = new Counters();
        List<ConversationSessionEntity> batch = sessionRepository
                .findAll(spec, PageRequest.of(0, limit, Sort.by("createdAt"))).getContent();
        for (ConversationSessionEntity s : batch) {
            purgeSession(s.getSessionId(), c);
        }
        long remaining = Math.max(0, sessionRepository.count(spec) - c.failed.size());

        ConversationPurgeResult.Users users = null;
        if (request.hasUserCriteria() && remaining == 0 && c.failed.isEmpty()) {
            users = purgeUsers(request);
        }
        boolean completed = remaining == 0 && c.failed.isEmpty();
        ConversationPurgeResult result = new ConversationPurgeResult(false, completed,
                c.sessions(matched, remaining), c.rows(), c.files(), users);
        audit(request, callerId, result);
        return result;
    }

    /** 스케줄러 한 회차의 결과. */
    public record ExpiredPurge(int sessionsDeleted, int sessionsFailed, int messagesDeleted,
                               int imagesDeleted, int versionsDeleted, int filesFailed) {
        public boolean touchedAnything() {
            return sessionsDeleted + sessionsFailed + messagesDeleted + imagesDeleted + versionsDeleted + filesFailed > 0;
        }
    }

    /** BIZ-295: 지금 테넌트에서 삭제 표시 뒤 보관 기간이 지난 것을 완전 삭제한다. 스케줄러가 테넌트마다 부른다. */
    public ExpiredPurge purgeExpiredForCurrentTenant() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays());
        int batch = batchLimit();
        Counters c = new Counters();
        for (ConversationSessionEntity s : sessionRepository.findSoftDeletedBefore(cutoff, PageRequest.of(0, batch))) {
            purgeSession(s.getSessionId(), c);
        }

        List<ConversationMessageEntity> messages = messageRepository.findSoftDeletedBefore(cutoff, PageRequest.of(0, batch));
        List<ChatGeneratedImageEntity> images = imageRepository.findByDeletedAtBefore(cutoff, PageRequest.of(0, batch));
        List<ChatDocumentVersionEntity> versions = versionRepository.findByDeletedAtBefore(cutoff, PageRequest.of(0, batch));

        List<UUID> imageIds = new ArrayList<>();
        for (ChatGeneratedImageEntity img : images) {
            if (deleteFile(img.getStoragePath(), c)) imageIds.add(img.getId());
        }
        List<Long> versionIds = new ArrayList<>();
        for (ChatDocumentVersionEntity v : versions) {
            boolean gone = deleteFile(v.getStoragePath(), c) & deleteFile(v.getSourceStoragePath(), c);
            if (gone) versionIds.add(v.getId());
        }
        List<UUID> messageIds = messages.stream().map(ConversationMessageEntity::getId).toList();
        int[] rows = new int[3];
        if (!imageIds.isEmpty() || !versionIds.isEmpty() || !messageIds.isEmpty()) {
            transactionTemplate.executeWithoutResult(status -> {
                rows[0] = imageIds.isEmpty() ? 0 : imageRepository.deleteByIds(imageIds);
                rows[1] = versionIds.isEmpty() ? 0 : versionRepository.deleteByIds(versionIds);
                rows[2] = messageIds.isEmpty() ? 0 : messageRepository.deleteByIds(messageIds);
            });
        }
        return new ExpiredPurge(c.deleted, c.failed.size(), rows[2], rows[0], rows[1], c.filesFailed);
    }

    /**
     * 대화 한 건의 완전 삭제(BIZ-292). 답을 멈추고, 파일을 지우고, 파일이 다 없어졌을 때만 행을 한 트랜잭션으로 지운다.
     * 파일이 남은 대화는 행을 남기고 실패로 센다. 다음 호출이나 다음 회차가 다시 본다.
     */
    void purgeSession(String sessionId, Counters c) {
        cancellationRegistry.cancel(sessionId);

        List<ChatAttachmentEntity> attachments = attachmentRepository.findBySessionId(sessionId);
        List<ChatGeneratedImageEntity> images = imageRepository.findBySessionId(sessionId);
        List<ChatDocumentEntity> documents = documentRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<UUID> documentIds = documents.stream().map(ChatDocumentEntity::getId).toList();
        List<ChatDocumentVersionEntity> versions = documentIds.isEmpty()
                ? List.of() : versionRepository.findByDocumentIdIn(documentIds);

        boolean clean = true;
        for (ChatAttachmentEntity a : attachments) clean &= deleteFile(a.getStoragePath(), c);
        for (ChatGeneratedImageEntity img : images) clean &= deleteFile(img.getStoragePath(), c);
        for (ChatDocumentVersionEntity v : versions) {
            clean &= deleteFile(v.getStoragePath(), c);
            clean &= deleteFile(v.getSourceStoragePath(), c);
        }
        ChatShareService.SnapshotPurge snapshots = shareService.deleteSnapshots(sessionId);
        c.filesDeleted += snapshots.deleted();
        c.filesFailed += snapshots.failed();
        clean &= snapshots.failed() == 0;
        if (!clean) {
            c.failed.add(sessionId);
            log.warn("Conversation purge kept rows of session {} because a file could not be deleted", sessionId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                c.messages += messageRepository.purgeBySessionId(sessionId);
                c.attachments += attachmentRepository.purgeBySessionId(sessionId);
                c.generatedImages += imageRepository.purgeBySessionId(sessionId);
                if (!documentIds.isEmpty()) c.documentVersions += versionRepository.purgeByDocumentIds(documentIds);
                c.documents += documentRepository.purgeBySessionId(sessionId);
                c.shares += shareRepository.purgeBySessionId(sessionId);
                c.feedback += feedbackRepository.purgeBySessionId(sessionId);
                c.briefs += briefRepository.purgeBySessionId(sessionId);
                c.memories += memoryRepository.purgeBySessionId(sessionId);
                c.toolResults += toolResultRepository.purgeBySessionId(sessionId);
                c.approvals += approvalRepository.purgeBySessionId(sessionId);
                c.traces += traceRepository.purgeBySessionId(sessionId);
                c.toolLogs += toolLogRepository.purgeBySessionId(sessionId);
                sessionRepository.deleteBySessionId(sessionId);
            });
        } catch (RuntimeException e) {
            c.failed.add(sessionId);
            log.error("Conversation purge failed for session {}", sessionId, e);
            return;
        }
        sessionStore.clearSession(sessionId);
        c.deleted++;
    }

    /** 파일 하나. 지웠거나 이미 없으면 true. 남아 있으면 false 로 세고 대화의 행을 남기게 한다. */
    private boolean deleteFile(String path, Counters c) {
        if (path == null || path.isBlank()) return true;
        try {
            if (storage.delete(path)) {
                c.filesDeleted++;
                return true;
            }
            if (!storage.exists(path)) return true;
        } catch (RuntimeException e) {
            log.warn("Conversation purge storage delete failed path={} ({})", path, e.getMessage());
        }
        c.filesFailed++;
        return false;
    }

    /** BIZ-293: 그 사람(들)의 자료. 대화가 다 지워진 뒤 한 트랜잭션으로. */
    private ConversationPurgeResult.Users purgeUsers(ConversationPurgeRequest request) {
        Set<String> refs = collectUserRefs(request);
        return transactionTemplate.execute(status -> {
            int preferences = 0;
            int memories = 0;
            int feedback = 0;
            int askerNames = 0;
            Map<UUID, ChatProjectEntity> projects = new LinkedHashMap<>();
            if (request.userRef() != null) {
                String ref = request.userRef();
                if (preferenceRepository.existsById(ref)) {
                    preferenceRepository.deleteById(ref);
                    preferences++;
                }
                memories += memoryRepository.purgeByUserId(ref);
                feedback += feedbackRepository.purgeByUserRef(ref);
                askerNames += messageRepository.clearAskerName(ref);
                projectRepository.findByOwnerRef(ref).forEach(p -> projects.put(p.getId(), p));
            }
            if (request.userRefPrefix() != null) {
                String pattern = likePrefix(request.userRefPrefix());
                List<String> prefRefs = preferenceRepository.findUserRefsByPrefix(pattern);
                if (!prefRefs.isEmpty()) {
                    preferenceRepository.deleteAllById(prefRefs);
                    preferences += prefRefs.size();
                }
                memories += memoryRepository.purgeByUserIdPrefix(pattern);
                feedback += feedbackRepository.purgeByUserRefPrefix(pattern);
                askerNames += messageRepository.clearAskerNameByPrefix(pattern);
                projectRepository.findByOwnerRefPrefix(pattern).forEach(p -> projects.put(p.getId(), p));
            }
            int projectFiles = 0;
            for (ChatProjectEntity p : projects.values()) {
                projectFiles += projectFileRepository.findByProjectIdOrderByCreatedAtAsc(p.getId()).size();
                projectService.delete(p.getOwnerRef(), p.getId().toString());
            }
            return new ConversationPurgeResult.Users(refs.size(), preferences, memories, projects.size(),
                    projectFiles, feedback, askerNames);
        });
    }

    /** 조건에 걸리는 사람. 대화 주인, 선호, 기억, 평가, 프로젝트에서 모은 서로 다른 user_ref. */
    Set<String> collectUserRefs(ConversationPurgeRequest request) {
        Set<String> refs = new TreeSet<>();
        if (request.userRef() != null) refs.add(request.userRef());
        if (request.userRefPrefix() != null) {
            String pattern = likePrefix(request.userRefPrefix());
            refs.addAll(sessionRepository.findUserIdsByPrefix(pattern));
            refs.addAll(preferenceRepository.findUserRefsByPrefix(pattern));
            refs.addAll(memoryRepository.findUserIdsByPrefix(pattern));
            refs.addAll(feedbackRepository.findUserRefsByPrefix(pattern));
            projectRepository.findByOwnerRefPrefix(pattern).forEach(p -> refs.add(p.getOwnerRef()));
        }
        return refs;
    }

    /** BIZ-294: 네 조건의 합집합. 접두어는 글자 그대로 앞부분 일치다. */
    static Specification<ConversationSessionEntity> criteria(ConversationPurgeRequest request) {
        return (root, query, cb) -> {
            List<Predicate> any = new ArrayList<>();
            if (request.userRef() != null) any.add(cb.equal(root.get("userId"), request.userRef()));
            if (request.shareKey() != null) any.add(cb.equal(root.get("shareKey"), request.shareKey()));
            if (request.userRefPrefix() != null) {
                any.add(cb.like(root.get("userId"), likePrefix(request.userRefPrefix()), LIKE_ESCAPE));
            }
            if (request.shareKeyPrefix() != null) {
                any.add(cb.like(root.get("shareKey"), likePrefix(request.shareKeyPrefix()), LIKE_ESCAPE));
            }
            return cb.or(any.toArray(new Predicate[0]));
        };
    }

    /** 접두어를 LIKE 패턴으로. '!', '%', '_' 는 글자 그대로 맞추게 '!' 로 이스케이프한다. */
    static String likePrefix(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 1);
        for (char ch : prefix.toCharArray()) {
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') sb.append(LIKE_ESCAPE);
            sb.append(ch);
        }
        return sb.append('%').toString();
    }

    private int retentionDays() {
        return Math.max(1, settings.getInt(RETENTION_DAYS_KEY, RETENTION_DAYS_DEFAULT));
    }

    private int batchLimit() {
        return Math.max(1, settings.getInt(BATCH_LIMIT_KEY, BATCH_LIMIT_DEFAULT));
    }

    /** BIZ-296: 호출마다 조건과 건수를 감사 기록에. 미리 보기는 남기지 않는다. */
    private void audit(ConversationPurgeRequest request, String callerId, ConversationPurgeResult r) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("criteria", request.criteria());
        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("matched", r.sessions().matched());
        sessions.put("deleted", r.sessions().deleted());
        sessions.put("remaining", r.sessions().remaining());
        sessions.put("failed", r.sessions().failed());
        detail.put("sessions", sessions);
        detail.put("files", Map.of("deleted", r.files().deleted(), "failed", r.files().failed()));
        if (r.users() != null) {
            Map<String, Object> users = new LinkedHashMap<>();
            users.put("matched", r.users().matched());
            users.put("preferences", r.users().preferences());
            users.put("memories", r.users().memories());
            users.put("projects", r.users().projects());
            users.put("feedback", r.users().feedback());
            users.put("asker_names_cleared", r.users().askerNamesCleared());
            detail.put("users", users);
        }
        auditLogger.log(AUDIT_ACTION, request.criteria().toString(), callerId, null, detail, null);
    }

    /** 한 호출 안의 집계. 람다 안에서 더하므로 필드로 둔다. */
    static final class Counters {
        int deleted;
        final List<String> failed = new ArrayList<>();
        int messages;
        int attachments;
        int generatedImages;
        int documents;
        int documentVersions;
        int shares;
        int feedback;
        int memories;
        int briefs;
        int approvals;
        int toolResults;
        int traces;
        int toolLogs;
        int filesDeleted;
        int filesFailed;

        ConversationPurgeResult.Sessions sessions(long matched, long remaining) {
            List<String> shown = failed.size() > FAILED_IDS_SHOWN ? failed.subList(0, FAILED_IDS_SHOWN) : failed;
            return new ConversationPurgeResult.Sessions(matched, deleted, remaining, failed.size(), List.copyOf(shown));
        }

        ConversationPurgeResult.Rows rows() {
            return new ConversationPurgeResult.Rows(messages, attachments, generatedImages, documents, documentVersions,
                    shares, feedback, memories, briefs, approvals, toolResults, traces, toolLogs);
        }

        ConversationPurgeResult.Files files() {
            return new ConversationPurgeResult.Files(filesDeleted, filesFailed);
        }
    }
}
