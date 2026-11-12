package com.platform.tool;

import com.platform.config.PlatformSettingsService;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.FileCreateToolGate;
import com.platform.tool.builtin.CreateFileTool;
import com.platform.tool.builtin.DataQueryTool;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.registry.SessionToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-183 TC-CHT-098 API 도구 목록에 붙이는 도구(BIZ-222). */
class ToolCallHandlerAttachedToolsTest {

    private static final UnifiedToolDef DATA = new UnifiedToolDef(DataQueryTool.NAME, "d", Map.of());
    private static final UnifiedToolDef OTHER = new UnifiedToolDef("file_read", "f", Map.of());

    private static ToolRegistry registry() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getDefinition()).thenReturn(DATA);
        when(registry.getExecutor(DataQueryTool.NAME)).thenReturn(executor);
        return registry;
    }

    @Test
    @DisplayName("붙이는 도구는 활성 목록 걸러내기 뒤에도 목록에 들어간다")
    void attachedToolAdded() {
        ToolFilterContext filter = ToolFilterContext.withAttached(null, List.of(DataQueryTool.NAME));

        List<UnifiedToolDef> out = ToolCallHandler.withAttached(registry(), filter, List.of(OTHER));

        assertThat(out).extracting(UnifiedToolDef::name).containsExactly("file_read", DataQueryTool.NAME);
    }

    @Test
    @DisplayName("exclude_tools 에 있거나 붙이는 도구가 없으면 목록을 바꾸지 않는다")
    void excludedOrNone_unchanged() {
        ToolFilterContext excluded = ToolFilterContext.withAttached(
                new ToolFilterContext(null, List.of(DataQueryTool.NAME), null, null, null, null),
                List.of(DataQueryTool.NAME));
        List<UnifiedToolDef> defs = List.of(OTHER);

        assertThat(ToolCallHandler.withAttached(registry(), excluded, defs)).containsExactly(OTHER);
        assertThat(ToolCallHandler.withAttached(registry(), null, defs)).isSameAs(defs);
    }

    @Test
    @DisplayName("CR-182 TC-CHT-083 스트리밍 고리의 세션 도구 거르기가 create_file 을 빼도 파일 만들기 거르기가 다시 싣는다")
    void fileCreateSurvivesSessionFilter() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        ChatRequest request = new ChatRequest("m", "s1", List.of(), true, false, "user-a", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null).withFileCreate(true);
        ToolFilterContext filter = new FileCreateToolGate(settings).filter(request, null);

        UnifiedToolDef create = new UnifiedToolDef(CreateFileTool.NAME, "c", Map.of());
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getDefinition()).thenReturn(create);
        when(registry.getExecutor(CreateFileTool.NAME)).thenReturn(executor);

        List<UnifiedToolDef> sessionFiltered = new SessionToolRegistry().filterActive("s1", List.of(create));
        assertThat(sessionFiltered).isEmpty();
        assertThat(ToolCallHandler.withAttached(registry, filter, sessionFiltered))
                .extracting(UnifiedToolDef::name).containsExactly(CreateFileTool.NAME);
    }
}
