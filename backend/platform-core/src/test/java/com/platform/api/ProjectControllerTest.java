package com.platform.api;

import com.platform.domain.ProjectEntity;
import com.platform.domain.ProjectMemberEntity;
import com.platform.domain.ProjectResourceEntity;
import com.platform.repository.ProjectMemberRepository;
import com.platform.repository.ProjectRepository;
import com.platform.repository.ProjectResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProjectController 단위 테스트 (CR-021).
 * 프로젝트 CRUD, 멤버 관리, 리소스 할당 검증.
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMemberRepository memberRepository;
    @Mock private ProjectResourceRepository resourceRepository;

    private ProjectController controller;

    @BeforeEach
    void setUp() {
        controller = new ProjectController(projectRepository, memberRepository, resourceRepository);
    }

    // ── 프로젝트 CRUD ─────────────────────────────────────────

    @Test
    void list_shouldReturnActiveProjects() {
        ProjectEntity p1 = buildProject("p1", "Project A");
        when(projectRepository.findByIsActiveTrueOrderByName()).thenReturn(List.of(p1));

        ResponseEntity<?> response = controller.list(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<ProjectEntity> body = (List<ProjectEntity>) response.getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).getName()).isEqualTo("Project A");
    }

    @Test
    void create_withId_shouldUseProvidedId() {
        when(projectRepository.save(any(ProjectEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ProjectController.ProjectRequest("custom-id", "My Project", "desc");
        ResponseEntity<?> response = controller.create(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectEntity body = (ProjectEntity) response.getBody();
        assertThat(body.getId()).isEqualTo("custom-id");
        assertThat(body.getName()).isEqualTo("My Project");
    }

    @Test
    void create_withoutId_shouldGenerateUUID() {
        when(projectRepository.save(any(ProjectEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ProjectController.ProjectRequest(null, "Auto ID", null);
        ResponseEntity<?> response = controller.create(request);

        ProjectEntity body = (ProjectEntity) response.getBody();
        assertThat(body.getId()).isNotNull().isNotEmpty();
        // UUID 형식 확인
        assertThat(body.getId()).matches("[0-9a-f\\-]{36}");
    }

    @Test
    void get_found_shouldReturnDetailWithMembersAndResources() {
        ProjectEntity project = buildProject("p1", "Test");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(memberRepository.findByProjectId("p1")).thenReturn(List.of());
        when(resourceRepository.findByProjectId("p1")).thenReturn(List.of());

        ResponseEntity<?> response = controller.get("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("id", "p1");
        assertThat(body).containsEntry("name", "Test");
        assertThat(body).containsEntry("resourceCount", 0);
        assertThat(body).containsKey("members");
        assertThat(body).containsKey("resources");
    }

    @Test
    void get_notFound_shouldReturn404() {
        when(projectRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.get("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_found_shouldPartialUpdate() {
        ProjectEntity existing = buildProject("p1", "Old Name");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(existing));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new ProjectController.ProjectRequest(null, "New Name", null);
        ResponseEntity<?> response = controller.update("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProjectEntity body = (ProjectEntity) response.getBody();
        assertThat(body.getName()).isEqualTo("New Name");
    }

    @Test
    void update_notFound_shouldReturn404() {
        when(projectRepository.findById("missing")).thenReturn(Optional.empty());

        var request = new ProjectController.ProjectRequest(null, "X", null);
        ResponseEntity<?> response = controller.update("missing", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_found_shouldReturn204() {
        when(projectRepository.existsById("p1")).thenReturn(true);

        ResponseEntity<?> response = controller.delete("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectRepository).deleteById("p1");
    }

    @Test
    void delete_notFound_shouldReturn404() {
        when(projectRepository.existsById("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.delete("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(projectRepository, never()).deleteById(any());
    }

    // ── 멤버 관리 ─────────────────────────────────────────────

    @Test
    void listMembers_shouldReturnMembers() {
        when(memberRepository.findByProjectId("p1")).thenReturn(List.of());

        ResponseEntity<?> response = controller.listMembers("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void addMember_withRole_shouldUseProvided() {
        when(memberRepository.save(any(ProjectMemberEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ProjectController.MemberRequest("user-1", "editor");
        ResponseEntity<?> response = controller.addMember("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectMemberEntity body = (ProjectMemberEntity) response.getBody();
        assertThat(body.getProjectId()).isEqualTo("p1");
        assertThat(body.getUserId()).isEqualTo("user-1");
        assertThat(body.getRole()).isEqualTo("editor");
    }

    @Test
    void addMember_noRole_shouldDefaultToViewer() {
        when(memberRepository.save(any(ProjectMemberEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ProjectController.MemberRequest("user-2", null);
        ResponseEntity<?> response = controller.addMember("p1", request);

        ProjectMemberEntity body = (ProjectMemberEntity) response.getBody();
        assertThat(body.getRole()).isEqualTo("viewer");
    }

    @Test
    void updateMemberRole_found_shouldUpdate() {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectId("p1");
        member.setUserId("user-1");
        member.setRole("viewer");

        when(memberRepository.findByProjectIdAndUserId("p1", "user-1"))
                .thenReturn(Optional.of(member));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new ProjectController.MemberRequest(null, "admin");
        ResponseEntity<?> response = controller.updateMemberRole("p1", "user-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProjectMemberEntity body = (ProjectMemberEntity) response.getBody();
        assertThat(body.getRole()).isEqualTo("admin");
    }

    @Test
    void updateMemberRole_notFound_shouldReturn404() {
        when(memberRepository.findByProjectIdAndUserId("p1", "ghost"))
                .thenReturn(Optional.empty());

        var request = new ProjectController.MemberRequest(null, "admin");
        ResponseEntity<?> response = controller.updateMemberRole("p1", "ghost", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeMember_shouldReturn204() {
        ResponseEntity<?> response = controller.removeMember("p1", "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(memberRepository).deleteByProjectIdAndUserId("p1", "user-1");
    }

    // ── 리소스 할당 ───────────────────────────────────────────

    @Test
    void listResources_noFilter_shouldReturnAll() {
        when(resourceRepository.findByProjectId("p1")).thenReturn(List.of());

        ResponseEntity<?> response = controller.listResources("p1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(resourceRepository).findByProjectId("p1");
    }

    @Test
    void listResources_withTypeFilter_shouldFilter() {
        when(resourceRepository.findByProjectIdAndResourceType("p1", "workflow"))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.listResources("p1", "workflow");

        verify(resourceRepository).findByProjectIdAndResourceType("p1", "workflow");
    }

    @Test
    void assignResource_shouldCreateAndReturn201() {
        when(resourceRepository.save(any(ProjectResourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ProjectController.ResourceRequest("knowledge_source", "ks-1");
        ResponseEntity<?> response = controller.assignResource("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectResourceEntity body = (ProjectResourceEntity) response.getBody();
        assertThat(body.getProjectId()).isEqualTo("p1");
        assertThat(body.getResourceType()).isEqualTo("knowledge_source");
        assertThat(body.getResourceId()).isEqualTo("ks-1");
    }

    @Test
    void removeResource_shouldReturn204() {
        ResponseEntity<?> response = controller.removeResource("p1", "workflow", "wf-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(resourceRepository).deleteByProjectIdAndResourceTypeAndResourceId("p1", "workflow", "wf-1");
    }

    // ── Helper ──────────────────────────────────────────────

    private ProjectEntity buildProject(String id, String name) {
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription("Test project");
        entity.setIsActive(true);
        return entity;
    }
}
