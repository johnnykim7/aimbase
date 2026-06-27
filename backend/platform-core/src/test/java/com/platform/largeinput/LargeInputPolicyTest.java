package com.platform.largeinput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-120: 분해 정책 우선순위(STEP > WF > SYSTEM) + 폴백 파싱 단위 테스트.
 */
class LargeInputPolicyTest {

    @Test
    @DisplayName("STEP 이 최우선, 없으면 WF, 없으면 SYSTEM, 다 없으면 AUTO")
    void resolveHonorsPriority() {
        assertThat(LargeInputPolicy.resolve("force", "off", "auto")).isEqualTo(LargeInputPolicy.FORCE);
        assertThat(LargeInputPolicy.resolve(null, "off", "auto")).isEqualTo(LargeInputPolicy.OFF);
        assertThat(LargeInputPolicy.resolve(null, null, "forbid")).isEqualTo(LargeInputPolicy.FORBID);
        assertThat(LargeInputPolicy.resolve(null, null, null)).isEqualTo(LargeInputPolicy.AUTO);
    }

    @Test
    @DisplayName("대소문자 무관 + 잘못된 값은 기본값으로 폴백")
    void parsesCaseInsensitiveWithFallback() {
        assertThat(LargeInputPolicy.fromString("FORCE", LargeInputPolicy.AUTO)).isEqualTo(LargeInputPolicy.FORCE);
        assertThat(LargeInputPolicy.fromString("nonsense", LargeInputPolicy.AUTO)).isEqualTo(LargeInputPolicy.AUTO);
        assertThat(LargeInputPolicy.fromString("", LargeInputPolicy.OFF)).isEqualTo(LargeInputPolicy.OFF);
        assertThat(LargeInputPolicy.fromString(null, LargeInputPolicy.FORBID)).isEqualTo(LargeInputPolicy.FORBID);
    }
}
