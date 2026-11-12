import { svgIcon } from "./icons";

/**
 * 첨부 파일의 종류 묶음(CR-183). 받는 확장자 목록과 묶음별 크기 상한은 위젯 설정 조회 응답에서 받고,
 * 여기서는 확장자가 어느 묶음인지만 안다. 묶음 이름은 그 응답의 max_bytes 열쇠와 같다.
 */
export type AttachmentGroup = "image" | "pdf" | "data" | "text";

const GROUP_BY_EXT: Readonly<Record<string, AttachmentGroup>> = {
  png: "image",
  jpg: "image",
  jpeg: "image",
  gif: "image",
  webp: "image",
  pdf: "pdf",
  xlsx: "data",
  xls: "data",
  csv: "data",
  tsv: "data",
  docx: "text",
  txt: "text",
  md: "text",
};

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf(".");
  return dot < 0 || dot === name.length - 1 ? "" : name.slice(dot + 1).toLowerCase();
}

/** 모르는 확장자면 null. 그때 크기는 서버가 본다. */
export function groupOf(name: string): AttachmentGroup | null {
  return GROUP_BY_EXT[extensionOf(name)] ?? null;
}

const IMAGE = '<rect width="18" height="18" x="3" y="3" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.1-3.1a2 2 0 0 0-2.8 0L6 21"/>';
const TABLE = '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 9h18"/><path d="M3 15h18"/><path d="M9 9v12"/><path d="M15 9v12"/>';
const DOCUMENT = '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/>';
const OTHER = '<path d="m21.4 11.1-9.2 9.2a6 6 0 0 1-8.5-8.5l8.6-8.6a4 4 0 0 1 5.7 5.7l-8.6 8.6a2 2 0 0 1-2.8-2.8l8.5-8.5"/>';

/** 묶음 아이콘. 데이터 파일은 표, PDF 와 워드, 텍스트는 문서. */
export function groupIcon(group: AttachmentGroup | null, size: number): string {
  switch (group) {
    case "image": return svgIcon(IMAGE, size);
    case "data": return svgIcon(TABLE, size);
    case "pdf":
    case "text": return svgIcon(DOCUMENT, size);
    default: return svgIcon(OTHER, size);
  }
}

/** 칩에 보이는 크기. 1MB 미만은 KB. */
export function formatSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))}KB`;
  return `${Number((bytes / 1024 / 1024).toFixed(1))}MB`;
}

/** 상한 바이트를 MB 로. 1MB 미만 상한도 0 으로 보이지 않게 소수 한 자리까지. */
export function limitMb(bytes: number): number {
  return Number((bytes / 1024 / 1024).toFixed(1));
}
