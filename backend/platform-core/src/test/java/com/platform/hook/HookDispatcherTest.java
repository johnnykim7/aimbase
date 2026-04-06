package com.platform.hook;

import com.platform.domain.HookDefinitionEntity;
import com.platform.repository.HookDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CR-030 Phase 2: HookDispatcher 단위 테스트.
 *
 * 검증 대상:
 * - 훅 없을 때 PASSTHROUGH 반환
 * - 단일 APPROVE 훅
 * - BLOCK이 하나라도 있으면 최종 BLOCK
 * - updatedInput 전파
 * - 도구명 매처 필터링
 */
class HookDispatcherTest {

    private HookDefinitionRepository repository;
    private ApplicationContext applicationContext;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private HookRegistry hookRegistry;
    private HookExecutor hookExecutor;
    private HookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = mock(HookDefinitionRepository.class);
        applicationContext = mock(ApplicationContext.class);
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        hookRegistry = new HookRegistry(repository);
        hookExecutor = new HookExecutor(applicationContext, objectMapper);
        dispatcher = new HookDispatcher(hookRegistry, hookExecutor);
    }

    @Test
    void noHooks_returnsPassthrough() {
        when(repository.findByIsActiveTrueOrderByEventAscExecOrderAsc())
                .thenReturn(List.of());

        HookOutput result = dispatcher.dispatch(HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, "session-1"));

        assertThat(result.decision()).isEqualTo(HookDecision.PASSTHROUGH);
    }

    @Test
    void singleApproveHook_returnsApprove() {
        HookDefinitionEntity def = createHookDef("h1", "PRE_TOOL_USE", "INTERNAL", "approveBean");

        when(repository.findByIsActiveTrueOrderByEventAscExecOrderAsc())
                .thenReturn(List.of(def));
        when(applicationContext.getBean("approveBean", HookCallback.class))
                .thenReturn(input -> HookOutput.APPROVE);

        hookRegistry.refresh();

        HookOutput result = dispatcher.dispatch(HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, "session-1"));

        assertThat(result.decision()).isEqualTo(HookDecision.APPROVE);
    }

    @Test
    void blockHook_overridesApprove() {
        HookDefinitionEntity approveFirst = createHookDef("h1", "PRE_TOOL_USE", "INTERNAL", "approveBean");
        approveFirst.setExecOrder(0);
        HookDefinitionEntity blockSecond = createHookDef("h2", "PRE_TOOL_USE", "INTERNAL", "blockBean");
        blockSecond.setExecOrder(1);

        when(repository.findByIsActiveTrueOrderByEventAscExecOrderAsc())
                .thenReturn(List.of(approveFirst, blockSecond));
        when(applicationContext.getBean("approveBean", HookCallback.class))
                .thenReturn(input -> HookOutput.APPROVE);
        when(applicationContext.getBean("blockBean", HookCallback.class))
                .thenReturn(input -> HookOutput.block("denied by test"));

        hookRegistry.refresh();

        HookOutput result = dispatcher.dispatch(HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, "session-1"));

        assertThat(result.decision()).isEqualTo(HookDecision.BLOCK);
        assertThat(result.metadata()).containsEntry("reason", "denied by test");
    }

    @Test
    void updatedInput_propagated() {
        HookDefinitionEntity def = createHookDef("h1", "USER_PROMPT_SUBMIT", "INTERNAL", "modifyBean");

        when(repository.findByIsActiveTrueOrderByEventAscExecOrderAsc())
                .thenReturn(List.of(def));
        when(applicationContext.getBean("modifyBean", HookCallback.class))
                .thenReturn(input -> HookOutput.approve(Map.of("prompt", "modified prompt")));

        hookRegistry.refresh();

        HookOutput result = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT,
                HookInput.of(HookEvent.USER_PROMPT_SUBMIT, "session-1"));

        assertThat(result.decision()).isEqualTo(HookDecision.APPROVE);
        assertThat(result.updatedInput()).containsEntry("prompt", "modified prompt");
    }

    @Test
    void toolMatcher_filtersCorrectly() {
        HookDefinitionEntity readOnly = createHookDef("h1", "PRE_TOOL_USE", "INTERNAL", "blockBean");
        readOnly.setMatcher("Write*");

        when(repository.findByIsActiveTrueOrderByEventAscExecOrderAsc())
                .thenReturn(List.of(readOnly));
        when(applicationContext.getBean("blockBean", HookCallback.class))
                .thenReturn(input -> HookOutput.block("write blocked"));

        hookRegistry.refresh();

        // ReadTool → 매처에 안 걸림
        HookOutput readResult = dispatcher.dispatch(HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, "s1", "ReadTool", Map.of()),
                "ReadTool");
        assertThat(readResult.decision()).isEqualTo(HookDecision.PASSTHROUGH);

        // WriteTool → 매처에 걸림
        HookOutput writeResult = dispatcher.dispatch(HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, "s1", "WriteTool", Map.of()),
                "WriteTool");
        assertThat(writeResult.decision()).isEqualTo(HookDecision.BLOCK);
    }

    @Test
    void failOpen_beanNotFound_returnsPassthrough() {
        HookDefinitionEntity def = createHookDef("h1", "SESSION_START", "INTERNAL", "missingBean");

        when(repository.findByIsActiveTrueOrderByEventAscExecOrderAsc())
                .thenReturn(List.of(def));
        when(applicationContext.getBean("missingBean", HookCallback.class))
                .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("missingBean"));

        hookRegistry.refresh();

        HookOutput result = dispatcher.dispatch(HookEvent.SESSION_START,
                HookInput.of(HookEvent.SESSION_START, "session-1"));

        assertThat(result.decision()).isEqualTo(HookDecision.PASSTHROUGH);
    }

    private HookDefinitionEntity createHookDef(String id, String event, String targetType, String target) {
        HookDefinitionEntity def = new HookDefinitionEntity();
        def.setId(id);
        def.setName("test-" + id);
        def.setEvent(event);
        def.setTargetType(targetType);
        def.setTarget(target);
        def.setTimeoutMs(5000);
        def.setExecOrder(0);
        def.setActive(true);
        return def;
    }
}
