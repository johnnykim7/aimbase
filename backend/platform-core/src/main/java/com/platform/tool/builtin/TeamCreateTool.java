package com.platform.tool.builtin;

import com.platform.agent.TeamService;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-039 PRD-265: Swarm 패턴 동적 팀 생성.
 *
 * 에이전트 그룹을 팀으로 묶어 역할 배정 + 병렬 실행.
 * BIZ-073: 팀당 멤버 최대 5명. BIZ-074: 세션당 활성 팀 최대 3개.
 */
@Component
public class TeamCreateTool implements EnhancedToolExecutor {

    private final TeamService teamService;

    public TeamCreateTool(TeamService teamService) {
        this.teamService = teamService;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "team_create",
                "Create a team of agents for collaborative work using the Swarm pattern. "
                        + "Each member is assigned an agent type and role. Members can communicate via send_message. "
                        + "Max 5 members per team, max 3 active teams per session.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string",
                                        "description", "Team name (e.g., 'code-review-team')"),
                                "objective", Map.of("type", "string",
                                        "description", "Team objective describing what the team should accomplish"),
                                "members", Map.of("type", "array",
                                        "description", "List of team members with agent type and role",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "agent_type", Map.of("type", "string",
                                                                "enum", List.of("GENERAL", "PLAN", "EXPLORE", "GUIDE", "VERIFICATION"),
                                                                "description", "Agent type for this member"),
                                                        "role", Map.of("type", "string",
                                                                "description", "Role description for this member (e.g., 'code explorer')")
                                                ),
                                                "required", List.of("agent_type")
                                        ))
                        ),
                        "required", List.of("name", "members")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "team_create", "1.0",
                ToolScope.NATIVE, PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("agent", "team", "swarm", "collaboration"),
                List.of("write", "agent")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        if (input.get("name") == null || ((String) input.get("name")).isBlank()) {
            return ValidationResult.fail("'name' is required");
        }
        Object membersObj = input.get("members");
        if (!(membersObj instanceof List<?> members) || members.isEmpty()) {
            return ValidationResult.fail("'members' must be a non-empty array");
        }
        return ValidationResult.OK;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String name = (String) input.get("name");
        String objective = (String) input.get("objective");
        List<Map<String, Object>> members = (List<Map<String, Object>>) input.get("members");

        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            return ToolResult.error("Session ID is required for team creation");
        }

        try {
            Map<String, Object> team = teamService.createTeam(sessionId, name, objective, members);
            String teamId = (String) team.get("team_id");
            int memberCount = members.size();
            return ToolResult.ok(team,
                    "Team '" + name + "' created (id=" + teamId + ", " + memberCount + " members). "
                            + "Members can now be dispatched with the agent tool, referencing this team.");
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
