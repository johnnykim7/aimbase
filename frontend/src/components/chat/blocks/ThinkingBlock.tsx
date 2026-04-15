import { useState } from "react";
import { Brain, ChevronDown, ChevronRight } from "lucide-react";

interface ThinkingBlockProps {
  text: string;
}

/**
 * CR-045: Extended Thinking 블록. 기본 접힘.
 */
export const ThinkingBlock = ({ text }: ThinkingBlockProps) => {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded border border-dashed border-border bg-muted/30 text-xs">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-muted-foreground hover:text-foreground"
      >
        {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        <Brain className="h-3 w-3" />
        <span className="font-medium">생각 중</span>
        {!open && <span className="truncate opacity-70">— {text.slice(0, 80)}{text.length > 80 ? "…" : ""}</span>}
      </button>
      {open && (
        <div className="whitespace-pre-wrap border-t border-border/50 px-3 py-2 text-muted-foreground">
          {text}
        </div>
      )}
    </div>
  );
};
