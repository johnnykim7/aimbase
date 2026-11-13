/**
 * 위젯이 뜰 때 받는 것(토큰, 위젯 설정, 기능의 첫 조회)이 끝날 때까지 질문 보내기를 기다리게 한다(CR-188).
 * 설정을 받기 전에 보낸 질문은 파일 만들기와 고른 모델처럼 설정에 따라 싣는 칸 없이 나갔다.
 */

/**
 * 설정 조회와 선호 조회에는 시한이 없어서 서버가 늦거나 멈추면 질문이 멈춰 있게 된다.
 * 이 시간을 넘기면 그때까지 받은 것으로 보낸다. 받지 못한 칸은 서버 기본값으로 답한다.
 */
export const FIRST_SEND_WAIT_MS = 10_000;

export class StartupGate {
  private readonly holds: Promise<unknown>[] = [];
  private opened = false;
  private release!: () => void;
  private readonly done = new Promise<void>((resolve) => (this.release = resolve));

  /** 뜰 때 할 일을 더한다. 열린 뒤에 더한 것은 기다리지 않는다 */
  hold(task: Promise<unknown>): void {
    if (!this.opened) this.holds.push(task);
  }

  /** 건 일이 모두 끝나면(실패도 끝난 것으로 본다) 연다. 기다리는 동안 새로 건 일도 기다린다 */
  async finish(): Promise<void> {
    let seen = 0;
    while (seen < this.holds.length) {
      const batch = this.holds.slice(seen);
      seen = this.holds.length;
      await Promise.allSettled(batch);
    }
    this.opened = true;
    this.release();
  }

  isOpen(): boolean {
    return this.opened;
  }

  /** 열릴 때까지, 길어도 limitMs 까지 기다린다 */
  wait(limitMs: number): Promise<void> {
    if (this.opened) return Promise.resolve();
    return new Promise((resolve) => {
      const timer = setTimeout(resolve, limitMs);
      void this.done.then(() => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}
