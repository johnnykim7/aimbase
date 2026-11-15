package com.platform.workflow.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.service.PromptTemplateService;
import com.platform.skill.ResolvedSkill;
import com.platform.skill.SkillBundleSettings;
import com.platform.skill.SkillCatalog;
import com.platform.skill.SkillNotFoundException;
import com.platform.skill.SkillSandboxClient;
import com.platform.skill.SkillSandboxException;
import com.platform.skill.SkillScope;
import com.platform.skill.SkillScriptRunner;
import com.platform.skill.SkillWorkspaceInstaller;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-193 검사 반복 코드 대조. TC-ECK-001 ~ 013 (T3-5).
 * LLM 호출, 스킬 해석, 묶음 풀기, 실행 컨테이너는 가짜이고 입력 파일 쓰기와 출력 해석은 실제로 돈다.
 */
class EvaluatorLoopCodeCheckTest {

    @TempDir
    Path base;

    private Path workspace;
    private Path bundleDir;
    private LlmCallStepExecutor llm;
    private SkillCatalog catalog;
    private SkillWorkspaceInstaller installer;
    private SkillSandboxClient sandbox;
    private CodeCheckRunner runner;
    private EvaluatorLoopStepExecutor executor;
    /** 실행 컨테이너가 받은 입력 파일 내용. 실행 뒤 파일이 지워지므로 실행 순간에 읽어 둔다. */
    private final List<String> inputFiles = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        workspace = base.resolve("t1/runs/run-1");
        bundleDir = workspace.resolve(".skills/checks/v1");
        Files.createDirectories(bundleDir.resolve("scripts"));
        Files.writeString(bundleDir.resolve("scripts/check.py"), "print('{}')");
        Files.writeString(bundleDir.resolve("scripts/check.rb"), "puts 1");

        llm = mock(LlmCallStepExecutor.class);
        catalog = mock(SkillCatalog.class);
        installer = mock(SkillWorkspaceInstaller.class);
        sandbox = mock(SkillSandboxClient.class);
        SkillBundleSettings settings = mock(SkillBundleSettings.class);
        when(settings.scriptTimeoutSeconds()).thenReturn(120);
        when(settings.scriptTimeoutMaxSeconds()).thenReturn(600);
        when(settings.scriptOutputMaxBytes()).thenReturn(65536);
        when(settings.runnableExtensions()).thenReturn(List.of(".py", ".js", ".mjs", ".sh"));
        when(catalog.resolve(eq("checks"), any()))
                .thenReturn(new ResolvedSkill("checks", SkillScope.TENANT, 1, "", UUID.randomUUID()));
        when(installer.install(any(), any())).thenReturn(bundleDir);

