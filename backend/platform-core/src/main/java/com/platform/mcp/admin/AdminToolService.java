package com.platform.mcp.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.WorkflowEntity;
import com.platform.repository.*;
import com.platform.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CR-020: MCP 관리 도구 — 실제 비즈니스 로직.
 * 도메인팀이 MCP 클라이언트로 호출하는 14개 도구의 구현체.
 */
@Service
public class AdminToolService {

    private static final Logger log = LoggerFactory.getLogger(AdminToolService.class);

    private final ObjectMapper objectMapper;
    private final ConnectionRepository connectionRepository;
    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final PromptRepository promptRepository;
    private final SchemaRepository schemaRepository;
    private final PolicyRepository policyRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowEngine workflowEngine;

    public AdminToolService(ObjectMapper objectMapper,
                            ConnectionRepository connectionRepository,
                            KnowledgeSourceRepository knowledgeSourceRepository,
                            PromptRepository promptRepository,
                            SchemaRepository schemaRepository,
                            PolicyRepository policyRepository,
                            WorkflowRepository workflowRepository,
                            WorkflowRunRepository workflowRunRepository,
                            WorkflowEngine workflowEngine) {
        this.objectMapper = objectMapper;
        this.connectionRepository = connectionRepository;
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.promptRepository = promptRepository;
        this.schemaRepository = schemaRepository;
        this.policyRepository = policyRepository;
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowEngine = workflowEngine;
    }

    // ── 조회 도구 (6개) ──────────────────────────────────────
    // list 응답은 요약만 반환 — JSONB 대용량 필드 제외 (SSE 버퍼 초과 방지)

    public String listConnections(Map<String, Object> input) {
        var list = connectionRepository.findAll(PageRequest.of(0, 50));
        var summaries = list.getContent().stream()
                .map(c -> Map.of(
                        "id", c.getId(),
                        "name", c.getName(),
                        "adapter", nullSafe(c.getAdapter()),
                        "type", nullSafe(c.getType()),
                        "status", nullSafe(c.getStatus())))
                .toList();
        return toJson(summaries);
    }

    public String listKnowledgeSources(Map<String, Object> input) {
        String type = (String) input.get("type");
        var sources = type != null
                ? knowledgeSourceRepository.findByType(type)
                : knowledgeSourceRepository.findAll(PageRequest.of(0, 50)).getContent();
        var summaries = sources.stream()
                .map(k -> Map.of(
                        "id", k.getId(),
                        "name", k.getName(),
                        "type", nullSafe(k.getType()),
                        "status", nullSafe(k.getStatus()),
                        "document_count", k.getDocumentCount(),
                        "chunk_count", k.getChunkCount()))
                .toList();
        return toJson(summaries);
    }

    public String listPrompts(Map<String, Object> input) {
        String domain = (String) input.get("domain");
        var prompts = domain != null
                ? promptRepository.findByDomainAndIsActiveTrue(domain)
                : promptRepository.findAll(PageRequest.of(0, 50)).getContent();
        var summaries = prompts.stream()
                .map(p -> Map.of(
                        "id", p.getPk().getId(),
                        "version", p.getPk().getVersion(),
                        "domain", nullSafe(p.getDomain()),
                        "type", nullSafe(p.getType()),
                        "is_active", p.isActive()))
                .toList();
        return toJson(summaries);
    }

    public String listSchemas(Map<String, Object> input) {
        var summaries = schemaRepository.findAll(PageRequest.of(0, 50)).getContent().stream()
                .map(s -> Map.of(
                        "id", s.getPk().getId(),
                        "version", s.getPk().getVersion(),
                        "domain", nullSafe(s.getDomain()),
                        "description", nullSafe(s.getDescription())))
                .toList();
        return toJson(summaries);
    }

    public String listPolicies(Map<String, Object> input) {
        var summaries = policyRepository.findAll(PageRequest.of(0, 50)).getContent().stream()
                .map(p -> Map.of(
                        "id", p.getId(),
                        "name", p.getName(),
                        "domain", nullSafe(p.getDomain()),
                        "priority", p.getPriority(),
                        "is_active", p.isActive()))
                .toList();
        return toJson(summaries);
    }

    public String listWorkflows(Map<String, Object> input) {
        String domain = (String) input.get("domain");
        String projectId = (String) input.get("project_id");
        List<WorkflowEntity> workflows;
        if (projectId != null) {
            workflows = workflowRepository.findByProjectIdOrSharedAndIsActiveTrue(projectId);
        } else if (domain != null) {
            workflows = workflowRepository.findByDomainAndIsActiveTrue(domain);
        } else {
            workflows = workflowRepository.findByIsActiveTrueOrderByName();
        }
        var summaries = workflows.stream()
                .map(w -> Map.<String, Object>of(
                        "id", w.getId(),
                        "name", w.getName(),
                        "domain", nullSafe(w.getDomain()),
                        "project_id", nullSafe(w.getProjectId()),
                        "step_count", w.getSteps() != null ? w.getSteps().size() : 0,
                        "is_active", w.isActive(),
                        "created_at", String.valueOf(w.getCreatedAt())))
                .toList();
        return toJson(summaries);
    }

