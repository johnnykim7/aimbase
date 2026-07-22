package com.platform.workflow.step;

import com.platform.workflow.StepContext;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
 *   <li>{@code collect=append}(기본): {@code output}/{@code results}/{@code structured_data} 모두 각 원소
 *       body 결과 List (후속 스텝이 {@code {{foreach.structured_data}}} 로 자식의 structured_data 를
 *       묻히지 않고 받을 수 있도록 동일 List 를 structured_data 로도 노출)</li>
 *   <li>{@code collect=none}: {@code output}/{@code results}/{@code structured_data} 모두 빈 List</li>
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
    /**
     * CR-090 후속: FOREACH 자식 body 의 STEP_START/END 를 직접 발행하기 위한 레코더.
     * <p>FOREACH 는 {@code bodyExecutor.execute()} 를 직접 호출해 {@link com.platform.workflow.WorkflowEngine}
     * 의 top-level STEP_START 발행 루프를 우회한다. 그래서 자식 body(특히 AGENT_CALL)가 도구를
     * 부르기 전에는 {@code 부모.body[N]} step_id 이벤트가 전혀 없어, FE 채팅흐름 뷰가 반복 카드를
     * 못 그리고 "반복 항목을 처리 중…" 만 띄운다(빈 컨테이너). 자식 시작 즉시 STEP_START 를 발행하면
     * TOOL_CALL 자식(즉시 TOOL_USE)과 동일하게 AGENT_CALL 자식도 시작 시점부터 반복 카드가 보인다.
     * <p>{@code ObjectProvider} 로 지연 주입 — 비워크플로우/테스트 경로(레코더 미등록)에서도 null 안전.
     */
    private final WorkflowRunEventRecorder eventRecorder;

    public ForeachStepExecutor(ApplicationContext applicationContext,
                               ObjectProvider<WorkflowRunEventRecorder> eventRecorderProvider) {
        this.applicationContext = applicationContext;
        this.eventRecorder = eventRecorderProvider != null ? eventRecorderProvider.getIfAvailable() : null;
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
        // CR-117: 자식 단위 재시도 횟수 (총 시도 = 1 + itemRetryMax). 기본 0 = 재시도 없음(현행).
        // 빈응답/timeout 류 실패 시 그 자식만 --resume 이어하기로 재시도(부모 step 전체 재실행 회피).
        int itemRetryMax = Math.max(0, asInt(config.get("item_retry_max"), 0));
        // CR-117: 실패율 임계치. 지정 시 failed/total 가 초과하면 step 전체 FAILED 승격
        // (on_item_error=continue 가 부분 누락을 "완료"로 둔갑시키는 것 차단). 미지정(<0)=현행.
        double maxFailedRatio = asDouble(config.get("max_failed_ratio"), -1.0);

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
                ? runParallel(step, context, items, itemVar, bodyStep, bodyExecutor, maxConcurrency, onItemError, itemRetryMax)
                : runSequential(step, context, items, itemVar, bodyStep, bodyExecutor, onItemError, itemRetryMax);

        long failed = results.stream().filter(r -> "failed".equals(r.get("status"))).count();

        // 전건 실패는 on_item_error=continue / max_failed_ratio 설정과 무관하게 항상 step FAILED 승격.
        // 모든 item 이 실패했는데 "완료(✅)"로 둔갑해 빈 결과가 후속 스텝에 흘러가던 문제 차단
        // (사용자 결정: 전건 실패면 스텝 실패 → run 종료). 부분 실패는 아래 max_failed_ratio 정책에 위임.
        boolean allFailed = failed == items.size();
        if (allFailed) {
            throw new IllegalStateException(
                    "FOREACH step '" + step.id() + "': all " + items.size()
                            + " item(s) failed — step FAILED (last error in item results)");
        }

        // CR-117: 실패율 임계치 초과 시 step 전체 FAILED 승격. on_item_error=continue 라도 부분 누락이
        // 임계를 넘으면 "완료" 둔갑을 막는다(빈 결과가 후속 스텝에 흘러가는 것 차단). 미지정(<0)=현행(부분 통과).
        if (maxFailedRatio >= 0.0 && !items.isEmpty()) {
            double ratio = (double) failed / items.size();
            if (ratio > maxFailedRatio) {
                throw new IllegalStateException(
                        "FOREACH step '" + step.id() + "': failed ratio " + String.format("%.2f", ratio)
                                + " (" + failed + "/" + items.size() + ") exceeds max_failed_ratio " + maxFailedRatio);
            }
        }

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
        // 후속 스텝이 {{foreach.structured_data}} 로 받을 수 있게 동일 List 를 structured_data 로도 노출한다.
        // 자식 body(예: LARGE_INPUT)는 context 폭발 방지로 fact 본체를 output 이 아니라 structured_data 키에만
        // 담는다(LargeInputStepExecutor 참조). 그런데 FOREACH 는 output/results 만 내보내 자식의 structured_data
        // 가 묻혀, verify 같은 후속 스텝의 {{extract_facts.structured_data}} 가 빈 값으로 치환되던 버그를 차단.
        // 값은 각 자식 결과 원소(그 안에 자식의 structured_data 포함)의 List — 후속 프롬프트는 "각 원소의
        // structured_data.facts" 를 훑으므로 results 와 동일 구조로 정합.
        output.put("structured_data", collected);
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
                                                     String onItemError, int itemRetryMax) {
        List<Map<String, Object>> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            results.add(runOne(step, context, items.get(i), i, itemVar, bodyStep, bodyExecutor, onItemError, itemRetryMax));
        }
        return results;
    }

    private List<Map<String, Object>> runParallel(WorkflowStep step, StepContext context,
                                                   List<Object> items, String itemVar,
                                                   WorkflowStep bodyStep, StepExecutor bodyExecutor,
                                                   int maxConcurrency, String onItemError, int itemRetryMax) {
        Semaphore gate = new Semaphore(maxConcurrency);
        // VT 는 부모 ThreadLocal 을 상속하지 않으므로 TenantContext 를 캡처해 각 VT 안에서 재주입.
        // 누락 시 body(AGENT_CALL→SubagentRunner.save 등)가 잘못된 DataSource(=master)로 라우팅되어
        // "relation \"subagent_runs\" does not exist" 로 전원 실패 (DB-per-Tenant 격리, BIZ-003).
        final String tenantId = com.platform.tenant.TenantContext.getTenantId();
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Object item = items.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (tenantId != null) com.platform.tenant.TenantContext.setTenantId(tenantId);
                gate.acquireUninterruptibly();
                try {
                    return runOne(step, context, item, idx, itemVar, bodyStep, bodyExecutor, onItemError, itemRetryMax);
                } finally {
                    gate.release();
                    com.platform.tenant.TenantContext.clear();
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

    /**
     * 단일 원소 처리 — item/index 주입 후 body 위임. on_item_error 정책 적용.
     *
     * <p>CR-117: 자식 단위 재시도. body 실행이 실패하면 그 자식만 최대 {@code itemRetryMax} 회 재시도한다
     * (부모 step 전체 재실행 회피 — 이미 성공한 형제 자식 낭비 방지). 직전 실패 메시지를
     * {@link StepContext#withRetryFailure} 로 다음 attempt 에 실어, body 가 AGENT_CALL 이면
     * AgentCallStepExecutor 가 timeout/빈응답 류일 때 결정적 childSessionId 로 {@code --resume} 이어하기를 적용한다.
     * 자식 stepId({@code parent.body[index]})가 결정적 멱등 키라 형제 간 세션 충돌이 없다.
     */
    private Map<String, Object> runOne(WorkflowStep step, StepContext context, Object item, int index,
                                       String itemVar, WorkflowStep bodyStep, StepExecutor bodyExecutor,
                                       String onItemError, int itemRetryMax) {
        StepContext itemCtx = injectItem(context, item, index, itemVar);
        WorkflowStep itemStep = withItemId(bodyStep, index);
        int maxAttempts = 1 + Math.max(0, itemRetryMax);
        Exception last = null;

        // CR-090 후속: 자식 STEP_START 를 body 실행 진입 전에 1회 발행. FOREACH 는 top-level STEP_START
        // 발행 루프를 우회하므로, 이 발행이 없으면 AGENT_CALL 자식이 첫 도구를 부르기 전까지 `부모.body[N]`
        // 이벤트가 0건 → FE 가 반복 카드를 못 그린다. 시작 즉시 발행해 빈 반복 카드라도 보이게 한다.
        UUID runUuid = parseRunUuid(context.workflowRunId());
        long childStart = System.currentTimeMillis();
        if (eventRecorder != null && runUuid != null) {
            eventRecorder.stepStart(runUuid, itemStep.id(), bodyStep.type().name());
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> r = bodyExecutor.execute(itemStep, itemCtx);
                Map<String, Object> enriched = new LinkedHashMap<>(r != null ? r : Map.of());
                enriched.putIfAbsent("status", "ok");
                enriched.put("index", index);
                if (attempt > 1) enriched.put("retried_attempts", attempt);
                // 자식 STEP_END — FE 가 이 반복을 "완료"로 확정(없을 땐 부모 종료/마지막 블록 추론에 의존).
                if (eventRecorder != null && runUuid != null) {
                    eventRecorder.stepEnd(runUuid, itemStep.id(), System.currentTimeMillis() - childStart,
                            estimateChildOutputSize(enriched));
                }
                return enriched;
            } catch (Exception e) {
                last = e;
                if (attempt < maxAttempts) {
                    log.warn("FOREACH step '{}' item[{}] attempt {}/{} failed: {} — retrying child",
                            step.id(), index, attempt, maxAttempts, e.getMessage());
                    // 다음 attempt 에 직전 실패 메시지 주입 → AGENT_CALL 이면 timeout/빈응답류 판정해 --resume.
                    itemCtx = itemCtx.withRetryFailure(e.getMessage());
                }
            }
        }

        // 재시도 상한까지 실패 — 자식 STEP_FAILED 발행(continue/throw 양쪽 공통). FE 가 이 반복을
        // 실패(❌ 사유)로 정확히 표시한다(없을 땐 빈응답 추정 배지로만 보임).
        String reason = last != null ? last.getMessage() : "unknown";
        if (eventRecorder != null && runUuid != null) {
            eventRecorder.stepFailed(runUuid, itemStep.id(), System.currentTimeMillis() - childStart,
                    reason, maxAttempts);
        }

        // 재시도 상한까지 실패 — on_item_error 정책 적용.
        if ("continue".equals(onItemError)) {
            log.warn("FOREACH step '{}' item[{}] failed after {} attempt(s) (on_item_error=continue): {}",
                    step.id(), index, maxAttempts, reason);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "failed");
            err.put("error", reason);
            err.put("index", index);
            err.put("attempts", maxAttempts);
            return err;
        }
        throw new RuntimeException(
                "FOREACH step '" + step.id() + "' item[" + index + "] failed after " + maxAttempts
                        + " attempt(s): " + reason, last);
    }

    /** workflowRunId(String) → UUID. null/형식오류면 null(이벤트 미발행 — 비워크플로우/테스트 경로 안전). */
    private static UUID parseRunUuid(String workflowRunId) {
        if (workflowRunId == null || workflowRunId.isBlank()) return null;
        try {
            return UUID.fromString(workflowRunId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 자식 결과 본문 크기 추정 — STEP_END output_size payload 용(대략값). */
    private static int estimateChildOutputSize(Map<String, Object> result) {
        Object out = result.get("output");
        if (out == null) return 0;
        return out.toString().length();
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

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return def;
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
