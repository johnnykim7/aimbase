package com.platform.llm;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.adapter.ClaudeCliRunnerClient;
import com.platform.repository.ConnectionRepository;
import com.platform.service.AgentRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-175 TC-CHT-009, TC-CHT-010: 연결의 허용 모델과 기본 모델. */
class ConnectionAdapterModelsTest {

    private static final String SETTING_MODEL = "anthropic/claude-opus-5-5";

    private ConnectionRepository connectionRepository;
    private ConnectionAdapterFactory factory;

    @BeforeEach
    void setUp() {
        connectionRepository = mock(ConnectionRepository.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenReturn("*");
        when(settings.getString(eq(ConnectionAdapterFactory.DEFAULT_MODEL_SETTING_KEY), isNull()))
                .thenReturn(SETTING_MODEL);
        factory = new ConnectionAdapterFactory(
                connectionRepository,
                16000,
                mock(com.platform.llm.thinking.AdaptiveThinkingPolicy.class),
                settings,
                mock(ClaudeCliRunnerClient.class),
                mock(AgentRegistryService.class));
    }

    @Test
    void model_outside_allowed_list_is_refused() {
        // TC-CHT-009
        stub(Map.of("model", "claude-sonnet-5-5", "models", List.of(
                Map.of("id", "claude-sonnet-5-5", "label", "Sonnet 5.5"),
                Map.of("id", "claude-opus-5-5", "label", "Opus 5.5"))));

        assertThat(factory.isModelAllowed("api-1", "claude-haiku-5-5")).isFalse();
        assertThat(factory.isModelAllowed("api-1", "claude-opus-5-5")).isTrue();
        assertThat(factory.isModelAllowed("api-1", "auto")).isTrue();
        assertThat(factory.isModelAllowed("api-1", "")).isTrue();
        assertThat(factory.resolveModel("api-1", "auto")).isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void connection_without_models_keeps_old_behaviour() {
        // TC-CHT-009
        stub(Map.of("model", "claude-sonnet-5-5"));

        assertThat(factory.isModelAllowed("api-1", "claude-haiku-5-5")).isTrue();
        assertThat(factory.selectableModels("api-1"))
                .containsExactly(Map.of("id", "claude-sonnet-5-5", "label", "claude-sonnet-5-5"));
    }

    @Test
    void list_keeps_written_order_and_label_falls_back_to_id() {
        Map<String, Object> second = new HashMap<>();
        second.put("id", "claude-haiku-5-5");
        second.put("label", " ");
        stub(Map.of("model", "claude-opus-5-5", "models", List.of(
                Map.of("id", "claude-opus-5-5", "label", "Opus 5.5"), second, Map.of("label", "id 없음"))));

        assertThat(factory.selectableModels("api-1")).containsExactly(
                Map.of("id", "claude-opus-5-5", "label", "Opus 5.5"),
                Map.of("id", "claude-haiku-5-5", "label", "claude-haiku-5-5"));
    }

    @Test
    void connection_without_model_uses_platform_setting() {
        // TC-CHT-010
        stub(Map.of());

        assertThat(factory.defaultModel("api-1")).isEqualTo(SETTING_MODEL);
        assertThat(factory.resolveModel("api-1", null)).isEqualTo(SETTING_MODEL);
        assertThat(factory.platformDefaultModel()).isEqualTo(SETTING_MODEL);
    }

    private void stub(Map<String, Object> config) {
        ConnectionEntity e = new ConnectionEntity();
        e.setId("api-1");
        e.setName("anthropic-api");
        e.setAdapter("anthropic");
        e.setStatus("active");
        e.setConfig(new HashMap<>(config));
        when(connectionRepository.findById("api-1")).thenReturn(Optional.of(e));
    }
}
