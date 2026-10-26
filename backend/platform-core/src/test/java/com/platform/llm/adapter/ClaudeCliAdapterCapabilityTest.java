package com.platform.llm.adapter;

import com.platform.service.AgentRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClaudeCliAdapterCapabilityTest {

    @Test
    @DisplayName("TC-CR159-030: CLI 연결은 그림과 PDF 블록을 받는다고 알린다")
    void declaresImageAndPdf() {
        ClaudeCliAdapter adapter = new ClaudeCliAdapter(mock(ClaudeCliRunnerClient.class),
                mock(AgentRegistryService.class), "claude-sonnet-4-5", "NATIVE", null, null, null, null, true);

        assertThat(adapter.capabilities()).isEqualTo(AdapterCapability.IMAGE_AND_PDF);
    }
}
