package com.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** CR-175 TC-CHT-011: 위젯 설정. */
@ExtendWith(MockitoExtension.class)
class ChatWidgetSettingsServiceTest {

    @Mock private PlatformSettingsService platformSettings;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private LLMAdapterRegistry adapterRegistry;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private LLMAdapter apiAdapter;
    @Mock private LLMAdapter cliAdapter;

    private ChatWidgetSettingsService service;

    @BeforeEach
    void setUp() {
        service = new ChatWidgetSettingsService(platformSettings, connectionAdapterFactory, adapterRegistry,
                connectionRepository, new ObjectMapper());
        lenient().when(platformSettings.getString(eq(ChatWidgetSettingsService.FEEDBACK_REASONS_KEY), anyString()))
                .thenReturn("[{\"code\":\"WRONG_INFO\",\"label\":\"틀린 정보\"}]");
        lenient().when(platformSettings.getString(eq(ChatWidgetSettingsService.FOLLOW_UPS_ENABLED_KEY), anyString()))
                .thenReturn("true");
        lenient().when(platformSettings.getInt(eq(ChatWidgetSettingsService.FEEDBACK_COMMENT_MAX_KEY), anyInt()))
                .thenReturn(500);
    }

    @Test
    void connection_with_models_lists_them_and_falls_back_to_first_when_default_is_outside() {
        when(connectionRepository.existsById("api-1")).thenReturn(true);
        when(connectionAdapterFactory.selectableModels("api-1")).thenReturn(List.of(
                Map.of("id", "claude-sonnet-5-5", "label", "Sonnet 5.5"),
                Map.of("id", "claude-opus-5-5", "label", "Opus 5.5")));
        when(connectionAdapterFactory.defaultModel("api-1")).thenReturn("claude-haiku-5-5");
        when(connectionAdapterFactory.getAdapter("api-1")).thenReturn(apiAdapter);
        when(apiAdapter.supportsThinkingOverride()).thenReturn(true);
        when(connectionAdapterFactory.resolveModelConfig("api-1")).thenReturn(ModelConfig.defaults());

        Map<String, Object> body = service.settings("api-1");

        assertThat(body.get("default_model")).isEqualTo("claude-sonnet-5-5");
        assertThat((List<?>) body.get("models")).hasSize(2);
        assertThat(body.get("thinking_supported")).isEqualTo(true);
        assertThat(body.get("thinking_default")).isEqualTo(false);
        assertThat(body.get("feedback_comment_max_chars")).isEqualTo(500);
        assertThat(body.get("follow_ups_enabled")).isEqualTo(true);
    }

    @Test
    void cli_connection_supports_thinking_and_starts_on_without_setting() {
        stubCliConnection(ModelConfig.defaults());

        Map<String, Object> body = service.settings("cli-1");

        assertThat((List<?>) body.get("models")).hasSize(1);
        assertThat(body.get("thinking_supported")).isEqualTo(true);
        assertThat(body.get("thinking_default")).isEqualTo(true);
    }

    @Test
    void connection_thinking_setting_wins_over_adapter_default() {
        stubCliConnection(new ModelConfig(null, null, null, null, false, null));

        assertThat(service.settings("cli-1").get("thinking_default")).isEqualTo(false);
    }

    private void stubCliConnection(ModelConfig config) {
        when(connectionRepository.existsById("cli-1")).thenReturn(true);
        when(connectionAdapterFactory.selectableModels("cli-1"))
                .thenReturn(List.of(Map.of("id", "claude-sonnet-5-5", "label", "claude-sonnet-5-5")));
        when(connectionAdapterFactory.defaultModel("cli-1")).thenReturn("claude-sonnet-5-5");
        when(connectionAdapterFactory.getAdapter("cli-1")).thenReturn(cliAdapter);
        when(connectionAdapterFactory.resolveModelConfig("cli-1")).thenReturn(config);
        when(cliAdapter.supportsThinkingOverride()).thenReturn(true);
        lenient().when(cliAdapter.thinksByDefault()).thenReturn(true);
    }

    @Test
    void no_connection_uses_platform_default_model_setting() {
        when(connectionAdapterFactory.platformDefaultModel()).thenReturn("anthropic/claude-sonnet-4-5");
        when(adapterRegistry.hasAdapter("anthropic/claude-sonnet-4-5")).thenReturn(true);
        when(adapterRegistry.getAdapter("anthropic/claude-sonnet-4-5")).thenReturn(apiAdapter);
        when(apiAdapter.supportsThinkingOverride()).thenReturn(true);

        Map<String, Object> body = service.settings(null);

        assertThat(body.get("default_model")).isEqualTo("anthropic/claude-sonnet-4-5");
        assertThat((List<?>) body.get("models")).hasSize(1);
        assertThat(body.get("thinking_default")).isEqualTo(false);
    }

    @Test
    void connection_of_another_tenant_is_not_found() {
        when(connectionRepository.existsById("other-tenant-conn")).thenReturn(false);

        assertThatThrownBy(() -> service.settings("other-tenant-conn"))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("connection_not_found")
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
