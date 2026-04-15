import { useState } from "react";
import { Wrench, Loader2, CheckCircle2, XCircle, ChevronDown, ChevronRight } from "lucide-react";

interface ToolUseBlockProps {
  id: string;
  name: string;
  input: Record<string, unknown>;
  result?: { output: string; isError: boolean };
}

/**
 * CR-045 Phase 4-B: 도구 호출 시작 + 결과를 하나로 묶은 블록.
 * result 없을 때는 spinner(실행 중), 있을 때는 성공/실패 아이콘.
 */
export const ToolUseBlock = ({ id, name, input, result }: ToolUseBlockProps) => {
  const [inputOpen, setInputOpen] = useState(false);
  const [outputOpen, setOutputOpen] = useState(false);

  const status = !result ? "running" : result.isError ? "error" : "ok";
  const StatusIcon =
    status === "running" ? Loader2 : status === "error" ? XCircle : CheckCircle2;
  const statusClass =
    status === "running"
      ? "text-muted-foreground animate-spin"
      : status === "error"
      ? "text-destructive"
      : "text-primary";

  const inputPreview = JSON.stringify(input);

  return (
    <div className="rounded border border-border bg-muted/40 text-xs">
      <div className="flex items-center gap-1.5 px-2 py-1.5">
        <Wrench className="h-3 w-3 text-muted-foreground" />
        <span className="font-mono font-medium">{name}</span>
        <StatusIcon className={`h-3 w-3 ${statusClass}`} />
        <span className="ml-auto font-mono text-[10px] text-muted-foreground" title={id}>
          {id.slice(-6)}
        </span>
      </div>

      <button
        type="button"
        onClick={() => setInputOpen((v) => !v)}
        className="flex w-full items-center gap-1 border-t border-border/50 px-2 py-1 text-[10px] text-muted-foreground hover:text-foreground"
      >
        {inputOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        <span>input</span>
        {!inputOpen && (
          <span className="truncate opacity-70">
            {inputPreview.length > 80 ? inputPreview.slice(0, 80) + "…" : inputPreview}
          </span>
        )}
      </button>
      {inputOpen && (
        <pre className="overflow-x-auto border-t border-border/50 bg-background/60 px-2 py-1 text-[10px]">
{JSON.stringify(input, null, 2)}
        </pre>
      )}

      {result && (
        <>
          <button
            type="button"
            onClick={() => setOutputOpen((v) => !v)}
            className="flex w-full items-center gap-1 border-t border-border/50 px-2 py-1 text-[10px] text-muted-foreground hover:text-foreground"
          >
            {outputOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
            <span>{result.isError ? "error" : "output"}</span>
            {!outputOpen && (
              <span className="truncate opacity-70">
                {result.output.length > 80 ? result.output.slice(0, 80) + "…" : result.output}
              </span>
            )}
          </button>
          {outputOpen && (
            <pre className={`overflow-x-auto border-t border-border/50 px-2 py-1 text-[10px] whitespace-pre-wrap ${result.isError ? "bg-destructive/10" : "bg-background/60"}`}>
{result.output}
            </pre>
          )}
        </>
      )}
    </div>
  );
};
