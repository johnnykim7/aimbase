// CR-096: Super Admin 테넌트 임퍼소네이션 상태 관리.
// super admin 의 원래 토큰을 백업해 두고, 대상 테넌트 토큰으로 교체한다.
// '나가기' 시 백업을 복원한다.

const ADMIN_TOKEN_BACKUP = "impersonation_admin_token";
const IMPERSONATING_TENANT = "impersonation_tenant_name";

export interface ImpersonationState {
  active: boolean;
  tenantName: string | null;
}

/**
 * 대상 테넌트로 진입한다. 현재 super admin 토큰을 백업한 뒤,
 * 받은 임퍼소네이션 토큰 + tenant_id 로 localStorage 를 교체한다.
 */
export function enterImpersonation(token: string, tenantId: string, tenantName: string) {
  const adminToken = localStorage.getItem("access_token");
  if (adminToken) {
    localStorage.setItem(ADMIN_TOKEN_BACKUP, adminToken);
  }
  localStorage.setItem("access_token", token);
  localStorage.setItem("tenant_id", tenantId);
  localStorage.setItem(IMPERSONATING_TENANT, tenantName);
}

/**
 * 임퍼소네이션을 종료하고 super admin 토큰을 복원한다.
 */
export function exitImpersonation() {
  const adminToken = localStorage.getItem(ADMIN_TOKEN_BACKUP);
  if (adminToken) {
    localStorage.setItem("access_token", adminToken);
  } else {
    localStorage.removeItem("access_token");
  }
  localStorage.removeItem(ADMIN_TOKEN_BACKUP);
  localStorage.removeItem("tenant_id");
  localStorage.removeItem(IMPERSONATING_TENANT);
}

export function getImpersonationState(): ImpersonationState {
  const tenantName = localStorage.getItem(IMPERSONATING_TENANT);
  return {
    active: localStorage.getItem(ADMIN_TOKEN_BACKUP) !== null,
    tenantName,
  };
}
