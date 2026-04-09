package com.platform.hook;

/**
 * CR-030 PRD-191: 내부 훅 콜백 인터페이스.
 *
 * Spring Bean으로 등록하면 HookExecutor가 동기 호출한다.
 * Bean 이름이 HookDefinitionEntity.target과 매칭되어야 한다.
 */
@FunctionalInterface
public interface HookCallback {

    /**
     * 훅 이벤트를 처리하고 결정을 반환.
     *
     * @param input 훅 입력 데이터
     * @return 훅 실행 결과 (null 반환 시 PASSTHROUGH 처리)
     */
    HookOutput handle(HookInput input);
}
