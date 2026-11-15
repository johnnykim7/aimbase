package com.platform.workflow.step;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.skill.ResolvedSkill;
import com.platform.skill.SkillBundleSettings;
import com.platform.skill.SkillCatalog;
import com.platform.skill.SkillSandboxClient;
import com.platform.skill.SkillSandboxException;
import com.platform.skill.SkillScriptRunner;
import com.platform.workflow.StepContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 검사 반복의 코드 대조(CR-193, BIZ-195, T3-7 16절). 단계 시작 때 설정을 확인해 스킬 판과 스크립트를 고정하고,
 * 회차마다 작성 결과를 입력 파일로 넘겨 대조 스크립트를 돌린 뒤 회차 기록에 넣을 결과를 만든다.
 */
@Component
public class CodeCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(CodeCheckRunner.class);
    static final String CONFIG_KEY = "code_check";
    /** 입력 파일을 두는 작업장 안 폴더. 실행 컨테이너가 같은 경로로 본다. */
    static final String INPUT_DIR = ".code-check";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final SkillCatalog catalog;
    private final SkillScriptRunner scriptRunner;
    private final SkillBundleSettings settings;
    private final ObjectMapper mapper;

    public CodeCheckRunner(SkillCatalog catalog, SkillScriptRunner scriptRunner, SkillBundleSettings settings,
                           ObjectMapper mapper) {
        this.catalog = catalog;
        this.scriptRunner = scriptRunner;
        this.settings = settings;
        this.mapper = mapper;
    }

    /** 단계 시작 때 고정한 것. 모든 회차가 같은 판과 스크립트를 쓴다. */
    public record Prepared(String stepId, Path workspace, Path script, int timeoutSeconds,
                           Map<String, Object> input) {}

    /**
     * 설정이 없으면 null. 설정이 틀렸으면(없는 스킬이나 판, 묶음 밖이거나 없는 스크립트,
     * 실행할 수 없는 확장자, 작업장 없음) 예외를 던져 작성 호출 전에 단계를 실패시킨다.
     */
    @SuppressWarnings("unchecked")
    public Prepared prepare(String stepId, Map<String, Object> stepConfig, StepContext context) {
        Object raw = stepConfig.get(CONFIG_KEY);
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> cfgRaw)) {
            throw stepError(stepId, "code_check must be an object");
        }
        Map<String, Object> cfg = (Map<String, Object>) cfgRaw;
        String skillName = requireText(stepId, cfg, "skill");
        String script = requireText(stepId, cfg, "script");
        Integer version = optionalPositiveInt(stepId, cfg, "version");
        Integer timeout = optionalPositiveInt(stepId, cfg, "timeout_seconds");
        Object input = cfg.get("input");
        if (input != null && !(input instanceof Map)) {
            throw stepError(stepId, "code_check.input must be an object");
        }

        String workspaceRaw = context.workspacePath();
        if (workspaceRaw == null || workspaceRaw.isBlank()) {
            throw stepError(stepId, "BIZ-122: 작업장이 없는 실행에서는 코드 대조를 돌릴 수 없습니다");
        }
        Path workspace = Path.of(workspaceRaw).toAbsolutePath().normalize();

        try {
            ResolvedSkill skill = catalog.resolve(skillName, version);
            Path scriptPath = scriptRunner.installBundleScript(skill, workspace, script);
            if (!Files.isRegularFile(scriptPath)) {
                throw new IllegalArgumentException("스킬 " + skill.name() + " 판 " + skill.version()
                        + " 에 스크립트가 없습니다: " + script);
            }
            String fileName = scriptPath.getFileName().toString().toLowerCase(Locale.ROOT);
            List<String> runnable = settings.runnableExtensions();
            if (runnable.stream().noneMatch(ext -> fileName.endsWith(ext.toLowerCase(Locale.ROOT)))) {
                throw new IllegalArgumentException("실행할 수 없는 확장자입니다: " + script + " (허용: " + runnable + ")");
            }
            return new Prepared(stepId, workspace, scriptPath, scriptRunner.timeoutSeconds(timeout),
                    input == null ? Map.of() : (Map<String, Object>) input);
        } catch (RuntimeException e) {
            throw stepError(stepId, "code_check: " + e.getMessage());
        }
    }

    /**
     * 한 회차의 코드 대조. 예외를 던지지 않는다. 실패는 passed null 과 error 로 기록한다(BIZ-195 결정 2).
     *
     * @param loopContext 그 회차 검사 LLM 이 보는 것과 같은 루프 변수를 담은 문맥. input 의 틀을 여기서 푼다
     */
    public Map<String, Object> run(Prepared p, int iteration, Object structuredOutput, String outputText,
                                   StepContext loopContext) {
        Map<String, Object> record = new LinkedHashMap<>();
        Path inputFile = null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("step_id", p.stepId());
            payload.put("iteration", iteration);
            payload.put("output", structuredOutput);
            payload.put("output_text", outputText);
            payload.put("input", resolveValue(p.input(), loopContext));

            Path dir = p.workspace().resolve(INPUT_DIR);
            Files.createDirectories(dir);
            inputFile = dir.resolve(safeName(p.stepId()) + "-" + iteration + "-" + UUID.randomUUID() + ".json");
            mapper.writeValue(inputFile.toFile(), payload);
            String arg = p.workspace().relativize(inputFile).toString();

            SkillSandboxClient.RunResult result = scriptRunner.run(p.workspace(), p.script(), List.of(arg),
                    p.timeoutSeconds());
            record.put("exit_code", result.exitCode());
            record.put("timed_out", result.timedOut());
            record.put("duration_ms", result.durationMs());
            interpret(p, result, record);
        } catch (SkillSandboxException | IOException e) {
            fail(record, e.getMessage(), null);
        } finally {
            if (inputFile != null) {
                try {
                    Files.deleteIfExists(inputFile);
                } catch (IOException e) {
                    log.warn("EVALUATOR_LOOP[{}] code_check input file not deleted: {}", p.stepId(), inputFile);
                }
            }
        }
        if (record.get("error") != null) {
            log.warn("EVALUATOR_LOOP[{}] iter {} code_check failed: {}", p.stepId(), iteration, record.get("error"));
        }
        return order(record);
    }

    private void interpret(Prepared p, SkillSandboxClient.RunResult r, Map<String, Object> record) {
        if (r.timedOut()) {
            fail(record, "timed out after " + p.timeoutSeconds() + "s", r.stderr());
            return;
        }
        if (r.exitCode() != 0) {
            fail(record, "exit code " + r.exitCode(), r.stderr());
            return;
        }
        if (r.stdoutTruncated()) {
            fail(record, "stdout is not a JSON object (truncated at " + scriptRunner.outputMaxBytes() + " bytes)",
                    r.stderr());
            return;
        }
        Map<String, Object> out;
        try {
            String text = r.stdout() == null ? "" : r.stdout().strip();
            // stdout 전체가 객체 하나여야 하므로 뒤에 붙은 글도 규약 밖으로 본다
            out = text.startsWith("{") ? mapper.readerFor(MAP).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(text) : null;
        } catch (IOException e) {
            out = null;
        }
        if (out == null) {
            fail(record, "stdout is not a JSON object", r.stderr());
            return;
        }
        if (!(out.get("passed") instanceof Boolean passed)) {
            fail(record, "passed must be a boolean", r.stderr());
            return;
        }
        Object feedback = out.get("feedback");
        if (feedback != null && !(feedback instanceof String)) {
            fail(record, "feedback must be a string", r.stderr());
            return;
        }
        if (!passed && (feedback == null || ((String) feedback).isBlank())) {
            fail(record, "feedback is required when passed is false", r.stderr());
            return;
        }
        Object issues = out.get("issues");
        if (issues != null && !(issues instanceof List)) {
            fail(record, "issues must be an array", r.stderr());
            return;
        }
        record.put("passed", passed);
        record.put("feedback", feedback);
        record.put("issues", issues == null ? List.of() : issues);
        record.put("error", null);
    }

    private static void fail(Map<String, Object> record, String error, String stderr) {
        record.put("passed", null);
        record.put("error", error);
        if (stderr != null && !stderr.isEmpty()) {
            record.put("stderr", stderr);
        }
    }

    /** 회차 기록의 칸 순서를 T3-7 16.5 와 맞춘다. */
    private static Map<String, Object> order(Map<String, Object> record) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : List.of("passed", "feedback", "issues", "exit_code", "timed_out", "duration_ms",
                "error", "stderr")) {
            if (record.containsKey(key)) ordered.put(key, record.get(key));
        }
        return ordered;
    }

    /** {{참조}} 하나뿐인 글은 참조한 값을 객체 그대로, 그 밖의 글은 글로 푼다. 객체와 배열 안도 같다. */
    @SuppressWarnings("unchecked")
    Object resolveValue(Object value, StepContext ctx) {
        if (value instanceof String s) {
            // resolveObject 는 {{참조}} 하나뿐이고 값이 있을 때만 null 이 아니다.
            Object resolved = ctx.resolveObject(s);
            return resolved != null ? resolved : ctx.resolve(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), resolveValue(e.getValue(), ctx));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(resolveValue(o, ctx));
            return out;
        }
        return value;
    }

    private static String safeName(String stepId) {
        return stepId == null ? "step" : stepId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String requireText(String stepId, Map<String, Object> cfg, String key) {
        if (!(cfg.get(key) instanceof String s) || s.isBlank()) {
            throw stepError(stepId, "code_check." + key + " is required");
        }
        return s.trim();
    }

    private static Integer optionalPositiveInt(String stepId, Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null) return null;
        if (!(v instanceof Integer || v instanceof Long || v instanceof Short) || ((Number) v).longValue() < 1
                || ((Number) v).longValue() > Integer.MAX_VALUE) {
            throw stepError(stepId, "code_check." + key + " must be an integer of 1 or more");
        }
        return ((Number) v).intValue();
    }

    private static RuntimeException stepError(String stepId, String message) {
        return new RuntimeException("EVALUATOR_LOOP step '" + stepId + "': " + message);
    }
}
