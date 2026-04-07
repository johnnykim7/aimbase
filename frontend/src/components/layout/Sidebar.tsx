import { useSyncExternalStore } from "react";
import { NavLink, useLocation, useNavigate } from "react-router-dom";
import {
  LayoutDashboard,
  PlugZap,
  Wrench,
  FileJson,
  Shield,
  MessageSquare,
  Zap,
  BookOpen,
  Target,
  FileText,
  FolderOpen,
  Users,
  BarChart3,
  Building2,
  CreditCard,
  KeyRound,
  Globe,
  LogOut,
  MessageSquareText,
  Layers,
  Settings2,
  Clock,
  Sparkles,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { useProjects } from "../../hooks/useProjects";
import { getProjectId, setProjectId, subscribe } from "../../store/projectContext";

interface NavItemDef {
  path: string;
  icon: LucideIcon;
  label: string;
  exact?: boolean;
}

const NAV_ITEMS: NavItemDef[] = [
  { path: "/", icon: LayoutDashboard, label: "대시보드", exact: true },
  { path: "/connections", icon: PlugZap, label: "연결 관리" },
  { path: "/mcp-servers", icon: Wrench, label: "MCP / Tool" },
  { path: "/schemas", icon: FileJson, label: "스키마" },
  { path: "/policies", icon: Shield, label: "정책" },
  { path: "/prompts", icon: MessageSquare, label: "프롬프트" },
  { path: "/workflows", icon: Zap, label: "워크플로우" },
  { path: "/knowledge", icon: BookOpen, label: "Knowledge" },
  { path: "/rag-evaluation", icon: Target, label: "RAG 평가" },
  { path: "/documents", icon: FileText, label: "문서 생성" },
  { path: "/projects", icon: FolderOpen, label: "프로젝트" },
  { path: "/sessions", icon: MessageSquareText, label: "세션" },
  { path: "/context-recipes", icon: Layers, label: "Context Recipe" },
  { path: "/domain-configs", icon: Settings2, label: "도메인 설정" },
  { path: "/scheduled-jobs", icon: Clock, label: "스케줄" },
  { path: "/skills", icon: Sparkles, label: "스킬" },
  { path: "/prompt-templates", icon: FileText, label: "프롬프트 템플릿" },
  { path: "/auth", icon: Users, label: "사용자/권한" },
  { path: "/monitoring", icon: BarChart3, label: "모니터링" },
];

const PLATFORM_ITEMS: NavItemDef[] = [
  { path: "/platform/tenants", icon: Building2, label: "테넌트 관리" },
  { path: "/platform/subscriptions", icon: CreditCard, label: "구독 관리" },
  { path: "/platform/api-keys", icon: KeyRound, label: "API Key 관리" },
  { path: "/platform/monitoring", icon: Globe, label: "플랫폼 현황" },
];

const NavItem = ({ path, icon: Icon, label, exact }: NavItemDef) => {
  const location = useLocation();
  const isActive = exact
    ? location.pathname === path
    : location.pathname.startsWith(path);

  return (
    <NavLink
      to={path}
      end={exact}
      className={cn(
        "flex items-center gap-2.5 px-4 py-2 rounded-lg text-[13px] no-underline transition-all duration-150",
        isActive
          ? "font-semibold text-primary bg-muted border-l-2 border-primary -ml-0.5"
          : "font-normal text-muted-foreground hover:text-foreground hover:bg-accent border-l-2 border-transparent"
      )}
    >
      <Icon className="size-4 shrink-0" />
      <span>{label}</span>
    </NavLink>
  );
};

const ProjectSelector = () => {
  const currentProjectId = useSyncExternalStore(subscribe, getProjectId);
  const { data: projects = [] } = useProjects();

  return (
    <div className="px-3 py-2 border-b border-border">
      <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">
        프로젝트
      </div>
      <select
        value={currentProjectId ?? ""}
        onChange={(e) => setProjectId(e.target.value || null)}
        className="w-full px-2 py-1.5 rounded-md border border-border bg-accent text-xs text-foreground cursor-pointer outline-none focus:border-primary focus:ring-2 focus:ring-primary/10"
      >
        <option value="">전체 (프로젝트 미선택)</option>
        {projects.map((p) => (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        ))}
      </select>
    </div>
  );
};

export const Sidebar = () => {
  const navigate = useNavigate();

  const handleLogout = async () => {
    try {
      const token = localStorage.getItem("access_token");
      if (token) {
        await fetch("/api/v1/auth/logout", {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
        });
      }
    } catch {
      // 서버 오류여도 클라이언트 세션은 정리
    } finally {
      localStorage.removeItem("access_token");
      localStorage.removeItem("tenant_id");
      navigate("/login", { replace: true });
    }
  };

  return (
    <div className="w-[220px] min-w-[220px] h-screen bg-card border-r border-border flex flex-col overflow-hidden">
      {/* Logo */}
      <div className="px-5 pt-5 pb-4 border-b border-border">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-primary to-info flex items-center justify-center text-base shrink-0">
            <Zap className="size-4 text-white" />
          </div>
          <div>
            <div className="text-sm font-bold text-foreground">Aimbase</div>
            <div className="text-[10px] font-mono text-muted-foreground/60">
              v1.0.0
            </div>
          </div>
        </div>
      </div>

      {/* Project Selector */}
      <ProjectSelector />

      {/* Main nav */}
      <div className="flex-1 overflow-y-auto px-3 pt-3">
        <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider px-1 pb-2">
          관리
        </div>
        <nav className="flex flex-col gap-0.5">
          {NAV_ITEMS.map((item) => (
            <NavItem key={item.path} {...item} />
          ))}
        </nav>

        {/* Platform section */}
        <div className="mt-4 pt-3 border-t border-border">
          <div className="text-[10px] font-mono text-primary/50 uppercase tracking-wider px-1 pb-2">
            Super Admin
          </div>
          <nav className="flex flex-col gap-0.5">
            {PLATFORM_ITEMS.map((item) => (
              <NavItem key={item.path} {...item} />
            ))}
          </nav>
        </div>
      </div>

      {/* Footer */}
      <div className="px-4 py-3 border-t border-border flex items-center gap-2.5">
        <div className="w-8 h-8 rounded-full bg-muted border border-border flex items-center justify-center text-xs font-bold font-mono text-primary shrink-0">
          관
        </div>
        <div className="flex-1 overflow-hidden">
          <div className="text-xs font-semibold text-foreground truncate">
            관리자
          </div>
          <div className="text-[10px] font-mono text-muted-foreground/60 truncate">
            admin@platform.io
          </div>
        </div>
        <button
          onClick={handleLogout}
          title="로그아웃"
          className="p-1.5 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors cursor-pointer border-none bg-transparent"
        >
          <LogOut className="size-4" />
        </button>
      </div>
    </div>
  );
};
