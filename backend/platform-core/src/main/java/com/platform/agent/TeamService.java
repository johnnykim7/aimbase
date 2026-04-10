package com.platform.agent;

import com.platform.domain.TeamEntity;
import com.platform.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * CR-039 PRD-265/266: Swarm 팀 관리 서비스.
 *
 * - 팀 생성/해체/조회
 * - BIZ-073: 팀당 멤버 최대 5명
 * - BIZ-074: 세션당 활성 팀 최대 3개
 * - 세션 스코프: 세션 종료 시 활성 팀 자동 DISSOLVED 전환
 */
@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private static final int MAX_MEMBERS_PER_TEAM = 5;
    private static final int MAX_ACTIVE_TEAMS_PER_SESSION = 3;

    private final TeamRepository teamRepository;
    private final SubagentLifecycleManager lifecycleManager;

    public TeamService(TeamRepository teamRepository,
                       SubagentLifecycleManager lifecycleManager) {
        this.teamRepository = teamRepository;
        this.lifecycleManager = lifecycleManager;
    }

    /**
     * 팀 생성.
     *
     * @param sessionId 세션 ID
     * @param name      팀 이름
     * @param objective 팀 목적
     * @param members   멤버 목록 [{agent_type, role}, ...]
     * @return 생성된 팀 정보
     */
    public Map<String, Object> createTeam(String sessionId, String name, String objective,
                                          List<Map<String, Object>> members) {
        // BIZ-074: 세션당 활성 팀 수 제한
        long activeCount = teamRepository.countBySessionIdAndStatus(sessionId, "ACTIVE");
        if (activeCount >= MAX_ACTIVE_TEAMS_PER_SESSION) {
            throw new IllegalStateException(
                    "Session active team limit reached: max " + MAX_ACTIVE_TEAMS_PER_SESSION
                            + ", current " + activeCount);
        }

        // BIZ-073: 팀당 멤버 수 제한
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("At least one member is required");
        }
        if (members.size() > MAX_MEMBERS_PER_TEAM) {
            throw new IllegalArgumentException(
                    "Team member limit exceeded: max " + MAX_MEMBERS_PER_TEAM
                            + ", requested " + members.size());
        }

        // 멤버별 agent_type 유효성 검증
        List<Map<String, Object>> validatedMembers = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            Map<String, Object> member = members.get(i);
            String agentTypeStr = (String) member.get("agent_type");
            AgentType agentType = AgentType.fromString(agentTypeStr);
            String role = (String) member.getOrDefault("role", agentType.getDisplayName());
            String memberId = UUID.randomUUID().toString();

            validatedMembers.add(Map.of(
                    "member_id", memberId,
                    "agent_type", agentType.name(),
                    "role", role,
                    "status", "IDLE",
                    "index", i
            ));
        }

        TeamEntity entity = new TeamEntity();
        entity.setSessionId(sessionId);
        entity.setName(name);
        entity.setObjective(objective);
        entity.setStatus("ACTIVE");
        entity.setMembers(validatedMembers);
        entity = teamRepository.save(entity);

        log.info("Team created: id={}, name={}, session={}, members={}",
                entity.getId(), name, sessionId, validatedMembers.size());

        return toMap(entity);
    }

    /**
     * 팀 해체 (graceful).
     *
     * @param teamId 팀 ID
     * @param reason 해체 사유
     * @return 해체 결과
     */
    public Map<String, Object> dissolveTeam(UUID teamId, String reason) {
        TeamEntity entity = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        if ("DISSOLVED".equals(entity.getStatus())) {
            throw new IllegalStateException("Team already dissolved: " + teamId);
        }

        // 활성 멤버 에이전트 중지 시도
        int membersStopped = 0;
        List<Map<String, Object>> members = entity.getMembers();
        if (members != null) {
            for (Map<String, Object> member : members) {
                String subagentRunId = (String) member.get("subagent_run_id");
                if (subagentRunId != null) {
                    boolean cancelled = lifecycleManager.cancel(subagentRunId);
                    if (cancelled) membersStopped++;
                }
            }
        }

        entity.setStatus("DISSOLVED");
        entity.setDissolvedAt(OffsetDateTime.now());
        entity.setResultSummary(reason != null ? reason : "Team dissolved");
        teamRepository.save(entity);

        log.info("Team dissolved: id={}, name={}, membersStopped={}",
                teamId, entity.getName(), membersStopped);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("team_id", teamId.toString());
        result.put("name", entity.getName());
        result.put("status", "DISSOLVED");
        result.put("members_stopped", membersStopped);
        result.put("reason", reason);
        result.put("dissolved_at", entity.getDissolvedAt().toString());
        return result;
    }

    /**
     * 세션의 팀 목록 조회.
     */
    public List<Map<String, Object>> listTeams(String sessionId) {
        return teamRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)
                .stream()
                .map(this::toMap)
                .toList();
    }

    /**
     * 세션의 활성 팀 목록.
     */
    public List<Map<String, Object>> listActiveTeams(String sessionId) {
        return teamRepository.findBySessionIdAndStatus(sessionId, "ACTIVE")
                .stream()
                .map(this::toMap)
                .toList();
    }

    /**
     * 팀 상세 조회.
     */
    public Map<String, Object> getTeam(UUID teamId) {
        return teamRepository.findById(teamId)
                .map(this::toMap)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    /**
     * 팀 멤버의 subagent_run_id 업데이트.
     * TeamCreateTool에서 AgentOrchestrator 실행 후 호출.
     */
    public void updateMemberRunId(UUID teamId, int memberIndex, String subagentRunId) {
        TeamEntity entity = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        List<Map<String, Object>> members = new ArrayList<>(entity.getMembers());
        if (memberIndex >= 0 && memberIndex < members.size()) {
            Map<String, Object> updated = new LinkedHashMap<>(members.get(memberIndex));
            updated.put("subagent_run_id", subagentRunId);
            updated.put("status", "RUNNING");
            members.set(memberIndex, updated);
            entity.setMembers(members);
            teamRepository.save(entity);
        }
    }

    /**
     * 세션 종료 시 활성 팀 일괄 해체.
     */
    public void dissolveAllActiveTeams(String sessionId) {
        List<TeamEntity> activeTeams = teamRepository.findBySessionIdAndStatus(sessionId, "ACTIVE");
        for (TeamEntity team : activeTeams) {
            try {
                dissolveTeam(team.getId(), "Session ended");
            } catch (Exception e) {
                log.warn("Failed to dissolve team on session end: teamId={}", team.getId(), e);
            }
        }
    }

    private Map<String, Object> toMap(TeamEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("team_id", entity.getId().toString());
        map.put("session_id", entity.getSessionId());
        map.put("name", entity.getName());
        map.put("objective", entity.getObjective());
        map.put("status", entity.getStatus());
        map.put("members", entity.getMembers());
        map.put("result_summary", entity.getResultSummary());
        map.put("created_at", entity.getCreatedAt().toString());
        if (entity.getDissolvedAt() != null) {
            map.put("dissolved_at", entity.getDissolvedAt().toString());
        }
        return map;
    }
}
