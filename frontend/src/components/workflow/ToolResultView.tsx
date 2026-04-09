import { useState } from "react";
import { cn } from "@/lib/utils";
import { CheckCircle2, XCircle, FileText, Clock, ChevronDown, ChevronRight } from "lucide-react";
import { DiffPreview } from "./DiffPreview";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface ToolResultViewProps {
  result: {
    success: boolean;
    summary?: string;
    output?: unknown;
    artifacts?: Array<{ type: string; ref?: string }>;
    sideEffects?: string[];
    durationMs?: number;
    diff?: string;
  };
}

/* ------------------------------------------------------------------ */
/*  Sub-components                                                     */
/* ------------------------------------------------------------------ */

function StatusBadge({ success }: { success: boolean }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium",
        success
          ? "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300"
          : "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300",
      )}
    >
      {success ? <CheckCircle2 className="h-3 w-3" /> : <XCircle className="h-3 w-3" />}
      {success ? "Success" : "Failed"}
    </span>
  );
}

function ArtifactList({ artifacts }: { artifacts: Array<{ type: string; ref?: string }> }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs font-medium text-muted-foreground">Artifacts</span>
      <ul className="flex flex-col gap-0.5">
        {artifacts.map((a, i) => (
          <li key={i} className="flex items-center gap-1.5 text-xs text-foreground">
            <FileText className="h-3 w-3 text-muted-foreground shrink-0" />
            <span className="font-mono">{a.ref ?? a.type}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function SideEffectChips({ effects }: { effects: string[] }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs font-medium text-muted-foreground">Side Effects</span>
      <div className="flex flex-wrap gap-1">
        {effects.map((e, i) => (
          <span
            key={i}
            className="inline-block px-2 py-0.5 rounded-md text-xs bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300"
          >
            {e}
          </span>
        ))}
      </div>
    </div>
  );
}

function CollapsibleJson({ data }: { data: unknown }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground transition-colors"
      >
        {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        Output
      </button>
      {open && (
        <pre className="text-xs font-mono bg-muted/50 rounded-lg p-3 overflow-x-auto max-h-[300px] overflow-y-auto border border-border">
          {typeof data === "string" ? data : JSON.stringify(data, null, 2)}
        </pre>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export function ToolResultView({ result }: ToolResultViewProps) {
  return (
    <div className="rounded-lg border border-border bg-card p-4 flex flex-col gap-3">
      {/* Header row */}
      <div className="flex items-center justify-between">
        <StatusBadge success={result.success} />
        {result.durationMs != null && (
          <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
            <Clock className="h-3 w-3" />
            {result.durationMs}ms
          </span>
        )}
      </div>

      {/* Summary */}
      {result.summary && (
        <p className="text-sm text-foreground">{result.summary}</p>
      )}

      {/* Artifacts */}
      {result.artifacts && result.artifacts.length > 0 && (
        <ArtifactList artifacts={result.artifacts} />
      )}

      {/* Side effects */}
      {result.sideEffects && result.sideEffects.length > 0 && (
        <SideEffectChips effects={result.sideEffects} />
      )}

      {/* Diff */}
      {result.diff && (
        <div className="flex flex-col gap-1">
          <span className="text-xs font-medium text-muted-foreground">Diff</span>
          <DiffPreview diff={result.diff} />
        </div>
      )}

      {/* Output (collapsible) */}
      {result.output != null && <CollapsibleJson data={result.output} />}
    </div>
  );
}
