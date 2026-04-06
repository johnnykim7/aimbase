package com.platform.hook;

import com.platform.domain.HookDefinitionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-030 PRD-192: 훅 디스패처.
 *
 * HookEvent 발생 → HookRegistry에서 매칭 훅 조회 → HookExecutor로 직렬 실행 → 결과 집계.
 * 집계 규칙: 하나라도 BLOCK이면 최종 BLOCK.
 */
@Component
public class HookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);

    private final HookRegistry hookRegistry;
    private final HookExecutor hookExecutor;

    public HookDispatcher(HookRegistry hookRegistry, HookExecutor hookExecutor) {
        this.hookRegistry = hookRegistry;
        this.hookExecutor = hookExecutor;
    }

    /**
     * 이벤트를 발행하고 모든 매칭 훅을 직렬 실행한 뒤 최종 결과를 반환.
     */
    public HookOutput dispatch(HookEvent event, HookInput input) {
        List<HookDefinitionEntity> hooks = hookRegistry.getHooksFor(event);
        if (hooks.isEmpty()) {
            return HookOutput.PASSTHROUGH;
        }

        log.debug("Dispatching event {} to {} hooks", event, hooks.size());
        return executeAndAggregate(hooks, input);
    }

    /**
     * Tool 이벤트: 도구명으로 추가 필터링하여 발행.
     */
    public HookOutput dispatch(HookEvent event, HookInput input, String toolName) {
        List<HookDefinitionEntity> hooks = hookRegistry.getHooksFor(event, toolName);
        if (hooks.isEmpty()) {
            return HookOutput.PASSTHROUGH;
        }

        log.debug("Dispatching event {} (tool={}) to {} hooks", event, toolName, hooks.size());
        return executeAndAggregate(hooks, input);
    }

    /**
     * 훅이 등록되어 있는지 빠르게 확인 (단축 경로용).
     */
    public boolean hasHooks(HookEvent event) {
        return hookRegistry.hasHooks(event);
    }

    /**
     * 훅 레지스트리 캐시 갱신.
     */
    public void refreshRegistry() {
        hookRegistry.refresh();
    }

    /**
     * 직렬 실행 + 결과 집계.
     * BLOCK이 하나라도 있으면 즉시 BLOCK 반환.
     * updatedInput은 마지막으로 수정한 훅의 것을 사용.
     */
    private HookOutput executeAndAggregate(List<HookDefinitionEntity> hooks, HookInput input) {
        HookOutput aggregated = HookOutput.PASSTHROUGH;
        Map<String, Object> latestUpdatedInput = null;

        for (HookDefinitionEntity hook : hooks) {
            HookOutput output = hookExecutor.execute(hook, input);

            if (output.decision() == HookDecision.BLOCK) {
                log.info("Hook BLOCK: hook={}, event={}", hook.getId(), hook.getEvent());
                return output; // 즉시 BLOCK
            }

            if (output.updatedInput() != null) {
                latestUpdatedInput = output.updatedInput();
            }

            // APPROVE > PASSTHROUGH
            if (output.decision() == HookDecision.APPROVE
                    && aggregated.decision() == HookDecision.PASSTHROUGH) {
                aggregated = output;
            }
        }

        // updatedInput이 있으면 최종 결과에 반영
        if (latestUpdatedInput != null) {
            return new HookOutput(aggregated.decision(), latestUpdatedInput, aggregated.metadata());
        }
        return aggregated;
    }
}
