package com.platform.service;

import com.platform.api.AgentRegistryController;
import com.platform.domain.AgentRegistryEntity;
import com.platform.repository.AgentRegistryRepository;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-156: 하트비트는 모르는 등록에 404 를 주고, agent 는 그걸 보고 다시 등록한다.
 */
class AgentRegistryHeartbeatTest {

    private AgentRegistryRepository repository;
    private AgentRegistryService service;
    private AgentRegistryController controller;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRegistryRepository.class);
        service = new AgentRegistryService(repository, mock(TenantDataSourceManager.class),
                mock(ApplicationEventPublisher.class));
        controller = new AgentRegistryController(service);
    }

    private AgentRegistryEntity agent(String status) {
        AgentRegistryEntity e = new AgentRegistryEntity();
        e.setId(UUID.randomUUID());
        e.setStatus(status);
        e.setLastHeartbeatAt(OffsetDateTime.now().minusMinutes(10));
        return e;
    }

    @Test
    @DisplayName("TC-AGR-001 모르는 id 의 하트비트는 서비스가 false, 컨트롤러가 404")
    void unknownAgentIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.heartbeat(id)).isFalse();
        assertThatThrownBy(() -> controller.heartbeat(id))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("TC-AGR-002 STALE 등록의 하트비트는 시각을 갱신하고 ACTIVE 로 되돌린다")
    void staleAgentRecovers() {
        AgentRegistryEntity e = agent("STALE");
        OffsetDateTime before = e.getLastHeartbeatAt();
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));

        assertThat(controller.heartbeat(e.getId())).isEqualTo(Map.of("status", "ok"));
        assertThat(e.getStatus()).isEqualTo("ACTIVE");
        assertThat(e.getLastHeartbeatAt()).isAfter(before);
        verify(repository).save(e);
    }

    @Test
    @DisplayName("TC-AGR-003 해제된 등록의 하트비트는 200 이고 DEREGISTERED 그대로다")
    void deregisteredAgentStaysDeregistered() {
        AgentRegistryEntity e = agent("DEREGISTERED");
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));

        assertThat(service.heartbeat(e.getId())).isTrue();
        assertThat(e.getStatus()).isEqualTo("DEREGISTERED");
    }
}
