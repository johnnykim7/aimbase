package com.platform.largeinput;

import com.platform.workflow.analysis.AnalysisInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * CR-120: 계층(재귀) Reduce — 청크 결과 단순 concat 도 한도(32MB)를 칠 수 있으므로,
 * 한도 안쪽 묶음씩 LLM 으로 정리하고 넘치면 그 결과를 다시 묶어 재귀한다(트리).
 *
 * <p>엔진과 분리하기 위해 실제 LLM 호출은 {@code llmCall} 콜백으로 주입받는다
 * (system, prompt → 응답 텍스트). 엔진이 어댑터/모델/connection 을 이미 해석해 넘긴다.
 */
@Service
public class HierarchicalReduceService {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalReduceService.class);

    /** 한 Reduce 묶음에 넣을 최대 fragment 문자 합계. 넘으면 묶음을 더 잘게 나눈다. */
    @Value("${largeinput.reduce-budget-chars:60000}")
    private int reduceBudgetChars;

    /** 한 묶음 최대 fragment 개수 (문자 예산과 별개 상한). */
    @Value("${largeinput.reduce-max-group-size:10}")
    private int reduceMaxGroupSize;

    /** 무한 재귀 방어 — 최대 Reduce 레벨. */
    @Value("${largeinput.reduce-max-levels:8}")
    private int reduceMaxLevels;

    /**
     * 청크 성공 결과 텍스트 목록을 단일 결과로 계층 정리.
     *
     * @param fragments    청크별 결과 텍스트 (성공 청크만)
     * @param reduceInstr  동작이 준 Reduce 지시 ({@code {{fragments}}} 자리표시자 포함)
     * @param llmCall      (system, prompt) → 응답 텍스트
     * @param reduceTree   레벨별 기록을 채울 출력 Map (job.reduce_tree 에 저장)
     * @return 최종 정리 텍스트 (fragment 1개면 그대로, 0개면 빈 문자열)
     */
    public String reduce(List<String> fragments, AnalysisInstruction reduceInstr,
                         BiFunction<String, String, String> llmCall,
                         Map<String, Object> reduceTree) {
        List<String> current = new ArrayList<>(fragments);
        if (current.isEmpty()) {
            reduceTree.put("levels", List.of());
            return "";
        }
        if (current.size() == 1) {
            reduceTree.put("levels", List.of(Map.of("level", 0, "groups", 0, "note", "single fragment, no reduce")));
            return current.get(0);
        }

        List<Map<String, Object>> levels = new ArrayList<>();
        int level = 0;
        while (current.size() > 1 && level < reduceMaxLevels) {
            List<List<String>> groups = group(current);
            List<String> next = new ArrayList<>(groups.size());
            for (List<String> g : groups) {
                String merged = String.join("\n\n---\n\n", g);
                String prompt = reduceInstr.prompt().replace("{{fragments}}", merged);
                String out = llmCall.apply(reduceInstr.system(), prompt);
                next.add(out != null ? out : "");
            }
            levels.add(level(level, current.size(), groups.size()));
            log.info("CR-120 reduce level {}: {} fragments → {} groups", level, current.size(), groups.size());
            current = next;
            level++;
        }

        if (current.size() > 1) {
            // 레벨 상한 도달 — 남은 것 단순 결합(정직 표기)
            log.warn("CR-120 reduce hit max levels {} with {} fragments remaining — concatenating",
                    reduceMaxLevels, current.size());
            levels.add(level(level, current.size(), 1));
            reduceTree.put("levels", levels);
            reduceTree.put("max_levels_hit", true);
            return String.join("\n\n---\n\n", current);
        }
        reduceTree.put("levels", levels);
        return current.isEmpty() ? "" : current.get(0);
    }

    /** 문자 예산 + 개수 상한으로 fragment 를 묶음으로 분할. */
    private List<List<String>> group(List<String> fragments) {
        List<List<String>> groups = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int curChars = 0;
        for (String f : fragments) {
            int len = f != null ? f.length() : 0;
            boolean overflow = (!cur.isEmpty())
                    && (curChars + len > reduceBudgetChars || cur.size() >= reduceMaxGroupSize);
            if (overflow) {
                groups.add(cur);
                cur = new ArrayList<>();
                curChars = 0;
            }
            cur.add(f != null ? f : "");
            curChars += len;
        }
        if (!cur.isEmpty()) groups.add(cur);
        return groups;
    }

    private Map<String, Object> level(int level, int inCount, int outCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("in_fragments", inCount);
        m.put("out_groups", outCount);
        return m;
    }

    void configureForTest(int budgetChars, int maxGroupSize, int maxLevels) {
        this.reduceBudgetChars = budgetChars;
        this.reduceMaxGroupSize = maxGroupSize;
        this.reduceMaxLevels = maxLevels;
    }
}
