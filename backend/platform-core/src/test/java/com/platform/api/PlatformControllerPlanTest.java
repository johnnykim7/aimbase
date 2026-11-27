package com.platform.api;

import com.platform.auth.JwtProvider;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.master.SubscriptionEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.repository.master.TenantUsageSummaryRepository;
import com.platform.tenant.TenantDataSourceManager;
import com.platform.tenant.migration.TenantMigrationRunner;
import com.platform.tenant.onboarding.TenantOnboardingService;
import com.platform.tenant.plan.SubscriptionPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("구독 변경의 플랜과 플랜 목록 API (CR-191, BIZ-153)")
class PlatformControllerPlanTest {

    private static final String TENANT = "t1";

    private SubscriptionRepository subscriptions;
    private SubscriptionEntity sub;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        subscriptions = mock(SubscriptionRepository.class);
        sub = new SubscriptionEntity();
        sub.setTenantId(TENANT);
        sub.setMaxConnections(5);
        when(subscriptions.findByTenantId(TENANT)).thenReturn(Optional.of(sub));
        when(subscriptions.save(any(SubscriptionEntity.class))).thenAnswer(call -> call.getArgument(0));

        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        Map<String, String> values = Map.of(
                "plan.codes", "free,starter,pro,enterprise",
                "plan.default", "free",
                "plan.free.api-rpm-limit", "60",
                "plan.starter.api-rpm-limit", "300",
                "plan.pro.api-rpm-limit", "1000",
                "plan.enterprise.api-rpm-limit", "0",
                "plan.free.chat-rpm-limit", "60",
                "plan.starter.chat-rpm-limit", "300",
                "plan.pro.chat-rpm-limit", "1000",
                "plan.enterprise.chat-rpm-limit", "0");
        when(settings.getString(anyString(), any())).thenAnswer(call ->
                values.getOrDefault(call.getArgument(0, String.class), call.getArgument(1, String.class)));

        PlatformController controller = new PlatformController(
                mock(TenantRepository.class),
                subscriptions,
                mock(TenantUsageSummaryRepository.class),
                mock(TenantOnboardingService.class),
                mock(TenantDataSourceManager.class),
                mock(TenantMigrationRunner.class),
                mock(JwtProvider.class),
                new SubscriptionPlanService(settings));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void current(String plan, int limit) {
        sub.setPlan(plan);
        sub.setApiRpmLimit(limit);
        sub.setChatRpmLimit(limit);
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
    @DisplayName("TC-PLAN-006 다른 플랜으로 바꾸면 한도도 그 플랜의 값이 된다")
    void tcPlan006_otherPlanTakesItsLimit() throws Exception {
        current("free", 60);

        update("{\"plan\":\"pro\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan").value("pro"))
                .andExpect(jsonPath("$.data.apiRpmLimit").value(1000));
    }

    @Test
    @DisplayName("TC-PLAN-007 같은 플랜을 다시 보내면 한도는 그대로다")
    void tcPlan007_samePlanKeepsLimit() throws Exception {
        current("pro", 300);

        update("{\"plan\":\"pro\"}").andExpect(status().isOk());

        assertThat(saved().getPlan()).isEqualTo("pro");
        assertThat(saved().getApiRpmLimit()).isEqualTo(300);
    }

    @Test
    @DisplayName("TC-PLAN-008 플랜과 한도를 같이 보내면 보낸 한도를 쓴다")
    void tcPlan008_explicitLimitWins() throws Exception {
        current("free", 60);

        update("{\"plan\":\"pro\",\"apiRpmLimit\":300}").andExpect(status().isOk());

        assertThat(saved().getPlan()).isEqualTo("pro");
        assertThat(saved().getApiRpmLimit()).isEqualTo(300);
    }

