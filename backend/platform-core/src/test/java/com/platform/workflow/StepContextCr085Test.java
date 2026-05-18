package com.platform.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-085 P1(중첩 경로 파서) + P2(채널 reducer) 단위 테스트.
 *
 * <p>최우선 검증축은 <b>하위호환</b>: 1뎁스 ref / 실패 폴백 빈문자열 / reducer 미지정 시
 * 기존 {@code withStepResult} 와 바이트 동일. 표현력 확장은 그 다음.
 */
class StepContextCr085Test {

    private StepContext ctx(Map<String, Object> input, Map<String, Object> stepResults) {
        return new StepContext("run-1", "wf-1", "sess-1", input, stepResults);
    }

    // ─────────────────────────────────────────────────────────────
    // P1: 중첩 경로 파서 — 하위호환 우선
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P1 하위호환: 1뎁스 ref / 실패 폴백")
    class P1Compat {

        @Test
        @DisplayName("{{input.key}} 1뎁스 — 기존과 동일 결과")
        void oneDepthInput() {
            StepContext c = ctx(Map.of("name", "Alice"), Map.of());
            assertThat(c.resolve("Hello {{input.name}}")).isEqualTo("Hello Alice");
        }

        @Test
        @DisplayName("{{stepId.field}} 1뎁스 — 기존과 동일 결과")
        void oneDepthStep() {
            Map<String, Object> steps = Map.of("gen", Map.of("output", "result-x"));
            StepContext c = ctx(Map.of(), steps);
            assertThat(c.resolve("{{gen.output}}")).isEqualTo("result-x");
        }

        @Test
        @DisplayName("참조 불가 — 기존과 동일하게 빈 문자열")
        void missingRefEmpty() {
            StepContext c = ctx(Map.of(), Map.of());
            assertThat(c.resolve("[{{gen.nope}}]")).isEqualTo("[]");
            assertThat(c.resolve("[{{input.nope}}]")).isEqualTo("[]");
        }

        @Test
        @DisplayName("namespace 없는 형식 불일치 — 원문 유지")
        void noNamespaceKeepsRaw() {
            StepContext c = ctx(Map.of(), Map.of());
            assertThat(c.resolve("{{plain}}")).isEqualTo("{{plain}}");
        }

