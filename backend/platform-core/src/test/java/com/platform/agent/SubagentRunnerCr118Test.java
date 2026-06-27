package com.platform.agent;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.ToolFilterContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-118: AGENT_CALL config 의 워크플로우별 도구 필터(tools/exclude_tools/read_only) 배선 검증.
 *
 * SubagentRunner.buildToolFilter 가 AgentType 필터 와 config 필터를 병합해
 * ChatRequest.toolFilter 로 전파하는지 확인한다(→ getToolDefs(filter) → CLI --allowedTools, CR-104 불변식).
 */
@ExtendWith(MockitoExtension.class)
class SubagentRunnerCr118Test {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;
    @Mock private AgentTypeRegistry agentTypeRegistry;
    @Mock private PlanService planService;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;

    private SubagentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager, agentTypeRegistry, planService, connectionAdapterFactory,
                new com.platform.agent.ActiveCliWorkerRegistry());
        lenient().when(hookDispatcher.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ChatResponse simpleResponse() {
        return new ChatResponse(
                "resp-x", "claude", "child",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(10, 20), 0.0);
    }

    private ToolFilterContext runAndCaptureFilter(Map<String, Object> config, AgentType type) {
        when(orchestratorEngine.chat(any())).thenReturn(simpleResponse());
        SubagentRequest req = new SubagentRequest(
                "wf-agent", "do work", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                config, "parent", type, null, null, null, null, true);
        runner.run(req);
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestratorEngine).chat(captor.capture());
        return captor.getValue().toolFilter();
    }

    private void stubGeneral() {
        when(agentTypeRegistry.getConfig(AgentType.GENERAL))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.GENERAL, "general prompt", null, false));
    }

    // ── config.tools 화이트리스트 (GENERAL: AgentType 필터 없음) ──

    @Test
    void config_tools_whitelist_applied_for_general() {
        stubGeneral();
        ToolFilterContext f = runAndCaptureFilter(
                Map.of("prompt", "x", "tools", List.of("read", "parse_document")),
                AgentType.GENERAL);

        assertThat(f).isNotNull();
        assertThat(f.allowedTools()).containsExactly("read", "parse_document");
        assertThat(f.excludeTools()).isNull();
        assertThat(f.readOnlyMode()).isNull();
    }

    @Test
    void config_exclude_tools_applied_for_general() {
        stubGeneral();
        ToolFilterContext f = runAndCaptureFilter(
                Map.of("exclude_tools", List.of("bash", "file_write")),
                AgentType.GENERAL);

        assertThat(f).isNotNull();
        assertThat(f.allowedTools()).isNull();
        assertThat(f.excludeTools()).containsExactly("bash", "file_write");
    }

    @Test
    void config_read_only_boolean_applied() {
        stubGeneral();
        ToolFilterContext f = runAndCaptureFilter(
                Map.of("read_only", true),
                AgentType.GENERAL);

        assertThat(f).isNotNull();
        assertThat(f.readOnlyMode()).isTrue();
    }

    @Test
    void config_read_only_string_true_applied() {
        // 템플릿 치환으로 문자열 "true" 로 들어오는 경우도 인식
        stubGeneral();
        ToolFilterContext f = runAndCaptureFilter(
                Map.of("read_only", "true"),
                AgentType.GENERAL);

        assertThat(f).isNotNull();
        assertThat(f.readOnlyMode()).isTrue();
    }

    @Test
    void no_config_filter_for_general_returns_null() {
        // config 에 도구 필터 키 없음 + GENERAL → 필터 미적용(기존 동작 보존)
        stubGeneral();
        ToolFilterContext f = runAndCaptureFilter(
                Map.of("prompt", "x"),
                AgentType.GENERAL);

        assertThat(f).isNull();
    }

    // ── AgentType 필터 와 config 필터 병합 ──

    @Test
    void merge_intersects_agentType_and_config_allow() {
        // EXPLORE allowed = {read, grep, send_message, task_output}, config.tools = {read, bash}
        // → 교집합 = {read} (bash 는 EXPLORE 화이트리스트에 없어 탈락)
        when(agentTypeRegistry.getConfig(AgentType.EXPLORE))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.EXPLORE, "explore prompt",
                        new java.util.LinkedHashSet<>(Set.of("read", "grep", "send_message", "task_output")),
                        true));

        ToolFilterContext f = runAndCaptureFilter(
                Map.of("tools", List.of("read", "bash")),
                AgentType.EXPLORE);

        assertThat(f).isNotNull();
        assertThat(f.allowedTools()).containsExactly("read");
        // readOnly: EXPLORE(true) OR config(미지정) → true
        assertThat(f.readOnlyMode()).isTrue();
    }

    @Test
    void merge_readOnly_or_semantics() {
        // GENERAL(readOnly=false) + config.read_only=true → true
        stubGeneral();
        ToolFilterContext f = runAndCaptureFilter(
                Map.of("tools", List.of("read"), "read_only", true),
                AgentType.GENERAL);

        assertThat(f).isNotNull();
        assertThat(f.allowedTools()).containsExactly("read");
        assertThat(f.readOnlyMode()).isTrue();
    }
}
