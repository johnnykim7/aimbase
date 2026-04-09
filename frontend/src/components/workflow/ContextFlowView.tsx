import { cn } from "@/lib/utils";
import { ArrowRight, Database } from "lucide-react";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface FlowStep {
  id: string;
  name: string;
  type: string;
  outputKeys?: string[];
}

interface ContextFlowViewProps {
  steps: FlowStep[];
  contextSources?: string[];
}

/* ------------------------------------------------------------------ */
/*  Sub-components                                                     */
/* ------------------------------------------------------------------ */

const TYPE_COLORS: Record<string, string> = {
  llm: "border-blue-400 dark:border-blue-600",
  tool: "border-violet-400 dark:border-violet-600",
  condition: "border-amber-400 dark:border-amber-600",
  action: "border-indigo-400 dark:border-indigo-600",
};

function StepBox({ step }: { step: FlowStep }) {
  const borderColor = TYPE_COLORS[step.type] ?? "border-border";

  return (
    <div
      className={cn(
        "flex flex-col rounded-lg border-2 bg-card px-4 py-3 min-w-[140px] max-w-[200px] shrink-0",
        borderColor,
      )}
    >
      <span className="text-xs text-muted-foreground font-mono">{step.type}</span>
      <span className="text-sm font-medium text-foreground truncate mt-0.5">{step.name}</span>

      {step.outputKeys && step.outputKeys.length > 0 && (
        <div className="flex flex-wrap gap-1 mt-2 pt-2 border-t border-border/50">
          {step.outputKeys.map((key) => (
            <span
              key={key}
              className="inline-block px-1.5 py-0.5 rounded text-[10px] font-mono bg-muted text-muted-foreground"
            >
              {key}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

function Arrow() {
  return (
    <div className="flex items-center shrink-0 text-muted-foreground/50">
      <ArrowRight className="h-4 w-4" />
    </div>
  );
}

function SourceList({ sources }: { sources: string[] }) {
  return (
    <div className="flex flex-col rounded-lg border border-dashed border-border bg-muted/30 px-4 py-3 min-w-[160px] shrink-0">
      <div className="flex items-center gap-1.5 mb-2">
        <Database className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-xs font-medium text-muted-foreground">Context Sources</span>
      </div>
      <ul className="flex flex-col gap-1">
        {sources.map((src) => (
          <li key={src} className="text-xs font-mono text-foreground">
            {src}
          </li>
        ))}
      </ul>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export function ContextFlowView({ steps, contextSources }: ContextFlowViewProps) {
  if (steps.length === 0) {
    return (
      <div className="text-sm text-muted-foreground italic py-2">
        표시할 스텝이 없습니다.
      </div>
    );
  }

  return (
    <div className="flex items-start gap-3 overflow-x-auto py-2">
      {/* Step chain */}
      {steps.map((step, idx) => (
        <div key={step.id} className="flex items-center gap-3">
          {idx > 0 && <Arrow />}
          <StepBox step={step} />
        </div>
      ))}

      {/* Context sources panel */}
      {contextSources && contextSources.length > 0 && (
        <>
          <Arrow />
          <SourceList sources={contextSources} />
        </>
      )}
    </div>
  );
}
