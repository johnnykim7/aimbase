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

    private GlobalConfigEntity entity(String key, String value) {
        GlobalConfigEntity e = new GlobalConfigEntity();
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }
}
