import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Badge } from "../components/common/Badge";
import { DataTable, type Column } from "../components/common/DataTable";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { inputStyle } from "../components/common/FormField";
import { MessageSquareText } from "lucide-react";
import { useSessions } from "../hooks/useSessions";
import type { SessionMeta, SessionScopeType, RuntimeKind } from "../types/session";
import type { BadgeColor } from "../components/common/Badge";

const SCOPE_COLORS: Record<SessionScopeType, BadgeColor> = {
  chat: "muted",
  workflow: "accent",
  project: "purple",
  runtime: "warning",
};

const RUNTIME_COLORS: Record<RuntimeKind, BadgeColor> = {
  claude_tool: "purple",
  llm_api: "accent",
  mcp_only: "success",
};

const SCOPE_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "전체 Scope" },
  { value: "chat", label: "Chat" },
  { value: "workflow", label: "Workflow" },
  { value: "project", label: "Project" },
  { value: "runtime", label: "Runtime" },
];

const RUNTIME_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "전체 Runtime" },
  { value: "claude_tool", label: "Claude Tool" },
  { value: "llm_api", label: "LLM API" },
  { value: "mcp_only", label: "MCP Only" },
];

export default function Sessions() {
  const navigate = useNavigate();
  const [scopeFilter, setScopeFilter] = useState("");
  const [runtimeFilter, setRuntimeFilter] = useState("");

  const { data: sessions = [], isLoading } = useSessions({
    scope_type: scopeFilter || undefined,
    runtime_kind: runtimeFilter || undefined,
  });

  const columns: Column<SessionMeta>[] = [
    {
      header: "Title",
      render: (s) => (
        <span className="font-mono text-primary text-[13px]">
          {s.title || s.sessionId?.slice(0, 12) + "..."}
        </span>
      ),
    },
    {
      header: "Scope Type",
      render: (s) => (
        <Badge color={SCOPE_COLORS[s.scopeType] ?? "muted"}>
          {s.scopeType}
        </Badge>
      ),
      width: "120px",
    },
    {
      header: "Runtime Kind",
      render: (s) =>
        s.runtimeKind ? (
          <Badge color={RUNTIME_COLORS[s.runtimeKind] ?? "muted"}>
            {s.runtimeKind}
          </Badge>
        ) : (
          <span className="text-muted-foreground/60">--</span>
        ),
      width: "130px",
    },
    {
      header: "Recipe",
      render: (s) =>
        s.contextRecipeId ? (
          <span className="text-xs font-mono text-muted-foreground">{s.contextRecipeId}</span>
        ) : (
          <span className="text-muted-foreground/60">--</span>
        ),
      width: "140px",
    },
    {
      header: "Messages",
      render: (s) => (
        <span className="text-xs font-mono text-muted-foreground">
          {s.messageCount ?? 0}
        </span>
      ),
      width: "90px",
    },
    {
      header: "Created",
      render: (s) => (
        <span className="text-xs text-muted-foreground">
          {s.createdAt ? new Date(s.createdAt).toLocaleDateString("ko-KR") : "--"}
        </span>
      ),
      width: "110px",
    },
  ];

  return (
    <Page>
      {/* Filters */}
      <div className="flex gap-3 mb-5">
        <select
          style={{ ...inputStyle, width: 160 }}
          value={scopeFilter}
          onChange={(e) => setScopeFilter(e.target.value)}
        >
          {SCOPE_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <select
          style={{ ...inputStyle, width: 160 }}
          value={runtimeFilter}
          onChange={(e) => setRuntimeFilter(e.target.value)}
        >
          {RUNTIME_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>

      {sessions.length === 0 && !isLoading ? (
        <EmptyState
          icon={<MessageSquareText className="size-6" />}
          title="세션이 없습니다"
          description="API를 통해 세션이 생성되면 여기에 표시됩니다"
        />
      ) : (
        <DataTable
          columns={columns}
          data={sessions}
          keyExtractor={(s) => s.id ?? s.sessionId}
          loading={isLoading}
          emptyMessage="세션이 없습니다"
          onRowClick={(s) => navigate(`/sessions/${s.sessionId}`)}
        />
      )}
    </Page>
  );
}
