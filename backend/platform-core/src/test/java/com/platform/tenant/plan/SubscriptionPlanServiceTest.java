package com.platform.tenant.plan;

import com.platform.config.PlatformSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("플랜 설정 읽기 (CR-191, BIZ-153, CR-205, BIZ-298)")
class SubscriptionPlanServiceTest {

    /** 설정 화면에 저장된 값처럼 키마다 문자열을 돌려주는 가짜 설정. */
    static PlatformSettingsService settings(Map<String, String> values) {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), any())).thenAnswer(call ->
                values.getOrDefault(call.getArgument(0, String.class), call.getArgument(1, String.class)));
        return settings;
    }

    private final SubscriptionPlanService service = new SubscriptionPlanService(settings(Map.of(
            "plan.codes", "free, pro",
            "plan.default", "free",
            "plan.free.api-rpm-limit", "60",
            "plan.pro.api-rpm-limit", "1000",
            "plan.free.chat-rpm-limit", "60",
            "plan.pro.chat-rpm-limit", "500")));

    @Test
    @DisplayName("TC-PLAN-001 목록과 기본 플랜, 플랜별 한도를 설정에서 읽는다")
    void tcPlan001_readsCatalogFromSettings() {
        SubscriptionPlanService.PlanCatalog catalog = service.catalog();

        assertThat(catalog.defaultPlan()).isEqualTo("free");
        assertThat(catalog.plans()).containsExactly(
                new SubscriptionPlanService.PlanInfo("free", 60, 60),
                new SubscriptionPlanService.PlanInfo("pro", 1000, 500));
    }

    @Test
    @DisplayName("TC-PLAN-002 목록에 없는 이름은 목록을 담은 문구로 거절한다")
    void tcPlan002_rejectsUnknownPlan() {
        assertThatThrownBy(() -> service.requireKnown("standard"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("plan must be one of: free, pro");
    }

    @Test
    @DisplayName("구독 행이 없는 테넌트는 기본 플랜의 한도, 설정 행이 없는 서버면 받은 값")
    void defaultPlanRpmLimitFallsBackOnlyWithoutSettings() {
        assertThat(service.defaultPlanRpmLimit(7)).isEqualTo(60);
        assertThat(new SubscriptionPlanService(settings(Map.of())).defaultPlanRpmLimit(7)).isEqualTo(7);
    }

    @Test
    @DisplayName("TC-PLAN-013 플랜의 채팅 한도를 설정에서 읽고, 행이 없으면 멈춘다")
    void tcPlan013_chatLimitFromSettings() {
        assertThat(service.chatRpmLimit("pro")).isEqualTo(500);
        assertThat(service.defaultPlanChatRpmLimit(7)).isEqualTo(60);

        SubscriptionPlanService withoutChatRow = new SubscriptionPlanService(settings(Map.of(
                "plan.codes", "free", "plan.default", "free", "plan.free.api-rpm-limit", "60")));
        assertThatThrownBy(() -> withoutChatRow.chatRpmLimit("free"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plan.free.chat-rpm-limit");
        assertThat(withoutChatRow.defaultPlanChatRpmLimit(7)).isEqualTo(7);
    }
}
