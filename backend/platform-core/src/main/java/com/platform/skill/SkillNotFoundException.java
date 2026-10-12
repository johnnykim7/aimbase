package com.platform.skill;

/**
 * CR-147: 이름이나 판을 찾지 못함(BIZ-120). API 는 404, 도구는 오류 결과.
 */
public class SkillNotFoundException extends RuntimeException {
    public SkillNotFoundException(String message) {
        super(message);
    }
}
