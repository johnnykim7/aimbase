package com.platform.api;

import com.platform.domain.PromptEntity;
import com.platform.domain.PromptEntityId;
import com.platform.repository.ProjectResourceRepository;
import com.platform.repository.PromptRepository;
import com.platform.tenant.ProjectContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.platform.auth.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/prompts")
@Tag(name = "Prompts", description = "프롬프트 템플릿 관리")
public class PromptController {

    private final PromptRepository promptRepository;
    private final ProjectResourceRepository projectResourceRepository;

    public PromptController(PromptRepository promptRepository,
                            ProjectResourceRepository projectResourceRepository) {
        this.promptRepository = promptRepository;
        this.projectResourceRepository = projectResourceRepository;
    }

    @GetMapping
    @Operation(summary = "프롬프트 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, name = "my") Boolean my
    ) {
        // CR-022: 사용자별 리소스 필터링
        if (Boolean.TRUE.equals(my)) {
            String userId = currentUserId();
            if (userId != null) return ApiResponse.ok(promptRepository.findByCreatedBy(userId));
        }
        // CR-021: 프로젝트 스코핑
        String projectId = ProjectContext.getProjectId();
        if (projectId != null) {
            List<String> ids = projectResourceRepository.findResourceIdsByProjectIdAndResourceType(projectId, "prompt");
            if (ids.isEmpty()) return ApiResponse.ok(List.of());
            return ApiResponse.ok(promptRepository.findByIdIn(ids));
        }
        return ApiResponse.page(promptRepository.findAll(PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "프롬프트 생성")
    public ApiResponse<PromptEntity> create(@Valid @RequestBody PromptRequest request) {
        String resolvedId = request.id() != null && !request.id().isBlank() ? request.id() : java.util.UUID.randomUUID().toString();
        PromptEntityId pk = new PromptEntityId(resolvedId, request.version());
        PromptEntity entity = new PromptEntity();
        entity.setPk(pk);
        entity.setDomain(request.domain());
        entity.setType(request.type());
        entity.setTemplate(request.template());
        entity.setVariables(request.variables());
        entity.setActive(request.isActive() != null && request.isActive());
        entity.setCreatedBy(currentUserId()); // CR-022
        return ApiResponse.ok(promptRepository.save(entity));
    }

    @GetMapping("/{id}/{version}")
    @Operation(summary = "프롬프트 상세 조회")
    public ApiResponse<PromptEntity> get(@PathVariable String id, @PathVariable int version) {
        return promptRepository.findById(new PromptEntityId(id, version))
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Prompt not found: " + id + " v" + version));
    }

    @PutMapping("/{id}/{version}")
    @Operation(summary = "프롬프트 수정")
    public ApiResponse<PromptEntity> update(@PathVariable String id, @PathVariable int version,
                                             @Valid @RequestBody PromptRequest request) {
        PromptEntity entity = promptRepository.findById(new PromptEntityId(id, version))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Prompt not found: " + id + " v" + version));
        entity.setTemplate(request.template());
        entity.setVariables(request.variables());
        if (request.isActive() != null) entity.setActive(request.isActive());
        return ApiResponse.ok(promptRepository.save(entity));
    }

    @DeleteMapping("/{id}/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "프롬프트 삭제")
    public void delete(@PathVariable String id, @PathVariable int version) {
        promptRepository.deleteById(new PromptEntityId(id, version));
    }

    @PostMapping("/{id}/{version}/test")
    @Operation(summary = "프롬프트 테스트 렌더링")
    public ApiResponse<Map<String, Object>> test(@PathVariable String id, @PathVariable int version,
                                                  @RequestBody Map<String, Object> variables) {
        PromptEntity entity = promptRepository.findById(new PromptEntityId(id, version))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Prompt not found: " + id + " v" + version));

        String rendered = renderTemplate(entity.getTemplate(), variables);
        return ApiResponse.ok(Map.of("rendered", rendered, "tokenEstimate", rendered.length() / 4));
    }

    private String renderTemplate(String template, Map<String, Object> variables) {
        String result = template;
        Pattern p = Pattern.compile("\\{\\{(\\w+)}}");
        Matcher m = p.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = variables.getOrDefault(key, "{{" + key + "}}");
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public record PromptRequest(
            String id,
            int version,
            String domain,
            @NotBlank String type,
            @NotBlank String template,
            List<Map<String, Object>> variables,
            Boolean isActive
    ) {}

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
