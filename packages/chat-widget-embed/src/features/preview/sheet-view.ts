import type { SheetData } from "./model";
import type { PreviewTexts } from "./strings";

/** 표를 그린 칸을 돌려준다. 선택 알림은 이 칸 안의 선택만 본다. */
export function renderSheets(body: HTMLElement, sheets: SheetData[], t: PreviewTexts): HTMLElement {
  const wrap = document.createElement("div");
  wrap.className = "pv-sheet";
  const tabs = document.createElement("div");
  tabs.className = "pv-tabs";
  tabs.setAttribute("role", "tablist");
  const notice = document.createElement("div");
  notice.className = "pv-notice";
  const scroller = document.createElement("div");
  scroller.className = "pv-sheet-scroll";
  scroller.tabIndex = 0;

  const show = (index: number) => {
    const sheet = sheets[index];
    tabs.querySelectorAll("button").forEach((b, i) => {
      b.classList.toggle("on", i === index);
      b.setAttribute("aria-selected", String(i === index));
    });
    const cols = sheet.rows.reduce((m, r) => Math.max(m, r.length), 0);
    notice.hidden = !sheet.truncated;
    notice.textContent = sheet.truncated ? t.truncated(sheet.rows.length, cols) : "";
    scroller.replaceChildren(table(sheet.rows, cols));
    scroller.scrollTo(0, 0);
  };

  sheets.forEach((sheet, i) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "pv-tab";
    b.setAttribute("role", "tab");
    b.textContent = sheet.name;
    b.addEventListener("click", () => show(i));
    tabs.appendChild(b);
  });
  tabs.hidden = sheets.length < 2;

  wrap.append(tabs, notice, scroller);
  body.appendChild(wrap);
  if (sheets.length) show(0);
  return scroller;
}

/** 첫 행은 머리로 고정하고 맨 왼쪽에 행 번호를 둔다. 칸 수가 모자란 행은 빈칸으로 채운다. */
function table(rows: string[][], cols: number): HTMLTableElement {
  const tableEl = document.createElement("table");
  tableEl.className = "pv-table";
  if (!rows.length) return tableEl;
  const head = document.createElement("thead");
  head.appendChild(row(rows[0], cols, 1, "th"));
  const bodyEl = document.createElement("tbody");
  for (let i = 1; i < rows.length; i++) bodyEl.appendChild(row(rows[i], cols, i + 1, "td"));
  tableEl.append(head, bodyEl);
  return tableEl;
}

function row(cells: string[], cols: number, number: number, tag: "th" | "td"): HTMLTableRowElement {
  const tr = document.createElement("tr");
  const rn = document.createElement("th");
  rn.className = "pv-rn";
  rn.textContent = String(number);
  tr.appendChild(rn);
  for (let c = 0; c < cols; c++) {
    const cell = document.createElement(tag);
    cell.textContent = cells[c] ?? "";
    tr.appendChild(cell);
  }
  return tr;
}
