import type { AuthResolver, TokenResponse } from "./types";

/**
 * 토큰 수명주기 관리 — 만료 5분 전 자동 재호출.
 * 메모리 내 클로저 보관 (localStorage 사용 금지 — XSS 노출).
 */
export class TokenStore {
  private token: TokenResponse | null = null;
  private expiresAtMs = 0;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private onExpiring?: () => void;

  constructor(private readonly resolver: AuthResolver, onExpiring?: () => void) {
    this.onExpiring = onExpiring;
  }

  async getToken(): Promise<string> {
    if (!this.token || Date.now() >= this.expiresAtMs - 30_000) {
      await this.refresh();
    }
    return this.token!.token;
  }

  /** 현재 토큰이 특정 scope 를 포함하는지 (지연 초기화됨 — 미초기화면 false) */
  hasScope(scope: string): boolean {
    return !!this.token?.scopes?.includes(scope);
  }

  /** 토큰 메타 미리 로드 (getToken 호출로 초기화만 수행) */
  async ensureLoaded(): Promise<void> {
    if (!this.token) await this.refresh();
  }

  private async refresh(): Promise<void> {
    const res = await this.resolver();
    this.token = res;
    this.expiresAtMs =
      typeof res.expires_at === "number"
        ? res.expires_at
        : new Date(res.expires_at).getTime();

    this.scheduleRefresh(res.refresh_after);
  }

  private scheduleRefresh(refreshAfterSec: number): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    const delayMs = Math.max(10_000, refreshAfterSec * 1000);
    this.refreshTimer = setTimeout(() => {
      try {
        this.onExpiring?.();
      } catch {
        // callback 실패는 무시
      }
      // 실패해도 다음 getToken() 이 다시 fetch
      this.refresh().catch(() => {});
    }, delayMs);
  }

  destroy(): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = null;
    this.token = null;
  }
}
