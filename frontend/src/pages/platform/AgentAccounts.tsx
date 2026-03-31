import { useState } from "react";
import { COLORS, FONTS } from "../../theme";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { Modal } from "../../components/common/Modal";
import { FormField, inputStyle, selectStyle } from "../../components/common/FormField";
import { EmptyState } from "../../components/common/EmptyState";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { PageHeader } from "../../components/layout/PageHeader";
import {
  useAgentAccounts, useCreateAgentAccount, useUpdateAgentAccount,
  useDeleteAgentAccount, useTestAgentAccount, useResetCircuit,
  useSaveToken,
  useAssignments, useCreateAssignment, useDeleteAssignment,
} from "../../hooks/usePlatform";
import type { AccountPoolStatus, AgentAccountCreateRequest, AssignmentCreateRequest } from "../../types/agentAccount";

const EMPTY_FORM: AgentAccountCreateRequest = {
  id: "", name: "", agentType: "claude_code", authType: "oauth",
  containerHost: "", containerPort: 9100, maxConcurrent: 1, priority: 0,
};

const EMPTY_ASSIGNMENT: AssignmentCreateRequest = {
  account_id: "", tenant_id: "", app_id: "", assignment_type: "fixed", priority: 0,
};

function generateInstallScript(account: AccountPoolStatus | AgentAccountCreateRequest, registryUrl: string): string {
  const id = "accountId" in account ? account.accountId : account.id;
  const name = account.name;
  const port = "containerPort" in account ? account.containerPort : 9100;

  return `#!/bin/bash
# ============================================
# Claude Sidecar 설치 스크립트
# 계정: ${name} (${id})
# 생성일: ${new Date().toISOString().split("T")[0]}
# ============================================

set -euo pipefail

REGISTRY="${registryUrl}"
IMAGE_NAME="claude-sidecar"
IMAGE_TAG="latest"
CONTAINER_NAME="claude-sidecar-${id}"
HOST_PORT=${port}

echo ">>> Claude Sidecar 이미지 Pull..."
docker pull "\${REGISTRY}/\${IMAGE_NAME}:\${IMAGE_TAG}"

echo ">>> 기존 컨테이너 정리..."
docker rm -f "\${CONTAINER_NAME}" 2>/dev/null || true

echo ">>> 컨테이너 실행..."
docker run -d \\
  --name "\${CONTAINER_NAME}" \\
  --restart unless-stopped \\
  -p \${HOST_PORT}:9100 \\
  -v "\${CONTAINER_NAME}-auth":/home/node/.claude \\
  -v "\${CONTAINER_NAME}-data":/data/workspace \\
  -e NODE_ENV=production \\
  "\${REGISTRY}/\${IMAGE_NAME}:\${IMAGE_TAG}"

echo ">>> 헬스체크 대기..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:\${HOST_PORT}/health > /dev/null 2>&1; then
    echo ">>> Sidecar 정상 실행 중 (port: \${HOST_PORT})"
    echo ""
    echo "다음 단계:"
    echo "  1. Aimbase 관리 UI에서 에이전트 계정 등록"
    echo "  2. POST /platform/agent-accounts/${id}/test 로 연결 확인"
    echo "  3. 테넌트 할당 설정"
    exit 0
  fi
  sleep 1
done

echo ">>> [경고] 헬스체크 타임아웃. 로그 확인: docker logs \${CONTAINER_NAME}"
exit 1
`;
}

