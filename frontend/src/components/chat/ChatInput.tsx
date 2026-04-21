import { useState, type KeyboardEvent } from "react";
import { Send, Square } from "lucide-react";

interface ChatInputProps {
  disabled?: boolean;
  isStreaming?: boolean;
  onSend?: (text: string) => void;
  onAbort?: () => void;
  placeholder?: string;
}

/**
 * CR-045 Phase 3-B / CR-046: 대화 입력창.
 * - 스트림 중에도 입력 허용(끼어들기). 전송 시 이전 응답은 BE/FE에서 자동 abort됨.
 * - isStreaming=true일 때는 [전송] 버튼이 [중지] 버튼으로 전환.
 * Cmd/Ctrl+Enter로 전송.
 */
export const ChatInput = ({
  disabled,
  isStreaming,
  onSend,
  onAbort,
  placeholder,
}: ChatInputProps) => {
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
      {isStreaming ? (
        <button
          type="button"
          className="flex h-10 items-center gap-1 rounded bg-destructive px-3 text-sm text-destructive-foreground hover:opacity-90"
          onClick={onAbort}
          title="응답 중지"
          aria-label="응답 중지"
        >
          <Square className="h-4 w-4" />
        </button>
      ) : (
        <button
          type="button"
          className="flex h-10 items-center gap-1 rounded bg-primary px-3 text-sm text-primary-foreground hover:opacity-90 disabled:opacity-40"
          disabled={!canSend}
          onClick={handleSend}
          aria-label="전송"
        >
          <Send className="h-4 w-4" />
        </button>
      )}
    </div>
  );
};
