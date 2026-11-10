package com.platform.llm.router;

import com.platform.config.PlatformSettingsService;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.IntentClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** CR-175 TC-CHT-010: 연결도 모델도 없을 때의 기본 모델은 런타임 설정이다. */
@ExtendWith(MockitoExtension.class)
class ModelRouterTest {

    private static final String SETTING_MODEL = "anthropic/claude-opus-5-5";

    @Mock private LLMAdapterRegistry registry;
    @Mock private IntentClassifier intentClassifier;
    @Mock private PlatformSettingsService platformSettings;
    @Mock private LLMAdapter adapter;

    private ModelRouter router;

    @BeforeEach
    void setUp() {
        router = new ModelRouter(registry, intentClassifier, platformSettings);
        lenient().when(platformSettings.getString(eq(ConnectionAdapterFactory.DEFAULT_MODEL_SETTING_KEY), isNull()))
                .thenReturn(SETTING_MODEL);
    }

    @Test
    void auto_and_blank_resolve_to_setting_value() {
        assertThat(router.resolveModelId("auto")).isEqualTo(SETTING_MODEL);
        assertThat(router.resolveModelId("")).isEqualTo(SETTING_MODEL);
        assertThat(router.resolveModelId(null)).isEqualTo(SETTING_MODEL);
    }

    @Test
    void explicit_model_is_kept() {
        assertThat(router.resolveModelId("anthropic/claude-haiku-4-5")).isEqualTo("anthropic/claude-haiku-4-5");
    }

    @Test
    void smart_routing_without_target_adapter_falls_back_to_setting_value() {
        when(intentClassifier.classify(anyString())).thenReturn(IntentClassifier.Complexity.COMPLEX);
        when(registry.hasAdapter(any())).thenReturn(false);
        when(registry.getAdapter(SETTING_MODEL)).thenReturn(adapter);
        LLMRequest request = new LLMRequest("auto",
                List.of(new UnifiedMessage(UnifiedMessage.Role.USER, List.of(new ContentBlock.Text("보고서 써 줘")))));

        assertThat(router.route(request)).isSameAs(adapter);
    }

    @Test
    void empty_setting_fails_loudly() {
        when(platformSettings.getString(eq(ConnectionAdapterFactory.DEFAULT_MODEL_SETTING_KEY), isNull()))
                .thenReturn(" ");

        assertThatThrownBy(() -> router.resolveModelId("auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConnectionAdapterFactory.DEFAULT_MODEL_SETTING_KEY);
    }
}
