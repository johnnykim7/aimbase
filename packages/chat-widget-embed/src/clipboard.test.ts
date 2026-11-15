import { afterEach, describe, expect, it, vi } from "vitest";
import { copyText } from "./clipboard";

interface Box {
  children: Field[];
  appendChild(n: Field): Field;
}

interface Field {
  value: string;
  style: Record<string, string>;
  parent: Box | null;
  setAttribute(): void;
  select(): void;
  remove(): void;
}

function box(): Box {
  return {
    children: [],
    appendChild(n) {
      this.children.push(n);
      n.parent = this;
      return n;
    },
  };
}

class FakeShadowRoot {
  children: Field[] = [];
  appendChild(n: Field): Field {
    this.children.push(n);
    n.parent = this;
    return n;
  }
  getRootNode(): FakeShadowRoot {
    return this;
  }
}

function field(): Field {
  return {
    value: "",
    style: {},
    parent: null,
    setAttribute() {},
    select() {},
    remove() {
      if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this);
      this.parent = null;
    },
  };
}

function page(onCopy: (body: Box) => void) {
  const body = box();
  vi.stubGlobal("window", { isSecureContext: false });
  vi.stubGlobal("navigator", {});
  vi.stubGlobal("ShadowRoot", FakeShadowRoot);
  vi.stubGlobal("HTMLElement", class {});
  vi.stubGlobal("document", {
    activeElement: null,
    body,
    createElement: () => field(),
    execCommand: () => {
      onCopy(body);
      return true;
    },
  });
  return body;
}

describe("TC-CHT-155 모달 위 위젯 복사", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("브라우저 복사 기능이 없으면 숨긴 칸을 누른 단추가 있는 위젯 그림자 영역 안에 두고 복사한다", async () => {
    let inShadow = "";
    let inBody = 0;
    const shadow = new FakeShadowRoot();
    page((body) => {
      inShadow = shadow.children[0]?.value ?? "";
      inBody = body.children.length;
    });
    const button = { getRootNode: () => shadow } as unknown as Node;

    expect(await copyText("답 본문", button)).toBe(true);
    expect(inShadow).toBe("답 본문");
    expect(inBody).toBe(0);
    expect(shadow.children).toHaveLength(0);
  });

  it("위젯 밖에서 부르면 지금처럼 페이지 몸통에 둔다", async () => {
    let inBody = "";
    page((body) => {
      inBody = body.children[0]?.value ?? "";
    });

    expect(await copyText("글")).toBe(true);
    expect(inBody).toBe("글");
  });
});
