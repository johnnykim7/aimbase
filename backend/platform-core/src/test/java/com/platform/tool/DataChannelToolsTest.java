package com.platform.tool;

import com.platform.tool.builtin.CreateFileTool;
import com.platform.tool.builtin.DataQueryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-182 TC-CHT-083 실행기 통로에 싣는 도구 목록(3.2.2, BIZ-210). */
class DataChannelToolsTest {

    @Test
    @DisplayName("거르기가 붙인 통로 도구만 고르고, 거르기가 없으면 빈 목록이다")
    void of_picksAttachedChannelTools() {
        ToolFilterContext both = ToolFilterContext.withAttached(null, List.of(CreateFileTool.NAME, DataQueryTool.NAME));
        ToolFilterContext excluded = ToolFilterContext.withAttached(
                new ToolFilterContext(null, List.of(DataQueryTool.NAME), null, null, null, null),
                List.of(DataQueryTool.NAME, CreateFileTool.NAME));

        assertThat(DataChannelTools.of(both)).containsExactly(DataQueryTool.NAME, CreateFileTool.NAME);
        assertThat(DataChannelTools.of(excluded)).containsExactly(CreateFileTool.NAME);
        assertThat(DataChannelTools.of(null)).isEmpty();
    }

    @Test
    @DisplayName("요청에 목록이 없으면 계산 도구 하나로 보고, 모르는 이름은 버린다")
    void open_defaultsToDataQuery() {
        assertThat(DataChannelTools.open(null)).containsExactly(DataQueryTool.NAME);
        assertThat(DataChannelTools.open(List.of())).containsExactly(DataQueryTool.NAME);
        assertThat(DataChannelTools.open(List.of(CreateFileTool.NAME, "bash"))).containsExactly(CreateFileTool.NAME);
    }

    @Test
    @DisplayName("머리글은 쉼표 목록으로 오가고, 머리글이 없으면 계산 도구 하나다")
    void header_roundTrip() {
        String header = DataChannelTools.encode(List.of(DataQueryTool.NAME, CreateFileTool.NAME));

        assertThat(header).isEqualTo("data_query,create_file");
        assertThat(DataChannelTools.decode(header)).containsExactly(DataQueryTool.NAME, CreateFileTool.NAME);
        assertThat(DataChannelTools.decode(" create_file ")).containsExactly(CreateFileTool.NAME);
        assertThat(DataChannelTools.decode(null)).containsExactly(DataQueryTool.NAME);
        assertThat(DataChannelTools.decode("")).containsExactly(DataQueryTool.NAME);
    }
}
