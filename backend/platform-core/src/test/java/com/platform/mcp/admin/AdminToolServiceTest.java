package com.platform.mcp.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.*;
import com.platform.workflow.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AdminToolService 단위 테스트 — 14개 MCP 관리 도구 (CR-020).
 */
@ExtendWith(MockitoExtension.class)
class AdminToolServiceTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private KnowledgeSourceRepository knowledgeSourceRepository;
    @Mock private PromptRepository promptRepository;
    @Mock private SchemaRepository schemaRepository;
    @Mock private PolicyRepository policyRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowEngine workflowEngine;

    private AdminToolService service;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        service = new AdminToolService(
                objectMapper,
                connectionRepository,
                knowledgeSourceRepository,
                promptRepository,
                schemaRepository,
                policyRepository,
                workflowRepository,
                workflowRunRepository,
                workflowEngine
        );
    }

    // ── 조회 도구 (6개) ──────────────────────────────────────

    @Test
    void listConnections_shouldReturnJsonArray() {
        when(connectionRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String result = service.listConnections(Map.of());

        assertThat(result).isEqualTo("[]");
        verify(connectionRepository).findAll(any(PageRequest.class));
    }

    @Test
    void listKnowledgeSources_noFilter_shouldReturnAll() {
        when(knowledgeSourceRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String result = service.listKnowledgeSources(Map.of());

        assertThat(result).isEqualTo("[]");
        verify(knowledgeSourceRepository).findAll(any(PageRequest.class));
    }

    @Test
    void listKnowledgeSources_withTypeFilter_shouldCallFindByType() {
        when(knowledgeSourceRepository.findByType("file")).thenReturn(List.of());

        String result = service.listKnowledgeSources(Map.of("type", "file"));

        assertThat(result).isEqualTo("[]");
        verify(knowledgeSourceRepository).findByType("file");
    }

    @Test
    void listPrompts_noDomain_shouldReturnAll() {
        when(promptRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String result = service.listPrompts(Map.of());

        assertThat(result).isEqualTo("[]");
    }

    @Test
    void listPrompts_withDomain_shouldFilter() {
        when(promptRepository.findByDomainAndIsActiveTrue("sales")).thenReturn(List.of());

        String result = service.listPrompts(Map.of("domain", "sales"));

        assertThat(result).isEqualTo("[]");
        verify(promptRepository).findByDomainAndIsActiveTrue("sales");
    }

    @Test
    void listSchemas_shouldReturnAll() {
        when(schemaRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String result = service.listSchemas(Map.of());

        assertThat(result).isEqualTo("[]");
    }

    @Test
    void listPolicies_shouldReturnAll() {
        when(policyRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String result = service.listPolicies(Map.of());

        assertThat(result).isEqualTo("[]");
    }

    @Test
    void listWorkflows_noFilter_shouldReturnActiveWorkflows() {
        when(workflowRepository.findByIsActiveTrueOrderByName()).thenReturn(List.of());

        String result = service.listWorkflows(Map.of());

        assertThat(result).isEqualTo("[]");
        verify(workflowRepository).findByIsActiveTrueOrderByName();
    }

    @Test
    void listWorkflows_withDomain_shouldFilterByDomain() {
        when(workflowRepository.findByDomainAndIsActiveTrue("hr")).thenReturn(List.of());

        String result = service.listWorkflows(Map.of("domain", "hr"));

        assertThat(result).isEqualTo("[]");
        verify(workflowRepository).findByDomainAndIsActiveTrue("hr");
    }

    @Test
    void listWorkflows_withProjectId_shouldFilterByProjectIdOrShared() {
        when(workflowRepository.findByProjectIdOrSharedAndIsActiveTrue("proj-1")).thenReturn(List.of());

        String result = service.listWorkflows(Map.of("project_id", "proj-1"));

        assertThat(result).isEqualTo("[]");
        verify(workflowRepository).findByProjectIdOrSharedAndIsActiveTrue("proj-1");
    }

    @Test
    void listWorkflows_projectIdTakesPrecedenceOverDomain() {
        when(workflowRepository.findByProjectIdOrSharedAndIsActiveTrue("proj-1")).thenReturn(List.of());

        // project_id와 domain 둘 다 있으면 project_id 우선
        String result = service.listWorkflows(Map.of("project_id", "proj-1", "domain", "hr"));

        verify(workflowRepository).findByProjectIdOrSharedAndIsActiveTrue("proj-1");
        verify(workflowRepository, never()).findByDomainAndIsActiveTrue(any());
    }

    // ── 워크플로우 CRUD 도구 (8개) ─────────────────────────

    @Test
    void getWorkflow_found_shouldReturnJson() {
        WorkflowEntity entity = buildWorkflow("wf-1", "Test WF");
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(entity));

        String result = service.getWorkflow(Map.of("workflow_id", "wf-1"));

        assertThat(result).contains("\"id\":\"wf-1\"");
        assertThat(result).contains("\"name\":\"Test WF\"");
    }

    @Test
    void getWorkflow_notFound_shouldReturnError() {
        when(workflowRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.getWorkflow(Map.of("workflow_id", "missing"));

        assertThat(result).contains("error");
        assertThat(result).contains("Workflow not found");
    }

    @Test
    void getWorkflow_missingRequiredParam_shouldThrow() {
        assertThatThrownBy(() -> service.getWorkflow(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow_id");
    }

    @Test
    void createWorkflow_shouldSaveAndReturnJson() {
        when(workflowRepository.save(any(WorkflowEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> input = new HashMap<>();
        input.put("name", "New Workflow");
        input.put("steps", List.of(Map.of("id", "step1", "type", "LLM_CALL")));

        String result = service.createWorkflow(input);

        assertThat(result).contains("\"name\":\"New Workflow\"");
        assertThat(result).contains("step1");
        verify(workflowRepository).save(any(WorkflowEntity.class));
    }

    @Test
    void createWorkflow_withOptionalFields_shouldSetAll() {
        when(workflowRepository.save(any(WorkflowEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> input = new HashMap<>();
        input.put("name", "Full WF");
        input.put("domain", "sales");
        input.put("project_id", "proj-x");
        input.put("trigger_config", Map.of("type", "webhook"));
        input.put("steps", List.of(Map.of("id", "s1", "type", "TOOL_USE")));
        input.put("error_handling", Map.of("strategy", "retry", "max_retries", 3));
        input.put("output_schema", Map.of("type", "object"));

        String result = service.createWorkflow(input);

        assertThat(result).contains("\"domain\":\"sales\"");
        assertThat(result).contains("webhook");
    }

    @Test
    void createWorkflow_missingName_shouldThrow() {
        Map<String, Object> input = new HashMap<>();
        input.put("steps", List.of());

        assertThatThrownBy(() -> service.createWorkflow(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void updateWorkflow_found_shouldPartialUpdate() {
        WorkflowEntity entity = buildWorkflow("wf-1", "Original");
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(entity));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> input = new HashMap<>();
        input.put("workflow_id", "wf-1");
        input.put("name", "Updated Name");

        String result = service.updateWorkflow(input);

        assertThat(result).contains("Updated Name");
        verify(workflowRepository).save(any());
    }

    @Test
    void updateWorkflow_notFound_shouldReturnError() {
        when(workflowRepository.findById("missing")).thenReturn(Optional.empty());

        String result = service.updateWorkflow(Map.of("workflow_id", "missing"));

        assertThat(result).contains("error");
    }

    @Test
    void deleteWorkflow_found_shouldDeleteAndReturnSuccess() {
        when(workflowRepository.existsById("wf-1")).thenReturn(true);

        String result = service.deleteWorkflow(Map.of("workflow_id", "wf-1"));

        assertThat(result).contains("\"success\":true");
        verify(workflowRepository).deleteById("wf-1");
    }

    @Test
    void deleteWorkflow_notFound_shouldReturnError() {
        when(workflowRepository.existsById("missing")).thenReturn(false);

        String result = service.deleteWorkflow(Map.of("workflow_id", "missing"));

        assertThat(result).contains("error");
        verify(workflowRepository, never()).deleteById(any());
    }

    @Test
    void runWorkflow_shouldExecuteAndReturnRun() {
        when(workflowRepository.existsById("wf-1")).thenReturn(true);
        WorkflowRunEntity runEntity = new WorkflowRunEntity();
        when(workflowEngine.execute(eq("wf-1"), any(), any())).thenReturn(runEntity);

        String result = service.runWorkflow(Map.of("workflow_id", "wf-1"));

        assertThat(result).isNotNull();
        verify(workflowEngine).execute(eq("wf-1"), any(), any());
    }

    @Test
    void runWorkflow_notFound_shouldReturnError() {
        when(workflowRepository.existsById("missing")).thenReturn(false);

        String result = service.runWorkflow(Map.of("workflow_id", "missing"));

        assertThat(result).contains("error");
        verify(workflowEngine, never()).execute(any(), any(), any());
    }

    @Test
    void getWorkflowRun_found_shouldReturn() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEntity runEntity = new WorkflowRunEntity();
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(runEntity));

        String result = service.getWorkflowRun(Map.of("run_id", runId.toString()));

        assertThat(result).isNotNull();
        assertThat(result).doesNotContain("\"error\":\"");
    }

    @Test
    void getWorkflowRun_notFound_shouldReturnError() {
        UUID runId = UUID.randomUUID();
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.empty());

        String result = service.getWorkflowRun(Map.of("run_id", runId.toString()));

        assertThat(result).contains("error");
    }

    @Test
    void listWorkflowRuns_shouldReturnPagedResults() {
        when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(eq("wf-1"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String result = service.listWorkflowRuns(Map.of("workflow_id", "wf-1"));

        assertThat(result).isEqualTo("[]");
    }

    @Test
    void listWorkflowRuns_withCustomSize_shouldUseIt() {
        when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(eq("wf-1"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listWorkflowRuns(Map.of("workflow_id", "wf-1", "size", 20));

        verify(workflowRunRepository).findByWorkflowIdOrderByStartedAtDesc(
                eq("wf-1"), eq(PageRequest.of(0, 20)));
    }

    @Test
    void approveWorkflowRun_approved_shouldResumeEngine() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEntity runEntity = new WorkflowRunEntity();
        when(workflowEngine.resume(eq(runId), eq(true), eq("looks good")))
                .thenReturn(runEntity);

        String result = service.approveWorkflowRun(Map.of(
                "run_id", runId.toString(),
                "approved", true,
                "reason", "looks good"
        ));

        assertThat(result).isNotNull();
        verify(workflowEngine).resume(runId, true, "looks good");
    }

    @Test
    void approveWorkflowRun_rejected_shouldPassFalse() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEntity runEntity = new WorkflowRunEntity();
        when(workflowEngine.resume(eq(runId), eq(false), eq("rejected")))
                .thenReturn(runEntity);

        service.approveWorkflowRun(Map.of(
                "run_id", runId.toString(),
                "approved", false,
                "reason", "rejected"
        ));

        verify(workflowEngine).resume(runId, false, "rejected");
    }

    @Test
    void approveWorkflowRun_engineThrows_shouldReturnError() {
        UUID runId = UUID.randomUUID();
        when(workflowEngine.resume(any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("Run is not in WAITING_APPROVAL state"));

        String result = service.approveWorkflowRun(Map.of(
                "run_id", runId.toString(),
                "approved", true
        ));

        assertThat(result).contains("error");
        assertThat(result).contains("WAITING_APPROVAL");
    }

    // ── Helper ──────────────────────────────────────────────

    private WorkflowEntity buildWorkflow(String id, String name) {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setTriggerConfig(Map.of("type", "manual"));
        entity.setSteps(List.of(Map.of("id", "s1", "type", "LLM_CALL")));
        entity.setActive(true);
        return entity;
    }
}
