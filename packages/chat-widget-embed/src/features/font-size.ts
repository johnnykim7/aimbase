import type { WidgetFeature } from "../frame/context";
import { defineStrings } from "../frame/i18n";
import type { FontSize } from "../types";

const S = defineStrings({
  ko: { fontSize: "글자 크기", small: "작게", medium: "보통", large: "크게" },
  en: { fontSize: "Text size", small: "Small", medium: "Medium", large: "Large" },
});

const SIZES: FontSize[] = ["small", "medium", "large"];

/** 더 보기 메뉴의 글자 크기 줄(CR-177 T3-3 화면 컨셉 3). 누른 뒤에도 메뉴는 열어 둔다. */
export const fontSize: WidgetFeature = {
  id: "font-size",
  install(ctx) {
    const t = ctx.strings(S);
    ctx.ui.moreMenu.addRow({
      id: "font-size",
      order: 10,
      group: 1,
      render: () => {
        const row = document.createElement("div");
        row.className = "row";
        const label = document.createElement("span");
        label.textContent = t.fontSize;
        const cells = document.createElement("span");
        cells.className = "fsz";
        const buttons = new Map<FontSize, HTMLButtonElement>();
        const mark = (current: FontSize) => buttons.forEach((b, size) => b.classList.toggle("on", size === current));
        for (const size of SIZES) {
          const b = document.createElement("button");
          b.type = "button";
          b.className = "chipb";
          b.textContent = t[size];
          b.addEventListener("click", () => {
            ctx.ui.setFontSize(size);
            mark(size);
          });
          buttons.set(size, b);
          cells.appendChild(b);
        }
        mark(ctx.ui.fontSize());
        row.append(label, cells);
        return row;
      },
    });
  },
};
