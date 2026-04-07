import { cn } from "@/lib/utils";
import { EmptyState } from "../common/EmptyState";
import { CheckSquare, Circle, Loader2 } from "lucide-react";
import type { TodoItem } from "../../api/sessions";

const STATUS_ICON: Record<string, React.ReactNode> = {
  completed: <CheckSquare className="size-3.5 text-success" />,
  in_progress: <Loader2 className="size-3.5 text-primary animate-spin" />,
  pending: <Circle className="size-3.5 text-muted-foreground" />,
};

export function TodoPanel({ todos }: { todos: TodoItem[] }) {
  if (todos.length === 0) {
    return (
      <EmptyState
        icon={<CheckSquare className="size-6" />}
        title="체크리스트 없음"
        description="이 세션에서 아직 TodoWrite가 사용되지 않았습니다"
      />
    );
  }

  const completed = todos.filter((t) => t.status === "completed").length;

  return (
    <div className="space-y-3">
      {/* Summary */}
      <div className="text-xs text-muted-foreground">
        {completed}/{todos.length} completed
      </div>

      {/* Todo list */}
      <div className="space-y-1.5">
        {todos.map((todo, i) => (
          <div
            key={i}
            className={cn(
              "flex items-center gap-2.5 px-3 py-2 rounded-lg border",
              todo.status === "completed"
                ? "border-success/20 bg-success/5"
                : todo.status === "in_progress"
                  ? "border-primary/20 bg-primary/5"
                  : "border-border bg-card"
            )}
          >
            {STATUS_ICON[todo.status] ?? STATUS_ICON.pending}
            <span
              className={cn(
                "text-xs flex-1",
                todo.status === "completed" ? "text-muted-foreground line-through" : "text-foreground"
              )}
            >
              {todo.status === "in_progress" ? todo.activeForm || todo.content : todo.content}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
