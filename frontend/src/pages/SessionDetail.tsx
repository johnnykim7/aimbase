import { useState, useLayoutEffect } from "react";
import { useParams } from "react-router-dom";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { useSetHeaderOverride } from "../components/layout/AppShell";
import { MessageSquareText, Wrench } from "lucide-react";
import { useSessionMeta, useToolLineage } from "../hooks/useSessions";
import type { ToolExecution } from "../types/tool";

export default function SessionDetail() {
  const { id } = useParams<{ id: string }>();
  const setHeaderOverride = useSetHeaderOverride();
  const { data: meta, isLoading: metaLoading } = useSessionMeta(id!);
  const { data: lineage = [], isLoading: lineageLoading } = useToolLineage(id!);
  const [tab, setTab] = useState<"conversation" | "lineage">("lineage");

  useLayoutEffect(() => {
    if (meta) {
      setHeaderOverride({
        title: meta.title || `Session ${meta.sessionId?.slice(0, 8)}`,
        subtitle: `${meta.scopeType} / ${meta.runtimeKind ?? "default"}`,
      });
    }
    return () => setHeaderOverride(null);
  }, [meta, setHeaderOverride]);

  if (metaLoading) return <LoadingSpinner fullPage />;
  if (!meta) return <EmptyState icon={<MessageSquareText className="size-6" />} title="세션을 찾을 수 없습니다" />;

  return (
    <Page>
      {/* Meta Card */}
      <div className="bg-card border border-border rounded-xl p-5 mb-5">
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
          <MetaItem label="Scope" value={meta.scopeType} />
          <MetaItem label="Runtime" value={meta.runtimeKind ?? "--"} />
          <MetaItem label="Recipe" value={meta.contextRecipeId ?? "--"} />
          <MetaItem label="Workspace" value={meta.workspaceRef ?? "--"} />
          <MetaItem label="App" value={meta.appId ?? "--"} />
          <MetaItem label="Project" value={meta.projectId ?? "--"} />
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 mb-5 border-b border-border">
        <TabButton active={tab === "conversation"} onClick={() => setTab("conversation")}>
          대화
        </TabButton>
        <TabButton active={tab === "lineage"} onClick={() => setTab("lineage")}>
          Tool Lineage
        </TabButton>
      </div>

      {/* Tab content */}
      {tab === "conversation" && (
        <EmptyState
          icon={<MessageSquareText className="size-6" />}
          title="대화 탭 (예정)"
          description="향후 구현 예정입니다"
        />
      )}

      {tab === "lineage" && (
        lineageLoading ? (
          <LoadingSpinner />
        ) : lineage.length === 0 ? (
          <EmptyState
            icon={<Wrench className="size-6" />}
            title="Tool 실행 이력이 없습니다"
            description="이 세션에서 도구가 실행되면 타임라인이 표시됩니다"
          />
        ) : (
          <div className="relative pl-6">
            {/* vertical line */}
            <div className="absolute left-2.5 top-2 bottom-2 w-px bg-border" />
            <div className="flex flex-col gap-3">
              {lineage.map((exec: ToolExecution, i: number) => (
                <div key={exec.id ?? i} className="relative">
                  {/* dot */}
                  <div
                    className={cn(
                      "absolute -left-[14px] top-4 size-2.5 rounded-full border-2 border-card",
                      exec.success ? "bg-success" : "bg-destructive"
                    )}
                  />
                  {/* card */}
                  <div className="bg-card border border-border rounded-lg px-4 py-3">
                    <div className="flex items-center gap-2 mb-1.5">
                      <span className="text-[13px] font-semibold text-foreground">
                        {exec.toolName}
                      </span>
                      <Badge color={exec.success ? "success" : "danger"}>
                        {exec.success ? "Success" : "Fail"}
                      </Badge>
                      {exec.durationMs != null && (
                        <span className="text-[11px] font-mono text-muted-foreground">
                          {exec.durationMs}ms
                        </span>
                      )}
                      <span className="ml-auto text-[11px] text-muted-foreground">
                        {exec.createdAt ? new Date(exec.createdAt).toLocaleTimeString("ko-KR") : ""}
                      </span>
                    </div>
                    {exec.inputSummary && (
                      <div className="text-xs text-muted-foreground mb-0.5">
                        <span className="font-mono text-muted-foreground/60">IN:</span> {exec.inputSummary}
                      </div>
                    )}
                    {exec.outputSummary && (
                      <div className="text-xs text-muted-foreground">
                        <span className="font-mono text-muted-foreground/60">OUT:</span> {exec.outputSummary}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )
      )}
    </Page>
  );
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-0.5">
        {label}
      </div>
      <div className="text-[13px] font-medium text-foreground truncate">{value}</div>
    </div>
  );
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "px-4 py-2 text-[13px] font-medium border-b-2 -mb-px transition-colors cursor-pointer bg-transparent",
        active
          ? "border-primary text-foreground"
          : "border-transparent text-muted-foreground hover:text-foreground"
      )}
    >
      {children}
    </button>
  );
}
