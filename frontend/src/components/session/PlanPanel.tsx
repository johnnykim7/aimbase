import { cn } from "@/lib/utils";
import { Badge } from "../common/Badge";
import { EmptyState } from "../common/EmptyState";
import { ClipboardList } from "lucide-react";
import type { PlanData } from "../../api/sessions";

const STATUS_COLOR: Record<string, "accent" | "success" | "warning" | "danger" | "muted"> = {
  PLANNING: "accent",
  EXECUTING: "warning",
  VERIFYING: "accent",
  COMPLETED: "success",
  ABANDONED: "danger",
};

export function PlanPanel({ plan }: { plan: PlanData | null | undefined }) {
  if (!plan) {
    return (
      <EmptyState
        icon={<ClipboardList className="size-6" />}
        title="계획 없음"
        description="이 세션에서 아직 Plan Mode가 사용되지 않았습니다"
      />
    );
  }

  const verif = plan.verification_result;
  const completionRate = verif?.completion_rate ?? 0;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <h3 className="text-sm font-semibold text-foreground">{plan.title}</h3>
        <Badge color={STATUS_COLOR[plan.status] ?? "neutral"}>{plan.status}</Badge>
        {verif && (
          <span className="text-xs text-muted-foreground ml-auto">
            {verif.verified_steps}/{verif.total_steps} steps ({Math.round(completionRate)}%)
          </span>
        )}
      </div>

      {/* Progress bar */}
      {verif && (
        <div className="w-full bg-muted rounded-full h-2">
          <div
            className={cn("h-2 rounded-full transition-all", completionRate >= 100 ? "bg-success" : "bg-primary")}
            style={{ width: `${Math.min(completionRate, 100)}%` }}
          />
        </div>
      )}

      {/* Goals */}
      <div>
        <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">Goals</div>
        <ul className="list-disc list-inside space-y-0.5">
          {plan.goals.map((g, i) => (
            <li key={i} className="text-xs text-foreground">{g}</li>
          ))}
        </ul>
      </div>

      {/* Steps */}
      {plan.steps.length > 0 && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">Steps</div>
          <div className="space-y-1.5">
            {plan.steps.map((step, i) => {
              const gap = verif?.gaps?.find((g) => g.step_id === step.id);
              return (
                <div key={step.id ?? i} className="flex items-start gap-2 text-xs">
                  <span className="text-muted-foreground font-mono w-5 shrink-0">{i + 1}.</span>
                  <span className={cn("flex-1", gap ? "text-destructive" : "text-foreground")}>
                    {step.description}
                  </span>
                  {gap && <Badge color="danger">{gap.issue}</Badge>}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
