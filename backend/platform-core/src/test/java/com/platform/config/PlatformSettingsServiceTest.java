package com.platform.config;

import com.platform.domain.master.GlobalConfigEntity;
import com.platform.policy.AuditLogger;
import com.platform.repository.master.GlobalConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CR-058: PlatformSettingsService.getStringList() CSV 파싱 테스트.
 */
@ExtendWith(MockitoExtension.class)
class PlatformSettingsServiceTest {

    @Mock private GlobalConfigRepository configRepository;
    @Mock private AuditLogger auditLogger;

    private PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        service = new PlatformSettingsService(configRepository, auditLogger);
    }

    @Test
    void getStringList_returnsDefaultWhenKeyMissing() {
        when(configRepository.findByConfigKey("widget.allowed-origins"))
                .thenReturn(Optional.empty());
        List<String> defaults = List.of("https://default.com");

        List<String> result = service.getStringList("widget.allowed-origins", defaults);

        assertThat(result).isSameAs(defaults);
    }

    @Test
    void getStringList_returnsDefaultWhenBlank() {
        when(configRepository.findByConfigKey("widget.allowed-origins"))
                .thenReturn(Optional.of(entity("widget.allowed-origins", "")));
        List<String> defaults = List.of("https://default.com");

        List<String> result = service.getStringList("widget.allowed-origins", defaults);

        assertThat(result).isSameAs(defaults);
    }

    @Test
    void getStringList_parsesSingleValue() {
        when(configRepository.findByConfigKey("widget.allowed-origins"))
                .thenReturn(Optional.of(entity("widget.allowed-origins", "https://oms.com")));

        List<String> result = service.getStringList("widget.allowed-origins", List.of());

        assertThat(result).containsExactly("https://oms.com");
    }

    @Test
    void getStringList_parsesCsvAndTrimsWhitespace() {
        when(configRepository.findByConfigKey("widget.allowed-scopes"))
                .thenReturn(Optional.of(entity("widget.allowed-scopes",
                        "chat:stream, workflow:subscribe , rag:read")));

        List<String> result = service.getStringList("widget.allowed-scopes", List.of());

        assertThat(result).containsExactly("chat:stream", "workflow:subscribe", "rag:read");
    }

    @Test
    void getStringList_dropsEmptyTokens() {
        when(configRepository.findByConfigKey("widget.allowed-origins"))
                .thenReturn(Optional.of(entity("widget.allowed-origins", "a,,b,  ,c")));

        List<String> result = service.getStringList("widget.allowed-origins", List.of());

        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void getAllGrouped_includesSkillSettings() {
        when(configRepository.findByConfigKeyStartingWith(anyString()))
                .thenReturn(List.of());
        when(configRepository.findByConfigKeyStartingWith("skills."))
                .thenReturn(List.of(entity("skills.read-max-bytes", "262144")));

        var grouped = service.getAllGrouped(null);

        assertThat(grouped).containsOnlyKeys("skills");
        assertThat(grouped.get("skills")).extracting(PlatformSettingsService.SettingItem::key)
                .containsExactly("skills.read-max-bytes");
    }

    /** CR-185 TC-CHT-115: 대화 승인 대기는 10 ~ 240 초만 받는다. */
    @Test
    void chatApprovalTimeout_acceptsOnlyTenTo240() {
        String key = "chat.approval-timeout-seconds";
        when(configRepository.findByConfigKey(key)).thenReturn(Optional.of(entity(key, "120")));

        for (String bad : List.of("9", "241", "abc")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateSettings(
                    List.of(new PlatformSettingsService.SettingUpdateRequest(key, bad)), "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.updateSettings(List.of(
                new PlatformSettingsService.SettingUpdateRequest(key, "10"),
                new PlatformSettingsService.SettingUpdateRequest(key, "240")), "admin")).isEqualTo(2);
    }

    private GlobalConfigEntity entity(String key, String value) {
        GlobalConfigEntity e = new GlobalConfigEntity();
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }
}
