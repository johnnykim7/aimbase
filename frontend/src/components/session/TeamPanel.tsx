import { Badge } from "../common/Badge";
import { EmptyState } from "../common/EmptyState";
import { Users } from "lucide-react";
import type { TeamData } from "../../api/sessions";

const STATUS_COLOR: Record<string, "accent" | "success" | "danger" | "warning" | "muted"> = {
  ACTIVE: "accent",
  COMPLETED: "success",
  DISSOLVED: "muted",
};

const MEMBER_STATUS_COLOR: Record<string, "accent" | "success" | "danger" | "muted"> = {
  IDLE: "muted",
  RUNNING: "accent",
  COMPLETED: "success",
  FAILED: "danger",
};

export function TeamPanel({ teams }: { teams: TeamData[] }) {
  if (teams.length === 0) {
    return (
      <EmptyState
        icon={<Users className="size-6" />}
        title="팀 없음"
        description="이 세션에서 아직 Team이 생성되지 않았습니다"
      />
    );
  }

  return (
    <div className="space-y-3">
      {teams.map((team) => (
        <div key={team.team_id} className="bg-card border border-border rounded-lg px-4 py-3">
          {/* Header */}
          <div className="flex items-center gap-2 mb-2">
            <Users className="size-4 text-muted-foreground" />
            <span className="text-[13px] font-semibold text-foreground">{team.name}</span>
            <Badge color={STATUS_COLOR[team.status] ?? "muted"}>{team.status}</Badge>
            <span className="ml-auto text-[11px] text-muted-foreground">
              {new Date(team.created_at).toLocaleTimeString("ko-KR")}
            </span>
          </div>

          {/* Objective */}
          {team.objective && (
            <div className="text-xs text-muted-foreground mb-2">{team.objective}</div>
          )}

          {/* Members */}
          <div className="space-y-1">
            {team.members.map((member) => (
              <div
                key={member.member_id}
                className="flex items-center gap-2 text-xs bg-muted/30 rounded px-2 py-1"
              >
                <Badge color={MEMBER_STATUS_COLOR[member.status] ?? "muted"}>
                  {member.agent_type}
                </Badge>
                <span className="text-muted-foreground">{member.role}</span>
                <span className="ml-auto text-[10px] font-mono text-muted-foreground/60">
                  {member.status}
                </span>
              </div>
            ))}
          </div>

          {/* Result */}
          {team.result_summary && (
            <div className="mt-2 text-[11px] text-muted-foreground border-t border-border pt-1.5">
              {team.result_summary}
            </div>
          )}

          {/* Dissolved time */}
          {team.dissolved_at && (
            <div className="text-[10px] text-muted-foreground/60 mt-1">
              Dissolved: {new Date(team.dissolved_at).toLocaleTimeString("ko-KR")}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
