import { useState } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { inputStyle } from "../components/common/FormField";
import { PageHeader } from "../components/layout/PageHeader";
import { useKnowledgeSources, useSyncKnowledge, useSearchKnowledge } from "../hooks/useKnowledge";
import type { KnowledgeSource, SearchResult } from "../types/knowledge";

const TYPE_ICONS: Record<string, string> = {
  file: "📄",
  url: "🌐",
  database: "🗄️",
  s3: "☁️",
  mcp: "🔧",
};

function statusColor(s?: string): "success" | "warning" | "danger" | "muted" {
  if (s === "active") return "success";
  if (s === "syncing") return "warning";
  if (s === "error") return "danger";
  return "muted";
}

export default function Knowledge() {
  const { data: sources = [], isLoading } = useKnowledgeSources();
  const syncKnowledge = useSyncKnowledge();
  const searchKnowledge = useSearchKnowledge();

  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [syncStatus, setSyncStatus] = useState<Record<string, string>>({});

  const handleSync = async (id: string) => {
    setSyncStatus((p) => ({ ...p, [id]: "syncing" }));
    try {
      await syncKnowledge.mutateAsync(id);
      setSyncStatus((p) => ({ ...p, [id]: "done" }));
    } catch {
      setSyncStatus((p) => ({ ...p, [id]: "error" }));
    }
  };

  const handleSearch = async () => {
    if (!query.trim()) return;
    const results = await searchKnowledge.mutateAsync({ query, topK: 5 });
    setSearchResults(results ?? []);
  };

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title="Knowledge Base"
        subtitle="RAG 소스 관리 및 벡터 검색"
        actions={
          <ActionButton variant="ghost" icon="+" small>소스 추가</ActionButton>
        }
      />

      {/* Phase 3 notice */}
      <div
        style={{
          background: COLORS.surface,
          border: `1px solid ${COLORS.warningDim}`,
          borderRadius: 12,
          padding: "14px 20px",
          marginBottom: 24,
          display: "flex",
          alignItems: "center",
          gap: 10,
        }}
      >
        <span style={{ fontSize: 18 }}>🚧</span>
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.warning }}>
            Phase 3 — RAG Pipeline 구현 예정
          </div>
          <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, marginTop: 2 }}>
            Knowledge Ingestion, Vector Search, Embedding 기능은 Phase 3에서 구현됩니다. API 레이어는 완성 상태입니다.
          </div>
        </div>
      </div>

      {sources.length === 0 ? (
        <EmptyState
          icon="📚"
          title="등록된 Knowledge 소스가 없습니다"
          description="PDF, URL, Database, S3에서 문서를 수집하고 벡터 검색을 활성화하세요"
        />
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 16, marginBottom: 24 }}>
          {sources.map((src: KnowledgeSource) => (
            <div
              key={src.id}
              style={{
                background: COLORS.surface,
                border: `1px solid ${COLORS.border}`,
                borderRadius: 12,
                padding: 20,
                borderLeft: `3px solid ${src.status === "active" ? COLORS.success : COLORS.border}`,
              }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 12 }}>
                <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                  <span style={{ fontSize: 22 }}>{TYPE_ICONS[src.type ?? "file"] ?? "📄"}</span>
                  <div>
                    <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>{src.name}</div>
                    <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>{src.type}</div>
                  </div>
                </div>
                <Badge color={statusColor(src.status)}>{src.status ?? "pending"}</Badge>
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>
                  {src.chunkCount != null ? `${src.chunkCount.toLocaleString()}청크` : "미동기화"}
                  {src.lastSyncAt ? ` · ${new Date(src.lastSyncAt).toLocaleDateString("ko-KR")}` : ""}
                </span>
              </div>
              <div style={{ display: "flex", gap: 6 }}>
                <ActionButton
                  small variant="default" icon="🔄"
                  disabled={syncKnowledge.isPending}
                  onClick={() => handleSync(src.id)}
                >
                  {syncStatus[src.id] === "syncing" ? "동기화 중..." : syncStatus[src.id] === "done" ? "완료" : "동기화"}
                </ActionButton>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Search Test */}
      <div
        style={{
          background: COLORS.surface,
          border: `1px solid ${COLORS.border}`,
          borderRadius: 12,
          padding: 20,
        }}
      >
        <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 16 }}>
          벡터 검색 테스트
        </div>
        <div style={{ display: "flex", gap: 10, marginBottom: 16 }}>
          <input
            style={{ ...inputStyle, flex: 1 }}
            placeholder="검색 쿼리를 입력하세요..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          />
          <ActionButton variant="primary" disabled={searchKnowledge.isPending || !query} onClick={handleSearch}>
            검색
          </ActionButton>
        </div>
        {searchResults.length > 0 ? (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            {searchResults.map((r, i) => (
              <div
                key={i}
                style={{
                  padding: "12px 14px",
                  borderRadius: 8,
                  background: COLORS.surfaceHover,
                  border: `1px solid ${COLORS.border}`,
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                  <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>
                    {r.sourceName ?? r.sourceId ?? "소스"}
                  </span>
                  {r.similarity != null && (
                    <Badge color={r.similarity >= 0.8 ? "success" : r.similarity >= 0.6 ? "warning" : "muted"}>
                      {(r.similarity * 100).toFixed(0)}%
                    </Badge>
                  )}
                </div>
                <div style={{ fontSize: 13, fontFamily: FONTS.sans, color: COLORS.text, lineHeight: 1.6 }}>
                  {r.content}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div style={{ padding: "20px 0", textAlign: "center", color: COLORS.textDim, fontFamily: FONTS.mono, fontSize: 12 }}>
            검색어를 입력하고 검색 버튼을 클릭하세요
          </div>
        )}
      </div>
    </div>
  );
}
