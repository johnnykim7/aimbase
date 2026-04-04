import { useState } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
import {
  useConnectionGroups,
  useCreateConnectionGroup,
  useUpdateConnectionGroup,
  useDeleteConnectionGroup,
  useTestConnectionGroup,
} from "../hooks/useConnectionGroups";
import { useConnections } from "../hooks/useConnections";
import type { ConnectionGroup, ConnectionGroupRequest, ConnectionGroupMember } from "../types/connectionGroup";
import type { Connection } from "../types/connection";

const STRATEGIES = [
  { id: "PRIORITY", label: "Priority", desc: "우선순위 고정 - 장애 시 다음 커넥션으로 전환" },
  { id: "ROUND_ROBIN", label: "Round Robin", desc: "순환 분산 - 요청마다 다음 커넥션 사용" },
  { id: "LEAST_USED", label: "Least Used", desc: "사용량 기반 - 호출 수 가장 적은 커넥션 선택" },
];

const ADAPTERS = ["Claude (Anthropic)", "OpenAI", "Ollama"];

function cbColor(state?: string): "success" | "danger" | "warning" | "muted" {
  if (state === "CLOSED") return "success";
  if (state === "OPEN") return "danger";
  if (state === "HALF_OPEN") return "warning";
  return "muted";
}

