import type { WidgetContext, WidgetFeature } from "../frame/context";
import { defineStrings } from "../frame/i18n";
import { svgIcon } from "../icons";

/**
 * + 메뉴 「이 화면 캡처」(CR-183, PRD-524). 브라우저 화면 공유로 지금 화면 한 장을 떠서 PNG 첨부로 넣고
 * 공유를 바로 멈춘다. 화면 공유를 지원하지 않거나 보안 연결이 아니면 항목을 숨긴다.
 */

const S = defineStrings({
  ko: {
    capture: "이 화면 캡처",
    failed: "화면을 캡처할 수 없습니다",
    fileName: (stamp: string) => `화면 캡처 ${stamp}.png`,
  },
  en: {
    capture: "Capture this screen",
    failed: "Couldn't capture the screen",
    fileName: (stamp: string) => `Screen capture ${stamp}.png`,
  },
});

/** 모델이 읽는 해상도. 긴 변이 이보다 크면 줄인다. */
const MAX_EDGE = 2000;

/** 첫 장면을 기다리는 최대 시간(ms). */
const FRAME_WAIT_MS = 500;

const CAPTURE_ICON =
  '<path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/><rect width="10" height="8" x="7" y="8" rx="1"/>';

function supported(): boolean {
  return (
    typeof window !== "undefined" &&
    window.isSecureContext &&
    typeof navigator.mediaDevices?.getDisplayMedia === "function"
  );
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

/** MMDD-HHmm, 화면 시각. */
function stamp(at: Date): string {
  return `${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}`;
}

/**
 * 선택 창에서 취소한 것인지. 브라우저는 취소와 거부를 모두 NotAllowedError 로 주고, 운영체제가 막았거나
 * 붙인 페이지가 화면 공유 권한을 주지 않은 경우만 사유 글로 구분된다.
 */
function cancelled(e: unknown): boolean {
  const err = e as { name?: string; message?: string } | null;
  if (err?.name === "AbortError") return true;
  return err?.name === "NotAllowedError" && !/system|policy/i.test(err.message ?? "");
}

type FrameVideo = HTMLVideoElement & { requestVideoFrameCallback?: (cb: () => void) => number };

async function grabFrame(stream: MediaStream): Promise<Blob> {
  const video = document.createElement("video") as FrameVideo;
  video.muted = true;
  video.playsInline = true;
  video.srcObject = stream;
  try {
    await video.play();
    // 첫 장면이 그려진 뒤에 그린다. 그 전에 그리면 빈 그림이 나온다. 화면이 멈춰 있으면 새 장면이 오지 않아
    // 콜백이 끝내 뜨지 않으므로 잠시 기다린 뒤에는 그대로 그린다.
    await new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, FRAME_WAIT_MS);
      video.requestVideoFrameCallback?.(() => {
        clearTimeout(timer);
        resolve();
      });
    });
    const w = video.videoWidth;
    const h = video.videoHeight;
    if (!w || !h) throw new Error("empty frame");
    const scale = Math.min(1, MAX_EDGE / Math.max(w, h));
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(w * scale);
    canvas.height = Math.round(h * scale);
    const g = canvas.getContext("2d");
    if (!g) throw new Error("no canvas");
    g.drawImage(video, 0, 0, canvas.width, canvas.height);
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("encode failed"))), "image/png"),
    );
  } finally {
    video.pause();
    video.srcObject = null;
  }
}

async function capture(ctx: WidgetContext, t: (typeof S)["ko"]): Promise<void> {
  let stream: MediaStream;
  try {
    // 지금 탭을 먼저 권한다. 이 값들을 모르는 브라우저는 무시한다.
    stream = await navigator.mediaDevices.getDisplayMedia({
      video: { displaySurface: "browser" },
      audio: false,
      preferCurrentTab: true,
      selfBrowserSurface: "include",
      surfaceSwitching: "exclude",
    } as DisplayMediaStreamOptions);
  } catch (e) {
    if (!cancelled(e)) ctx.ui.toast(t.failed);
    return;
  }
  try {
    const blob = await grabFrame(stream);
    await ctx.chat.attach(new File([blob], t.fileName(stamp(new Date())), { type: "image/png" }));
  } catch {
    ctx.ui.toast(t.failed);
  } finally {
    stream.getTracks().forEach((track) => track.stop());
  }
}

function install(ctx: WidgetContext): void {
  const t = ctx.strings(S);
  ctx.ui.plusMenu.addItem({
    id: "screen-capture",
    order: 30,
    group: 0,
    icon: svgIcon(CAPTURE_ICON, 16),
    label: () => t.capture,
    visible: supported,
    onSelect: () => void capture(ctx, t),
  });
}

export const screenCapture: WidgetFeature = {
  id: "screen-capture",
  install,
};
