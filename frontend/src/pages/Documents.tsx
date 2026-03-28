import { useEffect, useState } from "react";
import { FileText } from "lucide-react";
import { cn } from "@/lib/utils";
import { ActionButton } from "../components/common/ActionButton";
import { Badge } from "../components/common/Badge";
import { Page } from "../components/layout/Page";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { EmptyState } from "../components/common/EmptyState";
import { inputStyle } from "../components/common/FormField";
import { documentsApi } from "../api/documents";
import type { DocumentTemplate } from "../api/documents";

type Tab = "templates" | "generate";

const FORMAT_OPTIONS = ["pptx", "docx", "pdf", "xlsx", "csv", "html", "png", "jpg", "svg"];

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export default function Documents() {
  const [tab, setTab] = useState<Tab>("templates");
  const [templates, setTemplates] = useState<DocumentTemplate[]>([]);
  const [loading, setLoading] = useState(false);

  // 템플릿 등록 폼
  const [showForm, setShowForm] = useState(false);
  const [formMode, setFormMode] = useState<"file" | "code">("file");
  const [formName, setFormName] = useState("");
  const [formFormat, setFormFormat] = useState("pptx");
  const [formDesc, setFormDesc] = useState("");
  const [formCode, setFormCode] = useState("");
  const [formTags, setFormTags] = useState("");
  const [formFile, setFormFile] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadResult, setUploadResult] = useState("");

  // 렌더링
  const [renderTarget, setRenderTarget] = useState<DocumentTemplate | null>(null);
  const [renderVars, setRenderVars] = useState<Record<string, string>>({});
  const [renderFormat, setRenderFormat] = useState("");
  const [rendering, setRendering] = useState(false);

  // 직접 생성
  const [genCode, setGenCode] = useState("");
  const [genFormat, setGenFormat] = useState("pptx");
  const [generating, setGenerating] = useState(false);
  const [genResult, setGenResult] = useState<string>("");

  const loadTemplates = async () => {
    setLoading(true);
    try {
      const res = await documentsApi.listTemplates();
      setTemplates(res.data.data?.templates ?? []);
    } catch {
      setTemplates([]);
    }
    setLoading(false);
  };

  useEffect(() => { loadTemplates(); }, []);

  const handleSaveCode = async () => {
    if (!formName.trim() || !formCode.trim()) return;
    setSaving(true);
    try {
      await documentsApi.saveTemplate({
        name: formName,
        format: formFormat,
        templateType: "code",
        codeTemplate: formCode,
        description: formDesc,
        tags: formTags ? JSON.stringify(formTags.split(",").map(t => t.trim())) : "[]",
      });
      setShowForm(false);
      setFormName(""); setFormCode(""); setFormDesc(""); setFormTags("");
      loadTemplates();
    } catch (e) {
      console.error("Save failed:", e);
    }
    setSaving(false);
  };

  const handleUploadFile = async () => {
    if (!formFile || !formName.trim()) return;
    setSaving(true);
    setUploadResult("");
    try {
      const res = await documentsApi.uploadTemplate(
        formFile,
        formName,
        formDesc,
        formTags ? JSON.stringify(formTags.split(",").map(t => t.trim())) : "[]",
      );
      const data = res.data.data;
      if (data?.success) {
        setUploadResult(`업로드 완료: ${data.variable_count}개 변수 추출 (${(data.file_size_bytes / 1024).toFixed(1)} KB)`);
        setShowForm(false);
        setFormName(""); setFormFile(null); setFormDesc(""); setFormTags("");
        loadTemplates();
      } else {
        setUploadResult(`실패: ${data?.error ?? "알 수 없는 오류"}`);
      }
    } catch (e) {
      setUploadResult(`업로드 실패: ${e}`);
    }
    setSaving(false);
  };

  const handleDelete = async (id: string) => {
    if (!confirm("이 템플릿을 삭제하시겠습니까?")) return;
    try {
      await documentsApi.deleteTemplate(id);
      loadTemplates();
    } catch (e) {
      console.error("Delete failed:", e);
    }
  };

  const openRender = (tpl: DocumentTemplate) => {
    setRenderTarget(tpl);
    setRenderFormat("");
    const vars: Record<string, string> = {};
    (tpl.variables ?? []).forEach(v => { vars[v.name] = v.default_value ?? ""; });
    setRenderVars(vars);
  };

  const handleRender = async () => {
    if (!renderTarget) return;
    setRendering(true);
    try {
      const res = await documentsApi.renderDownload(renderTarget.id, renderVars, renderFormat);
      const contentDisp = res.headers["content-disposition"] ?? "";
      const match = contentDisp.match(/filename="?([^"]+)"?/);
      const outFmt = renderFormat || renderTarget.format;
      const filename = match?.[1] ?? `document.${outFmt}`;
      downloadBlob(res.data as Blob, filename);
      setGenResult(`${filename} 다운로드 완료`);
    } catch (e) {
      setGenResult(`렌더링 실패: ${e}`);
    }
    setRendering(false);
  };

  const handleGenerate = async () => {
    if (!genCode.trim()) return;
    setGenerating(true);
    setGenResult("");
    try {
      const res = await documentsApi.generateDownload(genCode, genFormat);
      const contentDisp = res.headers["content-disposition"] ?? "";
      const match = contentDisp.match(/filename="?([^"]+)"?/);
      const filename = match?.[1] ?? `generated.${genFormat}`;
      downloadBlob(res.data as Blob, filename);
      setGenResult(`${filename} (${((res.data as Blob).size / 1024).toFixed(1)} KB) 다운로드 완료`);
    } catch (e) {
      setGenResult(`생성 실패: ${e}`);
    }
    setGenerating(false);
  };

  return (
    <Page
      actions={
        tab === "templates" ? (
          <ActionButton variant="primary" onClick={() => setShowForm(!showForm)}>
            {showForm ? "취소" : "+ 템플릿 등록"}
          </ActionButton>
        ) : undefined
      }
    >
      {/* 탭 */}
      <div className="flex gap-2 mb-5">
        <button
          className={cn(
            "py-2 px-5 text-[13px] font-medium rounded-lg border cursor-pointer transition-colors",
            tab === "templates"
              ? "font-semibold text-primary bg-primary/10 border-primary"
              : "text-muted-foreground bg-transparent border-border hover:bg-accent"
          )}
          onClick={() => setTab("templates")}
        >
          템플릿 관리
        </button>
        <button
          className={cn(
            "py-2 px-5 text-[13px] font-medium rounded-lg border cursor-pointer transition-colors",
            tab === "generate"
              ? "font-semibold text-primary bg-primary/10 border-primary"
              : "text-muted-foreground bg-transparent border-border hover:bg-accent"
          )}
          onClick={() => setTab("generate")}
        >
          직접 생성
        </button>
      </div>

      {/* ── 템플릿 탭 ── */}
      {tab === "templates" && (
        <>
          <div className="mb-4">
            <div className="text-[13px] text-muted-foreground">
              {templates.length}개 템플릿
            </div>
          </div>

          {/* 등록 폼 */}
          {showForm && (
            <div className="bg-card border border-border rounded-xl p-5 mb-4">
              <div className="flex justify-between items-center mb-4">
                <div className="text-sm font-semibold text-foreground">
                  새 템플릿 등록
                </div>
                <div className="flex gap-1">
                  <button
                    className={cn(
                      "py-1 px-3.5 text-xs font-medium rounded-md border cursor-pointer transition-colors",
                      formMode === "file"
                        ? "text-primary bg-primary/10 border-primary font-semibold"
                        : "text-muted-foreground bg-transparent border-border"
                    )}
                    onClick={() => setFormMode("file")}
                  >
                    파일 업로드
                  </button>
                  <button
                    className={cn(
                      "py-1 px-3.5 text-xs font-medium rounded-md border cursor-pointer transition-colors",
                      formMode === "code"
                        ? "text-primary bg-primary/10 border-primary font-semibold"
                        : "text-muted-foreground bg-transparent border-border"
                    )}
                    onClick={() => setFormMode("code")}
                  >
                    코드 작성
                  </button>
                </div>
              </div>

              {/* 공통 필드 */}
              <div className={cn("grid gap-3 mb-3", formMode === "code" ? "grid-cols-2" : "grid-cols-1")}>
                <div>
                  <label className="block text-xs font-medium text-muted-foreground mb-1">이름 *</label>
                  <input style={{ ...inputStyle, width: "100%" }} value={formName} onChange={e => setFormName(e.target.value)} placeholder="입찰제안서" />
                </div>
                {formMode === "code" && (
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">포맷</label>
                    <select style={{ ...inputStyle, width: "100%" }} value={formFormat} onChange={e => setFormFormat(e.target.value)}>
                      {FORMAT_OPTIONS.map(f => <option key={f} value={f}>{f.toUpperCase()}</option>)}
                    </select>
                  </div>
                )}
              </div>
              <div className="mb-3">
                <label className="block text-xs font-medium text-muted-foreground mb-1">설명</label>
                <input style={{ ...inputStyle, width: "100%" }} value={formDesc} onChange={e => setFormDesc(e.target.value)} placeholder="템플릿 용도 설명" />
              </div>
              <div className="mb-3">
                <label className="block text-xs font-medium text-muted-foreground mb-1">태그 (쉼표 구분)</label>
                <input style={{ ...inputStyle, width: "100%" }} value={formTags} onChange={e => setFormTags(e.target.value)} placeholder="report, monthly" />
              </div>

              {/* 파일 업로드 모드 */}
              {formMode === "file" && (
                <>
                  <div className="mb-4">
                    <label className="block text-xs font-medium text-muted-foreground mb-1">PPTX / DOCX / PDF 파일 *</label>
                    <div
                      className={cn(
                        "border-2 border-dashed border-border rounded-lg p-6 text-center cursor-pointer transition-colors",
                        formFile ? "bg-primary/5" : "bg-accent hover:bg-muted"
                      )}
                      onClick={() => document.getElementById("tpl-file-input")?.click()}
                      onDragOver={e => e.preventDefault()}
                      onDrop={e => {
                        e.preventDefault();
                        const f = e.dataTransfer.files[0];
                        if (f) {
                          setFormFile(f);
                          if (!formName.trim()) setFormName(f.name.replace(/\.[^.]+$/, ""));
                        }
                      }}
                    >
                      <input
                        id="tpl-file-input"
                        type="file"
                        accept=".pptx,.docx,.pdf"
                        className="hidden"
                        onChange={e => {
                          const f = e.target.files?.[0];
                          if (f) {
                            setFormFile(f);
                            if (!formName.trim()) setFormName(f.name.replace(/\.[^.]+$/, ""));
                          }
                        }}
                      />
                      {formFile ? (
                        <div>
                          <div className="text-sm font-semibold text-foreground mb-1">{formFile.name}</div>
                          <div className="text-xs text-muted-foreground/40">{(formFile.size / 1024).toFixed(1)} KB</div>
                        </div>
                      ) : (
                        <div>
                          <div className="text-2xl mb-2">📎</div>
                          <div className="text-[13px] text-muted-foreground">클릭하거나 파일을 드래그하세요</div>
                          <div className="text-[11px] text-muted-foreground/40 mt-1">
                            {"PPTX, DOCX, PDF 파일 ({{변수명}} 플레이스홀더 자동 인식)"}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                  <ActionButton variant="primary" disabled={saving || !formName.trim() || !formFile} onClick={handleUploadFile}>
                    {saving ? "업로드 중..." : "파일 템플릿 등록"}
                  </ActionButton>
                  {uploadResult && (
                    <div className={cn("mt-2 text-xs font-mono", uploadResult.includes("실패") ? "text-destructive" : "text-success")}>
                      {uploadResult}
                    </div>
                  )}
                </>
              )}

              {/* 코드 작성 모드 */}
              {formMode === "code" && (
                <>
                  <div className="mb-4">
                    <label className="block text-xs font-medium text-muted-foreground mb-1">Python 코드 * (변수: {"{{변수명}}"} 형식)</label>
                    <textarea
                      style={{ ...inputStyle, width: "100%", minHeight: 200, resize: "vertical" }}
                      className="font-mono text-xs"
                      value={formCode}
                      onChange={e => setFormCode(e.target.value)}
                      placeholder={`from pptx import Presentation\nprs = Presentation()\nslide = prs.slides.add_slide(prs.slide_layouts[0])\nslide.shapes.title.text = "{{title}}"\nprs.save(OUTPUT_PATH)`}
                    />
                  </div>
                  <ActionButton variant="primary" disabled={saving || !formName.trim() || !formCode.trim()} onClick={handleSaveCode}>
                    {saving ? "저장 중..." : "코드 템플릿 저장"}
                  </ActionButton>
                </>
              )}
            </div>
          )}

          {/* 템플릿 목록 */}
          {loading ? (
            <LoadingSpinner />
          ) : templates.length === 0 ? (
            <EmptyState icon={<FileText className="size-6" />} title="등록된 템플릿이 없습니다" description="+ 템플릿 등록 버튼으로 새 템플릿을 추가하세요" />
          ) : (
            <div className="flex flex-col gap-3">
              {templates.map(tpl => (
                <div key={tpl.id} className="bg-card border border-border rounded-xl p-5">
                  <div className="flex justify-between items-start">
                    <div>
                      <div className="text-[15px] font-semibold text-foreground mb-1">
                        {tpl.name}
                      </div>
                      <div className="text-xs text-muted-foreground mb-2">
                        {tpl.description}
                      </div>
                      <div className="flex gap-1.5 flex-wrap items-center">
                        <Badge color="accent">{tpl.format.toUpperCase()}</Badge>
                        <Badge color="muted">{tpl.template_type}</Badge>
                        {(tpl.variables ?? []).map(v => (
                          <Badge key={v.name} color="warning">{`{{${v.name}}}`}</Badge>
                        ))}
                        {(tpl.tags ?? []).map(t => (
                          <span key={t} className="text-[10px] font-mono text-muted-foreground/40 bg-accent py-0.5 px-1.5 rounded">
                            {t}
                          </span>
                        ))}
                      </div>
                    </div>
                    <div className="flex gap-1.5 shrink-0">
                      <ActionButton variant="default" onClick={() => openRender(tpl)}>생성</ActionButton>
                      <ActionButton variant="danger" onClick={() => handleDelete(tpl.id)}>삭제</ActionButton>
                    </div>
                  </div>

                  {/* 렌더링 패널 */}
                  {renderTarget?.id === tpl.id && (
                    <div className="mt-4 p-4 bg-accent rounded-lg">
                      <div className="text-[13px] font-semibold text-foreground mb-3">
                        변수 입력
                      </div>
                      {(tpl.variables ?? []).length === 0 ? (
                        <div className="text-xs text-muted-foreground/40 mb-3">변수 없음 (코드 그대로 실행)</div>
                      ) : (
                        <div className="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-2.5 mb-3">
                          {(tpl.variables ?? []).map(v => (
                            <div key={v.name}>
                              <label className="block text-[11px] font-medium text-muted-foreground mb-1">
                                {v.name} {v.required && <span className="text-destructive">*</span>}
                              </label>
                              <input
                                style={{ ...inputStyle, width: "100%" }}
                                value={renderVars[v.name] ?? ""}
                                onChange={e => setRenderVars(prev => ({ ...prev, [v.name]: e.target.value }))}
                                placeholder={v.description || v.name}
                              />
                            </div>
                          ))}
                        </div>
                      )}
                      <div className="flex gap-2 items-center">
                        <select
                          style={{ ...inputStyle, minWidth: 120 }}
                          value={renderFormat}
                          onChange={e => setRenderFormat(e.target.value)}
                        >
                          <option value="">원본 ({tpl.format.toUpperCase()})</option>
                          {["pptx", "docx", "pdf"].filter(f => f !== tpl.format).map(f => (
                            <option key={f} value={f}>{f.toUpperCase()}</option>
                          ))}
                        </select>
                        <ActionButton variant="primary" disabled={rendering} onClick={handleRender}>
                          {rendering ? "생성 중..." : "다운로드"}
                        </ActionButton>
                        <ActionButton variant="default" onClick={() => setRenderTarget(null)}>닫기</ActionButton>
                      </div>
                      {genResult && (
                        <div className="mt-2 text-xs font-mono text-success">{genResult}</div>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {/* ── 직접 생성 탭 ── */}
      {tab === "generate" && (
        <div className="bg-card border border-border rounded-xl p-5">
          <div className="text-sm font-semibold text-foreground mb-4">
            코드로 문서 직접 생성
          </div>
          <div className="mb-3">
            <label className="block text-xs font-medium text-muted-foreground mb-1">출력 포맷</label>
            <select style={{ ...inputStyle, minWidth: 150 }} value={genFormat} onChange={e => setGenFormat(e.target.value)}>
              {FORMAT_OPTIONS.map(f => <option key={f} value={f}>{f.toUpperCase()}</option>)}
            </select>
          </div>
          <div className="mb-4">
            <label className="block text-xs font-medium text-muted-foreground mb-1">Python 코드 (OUTPUT_PATH 변수에 결과 저장)</label>
            <textarea
              style={{ ...inputStyle, width: "100%", minHeight: 300, resize: "vertical" }}
              className="font-mono text-xs"
              value={genCode}
              onChange={e => setGenCode(e.target.value)}
              placeholder={SAMPLE_CODES[genFormat] ?? `# ${genFormat.toUpperCase()} 생성 코드\n# OUTPUT_PATH 변수에 저장하세요`}
            />
          </div>
          <ActionButton variant="primary" disabled={generating || !genCode.trim()} onClick={handleGenerate}>
            {generating ? "생성 중..." : "문서 생성 & 다운로드"}
          </ActionButton>
          {genResult && (
            <div className={cn("mt-3 text-xs font-mono", genResult.includes("실패") ? "text-destructive" : "text-success")}>
              {genResult}
            </div>
          )}
        </div>
      )}
    </Page>
  );
}

const SAMPLE_CODES: Record<string, string> = {
  pptx: `from pptx import Presentation
from pptx.util import Inches, Pt

prs = Presentation()
slide = prs.slides.add_slide(prs.slide_layouts[0])
slide.shapes.title.text = "제목"
slide.placeholders[1].text = "내용"
prs.save(OUTPUT_PATH)`,
  docx: `from docx import Document

doc = Document()
doc.add_heading("제목", 0)
doc.add_paragraph("본문 내용을 여기에 작성합니다.")
doc.save(OUTPUT_PATH)`,
  xlsx: `from openpyxl import Workbook

wb = Workbook()
ws = wb.active
ws.title = "Sheet1"
ws["A1"] = "항목"
ws["B1"] = "값"
ws["A2"] = "매출"
ws["B2"] = 1200000
wb.save(OUTPUT_PATH)`,
  pdf: `from reportlab.lib.pagesizes import A4
from reportlab.pdfgen.canvas import Canvas

c = Canvas(OUTPUT_PATH, pagesize=A4)
c.setFont("Helvetica", 24)
c.drawString(100, 750, "Hello PDF")
c.save()`,
  csv: `import csv

with open(OUTPUT_PATH, "w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(["이름", "값"])
    writer.writerow(["항목1", 100])
    writer.writerow(["항목2", 200])`,
  html: `html_content = \"\"\"<!DOCTYPE html>
<html><head><title>Report</title></head>
<body><h1>제목</h1><p>내용</p></body>
</html>\"\"\"
with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
    f.write(html_content)`,
  png: `from PIL import Image, ImageDraw, ImageFont

img = Image.new("RGB", (800, 600), "white")
draw = ImageDraw.Draw(img)
draw.rectangle([50, 50, 750, 550], outline="black", width=2)
draw.text((100, 100), "Hello PNG", fill="black")
img.save(OUTPUT_PATH)`,
};
