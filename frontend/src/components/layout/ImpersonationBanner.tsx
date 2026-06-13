import { Eye, LogOut } from "lucide-react";
import { getImpersonationState, exitImpersonation } from "../../lib/impersonation";

/**
 * CR-096: 임퍼소네이션 중임을 알리는 상단 배너.
 * super admin 이 테넌트로 '들어간' 동안 어느 테넌트를 보고 있는지 표시하고,
 * '나가기'로 super admin 컨텍스트로 복원한다.
 */
export function ImpersonationBanner() {
  const { active, tenantName } = getImpersonationState();
  if (!active) return null;

  const handleExit = () => {
    exitImpersonation();
    // platform 테넌트 목록으로 복귀
    window.location.href = "/platform/tenants";
  };

  return (
    <div className="shrink-0 bg-amber-500/15 border-b border-amber-500/40 px-7 py-2.5 flex items-center justify-between">
      <div className="flex items-center gap-2 text-[13px] text-amber-700 dark:text-amber-400">
        <Eye className="size-4" />
        <span>
          <span className="font-semibold">{tenantName ?? "테넌트"}</span> 테넌트로 보는 중 (Super Admin 임퍼소네이션)
        </span>
      </div>
      <button
        onClick={handleExit}
        className="flex items-center gap-1.5 text-xs font-semibold text-amber-700 dark:text-amber-400 hover:text-amber-900 dark:hover:text-amber-200 px-2.5 py-1 rounded-md hover:bg-amber-500/20 transition-colors"
      >
        <LogOut className="size-3.5" />
        나가기
      </button>
    </div>
  );
}
