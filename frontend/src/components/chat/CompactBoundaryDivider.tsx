import { useState } from "react";
import { ChevronDown, ChevronRight, Layers } from "lucide-react";
import type { CompactBoundaryInfo } from "../../api/sessions";

interface Props {
  info: CompactBoundaryInfo;
}

/**
 * CR-049 PRD-303: 압축 경계 표시 — 위쪽 압축된 맥락을 접기/펼치기로 요약 보여준다.
 */
export function CompactBoundaryDivider({ info }: Props) {
  const [expanded, setExpanded] = useState(false);
  const compacted = info.compacted_count ?? 0;
  const tokensSaved = info.tokens_saved ?? 0;
  const summary = info.summary;

  return (
    <div className="my-4 border-t border-dashed border-muted-foreground/40 relative">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="absolute left-1/2 -translate-x-1/2 -top-3 bg-background px-3 py-1 rounded-full border border-border text-[11px] flex items-center gap-1 hover:bg-accent transition-colors"
      >
        {expanded ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
        <Layers className="size-3" />
        <span className="text-muted-foreground">
          이전 맥락 압축됨 · {compacted}개 메시지 · {Math.round(tokensSaved / 1000)}k 토큰 절약
        </span>
      </button>
      {expanded && (
        <div className="mt-6 mx-auto max-w-3xl bg-muted/40 rounded-lg p-3 text-xs text-muted-foreground whitespace-pre-wrap">
          {summary ?? "(요약 없음)"}
        </div>
      )}
    </div>
  );
}
