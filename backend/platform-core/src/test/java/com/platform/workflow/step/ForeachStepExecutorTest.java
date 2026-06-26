package com.platform.workflow.step;

import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-087 ForeachStepExecutor 단위 테스트.
 *
 * <p>body executor 를 fake StepExecutor 로 주입(ApplicationContext.getBeansOfType mock)하여
 * fan-out 제어 / 변수 주입 / 모드 / 수집 / 부분 실패 정책만 검증한다. 실제 LLM/Tool 호출은 없다.
 */
class ForeachStepExecutorTest {

    private ApplicationContext applicationContext;
    private ForeachStepExecutor executor;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        executor = new ForeachStepExecutor(applicationContext);
    }

    // ─── fake body executor: item_var 의 필드를 echo, 호출 순서/스레드 기록 ───

    /** body=TOOL_CALL 로 가장한 fake — {{item.url}} / {{index.value}} 를 resolve 해 output 에 담음. */
    private FakeBodyExecutor wireBody(WorkflowStep.StepType type) {
        FakeBodyExecutor fake = new FakeBodyExecutor(type);
        when(applicationContext.getBeansOfType(StepExecutor.class))
                .thenReturn(Map.of("fake", fake));
        return fake;
    }

    static class FakeBodyExecutor implements StepExecutor {
        final WorkflowStep.StepType type;
        final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        volatile boolean throwOnUrl3 = false;

        FakeBodyExecutor(WorkflowStep.StepType type) { this.type = type; }

        @Override public WorkflowStep.StepType supports() { return type; }

        @Override
        public Map<String, Object> execute(WorkflowStep step, StepContext context) {
            String url = context.resolve("{{item.url}}");
            String idx = context.resolve("{{index.value}}");
            if (throwOnUrl3 && "u3".equals(url)) {
                throw new RuntimeException("boom on u3");
            }
            seen.add(url);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("output", "parsed:" + url + "@" + idx);
            return r;
        }
    }

    private StepContext ctxWithSamples(List<Object> samples) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("samples", samples);
        return new StepContext("run-1", "wf-1", "sess-1", input, new LinkedHashMap<>());
    }

    private List<Object> sampleMaps(String... urls) {
        List<Object> list = new ArrayList<>();
        for (String u : urls) list.add(Map.of("url", u));
        return list;
    }

    private WorkflowStep foreachStep(Map<String, Object> config) {
        return new WorkflowStep("fe", "Foreach", WorkflowStep.StepType.FOREACH,
                config, List.of(), null, null, null);
    }

    private Map<String, Object> baseConfig(String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "TOOL_CALL");
        body.put("config", Map.of("tool", "parse_document", "input", Map.of("url", "{{item.url}}")));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("items", "{{input.samples}}");
        config.put("mode", mode);
        config.put("body", body);
        return config;
    }

    // ═══════════════════════════════════════════════
    // sequential / parallel 기본 동작
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("sequential — 각 원소에 body 적용, 결과 순서대로 수집 + item/index 주입")
    void sequentialMapsEachItem() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u1", "u2"));

        Map<String, Object> out = executor.execute(foreachStep(baseConfig("sequential")), ctx);

        assertThat(out.get("item_count")).isEqualTo(3);
        assertThat(out.get("failed_count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).hasSize(3);
        // item/index 주입 검증 — output 에 url@index 가 박혀야 함
        assertThat(results.get(0).get("output")).isEqualTo("parsed:u0@0");
        assertThat(results.get(1).get("output")).isEqualTo("parsed:u1@1");
        assertThat(results.get(2).get("output")).isEqualTo("parsed:u2@2");
        assertThat(results).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("ok"));
        // sequential 은 호출 순서 보장
        assertThat(body.seen).containsExactly("u0", "u1", "u2");
    }

    @Test
    @DisplayName("parallel — 모든 원소 처리(순서 무관), 전부 수집")
    void parallelProcessesAllItems() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u1", "u2", "u3", "u4"));

        Map<String, Object> config = baseConfig("parallel");
        config.put("max_concurrency", 2);

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("item_count")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).hasSize(5);
        // 결과는 입력 인덱스 순서로 정렬되어 수집됨 (futures 순서 보존)
        assertThat(results.get(0).get("output")).isEqualTo("parsed:u0@0");
        assertThat(results.get(4).get("output")).isEqualTo("parsed:u4@4");
        assertThat(body.seen).containsExactlyInAnyOrder("u0", "u1", "u2", "u3", "u4");
    }

    // ═══════════════════════════════════════════════
    // 경계: 빈 컬렉션 / max_items
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("빈 컬렉션 — body 미호출, item_count=0")
    void emptyCollection() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        StepContext ctx = ctxWithSamples(List.of());

        Map<String, Object> out = executor.execute(foreachStep(baseConfig("sequential")), ctx);

        assertThat(out.get("item_count")).isEqualTo(0);
        assertThat((List<?>) out.get("results")).isEmpty();
        assertThat(body.seen).isEmpty();
    }

    @Test
    @DisplayName("max_items 초과 — step FAIL")
    void maxItemsExceededFails() {
        wireBody(WorkflowStep.StepType.TOOL_CALL);
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u1", "u2"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("max_items", 2);

        assertThatThrownBy(() -> executor.execute(foreachStep(config), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds max_items");
    }

    // ═══════════════════════════════════════════════
    // 부분 실패 정책 on_item_error
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("on_item_error=continue — 실패 원소는 기록 후 계속")
    void onItemErrorContinue() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        body.throwOnUrl3 = true;
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u3", "u2"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("on_item_error", "continue");

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("item_count")).isEqualTo(3);
        assertThat(out.get("failed_count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results.get(1).get("status")).isEqualTo("failed");
        assertThat(results.get(1).get("error")).asString().contains("boom on u3");
        // 실패 후에도 다음 원소 처리됨
        assertThat(results.get(2).get("output")).isEqualTo("parsed:u2@2");
    }

    @Test
    @DisplayName("on_item_error=fail(기본) — 원소 실패 시 step 전체 예외")
    void onItemErrorFailPropagates() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        body.throwOnUrl3 = true;
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u3"));

        // on_item_error 미지정 → 기본 fail
        assertThatThrownBy(() -> executor.execute(foreachStep(baseConfig("sequential")), ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("item[1] failed");
    }

    // ═══════════════════════════════════════════════
    // collect / 스칼라 원소 / 인라인 리스트
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("collect=none — 결과 수집 안 함(빈 List), item_count 는 유지")
    void collectNone() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u1"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("collect", "none");

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("item_count")).isEqualTo(2);
        assertThat((List<?>) out.get("results")).isEmpty();
        // body 는 여전히 호출됨
        assertThat(body.seen).containsExactly("u0", "u1");
    }

    @Test
    @DisplayName("스칼라 원소 — {value:원소} 래핑되어 {{item.value}} 로 접근")
    void scalarItemsWrapped() {
        // body 가 {{item.value}} 를 읽도록 별도 fake
        FakeScalarBody scalarBody = new FakeScalarBody();
        when(applicationContext.getBeansOfType(StepExecutor.class))
                .thenReturn(Map.of("fake", scalarBody));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("words", List.of("a", "b", "c"));
        StepContext ctx = new StepContext("run-1", "wf-1", "sess-1", input, new LinkedHashMap<>());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "TOOL_CALL");
        body.put("config", Map.of());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("items", "{{input.words}}");
        config.put("body", body);

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("item_count")).isEqualTo(3);
        assertThat(scalarBody.seen).containsExactly("a", "b", "c");
    }

    static class FakeScalarBody implements StepExecutor {
        final List<String> seen = new ArrayList<>();
        @Override public WorkflowStep.StepType supports() { return WorkflowStep.StepType.TOOL_CALL; }
        @Override public Map<String, Object> execute(WorkflowStep step, StepContext context) {
            seen.add(context.resolve("{{item.value}}"));
            return Map.of("output", "ok");
        }
    }

    // ═══════════════════════════════════════════════
    // collect=merge — TipTap content[] 평탄 병합
    // ═══════════════════════════════════════════════

    /** body=LLM_CALL 로 가장 — {{item.url}} 별로 structured_data.{type:doc, content:[node]} 반환. */
    static class FakeDocBody implements StepExecutor {
        volatile boolean failOnU1 = false;
        @Override public WorkflowStep.StepType supports() { return WorkflowStep.StepType.LLM_CALL; }
        @Override public Map<String, Object> execute(WorkflowStep step, StepContext context) {
            String url = context.resolve("{{item.url}}");
            if (failOnU1 && "u1".equals(url)) throw new RuntimeException("boom on u1");
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "paragraph");
            node.put("text", "section:" + url);
            Map<String, Object> sd = new LinkedHashMap<>();
            sd.put("type", "doc");
            sd.put("content", List.of(node));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("output", "");
            r.put("structured_data", sd);
            return r;
        }
    }

    private FakeDocBody wireDocBody() {
        FakeDocBody fake = new FakeDocBody();
        when(applicationContext.getBeansOfType(StepExecutor.class))
                .thenReturn(Map.of("fake", fake));
        return fake;
    }

    private Map<String, Object> docBaseConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "LLM_CALL");
        body.put("config", Map.of());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("items", "{{input.samples}}");
        config.put("body", body);
        config.put("collect", "merge");
        return config;
    }

    @Test
    @DisplayName("collect=merge — 각 섹션 structured_data.content[] 를 doc 1건으로 평탄 병합")
    @SuppressWarnings("unchecked")
    void collectMergeFlattensTipTapNodes() {
        wireDocBody();
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u1", "u2"));

        Map<String, Object> out = executor.execute(foreachStep(docBaseConfig()), ctx);

        assertThat(out.get("item_count")).isEqualTo(3);
        assertThat(out.get("failed_count")).isEqualTo(0);
        // output = 단일 doc Map
        Map<String, Object> doc = (Map<String, Object>) out.get("output");
        assertThat(doc.get("type")).isEqualTo("doc");
        List<Map<String, Object>> content = (List<Map<String, Object>>) doc.get("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(0).get("text")).isEqualTo("section:u0");
        assertThat(content.get(2).get("text")).isEqualTo("section:u2");
        // results = 원본 List 보존
        assertThat((List<?>) out.get("results")).hasSize(3);
    }

    @Test
    @DisplayName("collect=merge — 실패 섹션(on_item_error=continue)은 병합에서 제외")
    @SuppressWarnings("unchecked")
    void collectMergeSkipsFailedSections() {
        FakeDocBody body = wireDocBody();
        body.failOnU1 = true;
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u1", "u2"));

        Map<String, Object> config = docBaseConfig();
        config.put("on_item_error", "continue");

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("failed_count")).isEqualTo(1);
        Map<String, Object> doc = (Map<String, Object>) out.get("output");
        List<Map<String, Object>> content = (List<Map<String, Object>>) doc.get("content");
        // u1 실패 → u0, u2 노드만 병합
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("text")).isEqualTo("section:u0");
        assertThat(content.get(1).get("text")).isEqualTo("section:u2");
    }

    @Test
    @DisplayName("collect=merge — structured_data 자체가 노드 List 인 스키마 변형도 평탄 병합")
    @SuppressWarnings("unchecked")
    void collectMergeAcceptsFlatNodeArray() {
        // structured_data 가 {type:doc,...} 가 아니라 노드 배열 List 인 generator
        StepExecutor flatBody = new StepExecutor() {
            @Override public WorkflowStep.StepType supports() { return WorkflowStep.StepType.LLM_CALL; }
            @Override public Map<String, Object> execute(WorkflowStep step, StepContext context) {
                String url = context.resolve("{{item.url}}");
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("structured_data", List.of(Map.of("type", "paragraph", "text", url)));
                return r;
            }
        };
        when(applicationContext.getBeansOfType(StepExecutor.class))
                .thenReturn(Map.of("fake", flatBody));
        StepContext ctx = ctxWithSamples(sampleMaps("a", "b"));

        Map<String, Object> out = executor.execute(foreachStep(docBaseConfig()), ctx);

        Map<String, Object> doc = (Map<String, Object>) out.get("output");
        List<Map<String, Object>> content = (List<Map<String, Object>>) doc.get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("text")).isEqualTo("a");
        assertThat(content.get(1).get("text")).isEqualTo("b");
    }

    @Test
    @DisplayName("인라인 리스트 items — 변수 참조 없이 직접 리스트도 동작")
    void inlineListItems() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        StepContext ctx = new StepContext("run-1", "wf-1", "sess-1", new LinkedHashMap<>(), new LinkedHashMap<>());

        Map<String, Object> bodyDef = new LinkedHashMap<>();
        bodyDef.put("type", "TOOL_CALL");
        bodyDef.put("config", Map.of());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("items", List.of(Map.of("url", "x0"), Map.of("url", "x1")));
        config.put("body", bodyDef);

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("item_count")).isEqualTo(2);
        assertThat(body.seen).containsExactly("x0", "x1");
    }

    // ═══════════════════════════════════════════════
    // 방어
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("body 누락 — IllegalArgumentException")
    void missingBodyFails() {
        StepContext ctx = ctxWithSamples(sampleMaps("u0"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("items", "{{input.samples}}");
        // body 없음

        assertThatThrownBy(() -> executor.execute(foreachStep(config), ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    @DisplayName("중첩 FOREACH body — 차단")
    void nestedForeachBlocked() {
        StepContext ctx = ctxWithSamples(sampleMaps("u0"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "FOREACH");
        body.put("config", Map.of());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("items", "{{input.samples}}");
        config.put("body", body);

        assertThatThrownBy(() -> executor.execute(foreachStep(config), ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested FOREACH");
    }

    // ═══════════════════════════════════════════════
    // CR-117: 자식 단위 재시도 (item_retry_max)
    // ═══════════════════════════════════════════════

    /** N번 실패 후 성공하는 fake — 자식 재시도 검증용. url 별 시도 횟수 카운팅. */
    static class FlakyBody implements StepExecutor {
        final WorkflowStep.StepType type;
        final java.util.Map<String, Integer> attempts = new java.util.concurrent.ConcurrentHashMap<>();
        final int failTimes; // 각 url 이 처음 failTimes 번은 실패, 그 다음 성공

        FlakyBody(WorkflowStep.StepType type, int failTimes) { this.type = type; this.failTimes = failTimes; }

        @Override public WorkflowStep.StepType supports() { return type; }

        @Override
        public Map<String, Object> execute(WorkflowStep step, StepContext context) {
            String url = context.resolve("{{item.url}}");
            int n = attempts.merge(url, 1, Integer::sum);
            if (n <= failTimes) throw new RuntimeException("flaky fail #" + n + " for " + url);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("output", "parsed:" + url + "@try" + n);
            return r;
        }
    }

    @Test
    @DisplayName("CR-117 item_retry_max=2 — 1회 실패 후 재시도로 성공, 부모 실패 안 함")
    void itemRetrySucceedsAfterTransientFailure() {
        FlakyBody body = new FlakyBody(WorkflowStep.StepType.TOOL_CALL, 1); // 첫 시도만 실패
        when(applicationContext.getBeansOfType(StepExecutor.class)).thenReturn(Map.of("fake", body));
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u1"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("item_retry_max", 2);

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("failed_count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("ok"));
        // 각 url 2회 시도(1실패+1성공) — 재시도가 자식 단위로만 일어남
        assertThat(body.attempts.get("u0")).isEqualTo(2);
        assertThat(body.attempts.get("u1")).isEqualTo(2);
        assertThat(results.get(0).get("retried_attempts")).isEqualTo(2);
    }

    @Test
    @DisplayName("CR-117 item_retry_max 상한 초과 — on_item_error=continue 면 그 자식만 failed(attempts 기록)")
    void itemRetryExhaustedContinue() {
        FlakyBody body = new FlakyBody(WorkflowStep.StepType.TOOL_CALL, 99); // 항상 실패
        when(applicationContext.getBeansOfType(StepExecutor.class)).thenReturn(Map.of("fake", body));
        StepContext ctx = ctxWithSamples(sampleMaps("u0"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("item_retry_max", 2);          // 총 3회 시도
        config.put("on_item_error", "continue");

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("failed_count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results.get(0).get("status")).isEqualTo("failed");
        assertThat(results.get(0).get("attempts")).isEqualTo(3);
        assertThat(body.attempts.get("u0")).isEqualTo(3);
    }

    @Test
    @DisplayName("CR-117 item_retry_max 미지정(기본 0) — 재시도 없음(현행 동작)")
    void noRetryByDefault() {
        FlakyBody body = new FlakyBody(WorkflowStep.StepType.TOOL_CALL, 99);
        when(applicationContext.getBeansOfType(StepExecutor.class)).thenReturn(Map.of("fake", body));
        StepContext ctx = ctxWithSamples(sampleMaps("u0"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("on_item_error", "continue");

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);

        assertThat(out.get("failed_count")).isEqualTo(1);
        assertThat(body.attempts.get("u0")).isEqualTo(1); // 1회만 시도
    }

    // ═══════════════════════════════════════════════
    // CR-117: 실패율 임계치 (max_failed_ratio)
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("CR-117 max_failed_ratio 초과 — continue 라도 step 전체 FAILED 승격")
    void maxFailedRatioPromotesToStepFailure() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        body.throwOnUrl3 = true;
        // 3개 중 1개(u3) 실패 = 0.33 > 0.2 임계 → step FAIL
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u3", "u2"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("on_item_error", "continue");
        config.put("max_failed_ratio", 0.2);

        assertThatThrownBy(() -> executor.execute(foreachStep(config), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds max_failed_ratio");
    }

    @Test
    @DisplayName("CR-117 max_failed_ratio 이내 — 실패 있어도 step 완료(continue 유지)")
    void maxFailedRatioWithinThreshold() {
        FakeBodyExecutor body = wireBody(WorkflowStep.StepType.TOOL_CALL);
        body.throwOnUrl3 = true;
        // 4개 중 1개 실패 = 0.25 <= 0.5 임계 → step 완료
        StepContext ctx = ctxWithSamples(sampleMaps("u0", "u3", "u2", "u1"));

        Map<String, Object> config = baseConfig("sequential");
        config.put("on_item_error", "continue");
        config.put("max_failed_ratio", 0.5);

        Map<String, Object> out = executor.execute(foreachStep(config), ctx);
        assertThat(out.get("failed_count")).isEqualTo(1);
    }

}
