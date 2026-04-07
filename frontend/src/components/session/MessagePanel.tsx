import { cn } from "@/lib/utils";
import { Badge } from "../common/Badge";
import { EmptyState } from "../common/EmptyState";
import { MessageSquare } from "lucide-react";
import type { AgentMessage } from "../../api/agents";

const TYPE_COLOR: Record<string, "accent" | "success" | "danger" | "warning" | "muted"> = {
  TEXT: "accent",
  COMMAND: "warning",
  RESULT: "success",
  ERROR: "danger",
};

export function MessagePanel({ messages }: { messages: AgentMessage[] }) {
  if (messages.length === 0) {
    return (
      <EmptyState
        icon={<MessageSquare className="size-6" />}
        title="메시지 없음"
        description="이 세션에서 아직 에이전트 간 메시지가 없습니다"
      />
    );
  }

  return (
    <div className="space-y-2">
      {messages.map((msg) => (
        <div key={msg.id} className="bg-card border border-border rounded-lg px-4 py-3">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-[11px] font-mono text-primary truncate">
              {msg.fromAgentId}
            </span>
            <span className="text-[10px] text-muted-foreground">-&gt;</span>
            <span className="text-[11px] font-mono text-foreground truncate">
              {msg.toAgentId === "*" ? "broadcast" : msg.toAgentId}
            </span>
            <Badge color={TYPE_COLOR[msg.messageType] ?? "muted"}>
              {msg.messageType}
            </Badge>
            {!msg.read && (
              <Badge color="accent">unread</Badge>
            )}
            <span className="ml-auto text-[10px] text-muted-foreground">
              {new Date(msg.createdAt).toLocaleTimeString("ko-KR")}
            </span>
          </div>

          <div
            className={cn(
              "text-[12px] text-foreground/90 whitespace-pre-wrap break-words",
              msg.messageType === "ERROR" && "text-destructive"
            )}
          >
            {msg.content.length > 500 ? msg.content.slice(0, 500) + "..." : msg.content}
          </div>
        </div>
      ))}
    </div>
  );
}
