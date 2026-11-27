import type { TokenResponse } from "../types";

/**
 * 붙이는 앱 BFF 의 위젯 토큰 주소(token-endpoint)에 쿠키를 실어 묻는다. <aimbase-chat> 과 <aimbase-chat-frame> 이 같이 쓴다.
 * Aimbase ApiResponse 는 { data: { token, expires_at, refresh_after, scopes } } 이고, BFF 가 벗겨 줘도 받는다.
 */
export async function fetchWidgetToken(endpoint: string): Promise<TokenResponse> {
  const res = await fetch(endpoint, { method: "POST", credentials: "include" });
  if (!res.ok) throw new Error(`token endpoint ${res.status}`);
  const data = (await res.json()) as { data?: Record<string, unknown>; token?: string };
  return (data.data ?? data) as unknown as TokenResponse;
}

/** postMessage 로 넘길 수 있게 토큰 응답의 칸만 남긴다. */
export function plainToken(t: TokenResponse): TokenResponse {
  return {
    token: t.token,
    expires_at: t.expires_at,
    refresh_after: t.refresh_after,
    ...(Array.isArray(t.scopes) ? { scopes: t.scopes.filter((s) => typeof s === "string") } : {}),
  };
}
