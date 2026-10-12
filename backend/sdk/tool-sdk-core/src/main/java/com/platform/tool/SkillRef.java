package com.platform.tool;

/**
 * CR-147: 호출에 고정된 스킬 하나. 단계 시작 때 이름 해석한 결과라 같은 단계의 도구 호출은 이 판만 쓴다.
 *
 * @param scope "tenant" 또는 "platform"
 */
public record SkillRef(String name, String scope, int version) {
}
