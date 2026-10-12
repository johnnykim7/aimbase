package com.platform.tool.builtin;

import com.platform.config.WorkspaceProperties;
import com.platform.skill.*;
import com.platform.tool.PermissionLevel;
import com.platform.tool.SkillRef;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-147 skill_run 의 api 쪽 판단(작업장, 경로, 시간 상한)과 결과 글. TC-SKB-016~018.
 * 실제 실행은 실행 컨테이너 시험(skill-sandbox/tests)과 로컬 도커로 본다.
 */
class SkillRunToolTest {

    @TempDir
    Path base;

    private Path workspace;
    private SkillCatalog catalog;
    private SkillWorkspaceInstaller installer;
    private SkillSandboxClient sandbox;
    private SkillRunTool tool;

    private static final SkillSandboxClient.RunResult OK = new SkillSandboxClient.RunResult(
            0, false, "covered 12/12\n", "", false, false, 812, List.of("out/coverage.md"), 1);

    @BeforeEach
    void setUp() {
        workspace = base.resolve("t1/runs/run-1");
        catalog = mock(SkillCatalog.class);
        installer = mock(SkillWorkspaceInstaller.class);
        sandbox = mock(SkillSandboxClient.class);
        SkillBundleSettings settings = mock(SkillBundleSettings.class);
        when(settings.scriptTimeoutSeconds()).thenReturn(120);
        when(settings.scriptTimeoutMaxSeconds()).thenReturn(600);
        when(settings.scriptOutputMaxBytes()).thenReturn(65536);
        WorkspaceProperties props = new WorkspaceProperties();
        props.setWhitelistRoots(List.of(base.toString()));
        when(sandbox.run(any())).thenReturn(OK);
        tool = new SkillRunTool(catalog, installer, sandbox, settings, props);
    }

    private ToolContext ctx(String workspacePath, List<SkillRef> skillSet) {
        return new ToolContext("t1", null, null, "s1", "run-1", "step-1", null, null, null,
                PermissionLevel.RESTRICTED_WRITE, null, workspacePath, false, 0, skillSet);
    }

    private SkillSandboxClient.RunRequest sent() {
        ArgumentCaptor<SkillSandboxClient.RunRequest> req = ArgumentCaptor.forClass(SkillSandboxClient.RunRequest.class);
        verify(sandbox).run(req.capture());
        return req.getValue();
    }

    @Test
    void TC_SKB_016_bundle_script_runs_from_installed_dir() {
        List<SkillRef> skillSet = List.of(new SkillRef("ax-proposal", "tenant", 1));
        ResolvedSkill skill = new ResolvedSkill("ax-proposal", SkillScope.TENANT, 1, "d", UUID.randomUUID());
        Path bundleDir = workspace.resolve(".skills/ax-proposal/v1");
        when(catalog.resolveForCall("ax-proposal", skillSet)).thenReturn(skill);
        when(installer.install(skill, workspace)).thenReturn(bundleDir);

        ToolResult r = tool.execute(Map.of("name", "ax-proposal", "script", "scripts/check_coverage.py",
                "args", List.of("reqs.md", "draft.md")), ctx(workspace.toString(), skillSet));

        assertThat(r.success()).isTrue();
        SkillSandboxClient.RunRequest req = sent();
        assertThat(req.workdir()).isEqualTo(workspace.toString());
        assertThat(req.script()).isEqualTo(bundleDir.resolve("scripts/check_coverage.py").toString());
        assertThat(req.args()).containsExactly("reqs.md", "draft.md");
        assertThat(req.timeoutSeconds()).isEqualTo(120);
        assertThat(req.maxOutputBytes()).isEqualTo(65536);
        assertThat((String) r.output()).contains("exit_code: 0", "- out/coverage.md", "covered 12/12");
        assertThat(r.summary()).isEqualTo("exit 0");
    }

    @Test
    void TC_SKB_017_workspace_script_runs_without_name() {
        ToolResult r = tool.execute(Map.of("script", "build.js"), ctx(workspace.toString(), null));

        assertThat(r.success()).isTrue();
        assertThat(sent().script()).isEqualTo(workspace.resolve("build.js").toString());
        verify(installer, never()).install(any(), any());
    }

    @Test
    void TC_SKB_018_call_without_workspace_is_rejected() {
        ToolResult r = tool.execute(Map.of("script", "build.js"), ctx(null, null));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("BIZ-122");
        verify(sandbox, never()).run(any());
    }

    @Test
    void workspace_outside_whitelist_is_rejected() {
        ToolResult r = tool.execute(Map.of("script", "build.js"), ctx("/etc", null));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("BIZ-122");
        verify(sandbox, never()).run(any());
    }

    @Test
    void script_path_escaping_workspace_or_bundle_is_rejected() {
        ToolResult outside = tool.execute(Map.of("script", "../../other/x.py"), ctx(workspace.toString(), null));

        ResolvedSkill skill = new ResolvedSkill("demo", SkillScope.TENANT, 1, "d", UUID.randomUUID());
        when(catalog.resolveForCall(eq("demo"), any())).thenReturn(skill);
        when(installer.install(skill, workspace)).thenReturn(workspace.resolve(".skills/demo/v1"));
        ToolResult outOfBundle = tool.execute(Map.of("name", "demo", "script", "../../../x.py"),
                ctx(workspace.toString(), null));

        assertThat(outside.success()).isFalse();
        assertThat(outOfBundle.success()).isFalse();
        assertThat(outOfBundle.summary()).contains("BIZ-122");
        verify(sandbox, never()).run(any());
    }

    @Test
    void undesignated_skill_is_tool_error() {
        when(catalog.resolveForCall(eq("other"), any()))
                .thenThrow(new SkillNotFoundException("BIZ-121: 이 호출에 지정되지 않은 스킬입니다: other"));

        ToolResult r = tool.execute(Map.of("name", "other", "script", "a.py"),
                ctx(workspace.toString(), List.of(new SkillRef("demo", "tenant", 1))));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("BIZ-121");
    }

    @Test
    void timeout_is_clamped_and_accepts_strings() {
        tool.execute(Map.of("script", "a.py", "timeout_seconds", "9999"), ctx(workspace.toString(), null));
        assertThat(sent().timeoutSeconds()).isEqualTo(600);
    }

    @Test
    void bad_args_are_input_errors() {
        ToolResult r = tool.execute(Map.of("script", "a.py", "args", "not-an-array"), ctx(workspace.toString(), null));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).startsWith("INVALID_INPUT");
    }

    @Test
    void sandbox_failure_is_tool_error() {
        when(sandbox.run(any())).thenThrow(new SkillSandboxException("실행 컨테이너에 닿지 못했습니다: refused"));

        ToolResult r = tool.execute(Map.of("script", "a.py"), ctx(workspace.toString(), null));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("닿지 못했습니다");
    }

    @Test
    void format_reports_timeout_truncation_and_remaining_files() {
        SkillSandboxClient.RunResult res = new SkillSandboxClient.RunResult(
                -9, true, "partial", "err", true, false, 2003, List.of("a.txt", "b.txt"), 5);

        String text = SkillRunTool.format(res, 100);

        assertThat(text).contains("exit_code: -9", "timed_out: true", "changed_files (5):",
                "- b.txt", "- ... 3 more", "partial\n[stdout truncated at 100 bytes]", "stderr:\nerr\n");
        assertThat(text).doesNotContain("[stderr truncated");
    }
}
