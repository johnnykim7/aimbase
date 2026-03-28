import { useState } from "react";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { DataTable, type Column } from "../components/common/DataTable";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, textareaStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { FileJson } from "lucide-react";
import { useSchemas, useCreateSchema, useDeleteSchema, useValidateSchema } from "../hooks/useSchemas";
import type { Schema } from "../types/schema";

export default function Schemas() {
  const { data: schemas = [], isLoading } = useSchemas();
  const createSchema = useCreateSchema();
  const deleteSchema = useDeleteSchema();
  const validateSchema = useValidateSchema();

  const [showModal, setShowModal] = useState(false);
  const [editSchema, setEditSchema] = useState<Schema | null>(null);
  const [validInput, setValidInput] = useState("");
  const [validResult, setValidResult] = useState<{ valid: boolean; errors?: string[] } | null>(null);
  const [form, setForm] = useState({ id: "", version: "1", description: "", domain: "", schema: '{\n  "type": "object",\n  "properties": {}\n}' });

  const handleValidate = async (schema: Schema) => {
    try {
      const data = JSON.parse(validInput);
      const result = await validateSchema.mutateAsync({
        id: schema.id,
        version: schema.version ?? "1",
        data,
      });
      setValidResult(result);
    } catch {
      setValidResult({ valid: false, errors: ["JSON 파싱 오류"] });
    }
  };

  const columns: Column<Schema>[] = [
    {
      header: "스키마명",
      render: (s) => (
        <span className="font-mono text-primary text-[13px]">{s.description || s.id}</span>
      ),
    },
    { header: "설명", render: (s) => s.description ?? "—" },
    {
      header: "도메인",
      render: (s) => s.domain ? <Badge color="purple">{s.domain}</Badge> : <span className="text-muted-foreground/60">—</span>,
    },
    {
      header: "버전",
      render: (s) => <Badge color="muted">v{s.version ?? "1"}</Badge>,
      width: "80px",
    },
    {
      header: "액션",
      render: (s) => (
        <div className="flex gap-1.5">
          <ActionButton small variant="ghost" onClick={() => { setEditSchema(s); setValidInput(""); setValidResult(null); }}>편집</ActionButton>
          <ActionButton small variant="danger" onClick={() => deleteSchema.mutate({ id: s.id, version: s.version })}>삭제</ActionButton>
        </div>
      ),
      width: "140px",
    },
  ];

  return (
    <Page
      actions={
        <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>새 스키마</ActionButton>
      }
    >

      {schemas.length === 0 && !isLoading ? (
        <EmptyState
          icon={<FileJson className="size-6" />}
          title="등록된 스키마가 없습니다"
          description="LLM 출력 구조를 JSON Schema로 정의하세요"
          action={<ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>새 스키마 추가</ActionButton>}
        />
      ) : (
        <DataTable columns={columns} data={schemas} keyExtractor={(s) => s.id} loading={isLoading} emptyMessage="스키마가 없습니다" />
      )}

      {/* Edit/Validate Panel */}
      {editSchema && (
        <div className="mt-5 bg-card border border-border rounded-xl p-6">
          <div className="flex justify-between items-center mb-4">
            <div className="text-sm font-semibold text-foreground">
              {editSchema.description || editSchema.id} <Badge color="muted">v{editSchema.version ?? "1"}</Badge>
            </div>
            <ActionButton variant="ghost" small onClick={() => setEditSchema(null)}>닫기</ActionButton>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-2">JSON Schema</div>
              <textarea
                style={{ ...textareaStyle, minHeight: 200, fontSize: 12 }}
                className="font-mono"
                value={editSchema.schema ? JSON.stringify(editSchema.schema, null, 2) : "{}"}
                readOnly
              />
            </div>
            <div>
              <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-2">검증 테스트</div>
              <textarea
                style={{ ...textareaStyle, minHeight: 120, fontSize: 12 }}
                className="font-mono"
                placeholder='{ "field": "value" }'
                value={validInput}
                onChange={(e) => setValidInput(e.target.value)}
              />
              <ActionButton variant="default" small disabled={validateSchema.isPending || !validInput} onClick={() => handleValidate(editSchema)}>
                검증 실행
              </ActionButton>
              {validResult && (
                <div className={cn(
                  "mt-2.5 px-3.5 py-2.5 rounded-lg text-xs font-mono",
                  validResult.valid ? "bg-success/10 text-success" : "bg-destructive/10 text-destructive"
                )}>
                  {validResult.valid ? "✓ 유효한 데이터" : `✕ 검증 실패: ${validResult.errors?.join(", ") ?? ""}`}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Create Modal */}
      <Modal open={showModal} onClose={() => setShowModal(false)} title="새 스키마 생성">
        <FormField label="스키마 ID">
          <input style={inputStyle} placeholder="order_schema" value={form.id} onChange={(e) => setForm((p) => ({ ...p, id: e.target.value }))} />
        </FormField>
        <FormField label="버전">
          <input style={inputStyle} value={form.version} onChange={(e) => setForm((p) => ({ ...p, version: e.target.value }))} />
        </FormField>
        <FormField label="설명">
          <input style={inputStyle} value={form.description} onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))} />
        </FormField>
        <FormField label="도메인">
          <input style={inputStyle} placeholder="ecommerce" value={form.domain} onChange={(e) => setForm((p) => ({ ...p, domain: e.target.value }))} />
        </FormField>
        <FormField label="JSON Schema">
          <textarea
            style={{ ...textareaStyle, minHeight: 160, fontSize: 12 }}
            className="font-mono"
            value={form.schema}
            onChange={(e) => setForm((p) => ({ ...p, schema: e.target.value }))}
          />
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="💾"
            disabled={createSchema.isPending}
            onClick={() => {
              try {
                createSchema.mutate(
                  { id: form.id, version: form.version, description: form.description, domain: form.domain, schema: JSON.parse(form.schema) },
                  { onSuccess: () => setShowModal(false) }
                );
              } catch {
                alert("JSON Schema 파싱 오류");
              }
            }}
          >
            저장
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