        runner = new CodeCheckRunner(catalog, new SkillScriptRunner(installer, sandbox, settings), settings,
                new ObjectMapper());
        executor = new EvaluatorLoopStepExecutor(llm, mock(PromptTemplateService.class), runner);
    }

    // ── 헬퍼 ─────────────────────────────────────────

    private WorkflowStep step(int maxIter, Map<String, Object> codeCheck) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("max_iterations", maxIter);
        config.put("generator", Map.of("model", "gen-model", "prompt", "draft {{loop.feedback}}"));
        config.put("evaluator", Map.of("model", "eval-model", "prompt", "check {{loop.generator_output}}"));
        config.put("pass_criteria", Map.of("type", "JSONPATH_MATCH", "jsonpath", "$.passed", "expected", true));
        if (codeCheck != null) config.put("code_check", codeCheck);
        return new WorkflowStep("final", "작성과 대조 검사", WorkflowStep.StepType.EVALUATOR_LOOP,
                config, List.of(), null, null, null);
    }

    private static Map<String, Object> codeCheck() {
        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("skill", "checks");
        cc.put("script", "scripts/check.py");
        return cc;
    }

    private StepContext context() {
        return context(workspace.toString());
    }

    private StepContext context(String workspacePath) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("case", Map.of("facts", "민법 제750조"));
        input.put("docType", "내용증명");
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("guide", Map.of("output", "작성법 본문"));
        return new StepContext("run-1", "wf-1", "sess-1", input, results, null, null, workspacePath);
    }

    private static Map<String, Object> gen(String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("output", text);
        r.put("structured_data", Map.of("body", text));
        return r;
    }

    private static Map<String, Object> eval(boolean passed, String feedback) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("passed", passed);
        v.put("feedback", feedback);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("output", "verdict");
        r.put("structured_data", v);
        return r;
    }

    private static SkillSandboxClient.RunResult out(String stdout) {
        return new SkillSandboxClient.RunResult(0, false, stdout, "", false, false, 40, List.of(), 0);
    }

    private static SkillSandboxClient.RunResult failed(int exit, boolean timedOut, String stderr) {
        return new SkillSandboxClient.RunResult(exit, timedOut, "", stderr, false, false, 40, List.of(), 0);
    }

    /** 실행 컨테이너가 차례로 돌려줄 결과. 부를 때마다 입력 파일 내용을 남긴다. */
    private void sandboxReturns(SkillSandboxClient.RunResult... results) {
        List<SkillSandboxClient.RunResult> queue = new ArrayList<>(List.of(results));
        when(sandbox.run(any())).thenAnswer(inv -> {
            SkillSandboxClient.RunRequest req = inv.getArgument(0);
            inputFiles.add(Files.readString(Path.of(req.workdir()).resolve(req.args().get(0))));
            return queue.size() > 1 ? queue.remove(0) : queue.get(0);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> iterations(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("iterations");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> codeCheckOf(Map<String, Object> result, int i) {
        return (Map<String, Object>) iterations(result).get(i).get("code_check");
    }

    private List<StepContext> llmContexts(int calls) {
        ArgumentCaptor<StepContext> captor = ArgumentCaptor.forClass(StepContext.class);
        verify(llm, times(calls)).execute(any(), captor.capture());
        return captor.getAllValues();
    }

    // ── TC-ECK-001 ~ 013 ─────────────────────────────

    @Test
    void TC_ECK_001_without_code_check_nothing_changes() {
        when(llm.execute(any(), any())).thenReturn(gen("초안"), eval(true, "좋다"));

        Map<String, Object> result = executor.execute(step(2, null), context());

        verify(sandbox, never()).run(any());
        assertThat(iterations(result).get(0)).doesNotContainKey("code_check");
        assertThat(result.get("final_iteration")).isEqualTo(0);
        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        assertThat(result.get("structured_data")).isEqualTo(Map.of("body", "초안"));
    }

    @Test
    void TC_ECK_002_violation_then_fixed() {
        when(llm.execute(any(), any())).thenReturn(gen("민법 제598조 초안"), eval(true, null),
                gen("고친 초안"), eval(true, null));
        sandboxReturns(out("{\"passed\":false,\"feedback\":\"민법 제598조가 자료에 없다\",\"issues\":[{\"rule\":1}]}"),
                out("{\"passed\":true,\"issues\":[]}"));

        Map<String, Object> result = executor.execute(step(2, codeCheck()), context());

        assertThat(codeCheckOf(result, 0)).containsEntry("passed", false)
                .containsEntry("feedback", "민법 제598조가 자료에 없다")
                .containsEntry("issues", List.of(Map.of("rule", 1)));
        assertThat(codeCheckOf(result, 1)).containsEntry("passed", true);
        assertThat(result.get("final_iteration")).isEqualTo(1);
        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        assertThat(result.get("output")).isEqualTo("고친 초안");
        StepContext gen1 = llmContexts(4).get(2);
        assertThat(gen1.loopData().get("feedback")).isEqualTo("민법 제598조가 자료에 없다");
    }

    @Test
    void TC_ECK_003_no_violation_passes_first_iteration() {
        when(llm.execute(any(), any())).thenReturn(gen("초안"), eval(true, "좋다"));
        sandboxReturns(out("{\"passed\":true,\"issues\":[]}"));

        Map<String, Object> result = executor.execute(step(2, codeCheck()), context());

        assertThat(codeCheckOf(result, 0)).containsEntry("passed", true).containsEntry("issues", List.of())
                .containsEntry("exit_code", 0).containsEntry("error", null);
        assertThat(result.get("final_iteration")).isEqualTo(0);
        assertThat(result.get("final_verdict")).isEqualTo(Map.of("passed", true, "feedback", "좋다"));
        llmContexts(2);
    }

    @Test
    void TC_ECK_004_feedback_join() {
        Map<String, Object> violation = Map.of("passed", false, "feedback", "조문 지적");
        Map<String, Object> passed = Map.of("passed", true, "feedback", "무시");
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("passed", null);
        error.put("error", "exit code 1");

        assertThat(EvaluatorLoopStepExecutor.joinFeedback(violation, Map.of("feedback", "날짜 지적")))
                .isEqualTo("조문 지적\n날짜 지적");
        assertThat(EvaluatorLoopStepExecutor.joinFeedback(violation, Map.of("feedback", " "))).isEqualTo("조문 지적");
        assertThat(EvaluatorLoopStepExecutor.joinFeedback(violation, null)).isEqualTo("조문 지적");
        assertThat(EvaluatorLoopStepExecutor.joinFeedback(passed, Map.of("feedback", "날짜 지적"))).isEqualTo("날짜 지적");
        assertThat(EvaluatorLoopStepExecutor.joinFeedback(error, Map.of("feedback", "날짜 지적"))).isEqualTo("날짜 지적");
        assertThat(EvaluatorLoopStepExecutor.joinFeedback(null, null)).isNull();
    }

    @Test
    void TC_ECK_005_evaluator_runs_even_on_violation() {
        when(llm.execute(any(), any())).thenReturn(gen("초안"), eval(false, "날짜 지적"),
                gen("둘째 초안"), eval(true, null));
        sandboxReturns(out("{\"passed\":false,\"feedback\":\"조문 지적\"}"), out("{\"passed\":true}"));

        Map<String, Object> result = executor.execute(step(2, codeCheck()), context());

        assertThat(iterations(result).get(0)).containsKey("evaluator_verdict");
        List<StepContext> calls = llmContexts(4);
        assertThat(calls.get(2).loopData().get("feedback")).isEqualTo("조문 지적\n날짜 지적");
        assertThat(calls.get(3).loopData().get("previous_feedback")).isEqualTo("조문 지적\n날짜 지적");
    }

    @Test
    void TC_ECK_006_script_failure_never_passes_and_step_completes() {
        when(llm.execute(any(), any())).thenReturn(gen("초안"), eval(true, "문제 없다"),
                gen("둘째 초안"), eval(true, "문제 없다"));
        sandboxReturns(failed(1, false, "Traceback: boom"));

        Map<String, Object> result = executor.execute(step(2, codeCheck()), context());

        for (int i = 0; i < 2; i++) {
            assertThat(codeCheckOf(result, i)).containsEntry("passed", null).containsEntry("error", "exit code 1")
                    .containsEntry("stderr", "Traceback: boom").containsEntry("exit_code", 1);
        }
        assertThat(result.get("loop_exhausted")).isEqualTo(true);
        assertThat(result.get("output")).isEqualTo("둘째 초안");
        assertThat(llmContexts(4).get(2).loopData().get("feedback")).isEqualTo("문제 없다");
    }

    @Test
    void TC_ECK_007_timeout_is_not_a_pass() {
        when(llm.execute(any(), any())).thenReturn(gen("초안"), eval(true, null));
        sandboxReturns(failed(-1, true, ""));

        Map<String, Object> result = executor.execute(step(1, codeCheck()), context());

        assertThat(codeCheckOf(result, 0)).containsEntry("passed", null).containsEntry("timed_out", true)
                .containsEntry("error", "timed out after 120s").doesNotContainKey("stderr");
        assertThat(result.get("loop_exhausted")).isEqualTo(true);
    }

    static Stream<Arguments> badOutputs() {
        return Stream.of(
                Arguments.of("not json", "stdout is not a JSON object"),
                Arguments.of("[{\"passed\":true}]", "stdout is not a JSON object"),
                Arguments.of("{\"passed\":true} extra", "stdout is not a JSON object"),
                Arguments.of("", "stdout is not a JSON object"),
                Arguments.of("{\"feedback\":\"x\"}", "passed must be a boolean"),
                Arguments.of("{\"passed\":\"true\"}", "passed must be a boolean"),
                Arguments.of("{\"passed\":false,\"feedback\":\" \"}", "feedback is required when passed is false"),
                Arguments.of("{\"passed\":false}", "feedback is required when passed is false"),
                Arguments.of("{\"passed\":true,\"feedback\":3}", "feedback must be a string"),
                Arguments.of("{\"passed\":true,\"issues\":{\"a\":1}}", "issues must be an array"));
    }

    @ParameterizedTest
    @MethodSource("badOutputs")
    void TC_ECK_008_output_outside_contract(String stdout, String error) {
        sandboxReturns(out(stdout));
        CodeCheckRunner.Prepared p = runner.prepare("final", step(1, codeCheck()).config(), context());

        Map<String, Object> rec = runner.run(p, 0, null, "초안", context());

        assertThat(rec).containsEntry("passed", null).containsEntry("error", error).containsEntry("exit_code", 0);
    }

    @Test
    void TC_ECK_008_truncated_stdout_is_outside_contract() {
        when(sandbox.run(any())).thenReturn(new SkillSandboxClient.RunResult(0, false, "{\"passed\":true}", "",
                true, false, 40, List.of(), 0));
        CodeCheckRunner.Prepared p = runner.prepare("final", step(1, codeCheck()).config(), context());

        assertThat(runner.run(p, 0, null, "초안", context()).get("error").toString())
                .startsWith("stdout is not a JSON object");
    }

    @Test
    void TC_ECK_009_sandbox_error_continues_step() {
        when(llm.execute(any(), any())).thenReturn(gen("초안"), eval(true, null), gen("둘째"), eval(true, null));
        when(sandbox.run(any())).thenThrow(new SkillSandboxException("BIZ-122: 실행 컨테이너에 닿지 못했습니다"));

        Map<String, Object> result = executor.execute(step(2, codeCheck()), context());

        assertThat(codeCheckOf(result, 0)).containsEntry("passed", null)
                .containsEntry("error", "BIZ-122: 실행 컨테이너에 닿지 못했습니다")
                .doesNotContainKeys("exit_code", "timed_out", "duration_ms");
        assertThat(result.get("loop_exhausted")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void TC_ECK_010_input_file() throws Exception {
        sandboxReturns(out("{\"passed\":true}"));
        Map<String, Object> cc = codeCheck();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("case", "{{input.case}}");
        input.put("label", "사건 {{input.docType}}");
        input.put("nested", Map.of("guide", "{{guide.output}}", "list", List.of("{{input.docType}}")));
        input.put("round", "{{loop.iteration}}");
        cc.put("input", input);
        CodeCheckRunner.Prepared p = runner.prepare("final", step(1, cc).config(), context());
        StepContext loopCtx = context().withLoopVars(Map.of("iteration", 0));

        runner.run(p, 0, Map.of("body", "초안"), "초안 글", loopCtx);

        ArgumentCaptor<SkillSandboxClient.RunRequest> req = ArgumentCaptor.forClass(SkillSandboxClient.RunRequest.class);
        verify(sandbox).run(req.capture());
        assertThat(req.getValue().workdir()).isEqualTo(workspace.toString());
        assertThat(req.getValue().script()).isEqualTo(bundleDir.resolve("scripts/check.py").toString());
        assertThat(req.getValue().args()).hasSize(1);
        assertThat(req.getValue().args().get(0)).startsWith(".code-check/").endsWith(".json");
        Map<String, Object> file = new ObjectMapper().readValue(inputFiles.get(0), Map.class);
        assertThat(file).containsEntry("step_id", "final").containsEntry("iteration", 0)
                .containsEntry("output", Map.of("body", "초안")).containsEntry("output_text", "초안 글");
        Map<String, Object> resolved = (Map<String, Object>) file.get("input");
        assertThat(resolved.get("case")).isEqualTo(Map.of("facts", "민법 제750조"));
        assertThat(resolved.get("label")).isEqualTo("사건 내용증명");
        assertThat(resolved.get("nested")).isEqualTo(Map.of("guide", "작성법 본문", "list", List.of("내용증명")));
        assertThat(resolved.get("round")).isEqualTo(0);
        try (Stream<Path> left = Files.list(workspace.resolve(".code-check"))) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void TC_ECK_011_timeout_default_requested_and_clamped() {
        sandboxReturns(out("{\"passed\":true}"));
        List<Integer> seen = new ArrayList<>();
        for (Integer requested : new Integer[]{null, 5, 9999}) {
            Map<String, Object> cc = codeCheck();
            if (requested != null) cc.put("timeout_seconds", requested);
            seen.add(runner.prepare("final", step(1, cc).config(), context()).timeoutSeconds());
        }
        assertThat(seen).containsExactly(120, 5, 600);
    }

    static Stream<Arguments> configErrors() {
        return Stream.of(
                Arguments.of("missing", "scripts/check.py", "활성 스킬 묶음이 없습니다"),
                Arguments.of("checks", "../x.py", "스킬 묶음 밖"),
                Arguments.of("checks", "scripts/none.py", "스크립트가 없습니다"),
                Arguments.of("checks", "scripts/check.rb", "실행할 수 없는 확장자"));
    }

    @ParameterizedTest
    @MethodSource("configErrors")
    void TC_ECK_012_config_errors_fail_before_generation(String skill, String script, String message) {
        when(catalog.resolve(eq("missing"), any()))
                .thenThrow(new SkillNotFoundException("BIZ-120: 활성 스킬 묶음이 없습니다: missing"));
        Map<String, Object> cc = codeCheck();
        cc.put("skill", skill);
        cc.put("script", script);

        assertThatThrownBy(() -> executor.execute(step(2, cc), context()))
                .hasMessageContaining("step 'final'").hasMessageContaining(message);
        verify(llm, never()).execute(any(), any());
    }

    @Test
    void TC_ECK_012_missing_workspace_fails_before_generation() {
        assertThatThrownBy(() -> executor.execute(step(2, codeCheck()), context(null)))
                .hasMessageContaining("BIZ-122");
        verify(llm, never()).execute(any(), any());
        verify(installer, never()).install(any(), any());
    }

    @Test
    void TC_ECK_013_failed_generation_skips_code_check() {
        when(llm.execute(any(), any())).thenThrow(new RuntimeException("gen down"))
                .thenReturn(gen("초안"), eval(true, null));
        sandboxReturns(out("{\"passed\":true}"));

        Map<String, Object> result = executor.execute(step(2, codeCheck()), context());

        assertThat(iterations(result).get(0)).containsKey("generator_error").doesNotContainKey("code_check");
        assertThat(codeCheckOf(result, 1)).containsEntry("passed", true);
        verify(sandbox, times(1)).run(any());
    }
}
