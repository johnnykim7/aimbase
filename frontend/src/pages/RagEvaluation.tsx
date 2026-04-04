import { useState } from "react";
import { Target } from "lucide-react";
import { cn } from "@/lib/utils";
import { ActionButton } from "../components/common/ActionButton";
import { Badge } from "../components/common/Badge";
import { Page } from "../components/layout/Page";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { EmptyState } from "../components/common/EmptyState";
import { inputStyle } from "../components/common/FormField";
import { useKnowledgeSources } from "../hooks/useKnowledge";
import { evaluationApi } from "../api/evaluation";
import type { RagEvaluation } from "../api/evaluation";
import type { KnowledgeSource } from "../types/knowledge";

const METRIC_LABELS: Record<string, string> = {
  faithfulness: "Faithfulness",
  context_relevancy: "Context Relevancy",
  answer_relevancy: "Answer Relevancy",
  context_precision: "Context Precision",
  context_recall: "Context Recall",
};

function metricColorClass(value: number): string {
  if (value >= 0.8) return "text-success";
  if (value >= 0.6) return "text-warning";
  return "text-destructive";
}

function statusBadge(s: string): "success" | "warning" | "danger" | "muted" {
  if (s === "completed") return "success";
  if (s === "running") return "warning";
  if (s === "failed") return "danger";
  return "muted";
}

export default function RagEvaluation() {
  const { data: sources = [], isLoading: srcLoading } = useKnowledgeSources();
  const [selectedSource, setSelectedSource] = useState("");
  const [evaluations, setEvaluations] = useState<RagEvaluation[]>([]);
  const [loading, setLoading] = useState(false);
  const [testQuestions, setTestQuestions] = useState("");
  const [running, setRunning] = useState(false);
  const [evalMode, setEvalMode] = useState<"fast" | "accurate">("fast");

  const loadEvaluations = async (sourceId: string) => {
    setSelectedSource(sourceId);
    setLoading(true);
    try {
      const res = await evaluationApi.listRagQuality(sourceId);
      setEvaluations(res.data.data ?? []);
    } catch {
      setEvaluations([]);
    }
    setLoading(false);
  };

  const runEvaluation = async () => {
    if (!selectedSource || !testQuestions.trim()) return;
    setRunning(true);
    try {
      const testSet = testQuestions
        .split("\n")
        .filter((l) => l.trim())
        .map((q) => ({ question: q.trim() }));

      await evaluationApi.runRagQuality({
        sourceId: selectedSource,
        testSet,
        config: {},
        mode: evalMode,
      });

      // 2초 후 목록 새로고침
      setTimeout(() => loadEvaluations(selectedSource), 2000);
    } catch (e) {
      console.error("Evaluation failed:", e);
    }
    setRunning(false);
  };

  if (srcLoading) return <LoadingSpinner fullPage />;

  return (
    <Page>
      {/* 소스 선택 */}
      <div className="mb-5 flex gap-2.5 items-center">
        <select
          style={{ ...inputStyle, minWidth: 250 }}
          value={selectedSource}
          onChange={(e) => loadEvaluations(e.target.value)}
        >
          <option value="">Knowledge 소스 선택...</option>
          {sources.map((src: KnowledgeSource) => (
            <option key={src.id} value={src.id}>
              {src.name} ({src.chunkCount ?? 0} chunks)
            </option>
          ))}
        </select>
      </div>

      {selectedSource && (
        <>
          {/* 평가 실행 */}
          <div className="bg-card border border-border rounded-xl p-5 mb-5">
            <div className="text-sm font-semibold text-foreground mb-3">
              새 평가 실행
            </div>
            <textarea
              style={{ ...inputStyle, width: "100%", minHeight: 80, resize: "vertical" }}
              className="mb-3"
              placeholder={"테스트 질문을 한 줄에 하나씩 입력하세요...\n예: Python이란 무엇인가요?\n예: 머신러닝의 종류를 설명해주세요"}
              value={testQuestions}
              onChange={(e) => setTestQuestions(e.target.value)}
            />
            <div className="flex items-center gap-4 mb-3">
              <div className="text-xs text-muted-foreground">평가 모드</div>
              <label className={cn("flex items-center gap-1 cursor-pointer text-xs font-mono", evalMode === "fast" ? "text-primary" : "text-muted-foreground/40")}>
                <input type="radio" name="evalMode" value="fast" checked={evalMode === "fast"} onChange={() => setEvalMode("fast")} />
                Fast (임베딩 유사도)
              </label>
              <label className={cn("flex items-center gap-1 cursor-pointer text-xs font-mono", evalMode === "accurate" ? "text-primary" : "text-muted-foreground/40")}>
                <input type="radio" name="evalMode" value="accurate" checked={evalMode === "accurate"} onChange={() => setEvalMode("accurate")} />
                Accurate (LLM Judge)
              </label>
            </div>
            <ActionButton
              variant="primary"
              disabled={running || !testQuestions.trim()}
              onClick={runEvaluation}
            >
              {running ? "평가 중..." : "RAGAS 평가 실행"}
            </ActionButton>
          </div>

          {/* 평가 결과 목록 */}
          {loading ? (
            <LoadingSpinner />
          ) : evaluations.length === 0 ? (
            <EmptyState
              icon={<Target className="size-6" />}
              title="아직 평가 결과가 없습니다"
              description="위에서 테스트 질문을 입력하고 평가를 실행하세요"
            />
          ) : (
            <div className="flex flex-col gap-4">
              {evaluations.map((ev) => (
                <div
                  key={ev.id}
                  className="bg-card border border-border rounded-xl p-5"
                >
                  <div className="flex justify-between items-center mb-4">
                    <div>
                      <Badge color={statusBadge(ev.status)}>{ev.status}</Badge>
                      <span className="ml-2.5 text-xs font-mono text-muted-foreground/40">
                        {ev.sampleCount} samples · {new Date(ev.createdAt).toLocaleString("ko-KR")}
                      </span>
                    </div>
                    <span className="text-[11px] font-mono text-muted-foreground/40">
                      {ev.id.slice(0, 8)}
                    </span>
                  </div>

                  {ev.status === "completed" && ev.metrics && (
                    <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-3">
                      {Object.entries(ev.metrics).map(([key, value]) => (
                        <div
                          key={key}
                          className="bg-accent rounded-lg py-3 px-4 text-center"
                        >
                          <div className="text-[11px] font-mono text-muted-foreground/40 mb-1.5">
                            {METRIC_LABELS[key] ?? key}
                          </div>
                          <div className={cn("text-2xl font-bold font-mono", metricColorClass(value))}>
                            {(value * 100).toFixed(1)}%
                          </div>
                        </div>
                      ))}
                    </div>
                  )}

                  {ev.status === "failed" && ev.errorMessage && (
                    <div className="text-xs font-mono text-destructive p-2 px-3 bg-accent rounded-lg">
                      {ev.errorMessage}
                    </div>
                  )}

                  {ev.status === "running" && (
                    <div className="text-center p-4">
                      <LoadingSpinner />
                      <div className="text-xs text-muted-foreground/40 mt-2">평가 진행 중...</div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </Page>
  );
}
