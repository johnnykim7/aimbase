package com.platform.api;

import com.platform.domain.MCPServerEntity;
import com.platform.mcp.MCPServerHeaders;
import com.platform.mcp.MCPServerManager;
import com.platform.repository.MCPServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-145 등록 조회 응답 가림(TC-MCPH-004), 수정 시 가린 값 유지(TC-MCPH-005), 잘못된 머리글 거부(TC-MCPH-006). */
@ExtendWith(MockitoExtension.class)
class MCPControllerHeadersTest {

    private static final String URL = "http://host:8183/api/mcp";
    private static final String SECRET = "secret-1";

    @Mock MCPServerRepository repository;
    @Mock MCPServerManager manager;

    private MCPController controller;

    @BeforeEach
    void setUp() {
        controller = new MCPController(repository, manager);
    }

    private static MCPServerEntity storedServer() {
        MCPServerEntity entity = new MCPServerEntity();
        entity.setId("bid");
        entity.setName("bidding");
        entity.setTransport("sse");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", URL);
        config.put("headers", new LinkedHashMap<>(Map.of("X-API-Key", SECRET, "X-Old", "old")));
        entity.setConfig(config);
        return entity;
    }

    private static Map<String, Object> headersOf(Map<String, Object> config) {
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) config.get("headers");
        return headers;
    }

    @Test
    void get_masksHeaderValuesWithoutTouchingStoredConfig() {
        MCPServerEntity entity = storedServer();
        when(repository.findById("bid")).thenReturn(Optional.of(entity));

        MCPController.MCPServerResponse body = controller.get("bid").data();

        assertThat(headersOf(body.config()))
                .containsEntry("X-API-Key", MCPServerHeaders.MASK)
                .containsEntry("X-Old", MCPServerHeaders.MASK);
        assertThat(body.config()).containsEntry("url", URL);
        assertThat(headersOf(entity.getConfig())).containsEntry("X-API-Key", SECRET);
    }

    @Test
    void list_masksHeaderValues() {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(storedServer())));

        @SuppressWarnings("unchecked")
        List<MCPController.MCPServerResponse> data =
                (List<MCPController.MCPServerResponse>) controller.list(0, 20).data();

        assertThat(headersOf(data.get(0).config())).containsEntry("X-API-Key", MCPServerHeaders.MASK);
    }

    @Test
    void create_storesValueAndReturnsMasked() {
        when(repository.save(any(MCPServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> config = Map.of("url", URL, "headers", Map.of("X-API-Key", SECRET));

        MCPController.MCPServerResponse body = controller.create(
                new MCPController.MCPServerRequest("bid", "bidding", "sse", config, true)).data();

        ArgumentCaptor<MCPServerEntity> saved = ArgumentCaptor.forClass(MCPServerEntity.class);
        verify(repository).save(saved.capture());
        assertThat(headersOf(saved.getValue().getConfig())).containsEntry("X-API-Key", SECRET);
        assertThat(headersOf(body.config())).containsEntry("X-API-Key", MCPServerHeaders.MASK);
    }

    @Test
    void update_keepsMaskedValueReplacesNewAndDropsMissing() {
        when(repository.findById("bid")).thenReturn(Optional.of(storedServer()));
        when(repository.save(any(MCPServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> config = Map.of("url", URL,
                "headers", Map.of("X-API-Key", MCPServerHeaders.MASK, "X-New", "new"));

        MCPController.MCPServerResponse body = controller.update("bid",
                new MCPController.MCPServerRequest(null, "bidding", "sse", config, null)).data();

        ArgumentCaptor<MCPServerEntity> saved = ArgumentCaptor.forClass(MCPServerEntity.class);
        verify(repository).save(saved.capture());
        assertThat(headersOf(saved.getValue().getConfig()))
                .isEqualTo(Map.of("X-API-Key", SECRET, "X-New", "new"));
        assertThat(headersOf(body.config()))
                .isEqualTo(Map.of("X-API-Key", MCPServerHeaders.MASK, "X-New", MCPServerHeaders.MASK));
    }

    @Test
    void create_rejectsRestrictedHeader() {
        Map<String, Object> config = Map.of("url", URL, "headers", Map.of("Host", "evil"));

        assertThatThrownBy(() -> controller.create(
                new MCPController.MCPServerRequest("bid", "bidding", "sse", config, true)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void update_rejectsMaskWithoutStoredValue() {
        when(repository.findById("bid")).thenReturn(Optional.of(storedServer()));
        Map<String, Object> config = Map.of("url", URL, "headers", Map.of("X-Unknown", MCPServerHeaders.MASK));

        assertThatThrownBy(() -> controller.update("bid",
                new MCPController.MCPServerRequest(null, "bidding", "sse", config, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }
}
