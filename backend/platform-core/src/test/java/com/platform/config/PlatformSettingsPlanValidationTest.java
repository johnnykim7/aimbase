package com.platform.config;

import com.platform.domain.master.GlobalConfigEntity;
import com.platform.policy.AuditLogger;
import com.platform.repository.master.GlobalConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("운영 설정 저장의 플랜 검사 (CR-191, BIZ-153)")
class PlatformSettingsPlanValidationTest {

    private GlobalConfigRepository repository;
    private PlatformSettingsService service;
    private final Map<String, GlobalConfigEntity> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(GlobalConfigRepository.class);
        row("plan.codes", "free,starter,pro,enterprise");
        row("plan.default", "free");
        row("plan.free.api-rpm-limit", "60");
        row("plan.starter.api-rpm-limit", "300");
        row("plan.pro.api-rpm-limit", "1000");
        row("plan.enterprise.api-rpm-limit", "0");
        when(repository.findByConfigKey(anyString()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
        service = new PlatformSettingsService(repository, mock(AuditLogger.class));
    }

    private void row(String key, String value) {
        GlobalConfigEntity e = new GlobalConfigEntity();
        e.setConfigKey(key);
        e.setConfigValue(value);
        rows.put(key, e);
    }

    private static PlatformSettingsService.SettingUpdateRequest set(String key, String value) {
        return new PlatformSettingsService.SettingUpdateRequest(key, value);
    }

    @ParameterizedTest(name = "TC-PLAN-011 {0}={1}")
    @CsvSource(delimiter = '|', value = {
            "plan.pro.api-rpm-limit|-1",
            "plan.pro.api-rpm-limit|1.5",
            "plan.pro.api-rpm-limit|abc",
            "plan.default|standard",
            "plan.codes|free,gold",
            "plan.codes|' '"})
    @DisplayName("TC-PLAN-011 틀린 플랜 값은 거절하고 저장하지 않는다")
    void tcPlan011_rejectsInvalidPlanValues(String key, String value) {
        assertThatThrownBy(() -> service.updateSettings(List.of(set(key, value)), "admin"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("TC-PLAN-011 같은 요청에 틀린 키가 하나라도 있으면 맞는 키도 저장하지 않는다")
    void tcPlan011_oneInvalidKeySavesNothing() {
        assertThatThrownBy(() -> service.updateSettings(List.of(
                set("plan.starter.api-rpm-limit", "301"),
                set("plan.pro.api-rpm-limit", "-1")), "admin"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
        assertThat(rows.get("plan.starter.api-rpm-limit").getConfigValue()).isEqualTo("300");
    }

    @Test
    @DisplayName("TC-PLAN-011 0 은 무제한으로 받는다")
    void tcPlan011_zeroIsAccepted() {
        int updated = service.updateSettings(List.of(set("plan.pro.api-rpm-limit", "0")), "admin");

        assertThat(updated).isEqualTo(1);
        verify(repository).save(rows.get("plan.pro.api-rpm-limit"));
        assertThat(rows.get("plan.pro.api-rpm-limit").getConfigValue()).isEqualTo("0");
    }
}
