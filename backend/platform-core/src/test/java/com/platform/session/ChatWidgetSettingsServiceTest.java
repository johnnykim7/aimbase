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

/** CR-175 TC-CHT-011: 위젯 설정. CR-177 TC-CHT-030, 031: 이유 이름 언어와 값 모양. */
@ExtendWith(MockitoExtension.class)
class ChatWidgetSettingsServiceTest {

    @Mock private PlatformSettingsService platformSettings;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private LLMAdapterRegistry adapterRegistry;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private com.platform.image.ImageConnectionResolver imageConnectionResolver;
    @Mock private com.platform.image.ImageGenerationSettings imageSettings;
    @Mock private LLMAdapter apiAdapter;
    @Mock private LLMAdapter cliAdapter;

    private ChatWidgetSettingsService service;

    @BeforeEach
    void setUp() {
        service = new ChatWidgetSettingsService(platformSettings, connectionAdapterFactory, adapterRegistry,
                connectionRepository, new ObjectMapper(), imageConnectionResolver, imageSettings);
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

        Map<String, Object> body = service.settings("api-1", null);

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

        Map<String, Object> body = service.settings("cli-1", null);

        assertThat((List<?>) body.get("models")).hasSize(1);
        assertThat(body.get("thinking_supported")).isEqualTo(true);
        assertThat(body.get("thinking_default")).isEqualTo(true);
    }

    @Test
    void connection_thinking_setting_wins_over_adapter_default() {
        stubCliConnection(new ModelConfig(null, null, null, null, false, null));

        assertThat(service.settings("cli-1", null).get("thinking_default")).isEqualTo(false);
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

        Map<String, Object> body = service.settings(null, null);

        assertThat(body.get("default_model")).isEqualTo("anthropic/claude-sonnet-4-5");
        assertThat((List<?>) body.get("models")).hasSize(1);
        assertThat(body.get("thinking_default")).isEqualTo(false);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TC-CHT-108 1 그림_연결이_있는_연결만_그림_칸이_참이고_상한을_함께_준다")
    void image_generation_follows_image_connection() {
        stubCliConnection(ModelConfig.defaults());
        when(imageConnectionResolver.isConfigured("cli-1")).thenReturn(true, false);
        when(imageSettings.maxPromptChars()).thenReturn(4000);
        when(imageSettings.maxSourceImages()).thenReturn(4);

        Map<?, ?> on = (Map<?, ?>) service.settings("cli-1", null).get("image_generation");
        Map<?, ?> off = (Map<?, ?>) service.settings("cli-1", null).get("image_generation");

        assertThat(on.get("enabled")).isEqualTo(true);
        assertThat(on.get("max_prompt_chars")).isEqualTo(4000);
        assertThat(on.get("max_source_images")).isEqualTo(4);
        assertThat(off.get("enabled")).isEqualTo(false);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TC-CHT-108 2 connection_id_가_없으면_그림_칸은_거짓")
    void image_generation_off_without_connection() {
        when(connectionAdapterFactory.platformDefaultModel()).thenReturn("anthropic/claude-sonnet-4-5");
        when(adapterRegistry.hasAdapter("anthropic/claude-sonnet-4-5")).thenReturn(false);

        Map<?, ?> image = (Map<?, ?>) service.settings(null, null).get("image_generation");

        assertThat(image.get("enabled")).isEqualTo(false);
        org.mockito.Mockito.verifyNoInteractions(imageConnectionResolver);
    }

    /** CR-180: HTML 실행 틀 출처는 설정 JSON 목록, JSON 이 아니면 빈 목록. */
    @Test
    void preview_html_script_sources_come_from_setting() {
        when(platformSettings.getString(eq(ChatWidgetSettingsService.PREVIEW_HTML_SOURCES_KEY), anyString()))
                .thenReturn("[\"https://cdnjs.cloudflare.com\"]", "not json");

        assertThat(service.previewHtmlScriptSources()).containsExactly("https://cdnjs.cloudflare.com");
        assertThat(service.previewHtmlScriptSources()).isEmpty();
    }

    @Test
    void connection_of_another_tenant_is_not_found() {
        when(connectionRepository.existsById("other-tenant-conn")).thenReturn(false);

        assertThatThrownBy(() -> service.settings("other-tenant-conn", null))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("connection_not_found")
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static final String REASONS_WITH_LABELS =
            "[{\"code\":\"WRONG_INFO\",\"label\":\"틀린 정보\",\"labels\":{\"en\":\"Incorrect information\"}},"
            + "{\"code\":\"OTHER\",\"label\":\"기타\"}]";

    private void stubReasons(String value) {
        when(platformSettings.getString(eq(ChatWidgetSettingsService.FEEDBACK_REASONS_KEY), anyString())).thenReturn(value);
    }

    @Test
    void reason_label_follows_locale_and_falls_back_to_default_label() {
        stubReasons(REASONS_WITH_LABELS);

        assertThat(service.feedbackReasons("en")).containsExactly(
                Map.of("code", "WRONG_INFO", "label", "Incorrect information"), Map.of("code", "OTHER", "label", "기타"));
        assertThat(service.feedbackReasons("en-US").get(0).get("label")).isEqualTo("Incorrect information");
        assertThat(service.feedbackReasons("EN_us").get(0).get("label")).isEqualTo("Incorrect information");
        assertThat(service.feedbackReasons(null).get(0).get("label")).isEqualTo("틀린 정보");
        assertThat(service.feedbackReasons("ko").get(0).get("label")).isEqualTo("틀린 정보");
        assertThat(service.feedbackReasons("fr").get(0).get("label")).isEqualTo("틀린 정보");
    }

    @Test
    void settings_body_carries_reason_names_of_requested_locale() {
        stubReasons(REASONS_WITH_LABELS);
        when(connectionAdapterFactory.platformDefaultModel()).thenReturn("anthropic/claude-sonnet-4-5");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> reasons = (List<Map<String, String>>) service.settings(null, "en").get("feedback_reasons");

        assertThat(reasons.get(0).get("label")).isEqualTo("Incorrect information");
    }

    @Test
    void value_with_labels_still_gives_codes_and_default_labels_to_existing_readers() {
        stubReasons(REASONS_WITH_LABELS);

        assertThat(service.feedbackReasons()).extracting(r -> r.get("code")).containsExactly("WRONG_INFO", "OTHER");
        assertThat(service.feedbackReasons()).extracting(r -> r.get("label")).containsExactly("틀린 정보", "기타");
    }

    @Test
    void odd_items_are_read_without_failing_the_whole_value() {
        stubReasons("[{\"code\":\"WRONG_INFO\",\"label\":\"틀린 정보\",\"labels\":\"Incorrect\"},"
                + "{\"label\":\"이름만\"},{\"code\":\"NO_LABEL\"}]");

        assertThat(service.feedbackReasons("en")).containsExactly(
                Map.of("code", "WRONG_INFO", "label", "틀린 정보"), Map.of("code", "NO_LABEL", "label", "NO_LABEL"));
    }

    @Test
    void value_that_is_not_a_list_gives_no_reasons() {
        stubReasons("{\"code\":\"WRONG_INFO\"}");
        assertThat(service.feedbackReasons("en")).isEmpty();

        stubReasons("not json");
        assertThat(service.feedbackReasons()).isEmpty();
    }
}
