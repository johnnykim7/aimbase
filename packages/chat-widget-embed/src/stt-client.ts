import type { TokenStore } from "./token-store";
import type { SttResult } from "./types";

export interface SttArgs {
  baseUrl: string;
  sessionId: string;
  /** 녹음된 오디오 Blob (MediaRecorder 결과) */
  blob: Blob;
  /** "auto" 또는 ISO-639-1 코드. 미지정 시 서버 기본값 사용 */
  language?: string;
  /** MIME 에 맞는 확장자로 붙이는 파일명 (디폴트: rec.<ext>) */
  filename?: string;
}

/**
 * CR-060: 위젯 STT (Whisper) 클라이언트.
 * 엔드포인트: POST /api/v1/chat/stt (multipart/form-data, scope=chat:stt)
 *
 * 녹음 완료 후 1회 일괄 전송 방식 — 스트리밍 미지원(Whisper 한계).
 * XHR 로 구현해 업로드 진행률(onProgress) 을 얻는다.
 */
export class SttClient {
  constructor(private readonly tokens: TokenStore) {}

  async transcribe(args: SttArgs, onProgress?: (pct: number) => void): Promise<SttResult> {
    const token = await this.tokens.getToken();

    const ext = mimeToExt(args.blob.type);
    const filename = args.filename ?? `rec.${ext}`;

    const form = new FormData();
    form.append("session_id", args.sessionId);
    form.append("file", args.blob, filename);
    if (args.language) form.append("language", args.language);

    return new Promise<SttResult>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", `${args.baseUrl}/api/v1/chat/stt`);
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);

      if (onProgress && xhr.upload) {
        xhr.upload.addEventListener("progress", (e) => {
          if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100));
        });
      }

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const body = JSON.parse(xhr.responseText);
            resolve((body.data ?? body) as SttResult);
          } catch (e) {
            reject(new SttError("STT_INVALID_RESPONSE", `invalid JSON: ${(e as Error).message}`, xhr.status));
          }
        } else {
          const { code, message } = parseErrorBody(xhr.responseText, xhr.status);
          reject(new SttError(code, message, xhr.status));
        }
      };
      xhr.onerror = () => reject(new SttError("STT_NETWORK", "network error", 0));
      xhr.onabort = () => reject(new SttError("STT_ABORTED", "aborted", 0));
      xhr.send(form);
    });
  }
}

export class SttError extends Error {
  constructor(public readonly code: string, message: string, public readonly status: number) {
    super(message);
    this.name = "SttError";
  }
}

function parseErrorBody(raw: string, status: number): { code: string; message: string } {
  if (!raw) return { code: "STT_UNKNOWN", message: `status ${status}` };
  try {
    const body = JSON.parse(raw);
    const errText: string | undefined = body.error ?? body.message;
    if (errText) {
      // 서버는 "STT_*: 상세메시지" 형태로 보낸다
      const m = errText.match(/^(STT_[A-Z_]+):\s*(.*)$/);
      if (m) return { code: m[1], message: m[2] };
      return { code: "STT_UNKNOWN", message: errText };
    }
  } catch {
    // fallthrough
  }
  return { code: "STT_UNKNOWN", message: raw.slice(0, 200) };
}

function mimeToExt(mime: string): string {
  const m = (mime || "").toLowerCase().split(";")[0].trim();
  switch (m) {
    case "audio/webm": return "webm";
    case "audio/mp4":  return "mp4";
    case "audio/mpeg": return "mp3";
    case "audio/wav":  return "wav";
    case "audio/ogg":  return "ogg";
    default:            return "bin";
  }
}
