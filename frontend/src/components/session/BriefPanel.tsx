import { EmptyState } from "../common/EmptyState";
import { ActionButton } from "../common/ActionButton";
import { Badge } from "../common/Badge";
import { FileText, RefreshCw } from "lucide-react";
import type { BriefData } from "../../api/sessions";

interface BriefPanelProps {
  brief: BriefData | null | undefined;
  isLoading: boolean;
  onRefresh: () => void;
  isRefreshing: boolean;
}

export function BriefPanel({ brief, isLoading, onRefresh, isRefreshing }: BriefPanelProps) {
  if (isLoading) {
    return <div className="text-xs text-muted-foreground p-4">브리핑 로딩 중...</div>;
  }

  if (!brief) {
    return (
      <EmptyState
        icon={<FileText className="size-6" />}
        title="브리핑 없음"
        description="이 세션의 브리핑이 아직 생성되지 않았습니다"
        action={
          <ActionButton onClick={onRefresh} disabled={isRefreshing}>
            {isRefreshing ? "생성 중..." : "브리핑 생성"}
          </ActionButton>
        }
      />
    );
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <h3 className="text-sm font-semibold text-foreground">세션 브리핑</h3>
        <Badge color="accent">{brief.message_count}개 메시지</Badge>
        <span className="text-[10px] text-muted-foreground ml-auto">
          {new Date(brief.created_at).toLocaleString("ko-KR")}
        </span>
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="p-1 rounded hover:bg-muted transition-colors"
          title="브리핑 새로고침"
        >
          <RefreshCw className={`size-3.5 text-muted-foreground ${isRefreshing ? "animate-spin" : ""}`} />
        </button>
      </div>

      {/* Summary */}
      <div>
        <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">요약</div>
        <p className="text-xs text-foreground leading-relaxed">{brief.summary}</p>
      </div>

      {/* Key Decisions */}
      {brief.key_decisions.length > 0 && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">핵심 결정사항</div>
          <ul className="list-disc list-inside space-y-0.5">
            {brief.key_decisions.map((d, i) => (
              <li key={i} className="text-xs text-foreground">{d}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Pending Items */}
      {brief.pending_items.length > 0 && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">미완료 항목</div>
          <ul className="space-y-1">
            {brief.pending_items.map((p, i) => (
              <li key={i} className="flex items-start gap-2 text-xs text-foreground">
                <span className="text-warning mt-0.5">●</span>
                {p}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Model info */}
      {brief.model_used && (
        <div className="text-[10px] text-muted-foreground/50">
          모델: {brief.model_used}
        </div>
      )}
    </div>
  );
}
