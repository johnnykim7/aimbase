/** 기능에 보내는 알림. 한 기능의 처리 오류가 다른 기능이나 위젯 본체를 멈추지 않게 한다. */
export class Emitter<M extends object> {
  private readonly handlers = new Map<keyof M, Array<(payload: never) => void>>();

  on<K extends keyof M>(name: K, cb: (payload: M[K]) => void): () => void {
    const list = this.handlers.get(name) ?? [];
    list.push(cb as (payload: never) => void);
    this.handlers.set(name, list);
    return () => {
      const now = this.handlers.get(name) ?? [];
      this.handlers.set(name, now.filter((h) => h !== (cb as unknown)));
    };
  }

  emit<K extends keyof M>(name: K, payload: M[K]): void {
    for (const h of [...(this.handlers.get(name) ?? [])]) {
      try {
        (h as (p: M[K]) => void)(payload);
      } catch (e) {
        console.error(`[aimbase-chat] ${String(name)} handler failed`, e);
      }
    }
  }

  clear(): void {
    this.handlers.clear();
  }
}
