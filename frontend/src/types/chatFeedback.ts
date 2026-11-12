export type FeedbackRating = "UP" | "DOWN";

export interface FeedbackReason {
  code: string;
  label: string;
}

export interface ChatFeedbackRow {
  id: string;
  created_at: string;
  updated_at: string;
  rating: FeedbackRating;
  reasons: FeedbackReason[];
  comment: string | null;
  model: string | null;
  session_id: string;
  question_excerpt: string | null;
  answer_excerpt: string | null;
  answer_deleted: boolean;
}

export interface ChatFeedbackFilter {
  from: string;
  to: string;
  rating?: FeedbackRating;
  model?: string;
  page: number;
  size: number;
}
