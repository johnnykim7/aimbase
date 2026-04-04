import { useState } from "react";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { inputStyle } from "../components/common/FormField";
import { Page } from "../components/layout/Page";
import { BookOpen } from "lucide-react";
import { Modal } from "../components/common/Modal";
import { FormField } from "../components/common/FormField";
import { useKnowledgeSources, useSyncKnowledge, useSearchKnowledge, useCreateKnowledgeSource, useDeleteKnowledgeSource } from "../hooks/useKnowledge";
import { knowledgeApi } from "../api/knowledge";
import { useQueryClient } from "@tanstack/react-query";
import type { KnowledgeSource, SearchResult, ChunkingStrategy, EmbeddingModelId } from "../types/knowledge";

const TYPE_ICONS: Record<string, string> = {
  file: "📄",
  url: "🌐",
  database: "🗄️",
  s3: "☁️",
  mcp: "🔧",
};

const STRATEGY_OPTIONS: { value: ChunkingStrategy; label: string; description: string; guide: string }[] = [
  { value: "semantic", label: "Semantic", description: "LlamaIndex 시맨틱 분할", guide: "PDF 보고서, 논문, 매뉴얼 등 긴 문장이 이어지는 문서에 적합. 의미 단위로 자동 분할하여 주제별 청크를 생성합니다." },
  { value: "fixed", label: "Fixed", description: "고정 크기 분할", guide: "PPTX, 슬라이드, 짧은 문서, 구조화된 텍스트에 적합. 일정 크기(512자)로 나눠 안정적인 검색 품질을 제공합니다." },
  { value: "recursive", label: "Recursive", description: "재귀적 분할", guide: "마크다운, HTML 등 제목/섹션 구조가 있는 문서에 적합. 문단 → 문장 → 단어 순으로 자연스럽게 분할합니다." },
  { value: "contextual", label: "Contextual", description: "LLM 문맥 접두사 (A++)", guide: "검색 정확도가 가장 중요한 경우. 각 청크에 문서 전체 맥락 설명을 추가하여 검색 정확도를 ~49% 향상시킵니다. 처리 시간이 더 걸립니다." },
  { value: "parent_child", label: "Parent-Child", description: "계층적 청킹 (A++)", guide: "긴 기술문서, 법률문서 등 세부 매칭과 넓은 맥락이 모두 필요한 경우. 작은 청크로 정밀 검색 후 큰 청크의 풍부한 맥락을 반환합니다." },
];

const EMBEDDING_MODEL_OPTIONS: { value: EmbeddingModelId; label: string }[] = [
  { value: "BAAI/bge-m3", label: "BAAI/bge-m3 (로컬, 한국어 강점)" },
  { value: "text-embedding-3-small", label: "text-embedding-3-small (OpenAI)" },
  { value: "text-embedding-3-large", label: "text-embedding-3-large (OpenAI)" },
];

function statusColor(s?: string): "success" | "warning" | "danger" | "muted" {
  if (s === "active") return "success";
  if (s === "syncing") return "warning";
  if (s === "error") return "danger";
  return "muted";
}

