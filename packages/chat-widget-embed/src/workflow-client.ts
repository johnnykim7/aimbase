import type { TokenStore } from "./token-store";
import type { ApprovalEvent, WorkflowStepEvent } from "./types";

export interface WorkflowHandlers {
  onSnapshot?: (snapshot: Record<string, unknown>) => void;
  onStep?: (ev: WorkflowStepEvent) => void;
  onApproval?: (ev: ApprovalEvent) => void;
  onDone?: (ev: { run_id: string; status: string; duration_ms: number }) => void;
  onError?: (e: Error) => void;
}

/**
 * 워크플로우 실행 SSE 구독.
 * EventSource 는 커스텀 헤더 불가 → 쿼리파라미터로 토큰 전달 (Aimbase 가 widget 토큰만 수용).
 */
export class WorkflowClient {
  constructor(private readonly tokens: TokenStore) {}

  async subscribe(
    baseUrl: string,
    runId: string,
    handlers: WorkflowHandlers,
  ): Promise<() => void> {
    const token = await this.tokens.getToken();
    const url = `${baseUrl}/api/v1/workflows/runs/${encodeURIComponent(runId)}/subscribe?access_token=${encodeURIComponent(token)}`;
    const es = new EventSource(url);

    es.addEventListener("workflow.snapshot", (e) => {
      try {
        handlers.onSnapshot?.(JSON.parse((e as MessageEvent).data));
      } catch (err) {
        handlers.onError?.(err as Error);
      }
    });
    es.addEventListener("workflow.step", (e) => {
      try {
        handlers.onStep?.(JSON.parse((e as MessageEvent).data));
      } catch (err) {
        handlers.onError?.(err as Error);
      }
    });
    es.addEventListener("workflow.approval", (e) => {
      try {
        handlers.onApproval?.(JSON.parse((e as MessageEvent).data));
      } catch (err) {
        handlers.onError?.(err as Error);
      }
    });
    es.addEventListener("workflow.done", (e) => {
      try {
        handlers.onDone?.(JSON.parse((e as MessageEvent).data));
      } catch (err) {
        handlers.onError?.(err as Error);
      } finally {
        es.close();
      }
    });
    es.onerror = () => {
      // EventSource 는 기본적으로 자동 재연결 (readyState===CONNECTING 이면 유지)
      if (es.readyState === EventSource.CLOSED) {
        handlers.onError?.(new Error("workflow SSE closed"));
      }
    };

    return () => es.close();
  }

  async fetchChunk(
    baseUrl: string,
    sourceId: string,
    chunkId: string,
  ): Promise<Record<string, unknown>> {
    const token = await this.tokens.getToken();
    const res = await fetch(
      `${baseUrl}/api/v1/knowledge-sources/${encodeURIComponent(sourceId)}/chunks/${encodeURIComponent(chunkId)}`,
      { headers: { "Authorization": `Bearer ${token}` } },
    );
    if (!res.ok) throw new Error(`chunks ${res.status}`);
    const json = (await res.json()) as { data?: Record<string, unknown> };
    return json.data ?? {};
  }
}
