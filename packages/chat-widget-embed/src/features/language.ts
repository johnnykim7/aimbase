import type { WidgetFeature } from "../frame/context";
import { defineStrings, LOCALES, type Locale } from "../frame/i18n";
import { icon } from "../icons";

/** 언어 이름은 고르는 사람이 알아보도록 두 문구표 모두 그 언어 자신의 이름으로 쓴다. */
const S = defineStrings({
  ko: { language: "화면 언어", names: { ko: "한국어", en: "English" } as Record<Locale, string> },
  en: { language: "Language", names: { ko: "한국어", en: "English" } as Record<Locale, string> },
});

const CSS = `
.lang-row .ml { flex: 1; }
.lang-row .lang-chev { display: inline-flex; color: var(--aimbase-muted); transition: transform .15s; }
.lang-row[aria-expanded="true"] .lang-chev { transform: rotate(180deg); }
.lang-sub { padding: 2px 0 4px 26px; }
.lang-sub[hidden] { display: none; }
.lang-sub .mi .mk { visibility: hidden; }
.lang-sub .mi.on .mk { visibility: visible; }
`;

/** 더 보기 메뉴의 화면 언어 줄(CR-177 T3-3 화면 컨셉 3, 5). 다른 언어를 고르면 메뉴를 닫고 바로 바꾼다. */
export const language: WidgetFeature = {
  id: "language",
  css: CSS,
  install(ctx) {
    const t = ctx.strings(S);
    ctx.ui.moreMenu.addRow({
      id: "language",
      order: 20,
      group: 1,
      render: () => {
        const wrap = document.createElement("div");
        const row = document.createElement("button");
        row.type = "button";
        row.className = "mi lang-row";
        row.setAttribute("aria-expanded", "false");
        const ic = document.createElement("span");
        ic.className = "mic";
        ic.innerHTML = icon("globe");
        const label = document.createElement("span");
        label.className = "ml";
        label.textContent = t.language;
        const hint = document.createElement("span");
        hint.className = "mh";
        hint.textContent = t.names[ctx.locale()];
        const chev = document.createElement("span");
        chev.className = "lang-chev";
        chev.innerHTML = icon("cdown", 14);
        row.append(ic, label, hint, chev);

        const sub = document.createElement("div");
        sub.className = "lang-sub";
        sub.hidden = true;
        for (const locale of LOCALES) {
          const b = document.createElement("button");
          b.type = "button";
          b.className = "mi";
          b.setAttribute("role", "menuitemradio");
          b.setAttribute("aria-checked", String(locale === ctx.locale()));
          b.classList.toggle("on", locale === ctx.locale());
          const name = document.createElement("span");
          name.className = "ml";
          name.textContent = t.names[locale];
          name.lang = locale;
          const mk = document.createElement("span");
          mk.className = "mk";
          mk.innerHTML = icon("check", 14);
          b.append(name, mk);
          b.addEventListener("click", () => {
            ctx.ui.moreMenu.close();
            if (locale !== ctx.locale()) ctx.setLocale(locale);
          });
          sub.appendChild(b);
        }

        row.addEventListener("click", () => {
          sub.hidden = !sub.hidden;
          row.setAttribute("aria-expanded", String(!sub.hidden));
        });
        wrap.append(row, sub);
        return wrap;
      },
    });
  },
};
