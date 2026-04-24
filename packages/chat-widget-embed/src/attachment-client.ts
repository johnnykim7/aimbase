import type { TokenStore } from "./token-store";
import type { Attachment } from "./types";

export interface UploadArgs {
  baseUrl: string;
  sessionId: string;
  file: File;
}

/**
 * CR-061: 위젯 첨부 업로드/삭제 클라이언트.
 * 다음 엔드포인트를 쓴다:
 *  POST   /api/v1/chat/attachments         (multipart/form-data, scope=chat:upload)
 *  DELETE /api/v1/chat/attachments/{id}    (?session_id=...)
 *
 * XHR 로 구현해 업로드 진행률(onProgress) 을 실시간으로 얻는다 — fetch 는 body 업로드 진행률을
 * 관찰하는 방법이 브라우저에 따라 제한적.
 */
export class AttachmentClient {
  constructor(private readonly tokens: TokenStore) {}

  async upload(args: UploadArgs, onProgress?: (pct: number) => void): Promise<Attachment> {
    const token = await this.tokens.getToken();

    const form = new FormData();
    form.append("session_id", args.sessionId);
    form.append("file", args.file, args.file.name);

    return new Promise<Attachment>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", `${args.baseUrl}/api/v1/chat/attachments`);
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);

      if (onProgress && xhr.upload) {
        xhr.upload.addEventListener("progress", (e) => {
          if (e.lengthComputable) {
            const pct = Math.round((e.loaded / e.total) * 100);
            onProgress(pct);
          }
        });
      }

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const body = JSON.parse(xhr.responseText);
            const data = body.data ?? body;
            resolve(data as Attachment);
          } catch (e) {
            reject(new Error(`invalid response JSON: ${(e as Error).message}`));
          }
        } else {
          const msg = extractErrorMessage(xhr.responseText) ?? `status ${xhr.status}`;
          reject(new Error(msg));
        }
      };
      xhr.onerror = () => reject(new Error("network error"));
      xhr.send(form);
    });
  }

  async delete(args: { baseUrl: string; sessionId: string; attachmentId: string }): Promise<void> {
    const token = await this.tokens.getToken();
    const url =
      `${args.baseUrl}/api/v1/chat/attachments/${encodeURIComponent(args.attachmentId)}` +
      `?session_id=${encodeURIComponent(args.sessionId)}`;

    const res = await fetch(url, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok && res.status !== 404) {
      const text = await res.text().catch(() => "");
      throw new Error(`delete attachment ${res.status}: ${text.slice(0, 200)}`);
    }
  }
}

function extractErrorMessage(raw: string): string | null {
  if (!raw) return null;
  try {
    const body = JSON.parse(raw);
    if (typeof body.error === "string") return body.error;
    if (typeof body.message === "string") return body.message;
    return null;
  } catch {
    return raw.slice(0, 200);
  }
}
