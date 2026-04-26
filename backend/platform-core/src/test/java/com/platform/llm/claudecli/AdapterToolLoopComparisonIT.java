package com.platform.llm.claudecli;

import com.platform.context.AssemblyResult;
import com.platform.context.ContextAssemblyEngine;
import com.platform.integration.TestSecurityConfig;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-050 어댑터 비교 IT — OrchestratorEngine 본체 통합 호출.
 *
 * <p>CR-067 정공 검증 후 추가: 두 시나리오로 어댑터 비대칭(API 가 도구 안 부름) 원인 추적.
 * <ul>
 *   <li>concrete: 디렉토리 명시 ("claudecli-src 디렉토리의 .java 파일 6개를 분석")</li>
 *   <li>abstract: 디렉토리 익명화 ("주어진 디렉토리의 java 파일들을 분석")</li>
 * </ul>
 * concrete 에서만 API 가 도구 없이 답하면 prompt 의 디렉토리명이 모델 추측에 트리거됐다는 증거.
 *
 * <p>또한 {@link ContextAssemblyEngine} 직접 주입으로 LLM 에 실제 전달된 메시지를 dump 한다.
 * 시스템 프롬프트 길이, 사용자 메시지 본문, 메모리/히스토리 포함 여부를 확인.
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_CLI_IT", matches = "true")
@SpringBootTest(properties = {
        "platform.llm.anthropic-cli.enabled=true",
        "spring.flyway.enabled=false",
        "security.enabled=false"
})
@Import(TestSecurityConfig.class)
class AdapterToolLoopComparisonIT {

    private static final Logger log = LoggerFactory.getLogger(AdapterToolLoopComparisonIT.class);
    private static final String TENANT = "tenant_dev";
    private static final String API_CONNECTION = "claude-sonnet-dev";
    private static final String CLI_CONNECTION = "claude-cli-dev";
    private static final String MODEL = "claude-sonnet-4-6";

    @Autowired
    private OrchestratorEngine orchestrator;

    @Autowired
    private ContextAssemblyEngine contextAssemblyEngine;

