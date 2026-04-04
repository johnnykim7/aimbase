import { useState } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { COLORS, FONTS } from "../../theme";

const NAV_ITEMS = [
  { path: "/", icon: "📋", label: "대시보드", exact: true },
  { path: "/connections", icon: "🔌", label: "연결 관리" },
  { path: "/connection-groups", icon: "🔗", label: "연결 그룹" },
  { path: "/mcp-servers", icon: "🔧", label: "MCP / Tool" },
  { path: "/schemas", icon: "📝", label: "스키마" },
  { path: "/policies", icon: "🛡️", label: "정책" },
  { path: "/prompts", icon: "💬", label: "프롬프트" },
  { path: "/workflows", icon: "⚡", label: "워크플로우" },
  { path: "/knowledge", icon: "📚", label: "Knowledge" },
  { path: "/auth", icon: "👤", label: "사용자/권한" },
  { path: "/monitoring", icon: "📊", label: "모니터링" },
  { path: "/guides", icon: "📖", label: "매뉴얼" },
];

const PLATFORM_ITEMS = [
  { path: "/platform/apps", icon: "📱", label: "App 관리" },
  { path: "/platform/tenants", icon: "🏢", label: "테넌트 관리" },
  { path: "/platform/subscriptions", icon: "💳", label: "구독 관리" },
  { path: "/platform/agent-accounts", icon: "🤖", label: "에이전트 계정" },
  { path: "/platform/monitoring", icon: "🌐", label: "플랫폼 현황" },
];

const NavItem = ({
  path,
  icon,
  label,
  exact,
}: {
  path: string;
  icon: string;
  label: string;
  exact?: boolean;
}) => {
  const [hover, setHover] = useState(false);
  const location = useLocation();
  const isActive = exact ? location.pathname === path : location.pathname.startsWith(path);

  return (
    <NavLink
      to={path}
      end={exact}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        padding: "9px 16px",
        borderRadius: 8,
        textDecoration: "none",
        fontSize: 13,
        fontFamily: FONTS.sans,
        fontWeight: isActive ? 600 : 400,
        color: isActive ? COLORS.accent : hover ? COLORS.text : COLORS.textMuted,
        background: isActive
          ? COLORS.surfaceActive
          : hover
          ? COLORS.surfaceHover
          : "transparent",
        borderLeft: isActive ? `2px solid ${COLORS.accent}` : "2px solid transparent",
        transition: "all 0.15s ease",
        marginLeft: isActive ? -2 : 0,
      }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <span style={{ fontSize: 16, width: 20, textAlign: "center" }}>{icon}</span>
      <span>{label}</span>
    </NavLink>
  );
};

export const Sidebar = () => (
  <div
    style={{
      width: 220,
      minWidth: 220,
      height: "100vh",
      background: COLORS.surface,
      borderRight: `1px solid ${COLORS.border}`,
      display: "flex",
      flexDirection: "column",
      overflow: "hidden",
    }}
  >
    {/* Logo */}
    <div
      style={{
        padding: "20px 20px 16px",
        borderBottom: `1px solid ${COLORS.border}`,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: 8,
            background: `linear-gradient(135deg, ${COLORS.accent}, ${COLORS.purple})`,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 16,
            flexShrink: 0,
          }}
        >
          ⚡
        </div>
        <div>
          <div
            style={{
              fontSize: 14,
              fontWeight: 700,
              fontFamily: FONTS.display,
              color: COLORS.text,
            }}
          >
            Aimbase
          </div>
          <div
            style={{ fontSize: 10, fontFamily: FONTS.mono, color: COLORS.textDim }}
          >
            v1.0.0
          </div>
        </div>
      </div>
    </div>

    {/* Main nav */}
    <div style={{ flex: 1, overflowY: "auto", padding: "12px 12px 0" }}>
      <div
        style={{
          fontSize: 10,
          fontFamily: FONTS.mono,
          color: COLORS.textDim,
          textTransform: "uppercase",
          letterSpacing: 1.2,
          padding: "4px 4px 8px",
        }}
      >
        관리
      </div>
      <nav style={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {NAV_ITEMS.map((item) => (
          <NavItem key={item.path} {...item} />
        ))}
      </nav>

      {/* Platform section */}
      <div
        style={{
          margin: "16px 0 8px",
          borderTop: `1px solid ${COLORS.border}`,
          paddingTop: 12,
        }}
      >
        <div
          style={{
            fontSize: 10,
            fontFamily: FONTS.mono,
            color: COLORS.accentDim,
            textTransform: "uppercase",
            letterSpacing: 1.2,
            padding: "0 4px 8px",
          }}
        >
          Super Admin
        </div>
        <nav style={{ display: "flex", flexDirection: "column", gap: 2 }}>
          {PLATFORM_ITEMS.map((item) => (
            <NavItem key={item.path} {...item} />
          ))}
        </nav>
      </div>
    </div>

    {/* Footer */}
    <div
      style={{
        padding: "12px 16px",
        borderTop: `1px solid ${COLORS.border}`,
        display: "flex",
        alignItems: "center",
        gap: 10,
      }}
    >
      <div
        style={{
          width: 32,
          height: 32,
          borderRadius: "50%",
          background: COLORS.surfaceActive,
          border: `1px solid ${COLORS.borderLight}`,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 13,
          fontWeight: 700,
          fontFamily: FONTS.mono,
          color: COLORS.accent,
          flexShrink: 0,
        }}
      >
        관
      </div>
      <div style={{ overflow: "hidden" }}>
        <div
          style={{
            fontSize: 12,
            fontWeight: 600,
            fontFamily: FONTS.sans,
            color: COLORS.text,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          관리자
        </div>
        <div
          style={{
            fontSize: 10,
            fontFamily: FONTS.mono,
            color: COLORS.textDim,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          admin@platform.io
        </div>
      </div>
    </div>
  </div>
);
