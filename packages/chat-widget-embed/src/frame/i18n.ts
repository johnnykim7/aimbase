/**
 * 화면 언어와 문구표(CR-177, BIZ-160, BIZ-161).
 * 받는 언어는 ko, en 둘이다. 그 밖의 값은 없는 것으로 본다.
 */
export type Locale = "ko" | "en";

export const LOCALES: readonly Locale[] = ["ko", "en"];
export const DEFAULT_LOCALE: Locale = "ko";
const LOCALE_STORAGE_KEY = "aimbase-chat:locale";

/** 붙이는 앱이 주는 글. 글 하나이거나 언어별 값이다. */
export type LocalizedText = string | Partial<Record<Locale, string>>;
export type LocalizedList = string[] | Partial<Record<Locale, string[]>>;

export interface StringPack<S> {
  readonly ko: S;
  readonly en: S;
}

/** ko 와 en 의 키와 값 모양이 다르면 타입 검사에서 막힌다. */
export function defineStrings<S extends object>(pack: { ko: S; en: NoInfer<S> }): StringPack<S> {
  return pack;
}

/** en-US, EN_us 처럼 지역이 붙은 값은 앞 언어만 본다. 받지 않는 언어면 null. */
export function normalizeLocale(value: unknown): Locale | null {
  if (typeof value !== "string") return null;
  const lang = value.trim().split(/[-_]/, 1)[0].toLowerCase();
  return (LOCALES as readonly string[]).includes(lang) ? (lang as Locale) : null;
}

export function readStoredLocale(): Locale | null {
  try {
    return normalizeLocale(localStorage.getItem(LOCALE_STORAGE_KEY));
  } catch {
    return null;
  }
}

export function storeLocale(locale: Locale): void {
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // 저장소를 막은 브라우저에서는 이번 화면에서만 적용된다.
  }
}

/** 지금 언어 값, 없으면 한국어 값, 그것도 없으면 처음 값. */
export function pickText(value: LocalizedText | undefined, locale: Locale): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value === "string") return value;
  return value[locale] ?? value[DEFAULT_LOCALE] ?? Object.values(value).find((v) => typeof v === "string");
}

export function pickList(value: LocalizedList | undefined, locale: Locale): string[] {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  return value[locale] ?? value[DEFAULT_LOCALE] ?? Object.values(value).find((v) => Array.isArray(v)) ?? [];
}

/** 읽을 때마다 지금 언어의 값을 돌려주는 보기. */
export function stringsView<S extends object>(pack: StringPack<S>, locale: () => Locale): S {
  return new Proxy({} as S, {
    get: (_target, key) => (pack[locale()] as Record<string | symbol, unknown>)[key],
  });
}
