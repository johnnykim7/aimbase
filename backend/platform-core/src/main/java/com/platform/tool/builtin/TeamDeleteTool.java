package com.platform.tool.builtin;

import com.platform.agent.TeamService;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-039 PRD-266: 팀 해체.
 *
 * 실행 중 멤버 에이전트를 graceful stop 후 팀을 DISSOLVED 상태로 전환.
 */
@Component
public class TeamDeleteTool implements EnhancedToolExecutor {

    private final TeamService teamService;

    public TeamDeleteTool(TeamService teamService) {
        this.teamService = teamService;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "team_delete",
                "Dissolve a team, stopping all running member agents gracefully. "
                        + "The team record is preserved with DISSOLVED status for history.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "team_id", Map.of("type", "string",
                                        "description", "ID of the team to dissolve"),
                                "reason", Map.of("type", "string",
                                        "description", "Reason for dissolving the team (e.g., 'task completed')")
                        ),
                        "required", List.of("team_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "team_delete", "1.0",
                ToolScope.NATIVE, PermissionLevel.RESTRICTED_WRITE,
                false, false, true, true,
                RetryPolicy.NONE,
                List.of("agent", "team", "swarm", "collaboration"),
                List.of("write", "agent")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String teamId = (String) input.get("team_id");
        if (teamId == null || teamId.isBlank()) {
            return ValidationResult.fail("'team_id' is required");
        }
        try {
            UUID.fromString(teamId);
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("'team_id' must be a valid UUID");
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String teamIdStr = (String) input.get("team_id");
        String reason = (String) input.get("reason");
        UUID teamId = UUID.fromString(teamIdStr);

        try {
            Map<String, Object> result = teamService.dissolveTeam(teamId, reason);
            int stopped = (int) result.getOrDefault("members_stopped", 0);
            return ToolResult.ok(result,
                    "Team dissolved (id=" + teamIdStr + ", " + stopped + " members stopped). "
                            + "Reason: " + (reason != null ? reason : "not specified"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
