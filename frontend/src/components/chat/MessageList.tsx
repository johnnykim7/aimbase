import { TextBlock } from "./blocks/TextBlock";
import { ThinkingBlock } from "./blocks/ThinkingBlock";
import { ToolUseBlock } from "./blocks/ToolUseBlock";
import { SubagentBlock } from "./blocks/SubagentBlock";
import type { StreamMessage } from "../../hooks/useChatStream";

interface MessageListProps {
  messages: StreamMessage[];
  isStreaming?: boolean;
}

export const MessageList = ({ messages, isStreaming }: MessageListProps) => {
  return (
    <div className="space-y-4">
      {messages.map((m) => (
        <div key={m.id} className="rounded border border-border p-3">
          <div className="mb-2 text-xs font-medium text-muted-foreground">
            {m.role === "user" ? "🧑 You" : "🤖 Assistant"}
          </div>
          <div className="space-y-2">
            {m.blocks.map((b, i) => {
              if (b.kind === "text") return <TextBlock key={i} text={b.text} />;
              if (b.kind === "thinking") return <ThinkingBlock key={i} text={b.text} />;
              if (b.kind === "tool_use")
                return (
                  <ToolUseBlock
                    key={b.id || i}
                    id={b.id}
                    name={b.name}
                    input={b.input}
                    result={b.result}
                  />
                );
              if (b.kind === "subagent")
                return (
                  <SubagentBlock
                    key={b.runId || i}
                    runId={b.runId}
                    agentType={b.agentType}
                    description={b.description}
                    status={b.status}
                    summary={b.summary}
                    durationMs={b.durationMs}
                  />
                );
              return null;
            })}
            {m.role === "assistant" && isStreaming && m.blocks.length === 0 && (
              <div className="text-xs text-muted-foreground">···</div>
            )}
          </div>
        </div>
      ))}
    </div>
  );
};
