/**
 * 수동 SSE 파서 — fetch 로 받은 ReadableStream 을 `event:`/`data:` 블록 단위로 잘라준다.
 *
 * Aimbase 채팅 스트림은 `event:` 이름 + `data:` JSON 한 줄 형태.
 * fetch 기반으로 구현해야 Authorization 헤더 전송 가능 (EventSource 는 커스텀 헤더 불가).
 */

export interface SseEvent {
  name: string;
  data: string;
}

export async function* parseSseStream(
  response: Response,
  signal?: AbortSignal,
): AsyncGenerator<SseEvent> {
  if (!response.body) throw new Error("SSE response has no body");

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let currentEvent = "message";

  try {
    while (true) {
      if (signal?.aborted) break;
      const { value, done } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // event 블록은 빈 줄로 구분
      let boundary: number;
      while ((boundary = buffer.indexOf("\n\n")) !== -1) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);

        let dataLine = "";
        for (const raw of block.split("\n")) {
          if (raw.startsWith("event:")) {
            currentEvent = raw.slice(6).trim() || "message";
          } else if (raw.startsWith("data:")) {
            dataLine += raw.slice(5).replace(/^ /, "");
          } else if (raw.startsWith(":")) {
            // comment (heartbeat)
          }
        }
        if (dataLine) {
          yield { name: currentEvent, data: dataLine };
          currentEvent = "message";
        }
      }
    }
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // ignore
    }
  }
}
