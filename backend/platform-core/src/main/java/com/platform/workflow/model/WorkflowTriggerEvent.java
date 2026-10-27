package com.platform.workflow.model;

/** CR-164: trigger_config {"type":"event","event":"..."} 로 워크플로우를 실행하는 이벤트(BIZ-131). */
public enum WorkflowTriggerEvent {
    DEV_WORK_ITEM_STATUS_CHANGED;

    /** 엔진이 실행에 쓰는 trigger_config.type 은 이것 하나다. */
    public static final String TRIGGER_TYPE = "event";
}
