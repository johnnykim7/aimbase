package com.platform.largeinput;

import com.platform.workflow.analysis.AnalysisInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-120: 계층(재귀) Reduce — 단순 concat 이 한도를 칠 때 묶음씩 정리 후 재귀하는지 단위 테스트.
 * 실제 LLM 없이 콜백을 "fragments 를 이어붙여 길이를 줄이는 가짜 정리"로 주입한다.
 */
class HierarchicalReduceServiceTest {

    private HierarchicalReduceService service;

    @BeforeEach
    void setUp() {
        service = new HierarchicalReduceService();
        // 작은 예산으로 강제로 여러 레벨 재귀를 유도: 묶음당 최대 2개, 문자예산 충분히 작게
        service.configureForTest(50, 2, 8);
    }

    private final AnalysisInstruction reduceInstr =
            new AnalysisInstruction(null, "Consolidate:\n{{fragments}}");

    /** fragments 를 "[merged]" 접두 + 첫 20자로 줄이는 가짜 LLM (길이 감소 보장 → 재귀 종료). */
    private BiFunction<String, String, String> shrinkingLlm(AtomicInteger calls) {
        return (sys, prompt) -> {
            calls.incrementAndGet();
            String fragments = prompt.replace("Consolidate:\n", "");
            String head = fragments.length() <= 20 ? fragments : fragments.substring(0, 20);
            return "[m]" + head;
        };
    }

    @Test
    @DisplayName("fragment 1개면 LLM 호출 없이 그대로 반환")
    void singleFragmentNoCall() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> tree = new LinkedHashMap<>();
        String out = service.reduce(List.of("only one"), reduceInstr, shrinkingLlm(calls), tree);
        assertThat(out).isEqualTo("only one");
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("여러 fragment 는 묶음씩 정리하고 1개로 수렴할 때까지 재귀")
    void recursivelyReducesToOne() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> tree = new LinkedHashMap<>();
        List<String> fragments = new ArrayList<>();
        for (int i = 0; i < 7; i++) fragments.add("fragment-content-number-" + i);

        String out = service.reduce(fragments, reduceInstr, shrinkingLlm(calls), tree);

        // 최종 1개로 수렴
        assertThat(out).isNotBlank();
        assertThat(calls.get()).isGreaterThan(0);
        // reduce_tree 에 레벨 기록이 남는다
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> levels = (List<Map<String, Object>>) tree.get("levels");
        assertThat(levels).isNotEmpty();
        // 첫 레벨은 7개 입력 → 묶음 4개(2개씩 + 1) 이상으로 줄어든다
        assertThat((Integer) levels.get(0).get("in_fragments")).isEqualTo(7);
        assertThat((Integer) levels.get(0).get("out_groups")).isLessThan(7);
    }

    @Test
    @DisplayName("빈 입력은 빈 문자열")
    void emptyReturnsEmpty() {
        Map<String, Object> tree = new LinkedHashMap<>();
        String out = service.reduce(List.of(), reduceInstr, (s, p) -> "x", tree);
        assertThat(out).isEmpty();
    }
}
