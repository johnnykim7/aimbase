import { cn } from "@/lib/utils";
import { Badge } from "../common/Badge";
import { EmptyState } from "../common/EmptyState";
import { Cpu } from "lucide-react";
import type { TaskData } from "../../api/sessions";

const STATUS_COLOR: Record<string, "accent" | "success" | "danger" | "warning" | "muted"> = {
  running: "accent",
  completed: "success",
  failed: "danger",
  cancelled: "warning",
  timeout: "danger",
};

export function TaskPanel({ tasks }: { tasks: TaskData[] }) {
  if (tasks.length === 0) {
    return (
      <EmptyState
        icon={<Cpu className="size-6" />}
        title="태스크 없음"
        description="이 세션에서 아직 Task가 생성되지 않았습니다"
      />
    );
  }

  return (
    <div className="space-y-2">
      {tasks.map((task) => (
        <div key={task.task_id} className="bg-card border border-border rounded-lg px-4 py-3">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-[13px] font-semibold text-foreground truncate flex-1">
              {task.description ?? "Untitled task"}
            </span>
            <Badge color={STATUS_COLOR[task.status] ?? "neutral"}>{task.status}</Badge>
            {task.priority && task.priority !== "medium" && (
              <Badge color={task.priority === "high" ? "danger" : "muted"}>
                {task.priority}
              </Badge>
            )}
          </div>

          <div className="flex gap-4 text-[11px] text-muted-foreground">
            {task.duration_ms > 0 && (
              <span className="font-mono">{(task.duration_ms / 1000).toFixed(1)}s</span>
            )}
            {(task.token_usage?.input_tokens > 0 || task.token_usage?.output_tokens > 0) && (
              <span className="font-mono">
                {task.token_usage.input_tokens + task.token_usage.output_tokens} tokens
              </span>
            )}
            {task.created_at && (
              <span className={cn("ml-auto")}>
                {new Date(task.created_at).toLocaleTimeString("ko-KR")}
              </span>
            )}
          </div>

          {task.error && (
            <div className="mt-1.5 text-[11px] text-destructive bg-destructive/5 rounded px-2 py-1">
              {task.error}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
