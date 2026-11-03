package com.platform.devwork;

/** CR-164: 개발 작업 상태(BIZ-129). 워크플로우 실행과 잇는 쪽이 run 상태로 이것을 고른다(BIZ-130). */
public enum DevWorkStatus {
    IN_PROGRESS, COMPLETED, FAILED, CANCELLED
}
