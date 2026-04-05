import { cn } from "@/lib/utils";
import { FileText } from "lucide-react";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface DiffPreviewProps {
  diff: string;
  fileName?: string;
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function classForLine(line: string): string {
  if (line.startsWith("+")) return "bg-green-50 dark:bg-green-900/20 text-green-800 dark:text-green-300";
  if (line.startsWith("-")) return "bg-red-50 dark:bg-red-900/20 text-red-800 dark:text-red-300";
  return "text-foreground";
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export function DiffPreview({ diff, fileName }: DiffPreviewProps) {
  const lines = diff.split("\n");

  return (
    <div className="rounded-lg border border-border overflow-hidden">
      {/* Header */}
      {fileName && (
        <div className="flex items-center gap-2 px-3 py-2 bg-muted/50 border-b border-border">
          <FileText className="h-3.5 w-3.5 text-muted-foreground" />
          <span className="text-xs font-mono text-muted-foreground">{fileName}</span>
        </div>
      )}

      {/* Diff body */}
      <div className="overflow-x-auto">
        <table className="w-full text-xs font-mono leading-5">
          <tbody>
            {lines.map((line, idx) => (
              <tr key={idx} className={cn("border-b border-border/30 last:border-b-0", classForLine(line))}>
                <td className="w-10 text-right pr-3 pl-2 select-none text-muted-foreground/60 border-r border-border/30">
                  {idx + 1}
                </td>
                <td className="px-3 whitespace-pre">{line}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
