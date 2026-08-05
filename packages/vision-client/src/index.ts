/**
 * Aimbase Vision Client — 영상 판독(CR-137) 업로드 SDK.
 *
 * **UI 를 포함하지 않는다.** 촬영 버튼·미리보기·결과 화면은 소비앱이 자기 화면톤에 맞춰 만든다.
 * 앱마다 재구현되면 안 되는 것은 UI 가 아니라 **업로드 신뢰성**(대용량, 모바일 네트워크 끊김,
 * 폴링 루프)이고, 이 SDK 는 그 부분만 담당한다.
 *
 * ```ts
 * const client = new VisionClient({
 *   baseUrl: 'https://aimbase.example.com',
 *   apiKey: 'plat-xxxx',
 *   tenantId: 'bp_wes',
 * });
 *
 * const { jobId } = await client.submit(file, { connectionId: 'qwen-vl-7b-mac-001' });
 * const result = await client.waitFor(jobId, { onProgress: (j) => setStatus(j.status) });
 * console.log(result.result);
 * ```
 */

export type VisionJobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface VisionClientOptions {
  /** Aimbase 서버 주소 (예: https://aimbase.example.com) */
  baseUrl: string;
  /** 시스템 API Key. `X-API-Key` 헤더로 전송된다. */
  apiKey?: string;
  /** JWT 를 쓰는 경우. apiKey 와 택일. */
  bearerToken?: string;
  /** 멀티테넌트 식별자. `X-Tenant-Id` 헤더로 전송된다. */
  tenantId?: string;
  /** 기본 요청 타임아웃(ms). 업로드는 파일이 커서 넉넉히 잡는다. */
  timeoutMs?: number;
}

export interface SubmitOptions {
  /** 판독에 쓸 LLM 커넥션 id. 생략 시 서버 기본값. */
  connectionId?: string;
  /** 추출할 프레임 수. 기본 6, 범위 1~20. */
  frames?: number;
  /** 판독 지시. 생략 시 서버 기본 프롬프트. */
  prompt?: string;
  /** 업로드 진행률 콜백 (0~1). XHR 경로에서만 동작한다. */
  onUploadProgress?: (ratio: number) => void;
  /** 외부 취소 신호. */
  signal?: AbortSignal;
}

export interface SubmitResult {
  jobId: string;
  status: VisionJobStatus;
  filename?: string;
  mimeType?: string;
  sizeBytes?: number;
  frames?: number;
}

export interface VisionJob {
  jobId: string;
  status: VisionJobStatus;
  filename?: string;
  mimeType?: string;
  sizeBytes?: number;
  durationSec?: number;
  frameCount?: number;
  /** VLM 판독 결과 (평문). COMPLETED 일 때만 채워진다. */
  result?: string;
  /** structured output 사용 시 파싱 결과. */
  resultJson?: Record<string, unknown> | null;
  elapsedSec?: number;
  errorMessage?: string;
  createdAt?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface WaitOptions {
  /** 폴링 간격(ms). 기본 3000 — 서버 권장(3~5초). */
  pollMs?: number;
  /** 전체 대기 상한(ms). 기본 600000(10분). */
  timeoutMs?: number;
  /** 상태가 바뀔 때마다 호출된다. 진행 표시용. */
  onProgress?: (job: VisionJob) => void;
  signal?: AbortSignal;
}

/** 서버가 4xx/5xx 를 반환했을 때 던진다. */
export class VisionError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body?: unknown,
  ) {
    super(message);
    this.name = 'VisionError';
  }
}

/** job 이 FAILED 로 끝났을 때 던진다. */
export class VisionJobFailedError extends Error {
  constructor(readonly job: VisionJob) {
    super(job.errorMessage || 'vision job failed');
    this.name = 'VisionJobFailedError';
  }
}

const DEFAULT_TIMEOUT_MS = 300_000;
const DEFAULT_POLL_MS = 3_000;
const DEFAULT_WAIT_TIMEOUT_MS = 600_000;

export class VisionClient {
  private readonly baseUrl: string;
  private readonly opts: VisionClientOptions;

  constructor(opts: VisionClientOptions) {
    if (!opts?.baseUrl) throw new Error('VisionClient: baseUrl is required');
    this.opts = opts;
    this.baseUrl = opts.baseUrl.replace(/\/+$/, '');
  }

  /**
   * 영상을 업로드하고 job 을 등록한다. 즉시 반환되며 판독은 서버 백그라운드에서 진행된다.
   *
   * 진행률이 필요하면 `onUploadProgress` 를 넘긴다 — fetch 는 업로드 진행률을 제공하지 않으므로
   * 그 경우에만 XHR 경로를 탄다.
   */
  async submit(file: File | Blob, options: SubmitOptions = {}): Promise<SubmitResult> {
    const form = new FormData();
    form.append('file', file, (file as File).name ?? 'video');
    if (options.connectionId) form.append('connection_id', options.connectionId);
    if (options.frames != null) form.append('frames', String(options.frames));
    if (options.prompt) form.append('prompt', options.prompt);

    const url = `${this.baseUrl}/api/v1/vision-jobs`;
    const raw = options.onUploadProgress
      ? await this.xhrPost(url, form, options)
      : await this.fetchPost(url, form, options.signal);

    const d = raw as Record<string, any>;
    return {
      jobId: d.job_id,
      status: d.status,
      filename: d.filename,
      mimeType: d.mime_type,
      sizeBytes: d.size_bytes,
      frames: d.frames,
    };
  }

