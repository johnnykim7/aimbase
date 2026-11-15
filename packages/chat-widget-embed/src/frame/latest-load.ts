/**
 * CR-194: 붙이는 앱이 연결 속성을 하나씩 바꾸면 위젯 설정 조회가 겹친다. 늦게 온 앞 조회(앞 연결이나 연결 없음)가
 * 지금 연결의 설정을 덮으면 그 연결이 받지 않는 모델로 묻게 되어 서버가 거절했다.
 * 마지막에 건 조회만 결과를 쓰고, 그 조회가 끝날 때까지 질문은 기다린다.
 */
export class LatestLoad {
  private seq = 0;
  private current: Promise<void> | null = null;

  /** 조회를 건다. task 는 결과를 쓰기 전에 isLatest 로 그 사이 다른 조회가 걸렸는지 본다 */
  run(task: (isLatest: () => boolean) => Promise<void>): Promise<void> {
    const mine = ++this.seq;
    const p = task(() => mine === this.seq);
    this.current = p;
    const clear = () => {
      if (this.current === p) this.current = null;
    };
    p.then(clear, clear);
    return p;
  }

  /** 지금까지 건 조회 뒤에 다른 조회가 걸리지 않았는가. 다른 길(화면 언어)로 받은 결과를 쓸지 볼 때 쓴다 */
  mark(): () => boolean {
    const mine = this.seq;
    return () => mine === this.seq;
  }

  busy(): boolean {
    return this.current !== null;
  }

  /** 마지막에 건 조회가 끝날 때까지(실패도 끝으로 본다), 길어도 limitMs 까지. 기다리는 사이 걸린 조회도 기다린다 */
  settled(limitMs: number): Promise<void> {
    const all = (async () => {
      while (this.current) {
        const p = this.current;
        await p.catch(() => undefined);
        if (this.current === p) break;
      }
    })();
    return new Promise((resolve) => {
      const timer = setTimeout(resolve, limitMs);
      void all.then(() => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}
