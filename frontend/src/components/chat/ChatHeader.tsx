import { MessageCircle } from "lucide-react";

interface ChatHeaderProps {
  sessionId?: string;
  workspaceRef?: string;
  model?: string;
}

/**
 * CR-045 Phase 3-B: 대화창 상단 헤더 (read-only workspace/model 표시).
 */
export const ChatHeader = ({ sessionId, workspaceRef, model }: ChatHeaderProps) => {
  const wsName = workspaceRef?.split("/").pop();
  return (
    <header className="flex items-center gap-2 border-b border-border px-4 py-2.5 text-sm">
      <MessageCircle className="h-4 w-4 text-primary" />
      {wsName ? (
        <span className="rounded bg-muted px-2 py-0.5 text-xs font-medium">
          💼 {wsName}
        </span>
      ) : (
        <span className="text-xs text-muted-foreground">새 대화</span>
      )}
      {model && (
        <span className="text-xs text-muted-foreground">{model}</span>
      )}
      {sessionId && (
        <span className="ml-auto font-mono text-[10px] text-muted-foreground" title={sessionId}>
          {sessionId.slice(0, 8)}…
        </span>
      )}
    </header>
  );
};
