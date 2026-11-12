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
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TC-CHT-045 ~ TC-CHT-047 (CR-178): 위젯 프로젝트 검사와 주인, 삭제, 프로젝트 자료.
 * 프로젝트 삭제 때 대화의 chat_project_id 를 비우는 것은 외래키(ON DELETE SET NULL)라 실제 DB 에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatProjectServiceTest {

    private static final String OWNER = "user-a";

    @Mock private ChatProjectRepository projectRepository;
    @Mock private ChatProjectFileRepository fileRepository;
    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private StorageService storage;
    @Mock private PlatformSettingsService settings;

    private ChatProjectService service;

    @BeforeEach
    void setUp() {
        service = new ChatProjectService(projectRepository, fileRepository, sessionRepository, attachmentService,
                storage, settings);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(projectRepository.save(any(ChatProjectEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // TC-CHT-045

    @Test
    void blank_or_too_long_name_is_rejected() {
        assertRejected(() -> service.create(OWNER, Map.of("name", "   ")),
                HttpStatus.BAD_REQUEST, "project_invalid:name");
        assertRejected(() -> service.create(OWNER, Map.of("name", "a".repeat(ChatProjectService.NAME_MAX + 1))),
                HttpStatus.BAD_REQUEST, "project_invalid:name");
        verify(projectRepository, never()).save(any());
    }

    @Test
    void instructions_over_the_setting_are_rejected() {
        when(settings.getInt(eq("widget.project-instructions-max-chars"), anyInt())).thenReturn(10);

        assertRejected(() -> service.create(OWNER, Map.of("name", "보고서", "instructions", "a".repeat(11))),
                HttpStatus.BAD_REQUEST, "project_invalid:instructions");
        Map<String, Object> ok = service.create(OWNER, Map.of("name", "보고서", "instructions", "a".repeat(10)));
        assertThat(ok.get("has_instructions")).isEqualTo(true);
    }

    @Test
    void someone_elses_project_is_not_found_for_read_change_and_delete() {
        UUID id = UUID.randomUUID();
        when(projectRepository.findByIdAndOwnerRef(id, OWNER)).thenReturn(Optional.empty());

        assertRejected(() -> service.get(OWNER, id.toString()), HttpStatus.NOT_FOUND, "project_not_found");
        assertRejected(() -> service.update(OWNER, id.toString(), Map.of("name", "x")),
                HttpStatus.NOT_FOUND, "project_not_found");
        assertRejected(() -> service.delete(OWNER, id.toString()), HttpStatus.NOT_FOUND, "project_not_found");
        assertRejected(() -> service.get(OWNER, "not-a-uuid"), HttpStatus.NOT_FOUND, "project_not_found");
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void update_changes_only_given_fields_and_null_clears_instructions() {
        ChatProjectEntity p = owned("보고서", "기존 지시");
        Map<String, Object> body = new HashMap<>();
        body.put("instructions", null);

        service.update(OWNER, p.getId().toString(), body);

        assertThat(p.getName()).isEqualTo("보고서");
        assertThat(p.getInstructions()).isNull();
    }

    // TC-CHT-046

    @Test
    void delete_removes_files_from_storage_and_the_project() {
        ChatProjectEntity p = owned("보고서", null);
        ChatProjectFileEntity f1 = file(p, "a.pdf");
        ChatProjectFileEntity f2 = file(p, "b.png");
        when(fileRepository.findByProjectIdOrderByCreatedAtAsc(p.getId())).thenReturn(List.of(f1, f2));
        when(storage.delete(f1.getStoragePath())).thenThrow(new IllegalStateException("gone"));

        service.delete(OWNER, p.getId().toString());

        verify(storage).delete(f1.getStoragePath());
        verify(storage).delete(f2.getStoragePath());
        verify(projectRepository).delete(p);
        verifyNoInteractions(sessionRepository);
    }

    // TC-CHT-047

    @Test
    void upload_to_a_full_project_is_conflict() {
        ChatProjectEntity p = owned("보고서", null);
        when(settings.getInt(eq("widget.project-files-max-count"), anyInt())).thenReturn(2);
        when(fileRepository.countByProjectId(p.getId())).thenReturn(2L);
        MockMultipartFile upload = new MockMultipartFile("file", "c.pdf", "application/pdf", new byte[]{1});

        assertRejected(() -> service.uploadFile(OWNER, p.getId().toString(), upload),
                HttpStatus.CONFLICT, "project_files_full");
        verifyNoInteractions(storage);
        verify(fileRepository, never()).save(any());
    }

    @Test
    void attaching_copies_the_file_into_a_new_session_attachment_and_keeps_the_original() {
        ChatProjectEntity p = owned("보고서", null);
        ChatProjectFileEntity f = file(p, "a.pdf");
        when(fileRepository.findById(f.getId())).thenReturn(Optional.of(f));
        ConversationSessionEntity s = session(OWNER);
        when(sessionRepository.findBySessionIdIncludingDeleted(s.getSessionId())).thenReturn(Optional.of(s));
        byte[] bytes = "pdf".getBytes(StandardCharsets.UTF_8);
        when(storage.load(f.getStoragePath())).thenReturn(new ByteArrayInputStream(bytes));
        ChatAttachmentEntity created = new ChatAttachmentEntity();
        when(attachmentService.saveForSession(s.getSessionId(), "a.pdf", "application/pdf", bytes)).thenReturn(created);

        ChatAttachmentEntity result = service.attachFile(OWNER, p.getId().toString(), f.getId().toString(),
                s.getSessionId());

        assertThat(result).isSameAs(created);
        verify(fileRepository, never()).delete(any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void attaching_before_the_first_question_needs_no_session_row() {
        ChatProjectEntity p = owned("보고서", null);
        ChatProjectFileEntity f = file(p, "a.pdf");
        when(fileRepository.findById(f.getId())).thenReturn(Optional.of(f));
        when(sessionRepository.findBySessionIdIncludingDeleted("s-new")).thenReturn(Optional.empty());
        when(storage.load(f.getStoragePath())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(attachmentService.saveForSession(eq("s-new"), eq("a.pdf"), eq("application/pdf"), any()))
                .thenReturn(new ChatAttachmentEntity());

        assertThat(service.attachFile(OWNER, p.getId().toString(), f.getId().toString(), "s-new")).isNotNull();
    }

    @Test
    void attaching_to_someone_elses_or_deleted_session_is_not_found() {
        ChatProjectEntity p = owned("보고서", null);
        ChatProjectFileEntity f = file(p, "a.pdf");
        when(fileRepository.findById(f.getId())).thenReturn(Optional.of(f));
        ConversationSessionEntity others = session("user-b");
        ConversationSessionEntity deleted = session(OWNER);
        deleted.setDeletedAt(OffsetDateTime.now());
        when(sessionRepository.findBySessionIdIncludingDeleted(others.getSessionId())).thenReturn(Optional.of(others));
        when(sessionRepository.findBySessionIdIncludingDeleted(deleted.getSessionId())).thenReturn(Optional.of(deleted));

        assertRejected(() -> service.attachFile(OWNER, p.getId().toString(), f.getId().toString(),
                others.getSessionId()), HttpStatus.NOT_FOUND, "session_not_found");
        assertRejected(() -> service.attachFile(OWNER, p.getId().toString(), f.getId().toString(),
                deleted.getSessionId()), HttpStatus.NOT_FOUND, "session_not_found");
        verifyNoInteractions(attachmentService);
    }

    @Test
    void file_of_another_project_is_not_found() {
        ChatProjectEntity p = owned("보고서", null);
        ChatProjectEntity other = new ChatProjectEntity();
        other.setId(UUID.randomUUID());
        ChatProjectFileEntity f = file(other, "a.pdf");
        when(fileRepository.findById(f.getId())).thenReturn(Optional.of(f));

        assertRejected(() -> service.deleteFile(OWNER, p.getId().toString(), f.getId().toString()),
                HttpStatus.NOT_FOUND, "file_not_found");
        verify(fileRepository, never()).delete(any());
    }

    private ChatProjectEntity owned(String name, String instructions) {
        ChatProjectEntity p = new ChatProjectEntity();
        p.setId(UUID.randomUUID());
        p.setOwnerRef(OWNER);
        p.setName(name);
        p.setInstructions(instructions);
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        when(projectRepository.findByIdAndOwnerRef(p.getId(), OWNER)).thenReturn(Optional.of(p));
        return p;
    }

    private static ChatProjectFileEntity file(ChatProjectEntity p, String filename) {
        ChatProjectFileEntity f = new ChatProjectFileEntity();
        f.setId(UUID.randomUUID());
        f.setProjectId(p.getId());
        f.setFilename(filename);
        f.setMediaType(filename.endsWith(".pdf") ? "application/pdf" : "image/png");
        f.setStoragePath("tenant/chat-project-files/" + p.getId() + "/" + filename);
        return f;
    }

    private static ConversationSessionEntity session(String owner) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setSessionId("s-" + UUID.randomUUID());
        s.setUserId(owner);
        return s;
    }

    private static void assertRejected(ThrowableAssert.ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOf(ChatRejectedException.class).hasMessage(code)
                .extracting("status").isEqualTo(status);
    }
}