    @Test
    @DisplayName("TC-PLAN-009 없는 플랜은 400 이고 같이 보낸 값도 저장하지 않는다")
    void tcPlan009_unknownPlanSavesNothing() throws Exception {
        current("free", 60);

        update("{\"plan\":\"standard\",\"maxConnections\":9}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("plan must be one of: free, starter, pro, enterprise"));

        verify(subscriptions, never()).save(any());
        assertThat(sub.getMaxConnections()).isEqualTo(5);
        assertThat(sub.getPlan()).isEqualTo("free");
    }

    @Test
    @DisplayName("TC-PLAN-012 플랜 목록 API 는 목록 순서대로 이름과 한도, 기본 플랜을 준다")
    void tcPlan012_listsPlans() throws Exception {
        mvc.perform(get("/api/v1/platform/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultPlan").value("free"))
                .andExpect(jsonPath("$.data.plans[0].code").value("free"))
                .andExpect(jsonPath("$.data.plans[0].apiRpmLimit").value(60))
                .andExpect(jsonPath("$.data.plans[1].code").value("starter"))
                .andExpect(jsonPath("$.data.plans[1].apiRpmLimit").value(300))
                .andExpect(jsonPath("$.data.plans[2].code").value("pro"))
                .andExpect(jsonPath("$.data.plans[2].apiRpmLimit").value(1000))
                .andExpect(jsonPath("$.data.plans[3].code").value("enterprise"))
                .andExpect(jsonPath("$.data.plans[3].apiRpmLimit").value(0));
    }

    /** CR-205 TC-PLAN-015: 채팅 한도는 플랜을 바꿀 때 그 플랜의 값, 같은 플랜은 그대로, 같이 보낸 값이 이긴다(BIZ-298). */
    @Test
    @DisplayName("TC-PLAN-015 플랜 변경과 채팅 분당 요청 한도")
    void tcPlan015_chatLimitFollowsPlanChange() throws Exception {
        current("free", 60);
        update("{\"plan\":\"pro\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatRpmLimit").value(1000));

        current("pro", 300);
        update("{\"plan\":\"pro\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatRpmLimit").value(300));

        current("free", 60);
        update("{\"plan\":\"pro\",\"chatRpmLimit\":120}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiRpmLimit").value(1000))
                .andExpect(jsonPath("$.data.chatRpmLimit").value(120));
    }

    /** CR-205 TC-PLAN-016: 채팅 한도 검사는 분당 요청 한도와 같다. 0 은 무제한이라 받는다. */
    @Test
    @DisplayName("TC-PLAN-016 틀린 채팅 한도는 400 이고 같이 보낸 값도 저장하지 않는다")
    void tcPlan016_invalidChatLimitSavesNothing() throws Exception {
        current("free", 60);

        for (String body : new String[] {"{\"chatRpmLimit\":-1}", "{\"chatRpmLimit\":1.5}",
                "{\"maxConnections\":9,\"chatRpmLimit\":-1}"}) {
            update(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("chatRpmLimit must be an integer of 0 or greater."));
        }
        update("{\"chatRpmLimit\":\"abc\"}").andExpect(status().isBadRequest());
        verify(subscriptions, never()).save(any());
        assertThat(sub.getMaxConnections()).isEqualTo(5);
        assertThat(sub.getChatRpmLimit()).isEqualTo(60);

        update("{\"chatRpmLimit\":0}").andExpect(status().isOk());
        assertThat(saved().getChatRpmLimit()).isEqualTo(0);
    }

    /** CR-205 TC-PLAN-017: 플랜 목록에 플랜마다 채팅 한도가 든다. */
    @Test
    @DisplayName("TC-PLAN-017 플랜 목록 API 는 채팅 분당 요청 한도도 준다")
    void tcPlan017_listsChatLimits() throws Exception {
        mvc.perform(get("/api/v1/platform/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plans[0].chatRpmLimit").value(60))
                .andExpect(jsonPath("$.data.plans[1].chatRpmLimit").value(300))
                .andExpect(jsonPath("$.data.plans[2].chatRpmLimit").value(1000))
                .andExpect(jsonPath("$.data.plans[3].chatRpmLimit").value(0));
    }
}
