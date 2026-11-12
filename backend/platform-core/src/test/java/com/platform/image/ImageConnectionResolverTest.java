package com.platform.image;

import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageConnectionResolverTest {

    @Mock private ConnectionRepository connectionRepository;

    private ImageConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ImageConnectionResolver(connectionRepository);
    }

    @Test
    @DisplayName("TC-CHT-100 1 대화_연결이_가리키는_OpenAI_연결에_그림_모델과_키가_있으면_그림_연결이다")
    void openai_connection_with_model_and_key_is_image_connection() {
        chat("chat-1", Map.of(ImageConnectionResolver.CONFIG_IMAGE_CONNECTION_ID, "img-1"));
        image("img-1", "openai", Map.of("image_model", "gpt-image-1", "image_quality", "high", "apiKey", "sk-test"));

        Optional<ImageConnection> conn = resolver.resolve("chat-1");

        assertThat(conn).isPresent();
        assertThat(conn.get().connectionId()).isEqualTo("img-1");
        assertThat(conn.get().model()).isEqualTo("gpt-image-1");
        assertThat(conn.get().quality()).isEqualTo("high");
        assertThat(conn.get().apiKey()).isEqualTo("sk-test");
        assertThat(conn.get().toString()).doesNotContain("sk-test");
    }

    @Test
    @DisplayName("TC-CHT-100 2 그림_연결_id_가_없으면_그림_연결이_없다")
    void no_image_connection_id() {
        chat("chat-1", Map.of());

        assertThat(resolver.resolve("chat-1")).isEmpty();
        assertThat(resolver.isConfigured("chat-1")).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-100 3 가리킨_연결이_없으면_그림_연결이_없다")
    void pointed_connection_missing() {
        chat("chat-1", Map.of(ImageConnectionResolver.CONFIG_IMAGE_CONNECTION_ID, "img-x"));
        when(connectionRepository.findById("img-x")).thenReturn(Optional.empty());

        assertThat(resolver.resolve("chat-1")).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-100 4 어댑터가_OpenAI_가_아니면_그림_연결이_없다")
    void non_openai_adapter() {
        chat("chat-1", Map.of(ImageConnectionResolver.CONFIG_IMAGE_CONNECTION_ID, "img-1"));
        image("img-1", "anthropic", Map.of("image_model", "gpt-image-1", "apiKey", "sk-test"));

        assertThat(resolver.resolve("chat-1")).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-100 5 그림_모델이_비면_그림_연결이_없다")
    void blank_image_model() {
        chat("chat-1", Map.of(ImageConnectionResolver.CONFIG_IMAGE_CONNECTION_ID, "img-1"));
        image("img-1", "openai", Map.of("image_model", " ", "apiKey", "sk-test"));

        assertThat(resolver.resolve("chat-1")).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-100 6 키가_없으면_그림_연결이_없다")
    void no_key() {
        chat("chat-1", Map.of(ImageConnectionResolver.CONFIG_IMAGE_CONNECTION_ID, "img-1"));
        image("img-1", "openai", Map.of("image_model", "gpt-image-1"));

        assertThat(resolver.resolve("chat-1")).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-100 7 품질이_없거나_모르는_값이면_medium_이고_연결_상태는_보지_않는다")
    void default_quality_and_status_ignored() {
        chat("chat-1", Map.of(ImageConnectionResolver.CONFIG_IMAGE_CONNECTION_ID, "img-1"));
        ConnectionEntity img = image("img-1", "openai", Map.of("image_model", "gpt-image-1", "apiKey", "sk-test",
                "image_quality", "ultra"));
        img.setStatus("error");

        assertThat(resolver.resolve("chat-1")).get().extracting(ImageConnection::quality).isEqualTo("medium");
    }

    private void chat(String id, Map<String, Object> config) {
        ConnectionEntity c = new ConnectionEntity();
        c.setId(id);
        c.setAdapter("anthropic");
        c.setConfig(new HashMap<>(config));
        when(connectionRepository.findById(id)).thenReturn(Optional.of(c));
    }

    private ConnectionEntity image(String id, String adapter, Map<String, Object> config) {
        ConnectionEntity c = new ConnectionEntity();
        c.setId(id);
        c.setAdapter(adapter);
        c.setConfig(new HashMap<>(config));
        lenient().when(connectionRepository.findById(id)).thenReturn(Optional.of(c));
        return c;
    }
}
