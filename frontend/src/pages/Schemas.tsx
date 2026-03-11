import { useState } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { DataTable, type Column } from "../components/common/DataTable";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, textareaStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { PageHeader } from "../components/layout/PageHeader";
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
      header: "ID",
      render: (s) => (
        <span style={{ fontFamily: FONTS.mono, color: COLORS.accent, fontSize: 13 }}>{s.id}</span>
      ),
    },
    { header: "설명", render: (s) => s.description ?? "—" },
    {
      header: "도메인",
      render: (s) => s.domain ? <Badge color="purple">{s.domain}</Badge> : <span style={{ color: COLORS.textDim }}>—</span>,
    },
    {
      header: "버전",
      render: (s) => <Badge color="muted">v{s.version ?? "1"}</Badge>,
      width: "80px",
    },
    {
      header: "액션",
      render: (s) => (
        <div style={{ display: "flex", gap: 6 }}>
          <ActionButton small variant="ghost" onClick={() => { setEditSchema(s); setValidInput(""); setValidResult(null); }}>
            편집
          </ActionButton>
          <ActionButton small variant="danger" onClick={() => deleteSchema.mutate({ id: s.id, version: s.version })}>
            삭제
          </ActionButton>
        </div>
      ),
      width: "140px",
    },
  ];

  return (
    <div>
      <PageHeader
        title="스키마 관리"
        subtitle="데이터 구조 정의 및 JSON Schema 검증"
        actions={
          <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
            새 스키마
          </ActionButton>
        }
      />

      {schemas.length === 0 && !isLoading ? (
        <EmptyState
          icon="📝"
          title="등록된 스키마가 없습니다"
          description="LLM 출력 구조를 JSON Schema로 정의하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              새 스키마 추가
            </ActionButton>
          }
        />
      ) : (
        <DataTable
          columns={columns}
          data={schemas}
          keyExtractor={(s) => s.id}
          loading={isLoading}
          emptyMessage="스키마가 없습니다"
        />
      )}

      {/* Edit/Validate Panel */}
      {editSchema && (
        <div
          style={{
            marginTop: 20,
            background: COLORS.surface,
            border: `1px solid ${COLORS.border}`,
            borderRadius: 12,
            padding: 24,
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
            <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
              {editSchema.id} <Badge color="muted">v{editSchema.version ?? "1"}</Badge>
            </div>
            <ActionButton variant="ghost" small onClick={() => setEditSchema(null)}>닫기</ActionButton>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
            <div>
              <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 8 }}>
                JSON Schema
              </div>
              <textarea
                style={{ ...textareaStyle, minHeight: 200, fontFamily: FONTS.mono, fontSize: 12 }}
                value={editSchema.schema ? JSON.stringify(editSchema.schema, null, 2) : "{}"}
                readOnly
              />
            </div>
            <div>
              <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 8 }}>
                검증 테스트
              </div>
              <textarea
                style={{ ...textareaStyle, minHeight: 120, fontFamily: FONTS.mono, fontSize: 12 }}
                placeholder='{ "field": "value" }'
                value={validInput}
                onChange={(e) => setValidInput(e.target.value)}
              />
              <ActionButton
                variant="default"
                small
                disabled={validateSchema.isPending || !validInput}
                onClick={() => handleValidate(editSchema)}
              >
                검증 실행
              </ActionButton>
              {validResult && (
                <div
                  style={{
                    marginTop: 10,
                    padding: "10px 14px",
                    borderRadius: 8,
                    fontSize: 12,
                    fontFamily: FONTS.mono,
                    background: validResult.valid ? COLORS.successDim + "40" : COLORS.dangerDim + "40",
                    color: validResult.valid ? COLORS.success : COLORS.danger,
                  }}
                >
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
            style={{ ...textareaStyle, minHeight: 160, fontFamily: FONTS.mono, fontSize: 12 }}
            value={form.schema}
            onChange={(e) => setForm((p) => ({ ...p, schema: e.target.value }))}
          />
        </FormField>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
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
    </div>
  );
}
