package com.platform.skill;

/**
 * CR-147: 올리기 검사(BIZ-118) 실패. GlobalExceptionHandler 가 400 으로 돌려준다.
 */
public class SkillBundleRejectedException extends IllegalArgumentException {
    public SkillBundleRejectedException(String message) {
        super("BIZ-118: " + message);
    }
}