  /** job 단건 조회. */
  async get(jobId: string, signal?: AbortSignal): Promise<VisionJob> {
    const res = await fetch(`${this.baseUrl}/api/v1/vision-jobs/${encodeURIComponent(jobId)}`, {
      method: 'GET',
      headers: this.headers(),
      signal,
    });
    const body = await this.parse(res);
    return toJob(body as Record<string, any>);
  }

  /**
   * job 이 끝날 때까지 폴링한다.
   *
   * @throws {VisionJobFailedError} job 이 FAILED 로 끝난 경우
   * @throws {Error} 대기 상한 초과
   */
  async waitFor(jobId: string, options: WaitOptions = {}): Promise<VisionJob> {
    const pollMs = options.pollMs ?? DEFAULT_POLL_MS;
    const deadline = Date.now() + (options.timeoutMs ?? DEFAULT_WAIT_TIMEOUT_MS);
    let lastStatus: VisionJobStatus | undefined;

    for (;;) {
      if (options.signal?.aborted) throw new Error('aborted');

      const job = await this.get(jobId, options.signal);
      if (job.status !== lastStatus) {
        lastStatus = job.status;
        options.onProgress?.(job);
      }
      if (job.status === 'COMPLETED') return job;
      if (job.status === 'FAILED') throw new VisionJobFailedError(job);

      if (Date.now() + pollMs > deadline) {
        throw new Error(`vision job ${jobId} did not finish within timeout (last=${job.status})`);
      }
      await sleep(pollMs, options.signal);
    }
  }

  /** submit + waitFor 를 한 번에. 가장 흔한 사용 형태. */
  async analyze(
    file: File | Blob,
    options: SubmitOptions & WaitOptions = {},
  ): Promise<VisionJob> {
    const { jobId } = await this.submit(file, options);
    return this.waitFor(jobId, options);
  }

  // ── internals ────────────────────────────────────────────

  private headers(): Record<string, string> {
    const h: Record<string, string> = {};
    if (this.opts.apiKey) h['X-API-Key'] = this.opts.apiKey;
    if (this.opts.bearerToken) h['Authorization'] = `Bearer ${this.opts.bearerToken}`;
    if (this.opts.tenantId) h['X-Tenant-Id'] = this.opts.tenantId;
    return h;
  }

  private async fetchPost(url: string, form: FormData, signal?: AbortSignal): Promise<unknown> {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), this.opts.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    const onAbort = () => ctrl.abort();
    signal?.addEventListener('abort', onAbort);
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: this.headers(), // Content-Type 은 브라우저가 boundary 와 함께 채운다
        body: form,
        signal: ctrl.signal,
      });
      return await this.parse(res);
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /** fetch 는 업로드 진행률을 주지 않는다 — 진행률이 필요할 때만 XHR 을 쓴다. */
  private xhrPost(url: string, form: FormData, options: SubmitOptions): Promise<unknown> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url);
      for (const [k, v] of Object.entries(this.headers())) xhr.setRequestHeader(k, v);
      xhr.timeout = this.opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;

      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) options.onUploadProgress?.(e.loaded / e.total);
      };
      xhr.onload = () => {
        let parsed: any;
        try {
          parsed = JSON.parse(xhr.responseText);
        } catch {
          reject(new VisionError(`invalid JSON response (${xhr.status})`, xhr.status, xhr.responseText));
          return;
        }
        if (xhr.status >= 200 && xhr.status < 300 && parsed?.success !== false) {
          resolve(parsed.data ?? parsed);
        } else {
          reject(new VisionError(parsed?.error ?? `request failed (${xhr.status})`, xhr.status, parsed));
        }
      };
      xhr.onerror = () => reject(new VisionError('network error', 0));
      xhr.ontimeout = () => reject(new VisionError('request timeout', 0));
      xhr.onabort = () => reject(new VisionError('aborted', 0));
      options.signal?.addEventListener('abort', () => xhr.abort());

      xhr.send(form);
    });
  }

  private async parse(res: Response): Promise<unknown> {
    const text = await res.text();
    let body: any;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      throw new VisionError(`invalid JSON response (${res.status})`, res.status, text);
    }
    if (!res.ok || body?.success === false) {
      throw new VisionError(body?.error ?? `request failed (${res.status})`, res.status, body);
    }
    return body?.data ?? body;
  }
}

function toJob(d: Record<string, any>): VisionJob {
  return {
    jobId: d.job_id,
    status: d.status,
    filename: d.filename,
    mimeType: d.mime_type,
    sizeBytes: d.size_bytes,
    durationSec: d.duration_sec,
    frameCount: d.frame_count,
    result: d.result,
    resultJson: d.result_json,
    elapsedSec: d.elapsed_sec,
    errorMessage: d.error_message,
    createdAt: d.created_at,
    startedAt: d.started_at,
    finishedAt: d.finished_at,
  };
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(new Error('aborted'));
    });
  });
}
