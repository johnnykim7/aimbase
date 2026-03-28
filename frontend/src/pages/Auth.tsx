import { useState } from "react";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { DataTable, type Column } from "../components/common/DataTable";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, selectStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { Users, KeyRound } from "lucide-react";
import {
  useUsers, useCreateUser, useDeleteUser, useGenerateApiKey,
  useRoles, useCreateRole, useDeleteRole,
} from "../hooks/useAuth";
import type { User, UserRequest, Role, RoleRequest } from "../types/auth";

const AVAILABLE_PERMISSIONS = [
  "chat:read", "chat:write",
  "connections:read", "connections:write",
  "policies:read", "policies:write",
  "workflows:read", "workflows:write",
  "schemas:read", "schemas:write",
  "prompts:read", "prompts:write",
  "knowledge:read", "knowledge:write",
  "admin:read", "admin:write",
];

export default function Auth() {
  const [activeTab, setActiveTab] = useState<"users" | "roles">("users");
  const { data: users = [], isLoading: usersLoading } = useUsers();
  const { data: roles = [], isLoading: rolesLoading } = useRoles();
  const createUser = useCreateUser();
  const deleteUser = useDeleteUser();
  const generateApiKey = useGenerateApiKey();
  const createRole = useCreateRole();
  const deleteRole = useDeleteRole();

  const [showUserModal, setShowUserModal] = useState(false);
  const [showRoleModal, setShowRoleModal] = useState(false);
  const [apiKeys, setApiKeys] = useState<Record<string, string>>({});
  const [userForm, setUserForm] = useState<UserRequest>({ username: "", email: "", password: "", roleId: "" });
  const [roleForm, setRoleForm] = useState<RoleRequest>({ name: "", description: "", permissions: [] });

  const handleGenerateKey = async (userId: string) => {
    const result = await generateApiKey.mutateAsync(userId);
    if (result?.apiKey) setApiKeys((p) => ({ ...p, [userId]: result.apiKey }));
  };

  const togglePermission = (perm: string) => {
    setRoleForm((p) => ({
      ...p,
      permissions: p.permissions?.includes(perm)
        ? p.permissions.filter((x) => x !== perm)
        : [...(p.permissions ?? []), perm],
    }));
  };

  const userColumns: Column<User>[] = [
    {
      header: "사용자명",
      render: (u) => <span className="font-mono text-primary">{u.username}</span>,
    },
    { header: "이메일", render: (u) => u.email ?? "—" },
    {
      header: "역할",
      render: (u) => u.role ? <Badge color="purple">{u.role}</Badge> : <span className="text-muted-foreground/60">—</span>,
    },
    {
      header: "상태",
      render: (u) => <Badge color={u.status === "active" ? "success" : "muted"}>{u.status ?? "active"}</Badge>,
      width: "80px",
    },
    {
      header: "API 키",
      render: (u) => {
        const key = apiKeys[u.id] ?? u.apiKey;
        return key ? (
          <span className="font-mono text-[11px] text-muted-foreground/60">{key.slice(0, 12)}...</span>
        ) : (
          <ActionButton small variant="ghost" disabled={generateApiKey.isPending} onClick={() => handleGenerateKey(u.id)}>생성</ActionButton>
        );
      },
      width: "160px",
    },
    {
      header: "액션",
      render: (u) => <ActionButton small variant="danger" onClick={() => deleteUser.mutate(u.id)}>삭제</ActionButton>,
      width: "70px",
    },
  ];

  const roleColumns: Column<Role>[] = [
    {
      header: "역할명",
      render: (r) => <span className="font-semibold text-foreground">{r.name}</span>,
    },
    { header: "설명", render: (r) => r.description ?? "—" },
    {
      header: "권한",
      render: (r) => (
        <div className="flex gap-1 flex-wrap">
          {(r.permissions ?? []).slice(0, 4).map((p) => (
            <Badge key={p} color="accent">{p}</Badge>
          ))}
          {(r.permissions ?? []).length > 4 && (
            <Badge color="muted">+{(r.permissions ?? []).length - 4}</Badge>
          )}
        </div>
      ),
    },
    {
      header: "액션",
      render: (r) => <ActionButton small variant="danger" onClick={() => deleteRole.mutate(r.id)}>삭제</ActionButton>,
      width: "70px",
    },
  ];

  const isLoading = activeTab === "users" ? usersLoading : rolesLoading;

  return (
    <Page
      actions={
        activeTab === "users" ? (
          <ActionButton variant="primary" icon="+" onClick={() => setShowUserModal(true)}>사용자 추가</ActionButton>
        ) : (
          <ActionButton variant="primary" icon="+" onClick={() => setShowRoleModal(true)}>역할 추가</ActionButton>
        )
      }
    >

      {/* Tabs */}
      <div className="flex gap-0 mb-5 border-b border-border">
        {(["users", "roles"] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={cn(
              "px-5 py-2.5 bg-transparent border-none text-[13px] font-semibold cursor-pointer transition-colors -mb-px",
              activeTab === tab
                ? "border-b-2 border-b-primary text-primary"
                : "border-b-2 border-b-transparent text-muted-foreground hover:text-foreground"
            )}
          >
            {tab === "users" ? "👤 사용자 관리" : "🔑 역할/권한"}
          </button>
        ))}
      </div>

      {isLoading ? (
        <LoadingSpinner fullPage />
      ) : activeTab === "users" ? (
        users.length === 0 ? (
          <EmptyState icon={<Users className="size-6" />} title="등록된 사용자가 없습니다" action={<ActionButton variant="primary" icon="+" onClick={() => setShowUserModal(true)}>사용자 추가</ActionButton>} />
        ) : (
          <DataTable columns={userColumns} data={users} keyExtractor={(u) => u.id} />
        )
      ) : (
        roles.length === 0 ? (
          <EmptyState icon={<KeyRound className="size-6" />} title="등록된 역할이 없습니다" action={<ActionButton variant="primary" icon="+" onClick={() => setShowRoleModal(true)}>역할 추가</ActionButton>} />
        ) : (
          <DataTable columns={roleColumns} data={roles} keyExtractor={(r) => r.id} />
        )
      )}

      {/* User Modal */}
      <Modal open={showUserModal} onClose={() => setShowUserModal(false)} title="사용자 추가">
        <FormField label="사용자명">
          <input style={inputStyle} value={userForm.username} onChange={(e) => setUserForm((p) => ({ ...p, username: e.target.value }))} />
        </FormField>
        <FormField label="이메일">
          <input type="email" style={inputStyle} value={userForm.email} onChange={(e) => setUserForm((p) => ({ ...p, email: e.target.value }))} />
        </FormField>
        <FormField label="비밀번호">
          <input type="password" style={inputStyle} value={userForm.password} onChange={(e) => setUserForm((p) => ({ ...p, password: e.target.value }))} />
        </FormField>
        <FormField label="역할">
          <select style={selectStyle} value={userForm.roleId} onChange={(e) => setUserForm((p) => ({ ...p, roleId: e.target.value }))}>
            <option value="">역할 선택</option>
            {roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowUserModal(false)}>취소</ActionButton>
          <ActionButton variant="primary" icon="💾" disabled={createUser.isPending} onClick={() => createUser.mutate(userForm, { onSuccess: () => setShowUserModal(false) })}>저장</ActionButton>
        </div>
      </Modal>

      {/* Role Modal */}
      <Modal open={showRoleModal} onClose={() => setShowRoleModal(false)} title="역할 추가">
        <FormField label="역할명">
          <input style={inputStyle} placeholder="tenant_admin" value={roleForm.name} onChange={(e) => setRoleForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="설명">
          <input style={inputStyle} value={roleForm.description} onChange={(e) => setRoleForm((p) => ({ ...p, description: e.target.value }))} />
        </FormField>
        <FormField label="권한">
          <div className="flex gap-1.5 flex-wrap mt-1">
            {AVAILABLE_PERMISSIONS.map((perm) => (
              <span
                key={perm}
                onClick={() => togglePermission(perm)}
                className={cn(
                  "py-1 px-2.5 rounded-md text-[11px] font-mono cursor-pointer border transition-all",
                  roleForm.permissions?.includes(perm)
                    ? "border-primary bg-primary/10 text-primary"
                    : "border-border bg-accent text-muted-foreground hover:bg-muted"
                )}
              >
                {perm}
              </span>
            ))}
          </div>
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowRoleModal(false)}>취소</ActionButton>
          <ActionButton variant="primary" icon="💾" disabled={createRole.isPending} onClick={() => createRole.mutate(roleForm, { onSuccess: () => setShowRoleModal(false) })}>저장</ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