    @BeforeEach
    void setupTenant() {
        TenantContext.setTenantId(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void compare_source_analysis_with_tool_loop() {
        String workingDir = System.getProperty("user.home") + "/aimbase-workspace/claudecli-src";

        // 시나리오 A: concrete — 디렉토리명·파일 개수 명시
        String concretePrompt = """
                작업: claudecli-src 디렉토리의 .java 파일 6개를 분석하여 1~2문단 짧은 요약을 작성하세요.

                절차:
                1. 도구로 디렉토리의 .java 파일 목록을 확인 (1번 호출)
                2. 각 파일을 도구로 읽기 (6번 호출)
                3. 짧게 종합 요약 작성 — 각 클래스 한 줄, 협력 흐름 2~3줄. 길게 쓰지 말 것.

                중요:
                - 같은 도구를 같은 인자로 반복 호출하지 마세요.
                - 추측하지 말고 도구 결과만 사용하세요.
                - 응답은 500자 이내로 짧게.
                """;

        // 시나리오 B: abstract — 디렉토리명 마스킹, 도구 사용 강제
        String abstractPrompt = """
                작업: 워크스페이스 루트의 java 파일들을 분석하여 1~2문단 짧은 요약을 작성하세요.

                절차:
                1. builtin_glob 으로 워크스페이스의 .java 파일 목록 확인 (도구 호출 필수)
                2. 발견된 각 파일을 builtin_file_read 로 읽기 (도구 호출 필수)
                3. 짧게 종합 요약 작성 — 각 클래스 한 줄, 협력 흐름 2~3줄.

                중요:
                - 도구 호출 없이 답변 작성 금지.
                - 추측 금지, 도구 결과로만 작성.
                - 응답은 500자 이내.
                """;

        long suffix = System.currentTimeMillis();

        // CR-067 dump 검증 — API 만 (CLI 도구 호출은 이미 검증됨)
        Result apiC = runOnce("API-C", API_CONNECTION, "cmp-api-c-" + suffix, concretePrompt, workingDir);
        Result apiA = runOnce("API-A", API_CONNECTION, "cmp-api-a-" + suffix, abstractPrompt, workingDir);

        // CLI 는 placeholder 결과로 report 형식 유지
        Result cliPlaceholder = new Result();
        cliPlaceholder.text = "(skipped — CLI tool calls already verified in previous runs)";
        printReport("CONCRETE", apiC, cliPlaceholder);
        printReport("ABSTRACT", apiA, cliPlaceholder);

        assertThat(apiC.text).isNotBlank();
        assertThat(apiA.text).isNotBlank();
    }

    private Result runOnce(String label, String connectionId, String sessionId, String prompt,
                           String workingDirectory) {
        ChatRequest req = new ChatRequest(
                MODEL,
                sessionId,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, prompt)),
                false,                  // stream
                true,                   // actionsEnabled — 도구 루프 활성화
                "cr050-it",             // userId
                null,                   // ragSourceId
                connectionId,
                null,                   // toolFilter
                null,                   // toolChoice
                null,                   // responseFormat
                null,                   // connectionGroupId
                workingDirectory);      // workingDirectory — 워크스페이스 샌드박스 경로

        // ★ DUMP: orchestrator.chat() 직전, ContextAssemblyEngine 으로 동일 호출을 한 번 더 해서
        //   LLM 에 실제 전달될 메시지 구성을 stdout 으로 출력. 본 호출과 별개이므로 결과에 영향 없음.
        try {
            AssemblyResult preview = contextAssemblyEngine.assemble(sessionId, null, req);
            dumpAssembled(label, preview);
        } catch (Exception e) {
            log.warn("[{}] context assembly preview failed: {}", label, e.getMessage());
        }

        long t0 = System.currentTimeMillis();
        ChatResponse resp;
        try {
            resp = orchestrator.chat(req);
        } catch (RuntimeException e) {
            log.error("[{}] runId={} failed: {}", label, sessionId, e.getMessage(), e);
            Result r = new Result();
            r.label = label;
            r.error = e.getMessage();
            return r;
        }
        long elapsed = System.currentTimeMillis() - t0;

        Result r = new Result();
        r.label = label;
        r.elapsedMs = elapsed;
        r.text = extractText(resp);
        r.toolCallCount = resp.actionsExecuted() != null ? resp.actionsExecuted().size() : 0;
        r.actions = resp.actionsExecuted() != null ? resp.actionsExecuted() : List.of();
        if (resp.usage() != null) {
            r.inputTokens = resp.usage().inputTokens();
            r.outputTokens = resp.usage().outputTokens();
            r.cacheCreation = resp.usage().cacheCreationInputTokens();
            r.cacheRead = resp.usage().cacheReadInputTokens();
        }
        r.costUsd = resp.costUsd();
        log.info("[{}] runId={} elapsed={}ms in={} out={} cacheR={} cacheC={} cost=${} tools={}",
                label, sessionId, r.elapsedMs, r.inputTokens, r.outputTokens,
                r.cacheRead, r.cacheCreation, r.costUsd, r.toolCallCount);
        return r;
    }

