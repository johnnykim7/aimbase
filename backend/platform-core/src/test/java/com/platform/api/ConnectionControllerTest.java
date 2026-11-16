package com.platform.api;

import com.platform.action.AdapterRegistry;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionManagementService;
import com.platform.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("연결 조회 API 가 HTTP 연결 인증키를 가린다 (CR-197)")
class ConnectionControllerTest {

    private ConnectionRepository connectionRepository;
    private ConnectionController controller;

    @BeforeEach
    void setUp() {
        connectionRepository = mock(ConnectionRepository.class);
        controller = new ConnectionController(connectionRepository,
                mock(AdapterRegistry.class),
                mock(ConnectionAdapterFactory.class),
                mock(ConnectionManagementService.class));
        when(connectionRepository.save(any(ConnectionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ConnectionEntity lawConnection(String value) {
        ConnectionEntity e = new ConnectionEntity();
        e.setId("law-api");
        e.setName("법제처");
        e.setAdapter("http");
        e.setType("HTTP");
        e.setConfig(config(value));
        return e;
    }

    private static Map<String, Object> config(String value) {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("type", "API_KEY");
        auth.put("in", "query");
        auth.put("name", "OC");
        auth.put("value", value);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("baseUrl", "https://www.law.go.kr");
        config.put("auth", auth);
        return config;
    }

    private static Object authValue(ConnectionEntity e) {
        return ((Map<?, ?>) e.getConfig().get("auth")).get("value");
    }

    @Test
    void get_masks_auth_value() {
        ConnectionEntity stored = lawConnection("real-key");
        when(connectionRepository.findById("law-api")).thenReturn(Optional.of(stored));

        ConnectionEntity body = controller.get("law-api").data();

        assertThat(authValue(body)).isEqualTo("***");
        assertThat(authValue(stored)).isEqualTo("real-key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_masks_auth_value_with_and_without_type_filter() {
        when(connectionRepository.findByType("HTTP")).thenReturn(List.of(lawConnection("real-key")));
        when(connectionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(lawConnection("real-key"))));

        List<ConnectionEntity> byType = (List<ConnectionEntity>) controller.list(0, 20, "HTTP").data();
        List<ConnectionEntity> paged = (List<ConnectionEntity>) controller.list(0, 20, null).data();

        assertThat(authValue(byType.get(0))).isEqualTo("***");
        assertThat(authValue(paged.get(0))).isEqualTo("***");
    }

    @Test
    void update_with_masked_value_keeps_stored_key_and_returns_masked() {
        ConnectionEntity stored = lawConnection("real-key");
        when(connectionRepository.findById("law-api")).thenReturn(Optional.of(stored));

        ConnectionEntity body = controller.update("law-api", new ConnectionController.ConnectionRequest(
                "law-api", "법제처 새 이름", "http", "HTTP", config("***"))).data();

        ArgumentCaptor<ConnectionEntity> saved = ArgumentCaptor.forClass(ConnectionEntity.class);
        verify(connectionRepository).save(saved.capture());
        assertThat(authValue(saved.getValue())).isEqualTo("real-key");
        assertThat(saved.getValue().getName()).isEqualTo("법제처 새 이름");
        assertThat(authValue(body)).isEqualTo("***");
    }

    @Test
    void create_returns_masked_value_and_saves_real_key() {
        ConnectionEntity body = controller.create(new ConnectionController.ConnectionRequest(
                "law-api", "법제처", "http", "HTTP", config("real-key"))).data();

        ArgumentCaptor<ConnectionEntity> saved = ArgumentCaptor.forClass(ConnectionEntity.class);
        verify(connectionRepository).save(saved.capture());
        assertThat(authValue(saved.getValue())).isEqualTo("real-key");
        assertThat(authValue(body)).isEqualTo("***");
    }
}
