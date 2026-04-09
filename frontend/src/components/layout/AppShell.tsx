import { createContext, useContext, useState, type ReactNode } from "react";
import { Outlet, useLocation } from "react-router-dom";
import {
  LayoutDashboard, PlugZap, Wrench, FileJson, Shield, MessageSquare,
  Zap, BookOpen, Target, FileText, FolderOpen, Users, BarChart3,
  Building2, CreditCard, KeyRound, Globe,
  MessageSquareText, Layers, Settings2,
} from "lucide-react";
import { Sidebar } from "./Sidebar";

/* ── 라우트별 정적 헤더 정의 (동기 렌더 → 깜박임 없음) ── */

interface RouteHeader {
  title: string;
  subtitle?: string;
  icon: ReactNode;
}

const ICON = "size-5";

const ROUTE_HEADERS: Record<string, RouteHeader> = {
  "/":                      { title: "대시보드", subtitle: "실시간 시스템 현황", icon: <LayoutDashboard className={ICON} /> },
  "/connections":           { title: "연결 관리", subtitle: "LLM 프로바이더 연결 설정 및 관리", icon: <PlugZap className={ICON} /> },
  "/mcp-servers":           { title: "MCP / Tool", subtitle: "Model Context Protocol 서버 관리", icon: <Wrench className={ICON} /> },
  "/schemas":               { title: "스키마 관리", subtitle: "데이터 구조 정의 및 JSON Schema 검증", icon: <FileJson className={ICON} /> },
  "/policies":              { title: "정책 관리", subtitle: "Policy Engine — 액션 실행 전 규칙 평가", icon: <Shield className={ICON} /> },
  "/prompts":               { title: "프롬프트 관리", subtitle: "LLM 프롬프트 템플릿 편집 및 버전 관리", icon: <MessageSquare className={ICON} /> },
  "/workflows":             { title: "워크플로우", subtitle: "DAG 기반 다단계 AI 오케스트레이션", icon: <Zap className={ICON} /> },
  "/knowledge":             { title: "Knowledge Base", subtitle: "RAG 소스 관리 및 벡터 검색", icon: <BookOpen className={ICON} /> },
  "/rag-evaluation":        { title: "RAG Quality Evaluation", subtitle: "RAGAS 메트릭으로 RAG 파이프라인 품질 측정", icon: <Target className={ICON} /> },
  "/documents":             { title: "Document Generation", subtitle: "AI 문서 생성 및 템플릿 관리", icon: <FileText className={ICON} /> },
  "/projects":              { title: "프로젝트 관리", subtitle: "회사 내 프로젝트를 생성하고 리소스를 할당합니다.", icon: <FolderOpen className={ICON} /> },
  "/auth":                  { title: "사용자/권한 관리", subtitle: "사용자 계정 및 역할 기반 접근 제어", icon: <Users className={ICON} /> },
  "/sessions":              { title: "세션 관리", subtitle: "AI 세션 목록 및 Tool Lineage 추적", icon: <MessageSquareText className={ICON} /> },
  "/context-recipes":       { title: "Context Recipe", subtitle: "컨텍스트 조립 전략 정의 및 소스 관리", icon: <Layers className={ICON} /> },
  "/domain-configs":        { title: "도메인 설정", subtitle: "도메인 앱별 기본 런타임, Recipe, 스코프 설정", icon: <Settings2 className={ICON} /> },
  "/monitoring":            { title: "모니터링", subtitle: "비용 추적 및 모델 성능", icon: <BarChart3 className={ICON} /> },
  "/platform/tenants":      { title: "테넌트 관리", subtitle: "Super Admin — 플랫폼 테넌트 전체 관리", icon: <Building2 className={ICON} /> },
  "/platform/subscriptions":{ title: "구독/쿼터 관리", subtitle: "Super Admin — 테넌트별 사용 한도 설정", icon: <CreditCard className={ICON} /> },
  "/platform/api-keys":     { title: "API Key 관리", subtitle: "Super Admin — 시스템 API Key 발급 및 관리", icon: <KeyRound className={ICON} /> },
  "/platform/monitoring":   { title: "플랫폼 현황", subtitle: "Super Admin — 전체 테넌트 사용량 총괄", icon: <Globe className={ICON} /> },
  "/platform/settings":    { title: "런타임 설정", subtitle: "Super Admin — 서버 재기동 없이 플랫폼 설정 미세조정", icon: <Settings2 className={ICON} /> },
};

function resolveHeader(pathname: string): RouteHeader | null {
  // 정확 매칭 우선
  if (ROUTE_HEADERS[pathname]) return ROUTE_HEADERS[pathname];
  // /workflows/:id → 워크플로우 상세 (edit 제외)
  if (/^\/workflows\/[^/]+$/.test(pathname) && !pathname.endsWith("/new")) {
    return { title: "워크플로우 상세", icon: <Zap className={ICON} /> };
  }
  // /sessions/:id → 세션 상세 (동적 오버라이드 사용)
  if (/^\/sessions\/[^/]+$/.test(pathname)) {
    return { title: "세션 상세", icon: <MessageSquareText className={ICON} /> };
  }
  return null; // WorkflowStudio 등 헤더 없는 페이지
}

/* ── 동적 actions Context (페이지별 버튼 등) ── */

type SetActions = (actions: ReactNode) => void;
const ActionsCtx = createContext<SetActions>(() => {});
export const useSetPageActions = () => useContext(ActionsCtx);

/* ── 동적 헤더 오버라이드 Context (WorkflowDetail 등) ── */

interface HeaderOverride { title?: string; subtitle?: string }
type SetHeaderOverride = (override: HeaderOverride | null) => void;
const HeaderOverrideCtx = createContext<SetHeaderOverride>(() => {});
export const useSetHeaderOverride = () => useContext(HeaderOverrideCtx);

/* ── AppShell ── */

export const AppShell = () => {
  const { pathname } = useLocation();
  const routeHeader = resolveHeader(pathname);

  const [actions, setActions] = useState<ReactNode>(null);
  const [override, setOverride] = useState<HeaderOverride | null>(null);

  const title = override?.title ?? routeHeader?.title;
  const subtitle = override?.subtitle ?? routeHeader?.subtitle;

  return (
    <ActionsCtx.Provider value={setActions}>
      <HeaderOverrideCtx.Provider value={setOverride}>
        <div className="flex h-screen overflow-hidden bg-background">
          <Sidebar />
          <main className="flex-1 flex flex-col overflow-hidden">
            {/* 페이지 헤더 — 셸 고정, 라우트에서 동기 렌더 */}
            {title && (
              <div className="shrink-0 bg-card border-b border-border px-7 py-5">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3.5">
                    {routeHeader?.icon && (
                      <div className="size-10 rounded-full bg-muted/80 border border-border flex items-center justify-center text-muted-foreground">
                        {routeHeader.icon}
                      </div>
                    )}
                    <div>
                      <h1 className="text-lg font-bold text-foreground leading-tight">{title}</h1>
                      {subtitle && (
                        <p className="text-[13px] text-muted-foreground mt-0.5">{subtitle}</p>
                      )}
                    </div>
                  </div>
                  {actions && (
                    <div className="flex items-center gap-2 [&_button]:text-xs [&_button]:py-1.5 [&_button]:px-3">
                      {actions}
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* 콘텐츠 영역 */}
            <div className="flex-1 overflow-y-auto">
              <Outlet />
            </div>
          </main>
        </div>
      </HeaderOverrideCtx.Provider>
    </ActionsCtx.Provider>
  );
};
