package com.platform.orchestrator;

import com.platform.config.PlatformSettingsService;
import com.platform.tool.ToolFilterContext;
import com.platform.tool.builtin.CreateFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-182 TC-CHT-083: 대화 요청에 파일 만들기 도구를 주는 조건. */
class FileCreateToolGateTest {

    private PlatformSettingsService settings;
    private FileCreateToolGate gate;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        gate = new FileCreateToolGate(settings);
    }

    @Test
    @DisplayName("TC-CHT-083 API 연결, file_create 참, actions 없음이면 도구는 create_file 하나")
    void api_file_create_without_actions_gets_only_create_file() {
        ChatRequest req = request(false, null, true);

        assertThat(gate.loop(req, false)).isTrue();
        ToolFilterContext filter = gate.filter(req, null);
        assertThat(filter.allowedTools()).containsExactly(CreateFileTool.NAME);
        assertThat(filter.isToolAllowed(CreateFileTool.NAME)).isTrue();
        assertThat(filter.isToolAllowed("bash")).isFalse();
        assertThat(filter.attaches(CreateFileTool.NAME)).isTrue();
        assertThat(gate.extraAllowed(req, false)).isNull();
    }

    @Test
    @DisplayName("TC-CHT-083 스트리밍 고리의 세션 도구 거르기에 빠지지 않게 create_file 을 붙인 도구로 싣는다")
    void file_create_is_attached_so_session_filter_keeps_it() {
        ToolFilterContext all = gate.filter(request(true, null, true), null);
        assertThat(all.allowedTools()).isNull();
        assertThat(all.isToolAllowed("bash")).isTrue();
        assertThat(all.attaches(CreateFileTool.NAME)).isTrue();

        ToolFilterContext excluded = new ToolFilterContext(null, List.of(CreateFileTool.NAME), null);
        assertThat(gate.filter(request(true, excluded, true), excluded).attaches(CreateFileTool.NAME)).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-083 file_create 가 없으면 도구 고리를 돌지 않는다(기존 경로)")
    void no_file_create_keeps_existing_path() {
        ChatRequest req = request(false, null, false);

        assertThat(gate.on(req)).isFalse();
        assertThat(gate.loop(req, false)).isFalse();
        assertThat(gate.loop(req, true)).isFalse();
        assertThat(gate.extraAllowed(req, true)).isNull();
    }

    @Test
    @DisplayName("TC-CHT-083 설정이 꺼져 있으면 file_create 를 실어도 도구가 없다")
    void disabled_setting_gives_no_tool() {
        when(settings.getString(eq(FileCreateToolGate.ENABLED_KEY), any())).thenReturn("false");
        ChatRequest req = request(false, null, true);

        assertThat(gate.enabled()).isFalse();
        assertThat(gate.on(req)).isFalse();
        assertThat(gate.loop(req, false)).isFalse();
        assertThat(gate.extraAllowed(req, true)).isNull();
    }

    @Test
    @DisplayName("TC-CHT-083 CLI 연결에 file_create 참이면 고리 없이 허용 도구에 create_file 을 더한다")
    void cli_file_create_adds_create_file_to_allowed_tools() {
        ChatRequest req = request(false, null, true);

        assertThat(gate.loop(req, true)).isFalse();
        assertThat(gate.extraAllowed(req, true)).containsExactly(CreateFileTool.NAME);
    }

    @Test
    @DisplayName("TC-CHT-083 actions 를 켠 대화는 file_create 가 없으면 create_file 을 빼고, 있으면 허용 목록에 더한다")
    void actions_enabled_request_excludes_or_adds_create_file() {
        ToolFilterContext base = new ToolFilterContext(List.of("bash"), null, null);

        ToolFilterContext without = gate.filter(request(true, base, false), base);
        assertThat(without.excludeTools()).contains(CreateFileTool.NAME);
        assertThat(without.allowedTools()).containsExactly("bash");

        ToolFilterContext with = gate.filter(request(true, base, true), base);
        assertThat(with.allowedTools()).containsExactly("bash", CreateFileTool.NAME);

        ToolFilterContext none = gate.filter(request(true, null, false), null);
        assertThat(none.isToolAllowed(CreateFileTool.NAME)).isFalse();
        assertThat(gate.extraAllowed(request(true, null, true), true)).isNull();
    }

    @Test
    @DisplayName("TC-CHT-083 도구를 켜지 않은 대화에 계산 도구가 붙어 있으면 create_file 을 함께 열고, 없으면 계산 도구만 둔다")
    void data_query_only_base_gets_create_file_added() {
        ToolFilterContext dataOnly = new ToolFilterContext(List.of("data_query"), null, null,
                null, null, null, List.of("data_query"));

        ToolFilterContext with = gate.filter(request(false, null, true), dataOnly);
        assertThat(with.allowedTools()).containsExactly("data_query", CreateFileTool.NAME);
        assertThat(with.attachedTools()).containsExactly("data_query", CreateFileTool.NAME);
        assertThat(with.isToolAllowed("bash")).isFalse();

        assertThat(gate.filter(request(false, null, false), dataOnly)).isSameAs(dataOnly);
    }

    private static ChatRequest request(boolean actionsEnabled, ToolFilterContext filter, boolean fileCreate) {
        return new ChatRequest("m", "s1", List.of(), false, actionsEnabled, "user-a", null, null, filter,
                null, null, null, null, null, null, null, null, null, null, null, null).withFileCreate(fileCreate);
    }
}
