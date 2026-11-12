import type { ConversationView } from "../../frame/context";
import type { MessagesResponse } from "./api";

function date(iso: string | null | undefined): Date | undefined {
  if (!iso) return undefined;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? undefined : d;
}

/** 이어보기 응답을 틀의 지난 대화 모양으로 바꾼다(CR-178 4절 1번). */
export function toView(r: MessagesResponse, truncatedNotice: string): ConversationView {
  return {
    sessionId: r.session_id,
    title: r.title ?? undefined,
    notice: r.truncated ? truncatedNotice : undefined,
    turns: r.turns.map((t) => ({
      turnId: t.turn_id ?? undefined,
      turnSeq: t.user_seq ?? undefined,
      question: {
        text: t.question.text,
        at: date(t.question.created_at),
        attachments: t.question.attachments.map((a) => ({
          filename: a.filename,
          mediaType: a.media_type,
          available: a.available,
        })),
      },
      // 답이 저장되지 않은 질문(응답 도중 끊김)은 빈 답으로 그린다.
      answer: t.answer
        ? {
            text: t.answer.text,
            model: t.answer.model ?? undefined,
            at: date(t.answer.created_at),
            stopped: t.answer.stopped,
            tools: t.answer.tool_uses.map((u) => u.name),
          }
        : { text: "" },
      feedback: t.feedback
        ? {
            rating: t.feedback.rating,
            reasonCodes: t.feedback.reason_codes ?? undefined,
            comment: t.feedback.comment ?? undefined,
          }
        : undefined,
    })),
  };
}

export type DateGroup = "today" | "yesterday" | "week" | "month" | "older";

export const DATE_GROUPS: DateGroup[] = ["today", "yesterday", "week", "month", "older"];

const DAY = 24 * 60 * 60 * 1000;

/** 보는 사람 시간대의 오늘 0시를 기준으로 묶는다(CR-178 4.2 1번). */
export function dateGroup(iso: string, now: Date = new Date()): DateGroup {
  const at = date(iso);
  if (!at) return "older";
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const t = at.getTime();
  if (t >= today) return "today";
  if (t >= today - DAY) return "yesterday";
  if (t >= today - 7 * DAY) return "week";
  if (t >= today - 30 * DAY) return "month";
  return "older";
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 파일 종류 표시. 미디어 종류의 뒤쪽을 대문자로 보인다(image/png 는 PNG). */
export function kindLabel(mediaType: string): string {
  const tail = mediaType.split("/")[1] ?? mediaType;
  return tail.replace(/^x-/, "").toUpperCase();
}
