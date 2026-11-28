package com.platform.session;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.domain.ChatGeneratedImageEntity;
import com.platform.domain.ChatProjectEntity;
import com.platform.domain.ChatProjectFileEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-204 TC-CHT-163 ~ 166: 대화 완전 삭제 순서, 조건과 반복 호출, 사용자 자료, 보관 기간 지난 것. */
@ExtendWith(MockitoExtension.class)
class ConversationPurgeServiceTest {

    @Mock ConversationSessionRepository sessionRepository;
    @Mock ConversationMessageRepository messageRepository;
    @Mock ChatAttachmentRepository attachmentRepository;
    @Mock ChatGeneratedImageRepository imageRepository;
    @Mock ChatDocumentRepository documentRepository;
    @Mock ChatDocumentVersionRepository versionRepository;
    @Mock ChatShareRepository shareRepository;
    @Mock MessageFeedbackRepository feedbackRepository;
    @Mock SessionBriefRepository briefRepository;
    @Mock ConversationMemoryRepository memoryRepository;
    @Mock ToolResultStorageRepository toolResultRepository;
    @Mock PendingApprovalRepository approvalRepository;
    @Mock TraceRepository traceRepository;
    @Mock ToolExecutionLogRepository toolLogRepository;
    @Mock WidgetUserPreferenceRepository preferenceRepository;
    @Mock ChatProjectRepository projectRepository;
    @Mock ChatProjectFileRepository projectFileRepository;
    @Mock ChatProjectService projectService;
    @Mock ChatShareService shareService;
    @Mock StorageService storage;
    @Mock CancellationRegistry cancellationRegistry;
    @Mock SessionStore sessionStore;
    @Mock AuditLogger auditLogger;
    @Mock PlatformSettingsService settings;

    ConversationPurgeService service;

