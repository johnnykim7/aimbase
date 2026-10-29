package com.platform.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-165: 관리 화면이 저장하는 어댑터 표시명과 API 로 넣는 어댑터 이름이 같은 내부 이름이 된다.
 */
class ConnectionAdapterTypeTest {

    @Test
    void openAiDisplayNamesBecomeOpenAi() {
        assertThat(ConnectionAdapterFactory.normalizeAdapterType("OpenAI")).isEqualTo("openai");
        assertThat(ConnectionAdapterFactory.normalizeAdapterType("openai")).isEqualTo("openai");
    }

    @Test
    void openAiCompatibleDisplayNameIsNotOpenAi() {
        assertThat(ConnectionAdapterFactory.normalizeAdapterType("OpenAI Compatible")).isEqualTo("openai_compatible");
        assertThat(ConnectionAdapterFactory.normalizeAdapterType("openai_compatible")).isEqualTo("openai_compatible");
    }

    @Test
    void otherAdaptersUnchanged() {
        assertThat(ConnectionAdapterFactory.normalizeAdapterType("Claude (Anthropic)")).isEqualTo("anthropic");
        assertThat(ConnectionAdapterFactory.normalizeAdapterType("Claude CLI")).isEqualTo("anthropic-cli");
        assertThat(ConnectionAdapterFactory.normalizeAdapterType("anthropic-cli")).isEqualTo("anthropic-cli");
    }
}
