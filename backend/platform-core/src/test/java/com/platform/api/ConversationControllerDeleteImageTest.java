package com.platform.api;

import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.session.CancellationRegistry;
import com.platform.session.SessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-184 BIZ-231: 대화를 지우면 그 대화에서 만든 그림에도 지운 표시를 한다. */
@ExtendWith(MockitoExtension.class)
class ConversationControllerDeleteImageTest {

    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private SessionStore sessionStore;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private ChatGeneratedImageRepository imageRepository;

    @InjectMocks private ConversationController controller;

    @Test
    @DisplayName("TC-CHT-106 3 대화를_지우면_그_대화_그림_모두에_지운_표시")
    void delete_soft_deletes_images() {
        when(sessionRepository.findBySessionId("s-1")).thenReturn(Optional.of(new ConversationSessionEntity()));

        controller.delete("s-1");

        verify(messageRepository).softDeleteBySessionId(eq("s-1"), any(OffsetDateTime.class));
        verify(imageRepository).softDeleteBySessionId(eq("s-1"), any(OffsetDateTime.class));
    }
}