        @Test
        @DisplayName("중첩 경로가 중간에 끊겨도 빈 문자열 (기존 폴백 동작 보존)")
        void brokenPathFallsBackEmpty() {
            Map<String, Object> steps = Map.of("gen", Map.of("output", "flat-string"));
            StepContext c = ctx(Map.of(), steps);
            // output 은 String 인데 .deep 더 들어가려 함 → null → 빈 문자열
            assertThat(c.resolve("[{{gen.output.deep}}]")).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("P1 표현력 확장: 중첩 Map / List 인덱스")
    class P1Nested {

        @Test
        @DisplayName("{{step.a.b.c}} 중첩 Map 경로")
        void nestedMapPath() {
            Map<String, Object> inner = Map.of("b", Map.of("c", "deep-val"));
            Map<String, Object> steps = Map.of("s1", Map.of("a", inner));
            StepContext c = ctx(Map.of(), steps);
            assertThat(c.resolve("{{s1.a.b.c}}")).isEqualTo("deep-val");
        }

        @Test
        @DisplayName("{{step.list[1]}} List 인덱스 접근")
        void listIndex() {
            Map<String, Object> steps = Map.of("s1",
                    Map.of("items", List.of("zero", "one", "two")));
            StepContext c = ctx(Map.of(), steps);
            assertThat(c.resolve("{{s1.items[1]}}")).isEqualTo("one");
        }

        @Test
        @DisplayName("{{step.a[0].name}} List → Map 혼합 경로")
        void listThenMap() {
            Map<String, Object> steps = Map.of("s1",
                    Map.of("rows", List.of(Map.of("name", "first"), Map.of("name", "second"))));
            StepContext c = ctx(Map.of(), steps);
            assertThat(c.resolve("{{s1.rows[1].name}}")).isEqualTo("second");
        }

        @Test
        @DisplayName("List 인덱스 범위 초과 — 빈 문자열")
        void listIndexOutOfBounds() {
            Map<String, Object> steps = Map.of("s1", Map.of("items", List.of("a")));
            StepContext c = ctx(Map.of(), steps);
            assertThat(c.resolve("[{{s1.items[9]}}]")).isEqualTo("[]");
        }

        @Test
        @DisplayName("{{input.cfg.timeout}} input 도 중첩 경로 지원")
        void nestedInput() {
            Map<String, Object> input = Map.of("cfg", Map.of("timeout", 30));
            StepContext c = ctx(input, Map.of());
            assertThat(c.resolve("{{input.cfg.timeout}}")).isEqualTo("30");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // P2: 채널 reducer — 미지정=replace 보존 우선
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P2 하위호환: reducer 미지정 = 기존 withStepResult")
    class P2Compat {

        @Test
        @DisplayName("3-arg withStepResult — 기존 동작 그대로")
        void legacyWithStepResult() {
            StepContext c = ctx(Map.of(), new LinkedHashMap<>())
                    .withStepResult("s1", Map.of("output", "v1"));
            assertThat(c.stepResults().get("s1")).isEqualTo(Map.of("output", "v1"));
        }

        @Test
        @DisplayName("channel=null → 기존 동작 (덮어쓰기, 채널 키 없음)")
        void nullChannelSameAsLegacy() {
            StepContext c = ctx(Map.of(), new LinkedHashMap<>())
                    .withStepResult("s1", Map.of("output", "v1"), null, "append");
            assertThat(c.stepResults()).containsOnlyKeys("s1");
            assertThat(c.stepResults().get("s1")).isEqualTo(Map.of("output", "v1"));
        }

        @Test
        @DisplayName("reduce=replace 명시 → 채널 키에 덮어쓰기, stepId 도 함께 기록")
        void explicitReplace() {
            StepContext c = ctx(Map.of(), new LinkedHashMap<>())
                    .withStepResult("s1", Map.of("output", "v1"), "ch", "replace");
            assertThat(c.stepResults().get("ch")).isEqualTo(Map.of("output", "v1"));
            assertThat(c.stepResults().get("s1")).isEqualTo(Map.of("output", "v1"));
        }
    }

    @Nested
    @DisplayName("P2 reducer: append / merge")
    class P2Reduce {

        @Test
        @DisplayName("append — 채널을 List 로 누적 (cyclic 메시지 누적 패턴)")
        @SuppressWarnings("unchecked")
        void appendAccumulates() {
            StepContext c = ctx(Map.of(), new LinkedHashMap<>())
                    .withStepResult("turn", Map.of("msg", "m1"), "messages", "append")
                    .withStepResult("turn", Map.of("msg", "m2"), "messages", "append");
            List<Object> acc = (List<Object>) c.stepResults().get("messages");
            assertThat(acc).hasSize(2);
            assertThat(acc.get(0)).isEqualTo(Map.of("msg", "m1"));
            assertThat(acc.get(1)).isEqualTo(Map.of("msg", "m2"));
            // 마지막 turn 결과는 stepId 로도 여전히 참조 가능
            assertThat(c.stepResults().get("turn")).isEqualTo(Map.of("msg", "m2"));
        }

        @Test
        @DisplayName("merge — 채널을 Map 으로 얕은 병합")
        @SuppressWarnings("unchecked")
        void mergeAccumulates() {
            StepContext c = ctx(Map.of(), new LinkedHashMap<>())
                    .withStepResult("s1", Map.of("a", 1), "state", "merge")
                    .withStepResult("s2", Map.of("b", 2), "state", "merge");
            Map<String, Object> state = (Map<String, Object>) c.stepResults().get("state");
            assertThat(state).containsEntry("a", 1).containsEntry("b", 2);
        }

        @Test
        @DisplayName("알 수 없는 reduce 전략 → replace 폴백")
        void unknownReduceFallsBackReplace() {
            StepContext c = ctx(Map.of(), new LinkedHashMap<>())
                    .withStepResult("s1", Map.of("x", "v"), "ch", "bogus");
            assertThat(c.stepResults().get("ch")).isEqualTo(Map.of("x", "v"));
        }

        @Test
        @DisplayName("append 후 중첩 경로로 누적 원소 참조 (P1+P2 시너지)")
        void appendThenNestedRef() {
            StepContext c = ctx(Map.of(), new LinkedHashMap<>())
                    .withStepResult("turn", Map.of("msg", "hello"), "messages", "append")
                    .withStepResult("turn", Map.of("msg", "world"), "messages", "append");
            assertThat(c.resolve("{{messages[0].msg}}")).isEqualTo("hello");
            assertThat(c.resolve("{{messages[1].msg}}")).isEqualTo("world");
        }
    }
}
