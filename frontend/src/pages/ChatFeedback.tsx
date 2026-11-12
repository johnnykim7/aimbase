import { useMemo } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { ThumbsUp } from "lucide-react";
import { Badge } from "../components/common/Badge";
import { DataTable, type Column } from "../components/common/DataTable";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../components/ui/select";
import { useChatFeedback } from "../hooks/useChatFeedback";
import { useScrollRestoration } from "../hooks/useScrollRestoration";
import type { ChatFeedbackRow, FeedbackRating } from "../types/chatFeedback";

const PAGE_SIZE = 20;
const DEFAULT_DAYS = 7;
// Radix Select 는 빈 글을 항목 값으로 받지 않는다.
const ALL = "ALL";

const RATING_LABEL: Record<FeedbackRating, string> = { UP: "좋아요", DOWN: "아쉬워요" };

const localDate = (d: Date) => {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

const addDays = (date: string, days: number) => {
  const [y, m, d] = date.split("-").map(Number);
  return localDate(new Date(y, m - 1, d + days));
};

// 서버는 시간대가 없는 시각을 거절한다. 브라우저 시간대의 자정을 오프셋까지 붙여 보낸다.
const startOfDay = (date: string) => {
  const [y, m, d] = date.split("-").map(Number);
  const offsetMin = -new Date(y, m - 1, d).getTimezoneOffset();
  const sign = offsetMin >= 0 ? "+" : "-";
  const abs = Math.abs(offsetMin);
  const hh = String(Math.floor(abs / 60)).padStart(2, "0");
  const mm = String(abs % 60).padStart(2, "0");
  return `${date}T00:00:00${sign}${hh}:${mm}`;
};

const formatTime = (iso: string) =>
  new Date(iso).toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });

const Clipped = ({ text, width }: { text: string | null; width: string }) =>
  text ? (
    <span className="block truncate text-xs text-muted-foreground" style={{ maxWidth: width }} title={text}>
      {text}
    </span>
  ) : (
    <span className="text-muted-foreground/60">--</span>
  );

export default function ChatFeedback() {
  const navigate = useNavigate();
  const location = useLocation();
  const [params, setParams] = useSearchParams();

  const today = localDate(new Date());
  const toDate = params.get("to") ?? today;
  const fromDate = params.get("from") ?? addDays(toDate, -(DEFAULT_DAYS - 1));
  const rating = (params.get("rating") as FeedbackRating | null) ?? undefined;
  const model = params.get("model") ?? "";
  const page = Number(params.get("page") ?? "0");

  const filter = useMemo(
    () => ({
      from: startOfDay(fromDate),
      to: startOfDay(addDays(toDate, 1)),
      rating,
      model: model.trim() || undefined,
      page,
      size: PAGE_SIZE,
    }),
    [fromDate, toDate, rating, model, page],
  );

  const { data, isLoading, isError } = useChatFeedback(filter);
  const rows = data?.rows ?? [];
  const totalPages = data?.pagination?.totalPages ?? 0;
  const { anchorRef, save } = useScrollRestoration<HTMLDivElement>(location.pathname + location.search, !isLoading);

  const change = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page");
    setParams(next, { replace: true });
  };

  const columns: Column<ChatFeedbackRow>[] = [
    {
      header: "작성일",
      render: (r) => <span className="text-xs text-muted-foreground whitespace-nowrap">{formatTime(r.created_at)}</span>,
      width: "110px",
    },
    {
      header: "평가",
      render: (r) => <Badge color={r.rating === "UP" ? "success" : "warning"}>{RATING_LABEL[r.rating]}</Badge>,
      width: "90px",
    },
    {
      header: "이유",
      render: (r) => <Clipped text={r.reasons.map((x) => x.label).join(", ") || null} width="160px" />,
    },
    {
      header: "의견",
      render: (r) => <Clipped text={r.comment} width="200px" />,
    },
    {
      header: "모델",
      render: (r) => <span className="text-xs font-mono text-muted-foreground">{r.model ?? "--"}</span>,
      width: "150px",
    },
    {
      header: "질문",
      render: (r) => <Clipped text={r.question_excerpt} width="200px" />,
    },
    {
      header: "응답",
      render: (r) => (
        <div className="flex items-center gap-1.5">
          {r.answer_deleted && <Badge color="muted">삭제된 응답</Badge>}
          <Clipped text={r.answer_excerpt} width="200px" />
        </div>
      ),
    },
  ];

  return (
    <Page>
      <div ref={anchorRef} className="flex flex-wrap items-center gap-2 mb-5">
        <span className="text-xs font-medium text-foreground">기간</span>
        <Input
          variant="sm"
          type="date"
          className="w-36"
          value={fromDate}
          max={toDate}
          onChange={(e) => change("from", e.target.value)}
        />
        <span className="text-xs text-muted-foreground">~</span>
        <Input
          variant="sm"
          type="date"
          className="w-36"
          value={toDate}
          min={fromDate}
          onChange={(e) => change("to", e.target.value)}
        />
        <span className="ms-3 text-xs font-medium text-foreground">평가</span>
        <Select value={rating ?? ALL} onValueChange={(v) => change("rating", v === ALL ? "" : v)}>
          <SelectTrigger size="sm" className="w-28">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>전체</SelectItem>
            <SelectItem value="UP">좋아요</SelectItem>
            <SelectItem value="DOWN">아쉬워요</SelectItem>
          </SelectContent>
        </Select>
        <span className="ms-3 text-xs font-medium text-foreground">모델</span>
        <Input
          variant="sm"
          className="w-48 font-mono"
          placeholder="claude-sonnet-5-5"
          defaultValue={model}
          onBlur={(e) => e.target.value.trim() !== model && change("model", e.target.value.trim())}
          onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()}
        />
      </div>

      {isError ? (
        <EmptyState icon={<ThumbsUp className="size-6" />} title="목록을 불러오지 못했습니다" />
      ) : rows.length === 0 && !isLoading ? (
        <EmptyState icon={<ThumbsUp className="size-6" />} title="남긴 평가가 없습니다" />
      ) : (
        <>
          <DataTable
            columns={columns}
            data={rows}
            keyExtractor={(r) => r.id}
            loading={isLoading}
            onRowClick={(r) => {
              save();
              navigate(`/sessions/${r.session_id}`);
            }}
          />
          {totalPages > 1 && (
            <div className="flex items-center justify-end gap-2 mt-4">
              <Button size="sm" variant="outline" disabled={page <= 0} onClick={() => change("page", String(page - 1))}>
                이전
              </Button>
              <span className="text-xs text-muted-foreground">
                {page + 1} / {totalPages}
              </span>
              <Button
                size="sm"
                variant="outline"
                disabled={page + 1 >= totalPages}
                onClick={() => change("page", String(page + 1))}
              >
                다음
              </Button>
            </div>
          )}
        </>
      )}
    </Page>
  );
}
