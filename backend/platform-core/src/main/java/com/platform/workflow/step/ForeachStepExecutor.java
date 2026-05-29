package com.platform.workflow.step;

import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * CR-087 — FOREACH 스텝 실행기 (동적 컬렉션 fan-out, LangGraph Send/map 대응).
 *
 * <p>런타임에 정해지는 컬렉션의 각 원소에 동일한 body step 을 적용한다. {@code PARALLEL}
 * (정적 step ID 목록) / {@code ROUTER}(N중 1택) / {@code cyclic}(노드 단위 수동 배선)
 * 으로는 표현할 수 없던 "각 원소에 map" 을 단일 노드 안에서 선언적으로 처리한다.
 *
 * <p>설계상 형제 패턴:
 * <ul>
 *   <li>{@link EvaluatorLoopStepExecutor} — 단일 노드 내부 루프 + {@code {{loop.*}}} 변수 주입</li>
 *   <li>{@link SubWorkflowStepExecutor} — {@code getExecutors()} 로 body step 위임 실행</li>
 * </ul>
 * FOREACH 는 단일 노드이므로 DAG/cyclic 어느 스케줄러에서도 그대로 하나의 step 으로 동작한다.
 *
 * <h3>config 형식</h3>
 * <pre>
 * config:
 *   items: "{{fetch.output.notices}}"   // 반복할 컬렉션 (변수 참조 → List). 인라인 List 도 가능.
 *   item_var: "item"                     // 각 원소를 바인딩할 변수명 (기본 "item")
 *   mode: sequential                     // sequential(기본) | parallel
 *   max_concurrency: 5                   // (parallel) 동시 실행 상한 (기본 5)
 *   max_items: 100                       // 처리 상한 (기본 100). 컬렉션이 초과하면 step FAIL
 *   on_item_error: fail                  // fail(기본) | continue
 *   collect: append                      // append(기본) | merge | none — 결과 수집 방식
 *   body:                                // 각 원소에 적용할 step (LLM_CALL/TOOL_CALL/SUB_WORKFLOW/...)
 *     type: TOOL_CALL
 *     config: { tool: parse_document, input: { url: "{{item.downloadUrl}}" } }
 * </pre>
 *
 * <h3>원소 변수 주입</h3>
 * 각 iteration 마다 원소를 {@code item_var} 키로 stepResults 에 임시 주입한다
 * (StepContext.withStepResult 재사용 — resolve 무변경). body 에서 참조:
 * <ul>
 *   <li>원소가 Map 이면 {@code {{item.field}}} 로 필드 접근 (원본 요구사항 주 사용처)</li>
 *   <li>원소가 스칼라(String/Number 등)이면 {@code {value: 원소}} 로 래핑 → {@code {{item.value}}}</li>
 *   <li>{@code {{index}}} → 0-based 현재 인덱스 ({@code __index__} step 으로 주입)</li>
 * </ul>
 *
 * <h3>출력 계약</h3>
 * {@code { output: ..., results: [...], item_count: N, failed_count: M }} —
 * <ul>
 *   <li>{@code collect=append}(기본): {@code output}/{@code results} 모두 각 원소 body 결과 List</li>
 *   <li>{@code collect=none}: {@code output}/{@code results} 모두 빈 List</li>
 *   <li>{@code collect=merge}: {@code output} 은 각 섹션 {@code structured_data.content[]} 를 평탄
 *       병합한 TipTap 문서 1건({@code {type:"doc", content:[...]}}, Map), {@code results} 는 원본 List 보존</li>
 * </ul>
 */