    /** Assembly 결과를 stdout 으로 dump — 메시지 개수, 역할별 길이, 본문 전체 파일 저장. */
    private void dumpAssembled(String label, AssemblyResult result) {
        if (result == null || result.messages() == null) {
            System.out.printf("[ASSEMBLY-%s] EMPTY%n", label);
            return;
        }
        List<UnifiedMessage> msgs = result.messages();
        System.out.printf("%n[ASSEMBLY-%s] %d messages, recipeId=%s, totalTokens=%d/%d%n",
                label, msgs.size(),
                result.trace() != null ? result.trace().recipeId() : "n/a",
                result.trace() != null ? result.trace().totalEstimatedTokens() : -1,
                result.trace() != null ? result.trace().effectiveWindow() : -1);
        // 본문 전체를 /tmp 에 저장 (시스템 프롬프트 19K+ 검사용)
        java.nio.file.Path dumpFile = java.nio.file.Paths.get("/tmp/cr067-assembly-" + label + ".txt");
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < msgs.size(); i++) {
            UnifiedMessage m = msgs.get(i);
            String text = extractMessageText(m);
            int len = text.length();
            // stdout: 길이 + head 한 줄
            String head = preview(text, 150);
            System.out.printf("  [%d] role=%s len=%d HEAD: %s%n", i, m.role(), len, head);
            // 파일: 전체
            full.append("=== [").append(i).append("] role=").append(m.role())
                .append(" len=").append(len).append(" ===\n")
                .append(text).append("\n\n");
        }
        try {
            java.nio.file.Files.writeString(dumpFile, full.toString());
            System.out.printf("  → full dump saved to %s%n", dumpFile);
        } catch (java.io.IOException e) {
            System.out.printf("  ! failed to write dump: %s%n", e.getMessage());
        }
    }

    private String extractMessageText(UnifiedMessage m) {
        if (m == null || m.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.Text t) sb.append(t.text());
        }
        return sb.toString();
    }

    private String preview(String s, int max) {
        if (s == null) return "";
        String single = s.replace("\n", "\\n").replace("\r", "");
        return single.length() <= max ? single : single.substring(0, max);
    }

    private String extractText(ChatResponse resp) {
        if (resp.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : resp.content()) {
            if (b instanceof ContentBlock.Text t) sb.append(t.text());
        }
        return sb.toString();
    }

    private void printReport(String scenario, Result api, Result cli) {
        String sep = "═".repeat(100);
        String thin = "─".repeat(100);
        System.out.println();
        System.out.println(sep);
        System.out.printf("[CR-067 어댑터 비교] 시나리오 = %s%n", scenario);
        System.out.println(sep);
        System.out.printf("%-30s %25s %25s%n", "metric", "API (anthropic)", "CLI (anthropic-cli)");
        System.out.println(thin);
        System.out.printf("%-30s %25d %25d%n", "elapsed_ms", api.elapsedMs, cli.elapsedMs);
        System.out.printf("%-30s %25d %25d%n", "tool_call_count", api.toolCallCount, cli.toolCallCount);
        System.out.printf("%-30s %25d %25d%n", "input_tokens", api.inputTokens, cli.inputTokens);
        System.out.printf("%-30s %25d %25d%n", "output_tokens", api.outputTokens, cli.outputTokens);
        System.out.printf("%-30s %25d %25d%n", "cache_creation_tokens", api.cacheCreation, cli.cacheCreation);
        System.out.printf("%-30s %25d %25d%n", "cache_read_tokens", api.cacheRead, cli.cacheRead);
        System.out.printf("%-30s %25s %25s%n", "cost_usd",
                String.format("%.6f", api.costUsd), String.format("%.6f", cli.costUsd));
        System.out.println(sep);
        System.out.printf("[도구 호출 시퀀스 — API/%s]%n", scenario);
        printActions(api.actions);
        System.out.println();
        System.out.printf("[도구 호출 시퀀스 — CLI/%s]%n", scenario);
        printActions(cli.actions);
        System.out.println(sep);
        System.out.printf("[최종 응답 — API/%s]%n", scenario);
        System.out.println(api.text);
        System.out.println();
        System.out.println(thin);
        System.out.printf("[최종 응답 — CLI/%s]%n", scenario);
        System.out.println(cli.text);
        System.out.println(sep);
    }

    private void printActions(List<Map<String, Object>> actions) {
        if (actions == null || actions.isEmpty()) {
            System.out.println("  (no tool calls)");
            return;
        }
        for (int i = 0; i < actions.size(); i++) {
            Map<String, Object> a = actions.get(i);
            System.out.printf("  %2d. %s  →  %s%n", i + 1, a.get("name"),
                    summarize(a.get("input")));
        }
    }

    private String summarize(Object obj) {
        if (obj == null) return "";
        String s = obj.toString().replaceAll("\\s+", " ");
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    private static class Result {
        String label;
        long elapsedMs;
        int toolCallCount;
        int inputTokens, outputTokens, cacheCreation, cacheRead;
        double costUsd;
        String text;
        List<Map<String, Object>> actions = List.of();
        String error;
    }
}
