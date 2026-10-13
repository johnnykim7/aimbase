package com.platform.api;

import com.platform.auth.ResourceOwner;
import com.platform.auth.UserPrincipal;
import com.platform.domain.WorkflowEntity;
import com.platform.repository.ProjectRepository;
import com.platform.repository.UserRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunEventRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.tenant.ProjectContext;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.WorkflowValidator;
import com.platform.workflow.event.WorkflowRunSubscriberRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-149: 워크플로우 저장 API 를 가이드 계약에 맞춘 부분(TC-WF-022, 025, 028~031).
 */
@DisplayName("WorkflowController 저장 계약 (CR-149)")
class WorkflowControllerSaveTest {

    private WorkflowRepository repository;
    private ProjectRepository projects;
    private UserRepository users;
    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        when(repository.save(any(WorkflowEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        projects = mock(ProjectRepository.class);
        when(projects.existsById(any())).thenReturn(true);
        users = mock(UserRepository.class);
        when(users.existsById("user-a")).thenReturn(true);
        controller = new WorkflowController(repository, mock(WorkflowRunRepository.class),
                mock(WorkflowEngine.class), new WorkflowValidator(),
                mock(WorkflowRunSubscriberRegistry.class), mock(WorkflowRunEventRepository.class),
                projects, new ResourceOwner(users));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal("user-a", "a@test.local", "t1", "ADMIN", Map.of()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ProjectContext.clear();
    }

    private static WorkflowController.WorkflowRequest request(String id, String projectId, String graphMode,
                                                              Map<String, Object> errorHandling) {
        return new WorkflowController.WorkflowRequest(id, "wf", "설명", "legal", projectId, graphMode,
                Map.of("type", "manual"), List.of(Map.of("id", "s1", "type", "LLM_CALL")),
                errorHandling, null, null);
    }

    @Test
    @DisplayName("생성: id 를 비우면 UUID, 설명과 만든 사람과 실행 모드 기본값을 저장한다")
    void create_fills_server_fields() {
        WorkflowEntity saved = controller.create(request(null, null, null, null)).data();

        assertThat(UUID.fromString(saved.getId())).isNotNull();
        assertThat(saved.getDescription()).isEqualTo("설명");
        assertThat(saved.getCreatedBy()).isEqualTo("user-a");
        assertThat(saved.getGraphMode()).isEqualTo("dag");
        assertThat(saved.getProjectId()).isNull();
    }

    @Test
    @DisplayName("생성: projectId 를 비우면 X-Project-Id, 보내면 그 값")
    void create_project_scope() {
        ProjectContext.setProjectId("p1");

        assertThat(controller.create(request("a", null, null, null)).data().getProjectId()).isEqualTo("p1");
        assertThat(controller.create(request("b", "p2", null, null)).data().getProjectId()).isEqualTo("p2");
    }

    @Test
    @DisplayName("생성: 없는 프로젝트면 400 이고 저장하지 않는다")
    void create_unknown_project_rejected() {
        when(projects.existsById("ghost")).thenReturn(false);

        assertThatThrownBy(() -> controller.create(request("g", "ghost", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("생성: 테넌트 사용자가 아니면(임퍼소네이션, 시스템 키) 만든 사람을 비운다")
    void create_by_non_tenant_user_leaves_owner_empty() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal("super-admin-uuid", "sa@test.local", "t1", "ADMIN", Map.of()), null, List.of()));
        assertThat(controller.create(request("i", null, null, null)).data().getCreatedBy()).isNull();

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal("system-key1", "k@test.local", "t1", "admin", Map.of()), null, List.of()));
        assertThat(controller.create(request("k", null, null, null)).data().getCreatedBy()).isNull();
    }

    @Test
    @DisplayName("생성: 이미 있는 id(비활성 포함)면 409 이고 저장하지 않는다")
    void create_duplicate_id_conflict() {
        when(repository.existsById("dup")).thenReturn(true);

        assertThatThrownBy(() -> controller.create(request("dup", null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("생성: graphMode cyclic 은 소문자로 저장, loop 는 400")
    void create_graph_mode() {
        assertThat(controller.create(request("c", null, "CYCLIC", null)).data().getGraphMode()).isEqualTo("cyclic");
        assertThatThrownBy(() -> controller.create(request("d", null, "loop", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("생성: errorHandling 의 옛 칸 이름은 400")
    void create_rejects_legacy_error_handling() {
        assertThatThrownBy(() -> controller.create(request("e", null, null, Map.of("strategy", "stop_on_first"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("수정: 설명과 실행 모드는 바꾸고 프로젝트, 도메인, 만든 사람은 그대로")
    void update_keeps_create_only_fields() {
        WorkflowEntity existing = new WorkflowEntity();
        existing.setId("w1");
        existing.setProjectId("p1");
        existing.setDomain("hr");
        existing.setCreatedBy("user-z");
        existing.setGraphMode("cyclic");
        when(repository.findByIdAndIsActiveTrue("w1")).thenReturn(Optional.of(existing));

        WorkflowEntity saved = controller.update("w1", request(null, "p9", null, null)).data();

        assertThat(saved.getDescription()).isEqualTo("설명");
        assertThat(saved.getGraphMode()).isEqualTo("dag");
        assertThat(saved.getProjectId()).isEqualTo("p1");
        assertThat(saved.getDomain()).isEqualTo("hr");
        assertThat(saved.getCreatedBy()).isEqualTo("user-z");
    }

    @Test
    @DisplayName("목록: my=true 면 내가 만든 것, 아니면 X-Project-Id 의 프로젝트와 공유")
    void list_filters() {
        WorkflowEntity mine = new WorkflowEntity();
        WorkflowEntity scoped = new WorkflowEntity();
        when(repository.findByCreatedByAndIsActiveTrue("user-a")).thenReturn(List.of(mine));
        when(repository.findByProjectIdOrSharedAndIsActiveTrue("p1")).thenReturn(List.of(scoped));
        ProjectContext.setProjectId("p1");

        assertThat(controller.list(0, 20, null, true).data()).isEqualTo(List.of(mine));
        assertThat(controller.list(0, 20, null, false).data()).isEqualTo(List.of(scoped));
        verify(repository, never()).findByIsActiveTrue(any());
    }
}
