package com.platform.tool.builtin;

import com.platform.domain.SkillEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SkillRepository;
import com.platform.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-237: 스킬 실행.
 * 등록된 스킬(프롬프트+도구 조합)을 경량 실행한다.
 * BIZ-060: 단일 LLM 호출 (워크플로우와 차별점 — 다단계 X).
 * BIZ-061: skill.tools에 명시된 도구만 사용 가능.
 */
@Component
public class SkillInvokeTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillInvokeTool.class);

    private final SkillRepository skillRepository;
    private final OrchestratorEngine orchestratorEngine;

    public SkillInvokeTool(SkillRepository skillRepository,
                           OrchestratorEngine orchestratorEngine) {
        this.skillRepository = skillRepository;
        this.orchestratorEngine = orchestratorEngine;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "skill_invoke",
                "등록된 스킬을 실행합니다. 스킬은 재사용 가능한 프롬프트와 도구 조합으로, " +
                        "단일 LLM 호출로 특정 작업을 수행합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "skill_id", Map.of("type", "string",
                                        "description", "실행할 스킬 ID"),
                                "input", Map.of("type", "string",
                                        "description", "스킬에 전달할 사용자 입력"),
                                "model", Map.of("type", "string",
                                        "description", "사용할 모델 (선택, 기본: 스킬 기본 모델)")
                        ),
                        "required", List.of("skill_id", "input")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "skill_invoke", "1.0", ToolScope.BUILTIN,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("skill", "automation"),
                List.of("execute", "skill")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String skillId = (String) input.get("skill_id");
        String userInput = (String) input.get("input");
        String model = (String) input.get("model");

        if (skillId == null || userInput == null) {
            return ToolResult.error("skill_id와 input은 필수입니다");
        }

        // 스킬 조회
        SkillEntity skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {
            return ToolResult.error("존재하지 않는 스킬: " + skillId);
        }
        if (!skill.isActive()) {
            return ToolResult.error("비활성화된 스킬: " + skillId);
        }

        try {
            // BIZ-061: skill.tools에 명시된 도구만 허용하는 ToolFilterContext 생성
            ToolFilterContext toolFilter = (skill.getTools() != null && !skill.getTools().isEmpty())
                    ? ToolFilterContext.allowOnly(skill.getTools())
                    : ToolFilterContext.none();

            // BIZ-060: 단일 LLM 호출 — system_prompt + 사용자 입력
            var messages = new java.util.ArrayList<UnifiedMessage>();
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, skill.getSystemPrompt()));
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, userInput));

            ChatRequest chatRequest = new ChatRequest(
                    model, null, messages, false, false, null,
                    null, null, toolFilter, "auto", null, null);

            ChatResponse response = orchestratorEngine.chat(chatRequest);

            log.info("Skill '{}' executed successfully", skill.getName());

            // content에서 텍스트 추출
            String responseText = response.content() != null
                    ? response.content().stream()
                        .filter(ContentBlock.Text.class::isInstance)
                        .map(b -> ((ContentBlock.Text) b).text())
                        .collect(java.util.stream.Collectors.joining("\n"))
                    : "";

            return ToolResult.ok(
                    Map.of(
                            "skill", skill.getName(),
                            "response", responseText,
                            "model", response.model() != null ? response.model() : ""
                    ),
                    "스킬 '" + skill.getName() + "' 실행 완료"
            );
        } catch (Exception e) {
            log.error("Skill {} execution failed: {}", skillId, e.getMessage());
            return ToolResult.error("스킬 실행 실패: " + e.getMessage());
        }
    }
}
