package com.platform.api;

import com.platform.domain.ChatGeneratedImageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.image.ImageGenerationService;
import com.platform.repository.ConversationSessionRepository;
import com.platform.session.ChatRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** CR-184 BIZ-233: 만든 그림은 그 대화 주인에게만 준다. */
@ExtendWith(MockitoExtension.class)
class ChatImageControllerTest {

    @Mock private ImageGenerationService imageGeneration;
    @Mock private ConversationSessionRepository sessionRepository;

    @InjectMocks private ChatImageController controller;

    private static final byte[] BYTES = {1, 2, 3};

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("TC-CHT-107 1 대화_주인이면_그림_바이트를_inline_하루_private_캐시로_준다")
    void owner_gets_image() {
        String id = stubImage("u-1");
        login("u-1");

        ResponseEntity<byte[]> res = controller.image(id, "s-1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(BYTES);
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline");
        assertThat(res.getHeaders().getCacheControl()).isEqualTo("max-age=86400, private");
    }

    @Test
    @DisplayName("TC-CHT-107 2 다른_대화_지운_그림_없는_id_는_404_를_그대로_올린다")
    void not_found_passes_through() {
        when(imageGeneration.loadForSession("x", "s-2"))
                .thenThrow(new ChatRejectedException(HttpStatus.NOT_FOUND, "image_not_found"));

        assertThatThrownBy(() -> controller.image("x", "s-2"))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("image_not_found")
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("TC-CHT-107 5 대화_user_id_가_다른_사람이면_403")
    void other_user_is_forbidden() {
        String id = stubImage("u-1");
        login("u-2");

        assertThatThrownBy(() -> controller.image(id, "s-1"))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("forbidden")
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String stubImage(String owner) {
        UUID id = UUID.randomUUID();
        ChatGeneratedImageEntity img = new ChatGeneratedImageEntity();
        img.setId(id);
        img.setMediaType("image/png");
        when(imageGeneration.loadForSession(id.toString(), "s-1"))
                .thenReturn(new ImageGenerationService.LoadedImage(img, BYTES));
        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setUserId(owner);
        when(sessionRepository.findBySessionId("s-1")).thenReturn(Optional.of(session));
        return id.toString();
    }

    private static void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