export default function AgentAccounts() {
  const { data: accounts = [], isLoading } = useAgentAccounts();
  const createAccount = useCreateAgentAccount();
  const updateAccount = useUpdateAgentAccount();
  const deleteAccount = useDeleteAgentAccount();
  const testAccount = useTestAgentAccount();
  const resetCircuit = useResetCircuit();
  const saveToken = useSaveToken();
  const { data: assignments = [] } = useAssignments();

  const [showTokenModal, setShowTokenModal] = useState(false);
  const [tokenAccountId, setTokenAccountId] = useState("");
  const [tokenForm, setTokenForm] = useState({ auth_type: "oauth_token", auth_token: "" });
  const createAssignment = useCreateAssignment();
  const deleteAssignment = useDeleteAssignment();

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [showScriptModal, setShowScriptModal] = useState(false);
  const [showAssignmentModal, setShowAssignmentModal] = useState(false);
  const [form, setForm] = useState<AgentAccountCreateRequest>({ ...EMPTY_FORM });
  const [editId, setEditId] = useState("");
  const [selectedAccount, setSelectedAccount] = useState<AccountPoolStatus | null>(null);
  const [registryUrl, setRegistryUrl] = useState("");
  const [copied, setCopied] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [assignForm, setAssignForm] = useState<AssignmentCreateRequest>({ ...EMPTY_ASSIGNMENT });

  const handleCopy = (script: string) => {
    navigator.clipboard.writeText(script);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const openEdit = (a: AccountPoolStatus) => {
    setEditId(a.accountId);
    setForm({
      id: a.accountId, name: a.name, agentType: a.agentType,
      authType: "oauth", containerHost: "", containerPort: 9100,
      maxConcurrent: a.maxConcurrent, priority: 0,
    });
    setShowEditModal(true);
  };

  const openScript = (a: AccountPoolStatus) => {
    setSelectedAccount(a);
    setRegistryUrl("");
    setCopied(false);
    setShowScriptModal(true);
  };

  const openAssignment = () => {
    setAssignForm({ ...EMPTY_ASSIGNMENT });
    setShowAssignmentModal(true);
  };

  const columns: Column<AccountPoolStatus>[] = [
    {
      header: "계정 ID",
      render: (a) => (
        <span style={{ fontFamily: FONTS.mono, color: COLORS.accent, fontWeight: 600, fontSize: 12 }}>
          {a.accountId}
        </span>
      ),
    },
    {
      header: "이름",
      render: (a) => <span style={{ fontWeight: 600 }}>{a.name}</span>,
    },
    {
      header: "타입",
      render: (a) => <Badge color="purple">{a.agentType}</Badge>,
      width: "110px",
    },
    {
      header: "상태",
      render: (a) => (
        <Badge color={a.status === "active" ? "success" : a.status === "maintenance" ? "warning" : "danger"}>
          {a.status}
        </Badge>
      ),
      width: "90px",
    },
    {
      header: "Health",
      render: (a) => (
        <Badge
          color={a.healthStatus === "healthy" ? "success" : a.healthStatus === "auth_required" ? "warning" : a.healthStatus === "unhealthy" ? "danger" : "muted"}
          pulse={a.healthStatus === "healthy"}
        >
          {a.healthStatus === "auth_required" ? "인증필요" : a.healthStatus}
        </Badge>
      ),
      width: "100px",
    },
    {
      header: "서킷",
      render: (a) => (
        <Badge color={a.circuitState === "CLOSED" ? "success" : a.circuitState === "OPEN" ? "danger" : "warning"}>
          {a.circuitState}
        </Badge>
      ),
      width: "90px",
    },
    {
      header: "동시성",
      render: (a) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 12 }}>
          {a.currentConcurrency} / {a.maxConcurrent}
        </span>
      ),
      width: "80px",
    },
    {
      header: "액션",
      render: (a) => (
        <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
          <ActionButton
            small variant="ghost"
            disabled={testAccount.isPending}
            onClick={() => testAccount.mutate(a.accountId, {
              onSuccess: (r) => setTestResult(`[${a.accountId}] ${r.status}: ${r.response || r.message || ""}`),
            })}
          >
            테스트
          </ActionButton>
          {a.circuitState !== "CLOSED" && (
            <ActionButton small variant="success" onClick={() => resetCircuit.mutate(a.accountId)}>
              리셋
            </ActionButton>
          )}
          <ActionButton small variant={a.healthStatus === "auth_required" ? "primary" : "default"}
            onClick={() => { setTokenAccountId(a.accountId); setTokenForm({ auth_type: "oauth_token", auth_token: "" }); setShowTokenModal(true); }}>
            토큰설정
          </ActionButton>
          <ActionButton small variant="default" onClick={() => openScript(a)}>
            스크립트
          </ActionButton>
          <ActionButton small variant="ghost" onClick={() => openEdit(a)}>
            수정
          </ActionButton>
          <ActionButton small variant="danger" onClick={() => deleteAccount.mutate(a.accountId)}>
            삭제
          </ActionButton>
        </div>
      ),
      width: "360px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title="에이전트 계정 관리"
        subtitle="Super Admin — Claude Code 사이드카 계정 풀 관리"
        actions={
          <div style={{ display: "flex", gap: 8 }}>
            <ActionButton variant="ghost" onClick={openAssignment}>
              할당 관리
            </ActionButton>
            <ActionButton variant="primary" icon="+" onClick={() => { setForm({ ...EMPTY_FORM }); setShowCreateModal(true); }}>
              계정 생성
            </ActionButton>
          </div>
        }
      />

      {testResult && (
        <div style={{
          padding: "10px 14px", marginBottom: 16, borderRadius: 8,
          background: COLORS.accentDim + "20", border: `1px solid ${COLORS.accentDim}`,
          fontSize: 12, fontFamily: FONTS.mono, color: COLORS.text,
          display: "flex", justifyContent: "space-between", alignItems: "center",
        }}>
          <span>{testResult}</span>
          <ActionButton small variant="ghost" onClick={() => setTestResult(null)}>닫기</ActionButton>
        </div>
      )}

      {accounts.length === 0 ? (
        <EmptyState
          icon="🤖"
          title="등록된 에이전트 계정이 없습니다"
          description="Claude Code 사이드카 계정을 등록하여 워크플로우에서 에이전트 도구를 사용하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => { setForm({ ...EMPTY_FORM }); setShowCreateModal(true); }}>
              첫 계정 생성
            </ActionButton>
          }
        />
      ) : (
        <DataTable columns={columns} data={accounts} keyExtractor={(a) => a.accountId} />
      )}

      {/* ── 생성 Modal ── */}
      <Modal open={showCreateModal} onClose={() => setShowCreateModal(false)} title="에이전트 계정 생성">
        <FormField label="계정 ID" hint="영문, 숫자, 하이픈 (예: agent-prod-1)">
          <input style={inputStyle} placeholder="agent-1" value={form.id} onChange={(e) => setForm((p) => ({ ...p, id: e.target.value }))} />
        </FormField>
        <FormField label="이름">
          <input style={inputStyle} placeholder="Claude Agent #1" value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="에이전트 타입">
          <select style={selectStyle} value={form.agentType} onChange={(e) => setForm((p) => ({ ...p, agentType: e.target.value }))}>
            <option value="claude_code">claude_code</option>
          </select>
        </FormField>
        <FormField label="인증 방식">
          <select style={selectStyle} value={form.authType} onChange={(e) => setForm((p) => ({ ...p, authType: e.target.value }))}>
            <option value="oauth">OAuth</option>
            <option value="api_key">API Key</option>
          </select>
        </FormField>
        <FormField label="사이드카 호스트" hint="컨테이너 호스트명 또는 IP">
          <input style={inputStyle} placeholder="10.0.1.50" value={form.containerHost} onChange={(e) => setForm((p) => ({ ...p, containerHost: e.target.value }))} />
        </FormField>
        <FormField label="사이드카 포트">
          <input type="number" style={inputStyle} value={form.containerPort} onChange={(e) => setForm((p) => ({ ...p, containerPort: parseInt(e.target.value) || 9100 }))} />
        </FormField>
        <FormField label="최대 동시 실행">
          <input type="number" style={inputStyle} value={form.maxConcurrent} onChange={(e) => setForm((p) => ({ ...p, maxConcurrent: parseInt(e.target.value) || 1 }))} />
        </FormField>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <ActionButton variant="ghost" onClick={() => setShowCreateModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary" icon="🤖"
            disabled={createAccount.isPending || !form.id || !form.name || !form.containerHost}
            onClick={() => createAccount.mutate(form, {
              onSuccess: () => { setShowCreateModal(false); setForm({ ...EMPTY_FORM }); },
            })}
          >
            생성
          </ActionButton>
        </div>
      </Modal>

      {/* ── 수정 Modal ── */}
      <Modal open={showEditModal} onClose={() => setShowEditModal(false)} title={`계정 수정 — ${editId}`}>
        <FormField label="이름">
          <input style={inputStyle} value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="사이드카 호스트">
          <input style={inputStyle} value={form.containerHost} onChange={(e) => setForm((p) => ({ ...p, containerHost: e.target.value }))} />
        </FormField>
        <FormField label="사이드카 포트">
          <input type="number" style={inputStyle} value={form.containerPort} onChange={(e) => setForm((p) => ({ ...p, containerPort: parseInt(e.target.value) || 9100 }))} />
        </FormField>
        <FormField label="최대 동시 실행">
          <input type="number" style={inputStyle} value={form.maxConcurrent} onChange={(e) => setForm((p) => ({ ...p, maxConcurrent: parseInt(e.target.value) || 1 }))} />
        </FormField>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <ActionButton variant="ghost" onClick={() => setShowEditModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            disabled={updateAccount.isPending}
            onClick={() => updateAccount.mutate(
              { id: editId, data: { name: form.name, containerHost: form.containerHost, containerPort: form.containerPort, maxConcurrent: form.maxConcurrent } },
              { onSuccess: () => setShowEditModal(false) },
            )}
          >
            저장
          </ActionButton>
        </div>
      </Modal>

      {/* ── 설치 스크립트 Modal ── */}
      <Modal open={showScriptModal} onClose={() => setShowScriptModal(false)} title="설치 스크립트 생성" width={700}>
        <FormField label="Docker 레지스트리 URL" hint="NAS 레지스트리 주소 (예: nas.local:5000/aimbase)">
          <input
            style={inputStyle}
            placeholder="nas.local:5000/aimbase"
            value={registryUrl}
            onChange={(e) => { setRegistryUrl(e.target.value); setCopied(false); }}
          />
        </FormField>

        {registryUrl && selectedAccount && (
          <>
            <div style={{
              padding: "16px",
              borderRadius: 8,
              background: COLORS.surfaceActive,
              border: `1px solid ${COLORS.border}`,
              overflow: "auto",
              maxHeight: 400,
            }}>
              <pre style={{
                margin: 0,
                fontSize: 12,
                fontFamily: FONTS.mono,
                color: COLORS.text,
                lineHeight: 1.6,
                whiteSpace: "pre-wrap",
                wordBreak: "break-all",
              }}>
                {generateInstallScript(selectedAccount, registryUrl)}
              </pre>
            </div>

            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 16 }}>
              <ActionButton
                variant={copied ? "success" : "primary"}
                icon={copied ? "✓" : "📋"}
                onClick={() => handleCopy(generateInstallScript(selectedAccount, registryUrl))}
              >
                {copied ? "복사됨!" : "클립보드 복사"}
              </ActionButton>
            </div>
          </>
        )}

        {!registryUrl && (
          <div style={{ textAlign: "center", padding: "24px 0", color: COLORS.textMuted, fontSize: 13 }}>
            레지스트리 URL을 입력하면 설치 스크립트가 생성됩니다
          </div>
        )}
      </Modal>

      {/* ── 할당 관리 Modal ── */}
      <Modal open={showAssignmentModal} onClose={() => setShowAssignmentModal(false)} title="테넌트/앱 할당 관리" width={650}>
        {/* 기존 할당 목록 */}
        {assignments.length > 0 ? (
          <div style={{ marginBottom: 20 }}>
            <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 8 }}>
              현재 할당
            </div>
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
              <thead>
                <tr style={{ borderBottom: `1px solid ${COLORS.border}` }}>
                  <th style={{ textAlign: "left", padding: "6px 8px", color: COLORS.textMuted, fontFamily: FONTS.mono, fontSize: 11 }}>계정</th>
                  <th style={{ textAlign: "left", padding: "6px 8px", color: COLORS.textMuted, fontFamily: FONTS.mono, fontSize: 11 }}>테넌트</th>
                  <th style={{ textAlign: "left", padding: "6px 8px", color: COLORS.textMuted, fontFamily: FONTS.mono, fontSize: 11 }}>앱</th>
                  <th style={{ textAlign: "left", padding: "6px 8px", color: COLORS.textMuted, fontFamily: FONTS.mono, fontSize: 11 }}>타입</th>
                  <th style={{ width: 50 }}></th>
                </tr>
              </thead>
              <tbody>
                {assignments.map((a) => (
                  <tr key={a.id} style={{ borderBottom: `1px solid ${COLORS.border}` }}>
                    <td style={{ padding: "6px 8px", fontFamily: FONTS.mono, color: COLORS.accent }}>{a.account?.name || a.account?.id}</td>
                    <td style={{ padding: "6px 8px" }}>{a.tenantId || "—"}</td>
                    <td style={{ padding: "6px 8px" }}>{a.appId || "—"}</td>
                    <td style={{ padding: "6px 8px" }}><Badge color={a.assignmentType === "round_robin" ? "purple" : "muted"}>{a.assignmentType}</Badge></td>
                    <td style={{ padding: "6px 8px" }}>
                      <ActionButton small variant="danger" onClick={() => deleteAssignment.mutate(a.id)}>삭제</ActionButton>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div style={{ textAlign: "center", padding: "16px 0", color: COLORS.textMuted, fontSize: 13, marginBottom: 16 }}>
            등록된 할당이 없습니다
          </div>
        )}

        {/* 새 할당 생성 */}
        <div style={{ borderTop: `1px solid ${COLORS.border}`, paddingTop: 16 }}>
          <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 12 }}>
            새 할당 추가
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <FormField label="계정">
              <select style={selectStyle} value={assignForm.account_id} onChange={(e) => setAssignForm((p) => ({ ...p, account_id: e.target.value }))}>
                <option value="">선택...</option>
                {accounts.map((a) => (
                  <option key={a.accountId} value={a.accountId}>{a.name} ({a.accountId})</option>
                ))}
              </select>
            </FormField>
            <FormField label="할당 타입">
              <select style={selectStyle} value={assignForm.assignment_type} onChange={(e) => setAssignForm((p) => ({ ...p, assignment_type: e.target.value }))}>
                <option value="fixed">fixed (전용)</option>
                <option value="round_robin">round_robin (분배)</option>
              </select>
            </FormField>
            <FormField label="테넌트 ID" hint="비워두면 전체">
              <input style={inputStyle} placeholder="tenant_dev" value={assignForm.tenant_id} onChange={(e) => setAssignForm((p) => ({ ...p, tenant_id: e.target.value }))} />
            </FormField>
            <FormField label="앱 ID" hint="비워두면 전체">
              <input style={inputStyle} placeholder="lexflow" value={assignForm.app_id} onChange={(e) => setAssignForm((p) => ({ ...p, app_id: e.target.value }))} />
            </FormField>
          </div>
          <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 12 }}>
            <ActionButton
              variant="primary" icon="+"
              disabled={createAssignment.isPending || !assignForm.account_id}
              onClick={() => createAssignment.mutate(assignForm, {
                onSuccess: () => setAssignForm({ ...EMPTY_ASSIGNMENT }),
              })}
            >
              할당 추가
            </ActionButton>
          </div>
        </div>
      </Modal>

      {/* ── 토큰 설정 Modal ── */}
      <Modal open={showTokenModal} onClose={() => setShowTokenModal(false)} title="인증 토큰 설정" width={500}>
        <div style={{ marginBottom: 16, padding: "10px 14px", borderRadius: 8, background: COLORS.accentDim + "15", fontSize: 12, color: COLORS.textMuted, lineHeight: 1.6 }}>
          <strong>구독 (Pro/Max)</strong>: 로컬에서 <code style={{ fontFamily: FONTS.mono, background: COLORS.surface, padding: "1px 4px", borderRadius: 3 }}>claude setup-token</code> 실행 후 발급된 토큰 입력<br />
          <strong>API Key (종량제)</strong>: Anthropic Console에서 발급한 API Key 입력
        </div>
        <FormField label="인증 방식">
          <select style={selectStyle} value={tokenForm.auth_type} onChange={(e) => setTokenForm((p) => ({ ...p, auth_type: e.target.value }))}>
            <option value="oauth_token">구독 토큰 (setup-token)</option>
            <option value="api_key">API Key (종량제)</option>
          </select>
        </FormField>
        <FormField label={tokenForm.auth_type === "oauth_token" ? "OAuth Token" : "API Key"} hint={tokenForm.auth_type === "oauth_token" ? "sk-ant-oat01-... 형식" : "sk-ant-api... 형식"}>
          <input
            style={inputStyle}
            type="password"
            placeholder={tokenForm.auth_type === "oauth_token" ? "sk-ant-oat01-..." : "sk-ant-api03-..."}
            value={tokenForm.auth_token}
            onChange={(e) => setTokenForm((p) => ({ ...p, auth_token: e.target.value }))}
          />
        </FormField>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <ActionButton variant="ghost" onClick={() => setShowTokenModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            disabled={saveToken.isPending || !tokenForm.auth_token.trim()}
            onClick={() => saveToken.mutate(
              { id: tokenAccountId, ...tokenForm },
              {
                onSuccess: (r) => {
                  setShowTokenModal(false);
                  setTestResult(`[${tokenAccountId}] ${r.message} (${r.auth_type})`);
                },
                onError: (e) => setTestResult(`[${tokenAccountId}] 토큰 저장 실패: ${e.message}`),
              }
            )}
          >
            저장
          </ActionButton>
        </div>
      </Modal>
    </div>
  );
}
