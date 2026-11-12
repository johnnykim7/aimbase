package com.platform.api;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.repository.MessageFeedbackRepository;
import com.platform.session.CancellationRegistry;
import com.platform.session.ChatHistoryService;
import com.platform.session.ChatProjectService;
import com.platform.session.SessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-184 BIZ-231: 대화를 지우면 그 대화에서 만든 그림에도 지운 표시를 한다.
 * CR-178 뒤로 이 주소도 위젯 대화 지우기와 같은 서비스를 거치므로 서비스를 실물로 두고 주소부터 본다.
 */
@ExtendWith(MockitoExtension.class)
class ConversationControllerDeleteImageTest {

    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private ChatAttachmentRepository attachmentRepository;
    @Mock private MessageFeedbackRepository feedbackRepository;
    @Mock private ChatProjectService projectService;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private SessionStore sessionStore;
    @Mock private PlatformSettingsService settings;
    @Mock private ChatGeneratedImageRepository imageRepository;

    @Test
    @DisplayName("TC-CHT-106 3 대화를_지우면_그_대화_그림_모두에_지운_표시")
    void delete_soft_deletes_images() {
        ChatHistoryService historyService = new ChatHistoryService(sessionRepository, messageRepository,
                attachmentRepository, feedbackRepository, projectService, cancellationRegistry, sessionStore,
                settings, imageRepository);
        ConversationController controller = new ConversationController(sessionRepository, messageRepository, historyService);
        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setSessionId("s-1");
        when(sessionRepository.findBySessionId("s-1")).thenReturn(Optional.of(session));

        controller.delete("s-1");

        verify(messageRepository).softDeleteBySessionId(eq("s-1"), any(OffsetDateTime.class));
        verify(imageRepository).softDeleteBySessionId(eq("s-1"), any(OffsetDateTime.class));
    }
}