export default function ConnectionGroups() {
  const { data: groups = [], isLoading } = useConnectionGroups();
  const { data: connections = [] } = useConnections({ type: "llm" });
  const createGroup = useCreateConnectionGroup();
  const updateGroup = useUpdateConnectionGroup();
  const deleteGroup = useDeleteConnectionGroup();
  const testGroup = useTestConnectionGroup();

  const [showModal, setShowModal] = useState(false);
  const [editing, setEditing] = useState<ConnectionGroup | null>(null);
  const [testResults, setTestResults] = useState<Record<string, { connection_id: string; ok: boolean; latencyMs?: number; error?: string }[]>>({});

  // Form state
  const [formId, setFormId] = useState("");
  const [formName, setFormName] = useState("");
  const [formAdapter, setFormAdapter] = useState(ADAPTERS[0]);
  const [formStrategy, setFormStrategy] = useState("PRIORITY");
  const [formDefault, setFormDefault] = useState(false);
  const [formMembers, setFormMembers] = useState<{ connection_id: string; priority: number; weight: number }[]>([]);

  const openCreate = () => {
    setEditing(null);
    setFormId("");
    setFormName("");
    setFormAdapter(ADAPTERS[0]);
    setFormStrategy("PRIORITY");
    setFormDefault(false);
    setFormMembers([]);
    setShowModal(true);
  };

  const openEdit = (g: ConnectionGroup) => {
    setEditing(g);
    setFormId(g.id);
    setFormName(g.name);
    setFormAdapter(g.adapter);
    setFormStrategy(g.strategy);
    setFormDefault(g.is_default);
    setFormMembers(g.members.map((m) => ({
      connection_id: m.connection_id,
      priority: m.priority,
      weight: m.weight,
    })));
    setShowModal(true);
  };

  const handleSave = async () => {
    const data: ConnectionGroupRequest = {
      id: formId,
      name: formName,
      adapter: formAdapter,
      strategy: formStrategy,
      members: formMembers,
      isDefault: formDefault,
    };
    if (editing) {
      await updateGroup.mutateAsync({ id: editing.id, data });
    } else {
      await createGroup.mutateAsync(data);
    }
    setShowModal(false);
  };

  const handleTest = async (id: string) => {
    const results = await testGroup.mutateAsync(id);
    setTestResults((prev) => ({ ...prev, [id]: results ?? [] }));
  };

  const availableConnections = connections.filter((c) => c.adapter === formAdapter);

  const addMember = (connId: string) => {
    if (formMembers.some((m) => m.connection_id === connId)) return;
    setFormMembers([...formMembers, { connection_id: connId, priority: formMembers.length + 1, weight: 100 }]);
  };

  const removeMember = (connId: string) => {
    setFormMembers(formMembers.filter((m) => m.connection_id !== connId).map((m, i) => ({ ...m, priority: i + 1 })));
  };

  const moveMember = (index: number, direction: -1 | 1) => {
    const newMembers = [...formMembers];
    const target = index + direction;
    if (target < 0 || target >= newMembers.length) return;
    [newMembers[index], newMembers[target]] = [newMembers[target], newMembers[index]];
    setFormMembers(newMembers.map((m, i) => ({ ...m, priority: i + 1 })));
  };

  if (isLoading) return <LoadingSpinner />;

  return (
    <div>
      <PageHeader
        title="Connection Groups"
        subtitle="커넥션 그룹으로 장애 대응 및 부하 분산을 관리합니다"
        actions={<ActionButton onClick={openCreate}>+ 그룹 생성</ActionButton>}
      />

      {groups.length === 0 ? (
        <EmptyState
          title="커넥션 그룹 없음"
          description="커넥션 그룹을 생성하여 여러 API 키를 묶고 자동 폴백/분산을 설정하세요"
        />
      ) : (
        <div style={{ display: "grid", gap: 16 }}>
          {groups.map((g) => (
            <div
              key={g.id}
              style={{
                background: COLORS.surface,
                border: `1px solid ${COLORS.border}`,
                borderRadius: 10,
                padding: 20,
              }}
            >
              {/* Header */}
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
                <div>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <span style={{ fontFamily: FONTS.display, fontSize: 16, fontWeight: 600, color: COLORS.text }}>
                      {g.name}
                    </span>
                    <Badge color="accent">{g.strategy}</Badge>
                    {g.is_default && <Badge color="purple">Default</Badge>}
                  </div>
                  <div style={{ fontSize: 12, color: COLORS.textDim, marginTop: 2, fontFamily: FONTS.mono }}>
                    {g.id} | {g.adapter}
                  </div>
                </div>
                <div style={{ display: "flex", gap: 6 }}>
                  <ActionButton small variant="default" onClick={() => handleTest(g.id)}>
                    Test
                  </ActionButton>
                  <ActionButton small variant="default" onClick={() => openEdit(g)}>
                    Edit
                  </ActionButton>
                  <ActionButton small variant="danger" onClick={() => deleteGroup.mutate(g.id)}>
                    Delete
                  </ActionButton>
                </div>
              </div>

              {/* Members Table */}
              <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13, fontFamily: FONTS.sans }}>
                <thead>
                  <tr style={{ borderBottom: `1px solid ${COLORS.border}` }}>
                    <th style={thStyle}>Priority</th>
                    <th style={thStyle}>Connection</th>
                    <th style={thStyle}>Status</th>
                    <th style={thStyle}>Circuit Breaker</th>
                    <th style={thStyle}>Usage</th>
                  </tr>
                </thead>
                <tbody>
                  {g.members.map((m: ConnectionGroupMember) => {
                    const testResult = testResults[g.id]?.find((t) => t.connection_id === m.connection_id);
                    return (
                      <tr key={m.connection_id} style={{ borderBottom: `1px solid ${COLORS.border}` }}>
                        <td style={tdStyle}>{m.priority}</td>
                        <td style={tdStyle}>
                          <div>{m.connection_name ?? m.connection_id}</div>
                          <div style={{ fontSize: 11, color: COLORS.textDim, fontFamily: FONTS.mono }}>
                            {m.connection_id}
                          </div>
                        </td>
                        <td style={tdStyle}>
                          <Badge color={m.status === "connected" ? "success" : m.status === "error" ? "danger" : "muted"}>
                            {m.status ?? "unknown"}
                          </Badge>
                          {testResult && (
                            <span style={{ fontSize: 11, marginLeft: 6, color: testResult.ok ? COLORS.success : COLORS.danger }}>
                              {testResult.ok ? `${testResult.latencyMs}ms` : testResult.error}
                            </span>
                          )}
                        </td>
                        <td style={tdStyle}>
                          <Badge color={cbColor(m.circuit_breaker_state)}>
                            {m.circuit_breaker_state ?? "CLOSED"}
                          </Badge>
                        </td>
                        <td style={tdStyle}>{m.usage_count ?? 0}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      )}

      {/* Create/Edit Modal */}
      {showModal && (
        <Modal open={showModal} title={editing ? "그룹 수정" : "그룹 생성"} onClose={() => setShowModal(false)}>
          <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            <FormField label="그룹 ID">
              <input
                style={inputStyle}
                value={formId}
                onChange={(e) => setFormId(e.target.value)}
                disabled={!!editing}
                placeholder="grp-anthropic-prod"
              />
            </FormField>

            <FormField label="그룹명">
              <input style={inputStyle} value={formName} onChange={(e) => setFormName(e.target.value)} placeholder="Anthropic 프로덕션 풀" />
            </FormField>

            <FormField label="Adapter (프로바이더)">
              <select
                style={inputStyle}
                value={formAdapter}
                onChange={(e) => {
                  setFormAdapter(e.target.value);
                  setFormMembers([]);
                }}
                disabled={!!editing}
              >
                {ADAPTERS.map((a) => (
                  <option key={a} value={a}>{a}</option>
                ))}
              </select>
            </FormField>

            <FormField label="분배 전략">
              <select style={inputStyle} value={formStrategy} onChange={(e) => setFormStrategy(e.target.value)}>
                {STRATEGIES.map((s) => (
                  <option key={s.id} value={s.id}>{s.label} - {s.desc}</option>
                ))}
              </select>
            </FormField>

            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <input type="checkbox" checked={formDefault} onChange={(e) => setFormDefault(e.target.checked)} id="isDefault" />
              <label htmlFor="isDefault" style={{ fontSize: 13, color: COLORS.text }}>기본 그룹으로 설정</label>
            </div>

            {/* Members */}
            <FormField label="멤버 커넥션">
              <div style={{ marginBottom: 8 }}>
                <select
                  style={inputStyle}
                  onChange={(e) => { if (e.target.value) addMember(e.target.value); e.target.value = ""; }}
                  value=""
                >
                  <option value="">커넥션 추가...</option>
                  {availableConnections
                    .filter((c) => !formMembers.some((m) => m.connection_id === c.id))
                    .map((c) => (
                      <option key={c.id} value={c.id}>{c.name} ({c.id})</option>
                    ))}
                </select>
              </div>

              {formMembers.length === 0 ? (
                <div style={{ fontSize: 12, color: COLORS.textDim, padding: 12, textAlign: "center" }}>
                  멤버 커넥션을 추가하세요
                </div>
              ) : (
                <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                  {formMembers.map((m, i) => {
                    const conn = connections.find((c: Connection) => c.id === m.connection_id);
                    return (
                      <div
                        key={m.connection_id}
                        style={{
                          display: "flex",
                          alignItems: "center",
                          gap: 8,
                          padding: "8px 10px",
                          background: COLORS.surfaceHover,
                          borderRadius: 6,
                          fontSize: 13,
                        }}
                      >
                        <span style={{ fontWeight: 600, width: 24, textAlign: "center", color: COLORS.accent }}>
                          {m.priority}
                        </span>
                        <span style={{ flex: 1 }}>{conn?.name ?? m.connection_id}</span>
                        <span style={{ fontSize: 11, color: COLORS.textDim, fontFamily: FONTS.mono }}>
                          w:{m.weight}
                        </span>
                        <button onClick={() => moveMember(i, -1)} disabled={i === 0} style={arrowBtnStyle}>
                          ▲
                        </button>
                        <button onClick={() => moveMember(i, 1)} disabled={i === formMembers.length - 1} style={arrowBtnStyle}>
                          ▼
                        </button>
                        <button onClick={() => removeMember(m.connection_id)} style={{ ...arrowBtnStyle, color: COLORS.danger }}>
                          ✕
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </FormField>

            <ActionButton
              onClick={handleSave}
              disabled={!formId || !formName || formMembers.length === 0}
            >
              {editing ? "수정" : "생성"}
            </ActionButton>
          </div>
        </Modal>
      )}
    </div>
  );
}

const thStyle: React.CSSProperties = {
  textAlign: "left",
  padding: "8px 10px",
  fontSize: 12,
  fontWeight: 600,
  color: COLORS.textMuted,
};

const tdStyle: React.CSSProperties = {
  padding: "8px 10px",
  verticalAlign: "middle",
};

const arrowBtnStyle: React.CSSProperties = {
  background: "none",
  border: "none",
  cursor: "pointer",
  fontSize: 11,
  color: COLORS.textMuted,
  padding: "2px 4px",
};
