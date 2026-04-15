import { useState, type KeyboardEvent } from "react";
import { Send } from "lucide-react";

interface ChatInputProps {
  disabled?: boolean;
  onSend?: (text: string) => void;
  placeholder?: string;
}

/**
 * CR-045 Phase 3-B: 대화 입력창.
 * Cmd/Ctrl+Enter로 전송. Phase 3-B는 onSend 미배선(disabled=true).
 * Phase 4에서 useChatStream.send 주입.
 */
export const ChatInput = ({ disabled, onSend, placeholder }: ChatInputProps) => {
  const [text, setText] = useState("");
  const canSend = !disabled && !!onSend && text.trim().length > 0;

  const handleSend = () => {
    if (!canSend) return;
    onSend!(text.trim());
    setText("");
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="flex items-end gap-2 border-t border-border p-3">
      <textarea
        className="flex-1 resize-none rounded border border-border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:bg-muted disabled:text-muted-foreground"
        rows={2}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        placeholder={placeholder ?? "메시지 입력 (Cmd+Enter 전송)"}
      />
      <button
        className="flex h-10 items-center gap-1 rounded bg-primary px-3 text-sm text-primary-foreground hover:opacity-90 disabled:opacity-40"
        disabled={!canSend}
        onClick={handleSend}
      >
        <Send className="h-4 w-4" />
      </button>
    </div>
  );
};
