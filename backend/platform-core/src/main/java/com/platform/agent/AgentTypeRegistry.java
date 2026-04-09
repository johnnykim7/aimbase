package com.platform.agent;

import com.platform.service.PromptTemplateService;
import com.platform.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CR-034 PRD-229: Built-in 에이전트 타입 레지스트리.
 *
 * 에이전트 타입별 시스템 프롬프트, 도구 필터를 관리하고,
 * SubagentRunner에서 타입별 실행 설정을 제공한다.
 */
@Component
public class AgentTypeRegistry {

    private final ToolRegistry toolRegistry;
    private final PromptTemplateService promptTemplateService;

    public AgentTypeRegistry(ToolRegistry toolRegistry, PromptTemplateService promptTemplateService) {
        this.toolRegistry = toolRegistry;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 에이전트 타입별 실행 설정을 반환.
     *
     * @param agentType 에이전트 타입
     * @return 시스템 프롬프트 + 도구 필터가 적용된 설정
     */
    public AgentTypeConfig getConfig(AgentType agentType) {
        // CR-036: DB 외부화된 프롬프트 우선, 없으면 enum 하드코딩 폴백
        String promptKey = "agent." + agentType.name().toLowerCase() + ".system";
        String systemPrompt = promptTemplateService.getTemplateOrFallback(promptKey, agentType.getSystemPrompt());
        String[] allowedTools = agentType.getAllowedTools();
        boolean readOnly = agentType.isReadOnly();

        // 허용 도구 목록 필터링 (실제 등록된 도구와 교차)
        Set<String> filteredTools = null;
        if (allowedTools != null) {
            Set<String> registeredTools = toolRegistry.getRegisteredToolNames();
            filteredTools = new LinkedHashSet<>();
            for (String tool : allowedTools) {
                String trimmed = tool.trim();
                if (registeredTools.contains(trimmed)) {
                    filteredTools.add(trimmed);
                }
            }
            // send_message, task_output은 모든 에이전트에서 허용
            filteredTools.add("send_message");
            filteredTools.add("task_output");
        }

        return new AgentTypeConfig(agentType, systemPrompt, filteredTools, readOnly);
    }

    /**
     * 전체 에이전트 타입 목록 반환.
     */
    public List<AgentTypeSummary> listTypes() {
        return Arrays.stream(AgentType.values())
                .map(t -> new AgentTypeSummary(
                        t.name(), t.getDisplayName(), t.getSystemPrompt(),
                        t.isReadOnly(), t.getAllowedToolsCsv()))
                .toList();
    }

    // ── 반환 DTO ──

    public record AgentTypeConfig(
            AgentType type,
            String systemPrompt,
            Set<String> allowedTools,   // null이면 제한 없음
            boolean readOnly
    ) {}

    public record AgentTypeSummary(
            String name,
            String displayName,
            String description,
            boolean readOnly,
            String allowedTools
    ) {}
}
