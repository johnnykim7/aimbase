import { describe, expect, it } from "vitest";
import { StreamingSpeech, chunkSpeech, splitLong, splitSentences, toSpeechText } from "./speech-text";

const PHRASES = { code: "코드는 화면에서 확인해 주세요.", table: "표는 화면에서 확인해 주세요." };

describe("TC-CHT-128 읽을 글 만들기", () => {
  it("제목, 굵게, 목록, 링크, 그림, 줄 안 코드는 기호 없이 글만 남기고 링크는 보이는 글만 읽는다", () => {
    const md = [
      "## 오늘 할 일",
      "**중요**한 것은 *세 가지*입니다.",
      "- 첫째, [출고 화면](https://example.com/out)을 엽니다.",
      "1. 둘째, `ORD-001` 주문을 봅니다.",
      "> 인용한 말입니다.",
      "![흐름도](https://example.com/a.png)",
      "snake_case_name 은 그대로 둡니다.",
    ].join("\n");

    expect(toSpeechText(md, PHRASES)).toBe([
      "오늘 할 일",
      "중요한 것은 세 가지입니다.",
      "첫째, 출고 화면을 엽니다.",
      "둘째, ORD-001 주문을 봅니다.",
      "인용한 말입니다.",
      "snake_case_name 은 그대로 둡니다.",
    ].join("\n"));
  });

  it("코드 묶음 둘이 이어지면 대신 읽는 줄은 한 번이다", () => {
    const md = "설명입니다.\n```java\nint a = 1;\n```\n\n```sql\nSELECT 1;\n```\n끝입니다.";
    expect(toSpeechText(md, PHRASES)).toBe(`설명입니다.\n${PHRASES.code}\n끝입니다.`);
  });

  it("표는 한 줄로 바꾼다", () => {
    const md = "결과입니다.\n| 이름 | 수량 |\n|---|---|\n| 사과 | 3 |\n합계는 3 입니다.";
    expect(toSpeechText(md, PHRASES)).toBe(`결과입니다.\n${PHRASES.table}\n합계는 3 입니다.`);
  });

  it("코드만 있는 응답은 대신 읽는 줄뿐이고, 기호만 있는 응답은 빈 글이다", () => {
    expect(toSpeechText("```\nls -al\n```", PHRASES)).toBe(PHRASES.code);
    expect(toSpeechText("---\n\n![그림](a.png)\n", PHRASES)).toBe("");
  });
});

describe("TC-CHT-129 조각 나누기", () => {
  it("첫 조각은 한 문장이고 나머지는 상한 이하로 문장을 모은다", () => {
    const sentence = (n: number) => `${"가".repeat(138)}${n}.`;
    const text = [1, 2, 3, 4, 5].map(sentence).join(" ");
    expect(text.length).toBeGreaterThanOrEqual(700);

    const chunks = chunkSpeech(text, 300);

    expect(chunks[0]).toBe(sentence(1));
    chunks.forEach((c) => expect(c.length).toBeLessThanOrEqual(300));
    expect(chunks.join(" ")).toBe(text);
    expect(chunks.slice(1)).toEqual([`${sentence(2)} ${sentence(3)}`, `${sentence(4)} ${sentence(5)}`]);
  });

  it("상한보다 긴 한 문장은 쉼표나 띄어쓰기 자리에서 자른다", () => {
    const withComma = `${"나".repeat(200)}, ${"다".repeat(150)} ${"라".repeat(100)}.`;
    expect(splitLong(withComma, 300)).toEqual([`${"나".repeat(200)},`, `${"다".repeat(150)} ${"라".repeat(100)}.`]);

    const withSpace = `${"가".repeat(200)} ${"나".repeat(200)}`;
    expect(splitLong(withSpace, 300)).toEqual(["가".repeat(200), "나".repeat(200)]);

    expect(splitLong("가".repeat(650), 300).map((p) => p.length)).toEqual([300, 300, 50]);
  });

  it("소수점과 줄바꿈을 구별한다", () => {
    expect(splitSentences("무게는 3.5 kg 입니다. 다음\n줄입니다")).toEqual(["무게는 3.5 kg 입니다.", "다음", "줄입니다"]);
  });

  it("응답이 오는 중 코드 묶음이 열리면 닫힐 때까지 그 뒤를 조각으로 내지 않는다", () => {
    const s = new StreamingSpeech(300, PHRASES);
    expect(s.push("안녕하세요. 설명을 시작합")).toEqual(["안녕하세요."]);
    expect(s.push("안녕하세요. 설명을 시작합니다.\n```java\nint a")).toEqual(["설명을 시작합니다."]);
    expect(s.push("안녕하세요. 설명을 시작합니다.\n```java\nint a = 1;\n")).toEqual([]);
    expect(s.push("안녕하세요. 설명을 시작합니다.\n```java\nint a = 1;\n```\n다 됐습니다.")).toEqual([`${PHRASES.code} 다 됐습니다.`]);
    expect(s.finish("안녕하세요. 설명을 시작합니다.\n```java\nint a = 1;\n```\n다 됐습니다.")).toEqual([]);
  });

  it("끝에 걸린 표는 닫힐 때까지 기다리고, 응답이 끝나면 남은 것을 모두 낸다", () => {
    const s = new StreamingSpeech(300, PHRASES);
    expect(s.push("결과입니다.\n| 이름 | 수량 |\n|---|---|\n")).toEqual(["결과입니다."]);
    expect(s.push("결과입니다.\n| 이름 | 수량 |\n|---|---|\n| 사과 | 3 |\n")).toEqual([]);
    expect(s.finish("결과입니다.\n| 이름 | 수량 |\n|---|---|\n| 사과 | 3 |\n합계는 3 입니다")).toEqual([`${PHRASES.table} 합계는 3 입니다`]);
  });

  it("덜 닫힌 링크가 있는 마지막 줄은 기다린다", () => {
    const s = new StreamingSpeech(300, PHRASES);
    expect(s.push("자세한 것은 [안내서](https://exa")).toEqual([]);
    expect(s.push("자세한 것은 [안내서](https://example.com)를 보세요. 그리고")).toEqual(["자세한 것은 안내서를 보세요."]);
  });
});