@Component
public class ForeachStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ForeachStepExecutor.class);

    static final int DEFAULT_MAX_ITEMS = 100;
    static final int DEFAULT_MAX_CONCURRENCY = 5;
    static final String DEFAULT_ITEM_VAR = "item";
    static final String INDEX_VAR = "index";

    private final ApplicationContext applicationContext;

    public ForeachStepExecutor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.FOREACH;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config() != null ? step.config() : Map.of();

        // 1) items 컬렉션 해석 (변수 참조 → List, 또는 인라인 List)
        Object itemsResolved = context.resolveObject(config.get("items"));
        List<Object> items;
        if (itemsResolved instanceof List<?> list) {
            items = new ArrayList<>(list);
        } else if (itemsResolved == null) {
            items = List.of();
        } else {
            // 단일 객체가 온 경우 1개 원소 컬렉션으로 취급 (방어적 — 검증에서 List 권장)
            items = List.of(itemsResolved);
        }

        String itemVar = asString(config.get("item_var"), DEFAULT_ITEM_VAR);
        String mode = asString(config.get("mode"), "sequential").toLowerCase();
        int maxItems = asInt(config.get("max_items"), DEFAULT_MAX_ITEMS);
        int maxConcurrency = Math.max(1, asInt(config.get("max_concurrency"), DEFAULT_MAX_CONCURRENCY));
        String onItemError = asString(config.get("on_item_error"), "fail").toLowerCase();
        String collect = asString(config.get("collect"), "append").toLowerCase();

        Object bodyObj = config.get("body");
        if (!(bodyObj instanceof Map)) {
            throw new IllegalArgumentException(
                    "FOREACH step '" + step.id() + "' requires 'body' (a step definition object)");
        }
        WorkflowStep bodyStep = parseBody(step.id(), (Map<String, Object>) bodyObj);

        // 2) 무한 방어 — 컬렉션이 max_items 초과면 FAIL (cyclic step budget 정신)
        if (items.size() > maxItems) {
            throw new IllegalStateException(
                    "FOREACH step '" + step.id() + "': items size " + items.size()
                            + " exceeds max_items " + maxItems);
        }

        if (items.isEmpty()) {
            log.debug("FOREACH step '{}': empty collection — 0 iterations", step.id());
            return Map.of("output", List.of(), "results", List.of(), "item_count", 0, "failed_count", 0);
        }

        log.debug("FOREACH step '{}': {} items, mode={}, body.type={}, collect={}, on_item_error={}",
                step.id(), items.size(), mode, bodyStep.type(), collect, onItemError);

        Map<WorkflowStep.StepType, StepExecutor> executors = getExecutors();
        StepExecutor bodyExecutor = executors.get(bodyStep.type());
        if (bodyExecutor == null) {
            throw new IllegalStateException(
                    "FOREACH step '" + step.id() + "': no executor for body type " + bodyStep.type());
        }

        List<Map<String, Object>> results = "parallel".equals(mode)
                ? runParallel(step, context, items, itemVar, bodyStep, bodyExecutor, maxConcurrency, onItemError)
                : runSequential(step, context, items, itemVar, bodyStep, bodyExecutor, onItemError);

        long failed = results.stream().filter(r -> "failed".equals(r.get("status"))).count();

        // 3) 결과 수집 (collect)
        if ("merge".equals(collect)) {
            // 각 섹션 body 결과의 structured_data 에서 TipTap content 노드[] 를 평탄 병합 →
            // {type:"doc", content:[...]} 단일 문서 1건. results(원본 List)도 보존.
            List<Object> mergedContent = new ArrayList<>();
            for (Map<String, Object> r : results) {
                if ("failed".equals(r.get("status"))) continue;
                mergedContent.addAll(extractTipTapNodes(r));
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("type", "doc");
            doc.put("content", mergedContent);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("output", doc);          // merge = doc 1건 (Map)
            output.put("results", results);     // 원본 List 보존
            output.put("item_count", items.size());
            output.put("failed_count", (int) failed);
            return output;
        }

        List<Map<String, Object>> collected = "none".equals(collect) ? List.of() : results;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("output", collected);
        output.put("results", collected);
        output.put("item_count", items.size());
        output.put("failed_count", (int) failed);
        return output;
    }

    /**
     * 단일 섹션 body 결과에서 TipTap content 노드 배열을 추출.
     * generator 의 structured_data 가 {type:"doc", content:[...]} 형태면 content[] 를,
     * structured_data 자체가 노드 배열(List)이면 그대로 사용한다 (스키마 변형 방어).
     */
    @SuppressWarnings("unchecked")
    private List<?> extractTipTapNodes(Map<String, Object> result) {
        Object sd = result.get("structured_data");
        if (sd instanceof Map<?, ?> sdMap) {
            Object nodes = sdMap.get("content");
            if (nodes instanceof List<?> list) return list;
        } else if (sd instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    // ─── 실행 모드 ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> runSequential(WorkflowStep step, StepContext context,
                                                     List<Object> items, String itemVar,
                                                     WorkflowStep bodyStep, StepExecutor bodyExecutor,
                                                     String onItemError) {
        List<Map<String, Object>> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            results.add(runOne(step, context, items.get(i), i, itemVar, bodyStep, bodyExecutor, onItemError));
        }
        return results;
    }

    private List<Map<String, Object>> runParallel(WorkflowStep step, StepContext context,
                                                   List<Object> items, String itemVar,
                                                   WorkflowStep bodyStep, StepExecutor bodyExecutor,
                                                   int maxConcurrency, String onItemError) {
        Semaphore gate = new Semaphore(maxConcurrency);
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Object item = items.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                gate.acquireUninterruptibly();
                try {
                    return runOne(step, context, item, idx, itemVar, bodyStep, bodyExecutor, onItemError);
                } finally {
                    gate.release();
                }
            }, Executors.newVirtualThreadPerTaskExecutor()));
        }
        List<Map<String, Object>> results = new ArrayList<>(items.size());
        for (CompletableFuture<Map<String, Object>> f : futures) {
            try {
                results.add(f.join());
            } catch (Exception e) {
                // runOne 이 on_item_error=fail 일 때 던진 예외가 join 에서 CompletionException 으로 래핑됨.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            }
        }
        return results;
    }

    /** 단일 원소 처리 — item/index 주입 후 body 위임. on_item_error 정책 적용. */
    private Map<String, Object> runOne(WorkflowStep step, StepContext context, Object item, int index,
                                       String itemVar, WorkflowStep bodyStep, StepExecutor bodyExecutor,
                                       String onItemError) {
        StepContext itemCtx = injectItem(context, item, index, itemVar);
        try {
            Map<String, Object> r = bodyExecutor.execute(withItemId(bodyStep, index), itemCtx);
            Map<String, Object> enriched = new LinkedHashMap<>(r != null ? r : Map.of());
            enriched.putIfAbsent("status", "ok");
            enriched.put("index", index);
            return enriched;
        } catch (Exception e) {
            if ("continue".equals(onItemError)) {
                log.warn("FOREACH step '{}' item[{}] failed (on_item_error=continue): {}",
                        step.id(), index, e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "failed");
                err.put("error", e.getMessage());
                err.put("index", index);
                return err;
            }
            throw new RuntimeException(
                    "FOREACH step '" + step.id() + "' item[" + index + "] failed: " + e.getMessage(), e);
        }
    }

    // ─── 변수 주입 ────────────────────────────────────────────────────────

    /**
     * 원소와 인덱스를 컨텍스트에 주입. {@code item_var} 키로 원소를, {@code index} 키로 인덱스를
     * stepResults 에 넣어 body 에서 {@code {{item.field}}} / {@code {{index.value}}} 로 참조 가능.
     * 원소가 Map 이 아니면 {@code {value: 원소}} 로 래핑한다.
     */
    @SuppressWarnings("unchecked")
    private StepContext injectItem(StepContext context, Object item, int index, String itemVar) {
        Map<String, Object> itemAsMap = item instanceof Map
                ? (Map<String, Object>) item
                : Map.of("value", item == null ? "" : item);
        return context
                .withStepResult(itemVar, itemAsMap)
                .withStepResult(INDEX_VAR, Map.of("value", index));
    }

    /** body step 에 iteration 별 고유 ID 부여 (로그/이벤트 추적용). */
    private WorkflowStep withItemId(WorkflowStep bodyStep, int index) {
        return new WorkflowStep(
                bodyStep.id() + "[" + index + "]",
                bodyStep.name(),
                bodyStep.type(),
                bodyStep.config(),
                bodyStep.dependsOn(),
                bodyStep.onSuccess(),
                bodyStep.onFailure(),
                bodyStep.timeoutMs());
    }

    // ─── 내부 헬퍼 ──────────────────────────────────────────────────────────

    private WorkflowStep parseBody(String parentId, Map<String, Object> bodyMap) {
        Object typeObj = bodyMap.get("type");
        if (typeObj == null) {
            throw new IllegalArgumentException("FOREACH step '" + parentId + "': body.type is required");
        }
        WorkflowStep.StepType bodyType;
        try {
            bodyType = WorkflowStep.StepType.valueOf(typeObj.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "FOREACH step '" + parentId + "': unknown body.type '" + typeObj + "'");
        }
        // 1차 범위: FOREACH 직접 중첩 차단 (검증기에서도 차단하나 방어적으로 재확인)
        if (bodyType == WorkflowStep.StepType.FOREACH) {
            throw new IllegalArgumentException(
                    "FOREACH step '" + parentId + "': nested FOREACH body is not allowed (use SUB_WORKFLOW)");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyConfig = bodyMap.get("config") instanceof Map
                ? (Map<String, Object>) bodyMap.get("config") : Map.of();
        String bodyId = bodyMap.get("id") != null ? bodyMap.get("id").toString() : parentId + ".body";
        return new WorkflowStep(bodyId, bodyId, bodyType, bodyConfig, null, null, null, null);
    }

    private Map<WorkflowStep.StepType, StepExecutor> getExecutors() {
        return applicationContext.getBeansOfType(StepExecutor.class).values().stream()
                .collect(Collectors.toMap(StepExecutor::supports, e -> e));
    }

    private static String asString(Object v, String def) {
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