    @BeforeEach
    void setUp() {
        service = new ConversationPurgeService(sessionRepository, messageRepository, attachmentRepository,
                imageRepository, documentRepository, versionRepository, shareRepository, feedbackRepository,
                briefRepository, memoryRepository, toolResultRepository, approvalRepository, traceRepository,
                toolLogRepository, preferenceRepository, projectRepository, projectFileRepository, projectService,
                shareService, storage, cancellationRegistry, sessionStore, auditLogger, settings,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        lenient().when(settings.getInt(eq(ConversationPurgeService.BATCH_LIMIT_KEY), anyInt())).thenReturn(200);
        lenient().when(settings.getInt(eq(ConversationPurgeService.RETENTION_DAYS_KEY), anyInt())).thenReturn(30);
        lenient().when(shareService.deleteSnapshots(anyString())).thenReturn(new ChatShareService.SnapshotPurge(0, 0));
        lenient().when(attachmentRepository.findBySessionId(anyString())).thenReturn(List.of());
        lenient().when(imageRepository.findBySessionId(anyString())).thenReturn(List.of());
        lenient().when(documentRepository.findBySessionIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
    }

    private static ConversationSessionEntity session(String id, String userId) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setSessionId(id);
        s.setUserId(userId);
        return s;
    }

    private static ConversationPurgeRequest byUser(String ref, Integer limit) {
        return new ConversationPurgeRequest(ref, null, null, null, null, limit);
    }

    @Test
    void sessionPurgeDeletesFilesBeforeRowsAndClearsRedis() {
        ChatAttachmentEntity att = new ChatAttachmentEntity();
        att.setStoragePath("widget-attachments/s1/a.pdf");
        ChatGeneratedImageEntity img = new ChatGeneratedImageEntity();
        img.setStoragePath("images/s1/i.png");
        ChatDocumentEntity doc = new ChatDocumentEntity();
        doc.setId(UUID.randomUUID());
        ChatDocumentVersionEntity v1 = new ChatDocumentVersionEntity();
        v1.setStoragePath("docs/s1/v1.md");
        v1.setSourceStoragePath("docs/s1/src.docx");
        ChatDocumentVersionEntity v2 = new ChatDocumentVersionEntity();
        v2.setStoragePath("docs/s1/v2.md");
        when(attachmentRepository.findBySessionId("s1")).thenReturn(List.of(att));
        when(imageRepository.findBySessionId("s1")).thenReturn(List.of(img));
        when(documentRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of(doc));
        when(versionRepository.findByDocumentIdIn(List.of(doc.getId()))).thenReturn(List.of(v1, v2));
        when(shareService.deleteSnapshots("s1")).thenReturn(new ChatShareService.SnapshotPurge(1, 0));
        when(storage.delete(anyString())).thenReturn(true);
        when(messageRepository.purgeBySessionId("s1")).thenReturn(12);
        when(versionRepository.purgeByDocumentIds(List.of(doc.getId()))).thenReturn(2);

        ConversationPurgeService.Counters c = new ConversationPurgeService.Counters();
        service.purgeSession("s1", c);

        InOrder order = inOrder(cancellationRegistry, storage, messageRepository, sessionRepository, sessionStore);
        order.verify(cancellationRegistry).cancel("s1");
        order.verify(storage, times(5)).delete(anyString());
        order.verify(messageRepository).purgeBySessionId("s1");
        order.verify(sessionRepository).deleteBySessionId("s1");
        order.verify(sessionStore).clearSession("s1");
        verify(attachmentRepository).purgeBySessionId("s1");
        verify(imageRepository).purgeBySessionId("s1");
        verify(documentRepository).purgeBySessionId("s1");
        verify(shareRepository).purgeBySessionId("s1");
        verify(feedbackRepository).purgeBySessionId("s1");
        verify(briefRepository).purgeBySessionId("s1");
        verify(memoryRepository).purgeBySessionId("s1");
        verify(toolResultRepository).purgeBySessionId("s1");
        verify(approvalRepository).purgeBySessionId("s1");
        verify(traceRepository).purgeBySessionId("s1");
        verify(toolLogRepository).purgeBySessionId("s1");
        assertThat(c.deleted).isEqualTo(1);
        assertThat(c.failed).isEmpty();
        assertThat(c.messages).isEqualTo(12);
        assertThat(c.documentVersions).isEqualTo(2);
        assertThat(c.filesDeleted).isEqualTo(6);
    }

    @Test
    void sessionWithUndeletableFileKeepsRowsAndIsReportedFailed() {
        ChatAttachmentEntity att = new ChatAttachmentEntity();
        att.setStoragePath("widget-attachments/s2/a.pdf");
        when(attachmentRepository.findBySessionId("s2")).thenReturn(List.of(att));
        when(storage.delete("widget-attachments/s2/a.pdf")).thenReturn(false);
        when(storage.exists("widget-attachments/s2/a.pdf")).thenReturn(true);

        ConversationPurgeService.Counters c = new ConversationPurgeService.Counters();
        service.purgeSession("s2", c);

        verify(messageRepository, never()).purgeBySessionId(anyString());
        verify(sessionRepository, never()).deleteBySessionId(anyString());
        verify(sessionStore, never()).clearSession(anyString());
        assertThat(c.failed).containsExactly("s2");
        assertThat(c.filesFailed).isEqualTo(1);
        assertThat(c.sessions(1, 0).failedSessionIds()).containsExactly("s2");
    }

    @Test
    void alreadyMissingFileCountsAsGone() {
        ChatAttachmentEntity att = new ChatAttachmentEntity();
        att.setStoragePath("gone.pdf");
        when(attachmentRepository.findBySessionId("s3")).thenReturn(List.of(att));
        when(storage.delete("gone.pdf")).thenReturn(false);
        when(storage.exists("gone.pdf")).thenReturn(false);

        ConversationPurgeService.Counters c = new ConversationPurgeService.Counters();
        service.purgeSession("s3", c);

        verify(sessionRepository).deleteBySessionId("s3");
        assertThat(c.deleted).isEqualTo(1);
        assertThat(c.filesDeleted).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchLimitLeavesRemainingAndUsersWaitUntilZero() {
        when(sessionRepository.count(any(Specification.class))).thenReturn(7L, 4L);
        when(sessionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session("a", "u1"), session("b", "u1"), session("c", "u1"))));

        ConversationPurgeResult r = service.purge(byUser("u1", 3), "system-k1");

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository).findAll(any(Specification.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
        assertThat(r.sessions().matched()).isEqualTo(7);
        assertThat(r.sessions().deleted()).isEqualTo(3);
        assertThat(r.sessions().remaining()).isEqualTo(4);
        assertThat(r.completed()).isFalse();
        assertThat(r.users()).isNull();
        verify(preferenceRepository, never()).deleteById(anyString());
        verify(auditLogger).log(eq(ConversationPurgeService.AUDIT_ACTION), anyString(), eq("system-k1"), isNull(),
                any(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestedLimitIsCappedByBatchSetting() {
        when(settings.getInt(eq(ConversationPurgeService.BATCH_LIMIT_KEY), anyInt())).thenReturn(2);
        when(sessionRepository.count(any(Specification.class))).thenReturn(0L);
        when(sessionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.purge(new ConversationPurgeRequest(null, "k", null, null, null, 500), null);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository).findAll(any(Specification.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lastCallPurgesUserDataAndClearsAskerNames() {
        when(sessionRepository.count(any(Specification.class))).thenReturn(1L, 0L);
        when(sessionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session("a", "u1"))));
        when(preferenceRepository.existsById("u1")).thenReturn(true);
        when(memoryRepository.purgeByUserId("u1")).thenReturn(3);
        when(feedbackRepository.purgeByUserRef("u1")).thenReturn(2);
        when(messageRepository.clearAskerName("u1")).thenReturn(5);
        ChatProjectEntity project = new ChatProjectEntity();
        project.setId(UUID.randomUUID());
        project.setOwnerRef("u1");
        when(projectRepository.findByOwnerRef("u1")).thenReturn(List.of(project));
        when(projectFileRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()))
                .thenReturn(List.of(new ChatProjectFileEntity(), new ChatProjectFileEntity()));

        ConversationPurgeResult r = service.purge(byUser("u1", null), "admin-1");

        assertThat(r.completed()).isTrue();
        assertThat(r.sessions().remaining()).isZero();
        assertThat(r.users()).isEqualTo(new ConversationPurgeResult.Users(1, 1, 3, 1, 2, 2, 5));
        verify(preferenceRepository).deleteById("u1");
        verify(projectService).delete("u1", project.getId().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shareKeyOnlyRequestNeverTouchesUserData() {
        when(sessionRepository.count(any(Specification.class))).thenReturn(1L, 0L);
        when(sessionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session("a", "u1"))));

        ConversationPurgeResult r = service.purge(new ConversationPurgeRequest(null, "k:1", null, null, null, null), null);

        assertThat(r.completed()).isTrue();
        assertThat(r.users()).isNull();
        verify(memoryRepository, never()).purgeByUserId(anyString());
        verify(messageRepository, never()).clearAskerName(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void prefixRequestEscapesLikeCharactersAndCollectsUsers() {
        when(sessionRepository.count(any(Specification.class))).thenReturn(0L, 0L);
        when(sessionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(sessionRepository.findUserIdsByPrefix("off!_1:%")).thenReturn(List.of("off_1:u1"));
        when(preferenceRepository.findUserRefsByPrefix("off!_1:%")).thenReturn(List.of("off_1:u2"));
        when(memoryRepository.findUserIdsByPrefix("off!_1:%")).thenReturn(List.of("off_1:u1"));
        when(feedbackRepository.findUserRefsByPrefix("off!_1:%")).thenReturn(List.of());
        when(projectRepository.findByOwnerRefPrefix("off!_1:%")).thenReturn(List.of());
        when(memoryRepository.purgeByUserIdPrefix("off!_1:%")).thenReturn(4);

        ConversationPurgeResult r = service.purge(new ConversationPurgeRequest(null, null, "off_1:", null, null, null), null);

        assertThat(r.users().matched()).isEqualTo(2);
        assertThat(r.users().preferences()).isEqualTo(1);
        assertThat(r.users().memories()).isEqualTo(4);
        verify(preferenceRepository).deleteAllById(List.of("off_1:u2"));
        verify(messageRepository).clearAskerNameByPrefix("off!_1:%");
        assertThat(ConversationPurgeService.likePrefix("a%b_c!d")).isEqualTo("a!%b!_c!!d%");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dryRunCountsOnlyAndWritesNoAudit() {
        when(sessionRepository.count(any(Specification.class))).thenReturn(4L);
        when(sessionRepository.findUserIdsByPrefix("o1:%")).thenReturn(List.of("o1:a", "o1:b"));
        when(preferenceRepository.findUserRefsByPrefix("o1:%")).thenReturn(List.of("o1:a"));
        when(memoryRepository.findUserIdsByPrefix("o1:%")).thenReturn(List.of());
        when(feedbackRepository.findUserRefsByPrefix("o1:%")).thenReturn(List.of());
        when(projectRepository.findByOwnerRefPrefix("o1:%")).thenReturn(List.of());

        ConversationPurgeResult r = service.purge(new ConversationPurgeRequest(null, null, "o1:", null, true, null), null);

        assertThat(r.dryRun()).isTrue();
        assertThat(r.sessions().matched()).isEqualTo(4);
        assertThat(r.sessions().deleted()).isZero();
        assertThat(r.users().matched()).isEqualTo(2);
        assertThat(r.rows()).isNull();
        verify(sessionRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(auditLogger, never()).log(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void expiredPurgeRemovesOnlyThingsPastRetention() {
        ConversationSessionEntity old = session("old", "u");
        when(sessionRepository.findSoftDeletedBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(old));
        ConversationMessageEntity m1 = new ConversationMessageEntity();
        m1.setId(UUID.randomUUID());
        ConversationMessageEntity m2 = new ConversationMessageEntity();
        m2.setId(UUID.randomUUID());
        when(messageRepository.findSoftDeletedBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(m1, m2));
        ChatGeneratedImageEntity img = new ChatGeneratedImageEntity();
        img.setId(UUID.randomUUID());
        img.setStoragePath("img.png");
        when(imageRepository.findByDeletedAtBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(img));
        ChatDocumentVersionEntity okVersion = new ChatDocumentVersionEntity();
        ReflectionTestUtils.setField(okVersion, "id", 1L);
        okVersion.setStoragePath("v1.md");
        ChatDocumentVersionEntity stuckVersion = new ChatDocumentVersionEntity();
        ReflectionTestUtils.setField(stuckVersion, "id", 2L);
        stuckVersion.setStoragePath("stuck.md");
        when(versionRepository.findByDeletedAtBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(okVersion, stuckVersion));
        when(storage.delete("img.png")).thenReturn(true);
        when(storage.delete("v1.md")).thenReturn(true);
        when(storage.delete("stuck.md")).thenReturn(false);
        when(storage.exists("stuck.md")).thenReturn(true);
        when(messageRepository.deleteByIds(List.of(m1.getId(), m2.getId()))).thenReturn(2);
        when(imageRepository.deleteByIds(List.of(img.getId()))).thenReturn(1);
        when(versionRepository.deleteByIds(List.of(1L))).thenReturn(1);

        ConversationPurgeService.ExpiredPurge r = service.purgeExpiredForCurrentTenant();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sessionRepository).findSoftDeletedBefore(cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getValue()).isBetween(OffsetDateTime.now().minusDays(30).minusMinutes(1),
                OffsetDateTime.now().minusDays(30).plusMinutes(1));
        verify(sessionRepository).deleteBySessionId("old");
        verify(versionRepository, never()).deleteByIds(List.of(1L, 2L));
        assertThat(r).isEqualTo(new ConversationPurgeService.ExpiredPurge(1, 0, 2, 1, 1, 1));
        assertThat(r.touchedAnything()).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void criteriaUnionBuildsOnePredicatePerGivenField() {
        Root<ConversationSessionEntity> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path path = mock(Path.class);
        Predicate p = mock(Predicate.class);
        when(root.get(anyString())).thenReturn(path);
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(p);
        when(cb.like(any(Expression.class), anyString(), eq('!'))).thenReturn(p);

        ConversationPurgeService.criteria(new ConversationPurgeRequest("u", "k", "u_p", "kp:", null, null))
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).or(p, p, p, p);
        verify(cb).like(path, "u!_p%", '!');
        verify(cb).like(path, "kp:%", '!');
        verify(root, times(2)).get("userId");
        verify(root, times(2)).get("shareKey");
    }
}