    // ── 워크플로우 CRUD 도구 (8개) ─────────────────────────

    public String getWorkflow(Map<String, Object> input) {
        String id = requireString(input, "workflow_id");
        return workflowRepository.findById(id)
                .map(this::toJson)
                .orElse("{\"error\":\"Workflow not found: " + id + "\"}");
    }

    @SuppressWarnings("unchecked")
    public String createWorkflow(Map<String, Object> input) {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName(requireString(input, "name"));
        entity.setDomain((String) input.get("domain"));
        entity.setProjectId((String) input.get("project_id"));
        entity.setTriggerConfig((Map<String, Object>) input.getOrDefault("trigger_config", Map.of("type", "manual")));
        entity.setSteps((List<Map<String, Object>>) input.get("steps"));
        entity.setErrorHandling((Map<String, Object>) input.get("error_handling"));
        entity.setOutputSchema((Map<String, Object>) input.get("output_schema"));
        entity.setInputSchema((Map<String, Object>) input.get("input_schema"));
        entity.setActive(true);
        return toJson(workflowRepository.save(entity));
    }

    @SuppressWarnings("unchecked")
    public String updateWorkflow(Map<String, Object> input) {
        String id = requireString(input, "workflow_id");
        return workflowRepository.findById(id)
                .map(entity -> {
                    if (input.containsKey("name")) entity.setName((String) input.get("name"));
                    if (input.containsKey("trigger_config")) entity.setTriggerConfig((Map<String, Object>) input.get("trigger_config"));
                    if (input.containsKey("steps")) entity.setSteps((List<Map<String, Object>>) input.get("steps"));
                    if (input.containsKey("error_handling")) entity.setErrorHandling((Map<String, Object>) input.get("error_handling"));
                    if (input.containsKey("output_schema")) entity.setOutputSchema((Map<String, Object>) input.get("output_schema"));
                    if (input.containsKey("input_schema")) entity.setInputSchema((Map<String, Object>) input.get("input_schema"));
                    return toJson(workflowRepository.save(entity));
                })
                .orElse("{\"error\":\"Workflow not found: " + id + "\"}");
    }

    public String deleteWorkflow(Map<String, Object> input) {
        String id = requireString(input, "workflow_id");
        if (!workflowRepository.existsById(id)) {
            return "{\"error\":\"Workflow not found: " + id + "\"}";
        }
        workflowRepository.deleteById(id);
        return "{\"success\":true,\"message\":\"Workflow deleted: " + id + "\"}";
    }

    @SuppressWarnings("unchecked")
    public String runWorkflow(Map<String, Object> input) {
        String id = requireString(input, "workflow_id");
        if (!workflowRepository.existsById(id)) {
            return "{\"error\":\"Workflow not found: " + id + "\"}";
        }
        Map<String, Object> runInput = (Map<String, Object>) input.get("input");
        var run = workflowEngine.execute(id, runInput, null);
        return toJson(run);
    }

    public String getWorkflowRun(Map<String, Object> input) {
        String runId = requireString(input, "run_id");
        return workflowRunRepository.findById(UUID.fromString(runId))
                .map(this::toJson)
                .orElse("{\"error\":\"Run not found: " + runId + "\"}");
    }

    public String listWorkflowRuns(Map<String, Object> input) {
        String workflowId = requireString(input, "workflow_id");
        int size = input.containsKey("size") ? ((Number) input.get("size")).intValue() : 10;
        return toJson(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(
                workflowId, PageRequest.of(0, size)).getContent());
    }

    public String approveWorkflowRun(Map<String, Object> input) {
        String runId = requireString(input, "run_id");
        boolean approved = Boolean.TRUE.equals(input.get("approved"));
        String reason = (String) input.get("reason");
        try {
            var run = workflowEngine.resume(UUID.fromString(runId), approved, reason);
            return toJson(run);
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        } catch (IllegalStateException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Helper ──────────────────────────────────────────────

    private String requireString(Map<String, Object> input, String key) {
        Object val = input.get(key);
        if (val == null) throw new IllegalArgumentException("Missing required parameter: " + key);
        return val.toString();
    }

    private String nullSafe(Object val) {
        return val != null ? val.toString() : "";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON serialization failed", e);
            return "{\"error\":\"Serialization failed\"}";
        }
    }
}