export default function Knowledge() {
  const [myOnly, setMyOnly] = useState(false);
  const { data: sources = [], isLoading } = useKnowledgeSources(myOnly || undefined);
  const syncKnowledge = useSyncKnowledge();
  const searchKnowledge = useSearchKnowledge();

  const createSource = useCreateKnowledgeSource();
  const deleteSource = useDeleteKnowledgeSource();
  const qc = useQueryClient();

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [form, setForm] = useState({ name: "", type: "file" as string, configUrl: "", embeddingModel: "BAAI/bge-m3" as EmbeddingModelId });
  const [uploadStatus, setUploadStatus] = useState<Record<string, string>>({});
  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [syncStatus, setSyncStatus] = useState<Record<string, string>>({});
  const [showStrategyGuide, setShowStrategyGuide] = useState(false);

  const handleFileUpload = async (sourceId: string, file: File) => {
    setUploadStatus((p) => ({ ...p, [sourceId]: "uploading" }));
    try {
      const formData = new FormData();
      formData.append("file", file);
      await knowledgeApi.upload(sourceId, formData);
      setUploadStatus((p) => ({ ...p, [sourceId]: "done" }));
      qc.invalidateQueries({ queryKey: ["knowledgeSources"] });
    } catch {
      setUploadStatus((p) => ({ ...p, [sourceId]: "error" }));
    }
  };

  const handleStrategyChange = async (src: KnowledgeSource, strategy: ChunkingStrategy) => {
    const newConfig = { ...(src.chunkingConfig ?? {}), strategy };
    try {
      await knowledgeApi.update(src.id, { chunkingConfig: newConfig } as Partial<KnowledgeSource>);
      qc.invalidateQueries({ queryKey: ["knowledgeSources"] });
    } catch (e) {
      console.error("Failed to update chunking strategy:", e);
    }
  };

  const handleEmbeddingModelChange = async (src: KnowledgeSource, model: EmbeddingModelId) => {
    try {
      await knowledgeApi.update(src.id, { embeddingModel: model } as Partial<KnowledgeSource>);
      qc.invalidateQueries({ queryKey: ["knowledgeSources"] });
    } catch (e) {
      console.error("Failed to update embedding model:", e);
    }
  };

  const handleSync = async (id: string) => {
    setSyncStatus((p) => ({ ...p, [id]: "syncing" }));
    try {
      await syncKnowledge.mutateAsync(id);
      const poll = setInterval(() => {
        qc.invalidateQueries({ queryKey: ["knowledgeSources"] });
      }, 3000);
      setTimeout(() => {
        clearInterval(poll);
        setSyncStatus((p) => ({ ...p, [id]: "done" }));
      }, 120000);
    } catch {
      setSyncStatus((p) => ({ ...p, [id]: "error" }));
    }
  };

  const handleSearch = async () => {
    if (!query.trim()) return;
    setSearchResults([]);
    try {
      const results = await searchKnowledge.mutateAsync({ query, topK: 5 });
      setSearchResults(results ?? []);
    } catch {
      setSearchResults([]);
    }
  };

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={
        <div className="flex gap-2 items-center">
          <ActionButton variant={myOnly ? "primary" : "ghost"} small onClick={() => setMyOnly((v) => !v)}>
            {myOnly ? "내 소스" : "전체"}
          </ActionButton>
          <ActionButton variant="primary" icon="+" small onClick={() => setShowCreateModal(true)}>소스 추가</ActionButton>
        </div>
      }
    >

      {sources.length === 0 ? (
        <EmptyState icon={<BookOpen className="size-6" />} title="등록된 Knowledge 소스가 없습니다" description="PDF, URL, Database, S3에서 문서를 수집하고 벡터 검색을 활성화하세요" />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 mb-6">
          {sources.map((src: KnowledgeSource) => (
            <div
              key={src.id}
              className={cn(
                "bg-card border border-border rounded-xl p-5 border-l-[3px]",
                src.status === "active" ? "border-l-success" : "border-l-border"
              )}
            >
              <div className="flex justify-between items-start mb-3">
                <div className="flex gap-2.5 items-center">
                  <span className="text-[22px]">{TYPE_ICONS[src.type ?? "file"] ?? "📄"}</span>
                  <div>
                    <div className="text-sm font-semibold text-foreground">{src.name}</div>
                    <div className="text-[11px] font-mono text-muted-foreground/60">{src.type}</div>
                  </div>
                </div>
                <Badge color={statusColor(src.status)}>{src.status ?? "pending"}</Badge>
              </div>
              <div className="flex justify-between items-center mb-1.5">
                <span className="text-[11px] font-mono text-muted-foreground/60">
                  {src.chunkCount != null ? `${src.chunkCount.toLocaleString()}청크` : "미동기화"}
                  {src.lastSyncAt ? ` · ${new Date(src.lastSyncAt).toLocaleDateString("ko-KR")}` : ""}
                </span>
              </div>
              <div className="mb-2.5">
                <span className="text-[10px] font-mono text-muted-foreground">
                  모델: {src.embeddingModel ?? "BAAI/bge-m3"}
                </span>
              </div>

              {/* File upload */}
              {src.type === "file" && (
                <div className="mb-2.5">
                  <label className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-mono rounded-md border border-dashed border-border cursor-pointer text-muted-foreground bg-accent hover:bg-muted transition-colors">
                    📎 파일 선택 (PDF, DOCX, TXT, CSV 등)
                    <input
                      type="file"
                      accept=".pdf,.docx,.xlsx,.pptx,.csv,.txt,.md,.html"
                      className="hidden"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) handleFileUpload(src.id, file);
                        e.target.value = "";
                      }}
                    />
                  </label>
                  {uploadStatus[src.id] === "uploading" && <span className="text-[11px] text-warning ml-2">업로드 중...</span>}
                  {uploadStatus[src.id] === "done" && <span className="text-[11px] text-success ml-2">업로드 완료</span>}
                  {uploadStatus[src.id] === "error" && <span className="text-[11px] text-destructive ml-2">업로드 실패</span>}
                </div>
              )}

              <div className="flex gap-1.5 items-center flex-wrap">
                <div className="flex gap-1 items-center">
                  <select
                    className="px-2 py-1 text-[11px] font-mono rounded-md border border-border bg-accent text-foreground cursor-pointer min-w-[120px] focus:border-primary focus:outline-none"
                    value={src.chunkingConfig?.strategy ?? "semantic"}
                    onChange={(e) => handleStrategyChange(src, e.target.value as ChunkingStrategy)}
                  >
                    {STRATEGY_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>{opt.label}</option>
                    ))}
                  </select>
                  <button
                    onClick={() => setShowStrategyGuide(true)}
                    className="size-5 rounded-full border border-border bg-accent text-muted-foreground/60 text-[11px] font-bold cursor-pointer flex items-center justify-center hover:bg-muted transition-colors"
                    title="청킹 전략 가이드"
                  >?</button>
                </div>
                <select
                  className="px-2 py-1 text-[11px] font-mono rounded-md border border-border bg-accent text-foreground cursor-pointer min-w-[120px] focus:border-primary focus:outline-none"
                  value={src.embeddingModel ?? "BAAI/bge-m3"}
                  onChange={(e) => handleEmbeddingModelChange(src, e.target.value as EmbeddingModelId)}
                >
                  {EMBEDDING_MODEL_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </select>
                <ActionButton
                  small variant="default" icon="🔄"
                  disabled={syncKnowledge.isPending || src.status === "syncing"}
                  onClick={() => handleSync(src.id)}
                >
                  {(src.status === "syncing" || syncStatus[src.id] === "syncing") ? "동기화 중..."
                    : src.chunkCount && src.chunkCount > 0 ? "재동기화"
                    : "동기화"}
                </ActionButton>
                <ActionButton
                  small variant="danger"
                  onClick={() => { if (confirm("이 소스를 삭제하시겠습니까?")) deleteSource.mutate(src.id); }}
                >
                  삭제
                </ActionButton>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Search Test */}
      <div className="bg-card border border-border rounded-xl p-5">
        <div className="text-sm font-semibold text-foreground mb-4">벡터 검색 테스트</div>
        <div className="flex gap-2.5 mb-4">
          <input
            style={{ ...inputStyle, flex: 1 }}
            placeholder="검색 쿼리를 입력하세요..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          />
          <ActionButton variant="primary" disabled={searchKnowledge.isPending || !query} onClick={handleSearch}>검색</ActionButton>
        </div>
        {searchResults.length > 0 ? (
          <div className="flex flex-col gap-2">
            {searchResults.map((r, i) => (
              <div key={i} className="px-3.5 py-3 rounded-lg bg-accent border border-border">
                <div className="flex justify-between mb-1.5">
                  <span className="text-[11px] font-mono text-muted-foreground/60">{r.sourceName ?? r.sourceId ?? "소스"}</span>
                  {(r.similarity != null || r.score != null) && (
                    <Badge color={(r.score ?? r.similarity ?? 0) >= 0.8 ? "success" : (r.score ?? r.similarity ?? 0) >= 0.6 ? "warning" : "muted"}>
                      점수: {(r.score ?? r.similarity ?? 0).toFixed(2)}
                    </Badge>
                  )}
                </div>
                <div className="text-[13px] text-foreground leading-relaxed">{r.content}</div>
              </div>
            ))}
          </div>
        ) : (
          <div className="py-5 text-center text-muted-foreground/60 font-mono text-xs">
            검색어를 입력하고 검색 버튼을 클릭하세요
          </div>
        )}
      </div>

      <Modal open={showCreateModal} onClose={() => setShowCreateModal(false)} title="Knowledge 소스 추가">
        <FormField label="소스 이름">
          <input style={inputStyle} placeholder="예: 제품 매뉴얼" value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="소스 유형">
          <select className="w-full px-3.5 py-2.5 rounded-lg border border-border bg-accent text-foreground text-[13px] font-mono cursor-pointer focus:border-primary focus:outline-none" value={form.type} onChange={(e) => setForm((p) => ({ ...p, type: e.target.value }))}>
            <option value="file">📄 File (파일 업로드)</option>
            <option value="url">🌐 URL (웹 크롤링)</option>
            <option value="database">🗄️ Database (DB 연결)</option>
            <option value="s3">☁️ S3 (클라우드 저장소)</option>
          </select>
        </FormField>
        {form.type === "url" && (
          <FormField label="URL">
            <input style={inputStyle} placeholder="https://example.com/docs" value={form.configUrl} onChange={(e) => setForm((p) => ({ ...p, configUrl: e.target.value }))} />
          </FormField>
        )}
        <FormField label="임베딩 모델">
          <select className="w-full px-3.5 py-2.5 rounded-lg border border-border bg-accent text-foreground text-[13px] font-mono cursor-pointer focus:border-primary focus:outline-none" value={form.embeddingModel} onChange={(e) => setForm((p) => ({ ...p, embeddingModel: e.target.value as EmbeddingModelId }))}>
            {EMBEDDING_MODEL_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowCreateModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            disabled={createSource.isPending || !form.name.trim()}
            onClick={() => {
              const config: Record<string, unknown> = {};
              if (form.type === "url" && form.configUrl) config.url = form.configUrl;
              createSource.mutate(
                { name: form.name, type: form.type as KnowledgeSource["type"], config, embeddingModel: form.embeddingModel },
                { onSuccess: () => { setShowCreateModal(false); setForm({ name: "", type: "file", configUrl: "", embeddingModel: "BAAI/bge-m3" }); } }
              );
            }}
          >
            저장
          </ActionButton>
        </div>
      </Modal>

      <Modal open={showStrategyGuide} onClose={() => setShowStrategyGuide(false)} title="청킹 전략 가이드">
        <div className="text-[13px] text-foreground leading-relaxed">
          <p className="mb-3 text-muted-foreground/60">
            문서 유형과 목적에 맞는 전략을 선택하면 검색 품질이 크게 달라집니다.
          </p>
          <table className="w-full border-collapse text-xs font-mono">
            <thead>
              <tr className="border-b-2 border-border text-left">
                <th className="p-2">전략</th>
                <th className="p-2">적합한 문서</th>
                <th className="p-2">특징</th>
              </tr>
            </thead>
            <tbody>
              {STRATEGY_OPTIONS.map((opt) => (
                <tr key={opt.value} className="border-b border-border">
                  <td className="p-2 font-semibold">{opt.label}</td>
                  <td className="p-2 text-muted-foreground/60">
                    {opt.value === "fixed" && "PPTX, 슬라이드, CSV, 짧은 텍스트"}
                    {opt.value === "semantic" && "PDF 보고서, 논문, 매뉴얼"}
                    {opt.value === "recursive" && "Markdown, HTML, 구조화 문서"}
                    {opt.value === "contextual" && "정확도 최우선 (모든 문서)"}
                    {opt.value === "parent_child" && "기술문서, 법률문서, 긴 보고서"}
                  </td>
                  <td className="p-2 text-muted-foreground/60">{opt.guide}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mt-4 px-3 py-2.5 rounded-lg bg-accent border border-border text-[11px] text-muted-foreground/60 leading-relaxed">
            <strong className="text-foreground">Tip</strong><br />
            - 전략 변경 후 <strong>재동기화</strong>하면 기존 임베딩을 삭제하고 새 전략으로 재생성합니다<br />
            - 같은 문서라도 전략에 따라 청크 수와 검색 품질이 달라집니다<br />
            - 잘 모르겠으면 <strong>Fixed</strong>로 시작하세요 (가장 안정적)
          </div>
        </div>
        <div className="flex justify-end mt-3">
          <ActionButton variant="primary" onClick={() => setShowStrategyGuide(false)}>확인</ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
