package com.platform.workflow.step;

import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;

import java.util.Map;

/**
 * 워크플로우 스텝 실행기 인터페이스.
 * 각 StepType에 대응하는 구현체가 @Component로 등록됨.
 */
public interface StepExecutor {

    /**
     * 이 실행기가 처리하는 StepType을 반환.
     */
    WorkflowStep.StepType supports();

    /**
     * 스텝을 실행하고 결과 Map을 반환.
     *
     * @param step    실행할 스텝 정의
     * @param context 현재 실행 컨텍스트 (이전 스텝 결과 포함)
     * @return 스텝 실행 결과 ("output" 키를 기본으로 포함)
     * @throws RuntimeException 실행 실패 시 (WorkflowEngine에서 재시도 처리)
     */
    Map<String, Object> execute(WorkflowStep step, StepContext context);
}
