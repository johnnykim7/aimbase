package com.platform.api;

import com.platform.domain.ProjectEntity;
import com.platform.domain.ProjectMemberEntity;
import com.platform.domain.ProjectResourceEntity;
import com.platform.repository.ProjectMemberRepository;
import com.platform.repository.ProjectRepository;
import com.platform.repository.ProjectResourceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.platform.auth.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.*;

/**
 * CR-021: 프로젝트 CRUD, 멤버 관리, 리소스 할당 API.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectResourceRepository resourceRepository;

    public ProjectController(ProjectRepository projectRepository,
                             ProjectMemberRepository memberRepository,
                             ProjectResourceRepository resourceRepository) {
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.resourceRepository = resourceRepository;
    }

    // ── 프로젝트 CRUD ─────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false, name = "my") Boolean my) {
        // CR-022: 사용자별 리소스 필터링
        if (Boolean.TRUE.equals(my)) {
            String userId = currentUserId();
            if (userId != null) return ResponseEntity.ok(projectRepository.findByCreatedByAndIsActiveTrueOrderByName(userId));
        }
        return ResponseEntity.ok(projectRepository.findByIsActiveTrueOrderByName());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody ProjectRequest request) {
        var entity = new ProjectEntity();
        entity.setId(request.id() != null ? request.id() : UUID.randomUUID().toString());
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setCreatedBy(currentUserId()); // CR-022
        projectRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(entity);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return projectRepository.findById(id)
                .map(project -> {
                    var members = memberRepository.findByProjectId(id);
                    var resources = resourceRepository.findByProjectId(id);
                    var detail = new LinkedHashMap<String, Object>();
                    detail.put("id", project.getId());
                    detail.put("name", project.getName());
                    detail.put("description", project.getDescription());
                    detail.put("isActive", project.getIsActive());
                    detail.put("members", members);
                    detail.put("resourceCount", resources.size());
                    detail.put("resources", resources);
                    detail.put("createdAt", project.getCreatedAt());
                    return ResponseEntity.ok(detail);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ProjectRequest request) {
        return projectRepository.findById(id)
                .map(entity -> {
                    if (request.name() != null) entity.setName(request.name());
                    if (request.description() != null) entity.setDescription(request.description());
                    entity.setUpdatedAt(Instant.now());
                    projectRepository.save(entity);
                    return ResponseEntity.ok(entity);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (!projectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        projectRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── 멤버 관리 ─────────────────────────────────────────────

    @GetMapping("/{id}/members")
    public ResponseEntity<?> listMembers(@PathVariable String id) {
        return ResponseEntity.ok(memberRepository.findByProjectId(id));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable String id, @Valid @RequestBody MemberRequest request) {
        var entity = new ProjectMemberEntity();
        entity.setProjectId(id);
        entity.setUserId(request.userId());
        entity.setRole(request.role() != null ? request.role() : "viewer");
        memberRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(entity);
    }

    @PutMapping("/{id}/members/{userId}")
    public ResponseEntity<?> updateMemberRole(@PathVariable String id, @PathVariable String userId,
                                              @RequestBody MemberRequest request) {
        return memberRepository.findByProjectIdAndUserId(id, userId)
                .map(entity -> {
                    entity.setRole(request.role());
                    memberRepository.save(entity);
                    return ResponseEntity.ok(entity);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable String id, @PathVariable String userId) {
        memberRepository.deleteByProjectIdAndUserId(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ── 리소스 할당 ───────────────────────────────────────────

    @GetMapping("/{id}/resources")
    public ResponseEntity<?> listResources(@PathVariable String id,
                                           @RequestParam(required = false) String resource_type) {
        if (resource_type != null) {
            return ResponseEntity.ok(resourceRepository.findByProjectIdAndResourceType(id, resource_type));
        }
        return ResponseEntity.ok(resourceRepository.findByProjectId(id));
    }

    @PostMapping("/{id}/resources")
    public ResponseEntity<?> assignResource(@PathVariable String id, @Valid @RequestBody ResourceRequest request) {
        var entity = new ProjectResourceEntity();
        entity.setProjectId(id);
        entity.setResourceType(request.resourceType());
        entity.setResourceId(request.resourceId());
        resourceRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(entity);
    }

    @DeleteMapping("/{id}/resources/{resourceType}/{resourceId}")
    @Transactional
    public ResponseEntity<?> removeResource(@PathVariable String id,
                                            @PathVariable String resourceType,
                                            @PathVariable String resourceId) {
        resourceRepository.deleteByProjectIdAndResourceTypeAndResourceId(id, resourceType, resourceId);
        return ResponseEntity.noContent().build();
    }

    // ── Request Records ───────────────────────────────────────

    record ProjectRequest(String id, @NotBlank String name, String description) {}
    record MemberRequest(String userId, String role) {}
    record ResourceRequest(@NotBlank String resourceType, @NotBlank String resourceId) {}

    /** CR-022: SecurityContext에서 현재 사용자 ID 추출. API Key 인증(system-*)은 users FK 없으므로 null 반환 */
    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal up) {
            String id = up.getId();
            if (id != null && id.startsWith("system-")) return null;
            return id;
        }
        return null;
    }
}
