package com.platform.api;

import com.platform.auth.JwtProvider;
import com.platform.domain.master.SubscriptionEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.repository.master.TenantUsageSummaryRepository;
import com.platform.tenant.TenantDataSourceManager;
import com.platform.tenant.migration.TenantMigrationRunner;
import com.platform.tenant.onboarding.TenantOnboardingService;
import com.platform.tenant.plan.SubscriptionPlanService;
import com.platform.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("구독 변경 API 의 분당 요청 한도 (CR-169, BIZ-142)")
class PlatformControllerSubscriptionTest {

    private static final String TENANT = "t1";
    private static final String REJECTED = "apiRpmLimit must be an integer of 0 or greater.";

    private SubscriptionRepository subscriptions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        subscriptions = mock(SubscriptionRepository.class);
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setTenantId(TENANT);
        sub.setApiRpmLimit(60);
        sub.setMaxConnections(5);
        when(subscriptions.findByTenantId(TENANT)).thenReturn(Optional.of(sub));
        when(subscriptions.save(any(SubscriptionEntity.class))).thenAnswer(call -> call.getArgument(0));

        PlatformController controller = new PlatformController(
                mock(TenantRepository.class),
                subscriptions,
                mock(TenantUsageSummaryRepository.class),
                mock(TenantOnboardingService.class),
                mock(TenantDataSourceManager.class),
                mock(TenantMigrationRunner.class),
                mock(JwtProvider.class),
                new SubscriptionPlanService(mock(PlatformSettingsService.class)));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ResultActions update(String body) throws Exception {
        return mvc.perform(put("/api/v1/platform/subscriptions/{id}", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private SubscriptionEntity saved() {
        ArgumentCaptor<SubscriptionEntity> captor = ArgumentCaptor.forClass(SubscriptionEntity.class);
        verify(subscriptions).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("TC-SUB-001 분당 요청 한도를 저장하고 다른 값은 그대로 둔다")
    void tcSub001_savesLimit() throws Exception {
        update("{\"apiRpmLimit\":600}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiRpmLimit").value(600));

        assertThat(saved().getApiRpmLimit()).isEqualTo(600);
        assertThat(saved().getMaxConnections()).isEqualTo(5);
    }

    @Test
    @DisplayName("TC-SUB-002 0 은 무제한으로 받는다")
    void tcSub002_zeroMeansUnlimited() throws Exception {
        update("{\"apiRpmLimit\":0}").andExpect(status().isOk());

        assertThat(saved().getApiRpmLimit()).isZero();
    }

    @Test
    @DisplayName("TC-SUB-003 보내지 않으면 분당 요청 한도를 그대로 둔다")
    void tcSub003_absentKeepsLimit() throws Exception {
        update("{\"maxConnections\":9}").andExpect(status().isOk());

        assertThat(saved().getMaxConnections()).isEqualTo(9);
        assertThat(saved().getApiRpmLimit()).isEqualTo(60);
    }

    @ParameterizedTest(name = "TC-SUB-004 ~ 006 {0}")
    @ValueSource(strings = {"-1", "1.5", "2147483648"})
    @DisplayName("TC-SUB-004 ~ 006 음수, 소수, 정수 범위 밖은 거절하고 저장하지 않는다")
    void tcSub004to006_rejectsUnusableNumbers(String value) throws Exception {
        update("{\"apiRpmLimit\":" + value + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(REJECTED));

        verify(subscriptions, never()).save(any());
    }

    @Test
    @DisplayName("TC-SUB-007 숫자가 아닌 값은 거절하고 저장하지 않는다")
    void tcSub007_rejectsNonNumber() throws Exception {
        update("{\"apiRpmLimit\":\"abc\"}").andExpect(status().isBadRequest());

        verify(subscriptions, never()).save(any());
    }

    @Test
    @DisplayName("TC-SUB-008 거절되면 같이 보낸 값도 저장하지 않는다")
    void tcSub008_rejectionSavesNothing() throws Exception {
        update("{\"maxConnections\":9,\"apiRpmLimit\":-1}").andExpect(status().isBadRequest());

        verify(subscriptions, never()).save(any());
    }
}
