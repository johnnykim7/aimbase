package com.platform.tenant.onboarding;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.master.SubscriptionEntity;
import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.tenant.TenantDataSourceManager;
import com.platform.tenant.plan.SubscriptionPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 테넌트 생성이 플랜의 분당 요청 한도로 구독을 만드는지 본다. DB 서버 작업(Step 2~5)은 가짜로 둔다.
 */
@DisplayName("테넌트 생성의 플랜과 분당 요청 한도 (CR-191, BIZ-153)")
class TenantOnboardingServicePlanTest {

    private TenantRepository tenants;
    private SubscriptionRepository subscriptions;
    private TenantOnboardingService service;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantRepository.class);
        subscriptions = mock(SubscriptionRepository.class);
        when(tenants.save(any(TenantEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(subscriptions.save(any(SubscriptionEntity.class))).thenAnswer(call -> call.getArgument(0));

        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        Map<String, String> values = Map.of(
                "plan.codes", "free,starter,pro,enterprise",
                "plan.default", "free",
                "plan.free.api-rpm-limit", "60",
                "plan.starter.api-rpm-limit", "300",
                "plan.pro.api-rpm-limit", "1000",
                "plan.enterprise.api-rpm-limit", "0");
        when(settings.getString(anyString(), any())).thenAnswer(call ->
                values.getOrDefault(call.getArgument(0, String.class), call.getArgument(1, String.class)));

        service = spy(new TenantOnboardingService(tenants, subscriptions, mock(TenantDataSourceManager.class),
                mock(JdbcTemplate.class), new SubscriptionPlanService(settings)));
        doNothing().when(service).prepareTenantDatabase(any(), anyString());
    }

    private static TenantOnboardingRequest request(String plan) {
        return TenantOnboardingRequest.withLocalDb("t1", null, "T1", "admin@t1.local", "pw", plan,
                "localhost", 5432, "platform", "platform");
    }

    private SubscriptionEntity savedSubscription() {
        ArgumentCaptor<SubscriptionEntity> captor = ArgumentCaptor.forClass(SubscriptionEntity.class);
        verify(subscriptions).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("TC-PLAN-003 플랜 없이 만들면 기본 플랜과 그 한도로 시작한다")
    void tcPlan003_noPlanUsesDefault() {
        service.provision(request(null));

        assertThat(savedSubscription().getPlan()).isEqualTo("free");
        assertThat(savedSubscription().getApiRpmLimit()).isEqualTo(60);
    }

    @ParameterizedTest(name = "TC-PLAN-004 {0} 는 {1}")
    @CsvSource({"pro,1000", "enterprise,0"})
    @DisplayName("TC-PLAN-004 고른 플랜의 한도로 시작한다")
    void tcPlan004_planLimitIsApplied(String plan, int limit) {
        service.provision(request(plan));

        assertThat(savedSubscription().getPlan()).isEqualTo(plan);
        assertThat(savedSubscription().getApiRpmLimit()).isEqualTo(limit);
    }

    @Test
    @DisplayName("TC-PLAN-005 없는 플랜은 테넌트 행과 DB 를 만들기 전에 거절한다")
    void tcPlan005_unknownPlanRejectedBeforeAnything() {
        assertThatThrownBy(() -> service.provision(request("standard")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("plan must be one of: free, starter, pro, enterprise");

        verify(tenants, never()).save(any());
        verify(service, never()).prepareTenantDatabase(any(), anyString());
        verify(subscriptions, never()).save(any());
    }
}
