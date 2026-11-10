/**
 * CR-131: 경량 XLSX writer.
 *
 * SheetJS(xlsx) npm 배포판은 write-only 시나리오에도 read 경로 취약점(Prototype
 * Pollution / ReDoS, audit high)이 딸려와 소비앱 보안 검사를 통과 못 하는 경우가 있다.
 * 표 → 시트 1개 export 는 OOXML 스펙이 단순하므로(4개 XML), 취약점 0 · 번들 ~10KB(fflate)
 * 로 정석 품질의 .xlsx 를 직접 생성한다. Excel/Numbers/LibreOffice 모두 정상 인식.
 *
 * fflate 는 lazy dynamic import — [엑셀] 버튼을 누를 때만 로드된다.
 */

/** XML 특수문자 이스케이프 (셀 텍스트 · sharedStrings 공용). */
function escapeXml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

/** 0-based 컬럼 인덱스 → 엑셀 열 이름 (0→A, 26→AA). */
function colName(idx: number): string {
  let n = idx;
  let name = "";
  do {
    name = String.fromCharCode(65 + (n % 26)) + name;
    n = Math.floor(n / 26) - 1;
  } while (n >= 0);
  return name;
}

/** 순수 숫자 셀인지 판별 (Excel 이 숫자로 인식하도록 t="n" 부여). */
function isNumeric(s: string): boolean {
  if (s.trim() === "") return false;
  // 천단위 콤마·통화기호가 섞이면 문자열로 둔다(표시 왜곡 방지).
  return /^-?\d+(\.\d+)?$/.test(s.trim());
}

/**
 * 2차원 문자열 배열(첫 행 헤더 포함 전체)을 .xlsx Blob 으로 변환.
 * @param rows  각 행이 셀 문자열 배열. 헤더 행 포함.
 * @param sheetName 시트 이름 (기본 "Sheet1")
 */
export async function tableToXlsxBlob(rows: string[][], sheetName = "Sheet1"): Promise<Blob> {
  const { zipSync, strToU8 } = await import("fflate");

  // sharedStrings 로 문자열 중복 제거 — 파일 크기·호환성 모두 유리.
  const sst: string[] = [];
  const sstIndex = new Map<string, number>();
  const internString = (s: string): number => {
    const found = sstIndex.get(s);
    if (found !== undefined) return found;
    const idx = sst.length;
    sst.push(s);
    sstIndex.set(s, idx);
    return idx;
  };

  const sheetRows: string[] = [];
  rows.forEach((row, r) => {
    const cells: string[] = [];
    row.forEach((raw, c) => {
      const ref = `${colName(c)}${r + 1}`;
      const val = raw ?? "";
      if (isNumeric(val)) {
        cells.push(`<c r="${ref}" t="n"><v>${val.trim()}</v></c>`);
      } else if (val === "") {
        // 빈 셀은 생략 가능하지만 참조 정합을 위해 빈 문자열 셀로 유지.
        cells.push(`<c r="${ref}"/>`);
      } else {
        const si = internString(val);
        cells.push(`<c r="${ref}" t="s"><v>${si}</v></c>`);
      }
    });
    sheetRows.push(`<row r="${r + 1}">${cells.join("")}</row>`);
  });

  const cols = rows.reduce((m, row) => Math.max(m, row.length), 0);
  const dim = rows.length ? `A1:${colName(Math.max(0, cols - 1))}${rows.length}` : "A1";

  const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>`;

  const rootRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;

  const safeSheetName = escapeXml(sheetName).slice(0, 31) || "Sheet1";
  const workbook = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${safeSheetName}" sheetId="1" r:id="rId1"/></sheets>
</workbook>`;

  const workbookRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>`;

  const worksheet = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<dimension ref="${dim}"/>
<sheetData>${sheetRows.join("")}</sheetData>
</worksheet>`;

  const sharedStrings = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sst.length}" uniqueCount="${sst.length}">
${sst.map((s) => `<si><t xml:space="preserve">${escapeXml(s)}</t></si>`).join("")}
</sst>`;

  // 최소 styles — 헤더 굵게(1번 폰트) 정도만. cellXfs 0=기본.
  const styles = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>`;

  const files: Record<string, Uint8Array> = {
    "[Content_Types].xml": strToU8(contentTypes),
    "_rels/.rels": strToU8(rootRels),
    "xl/workbook.xml": strToU8(workbook),
    "xl/_rels/workbook.xml.rels": strToU8(workbookRels),
    "xl/worksheets/sheet1.xml": strToU8(worksheet),
    "xl/sharedStrings.xml": strToU8(sharedStrings),
    "xl/styles.xml": strToU8(styles),
  };

  const zipped = zipSync(files, { level: 6 });
  // Uint8Array → Blob (fresh copy 로 안전한 ArrayBuffer 확보).
  return new Blob([zipped.slice()], {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  });
}
