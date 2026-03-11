import { useState, useEffect, useRef } from "react";

const COLORS = {
  bg: "#0a0b0f",
  surface: "#12131a",
  surfaceHover: "#1a1b24",
  surfaceActive: "#22232e",
  border: "#2a2b38",
  borderLight: "#3a3b48",
  text: "#e8e9ed",
  textMuted: "#8b8d9a",
  textDim: "#5a5c6a",
  accent: "#22d3ee",
  accentDim: "#0e7490",
  success: "#34d399",
  successDim: "#065f46",
  warning: "#fbbf24",
  warningDim: "#78350f",
  danger: "#f87171",
  dangerDim: "#7f1d1d",
  purple: "#a78bfa",
  purpleDim: "#4c1d95",
};

const FONTS = {
  mono: "'JetBrains Mono', 'Fira Code', monospace",
  sans: "'DM Sans', 'Pretendard', sans-serif",
  display: "'Space Grotesk', 'Pretendard', sans-serif",
};

// --- Utility Components ---
const Badge = ({ color = "accent", children, pulse }) => {
  const colorMap = {
    accent: { bg: COLORS.accentDim + "60", text: COLORS.accent, dot: COLORS.accent },
    success: { bg: COLORS.successDim + "60", text: COLORS.success, dot: COLORS.success },
    warning: { bg: COLORS.warningDim + "60", text: COLORS.warning, dot: COLORS.warning },
    danger: { bg: COLORS.dangerDim + "60", text: COLORS.danger, dot: COLORS.danger },
    purple: { bg: COLORS.purpleDim + "60", text: COLORS.purple, dot: COLORS.purple },
    muted: { bg: COLORS.border, text: COLORS.textMuted, dot: COLORS.textDim },
  };
  const c = colorMap[color];
  return (
    <span style={{
      display: "inline-flex", alignItems: "center", gap: 6,
      padding: "3px 10px", borderRadius: 6, fontSize: 11, fontWeight: 600,
      fontFamily: FONTS.mono, background: c.bg, color: c.text, letterSpacing: 0.3,
    }}>
      {pulse && (
        <span style={{
          width: 6, height: 6, borderRadius: "50%", background: c.dot,
          animation: "pulse 2s ease-in-out infinite",
        }} />
      )}
      {children}
    </span>
  );
};

const StatCard = ({ label, value, sub, color = COLORS.accent }) => (
  <div style={{
    background: COLORS.surface, border: `1px solid ${COLORS.border}`,
    borderRadius: 12, padding: "20px 24px", flex: 1, minWidth: 160,
    borderTop: `2px solid ${color}`,
  }}>
    <div style={{ fontSize: 11, color: COLORS.textMuted, fontFamily: FONTS.mono, textTransform: "uppercase", letterSpacing: 1.2, marginBottom: 8 }}>{label}</div>
    <div style={{ fontSize: 32, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, lineHeight: 1 }}>{value}</div>
    {sub && <div style={{ fontSize: 12, color: COLORS.textMuted, marginTop: 6, fontFamily: FONTS.mono }}>{sub}</div>}
  </div>
);

const ActionButton = ({ children, variant = "default", onClick, icon, small }) => {
  const styles = {
    primary: { bg: COLORS.accent, text: "#000", hover: "#06b6d4" },
    danger: { bg: COLORS.danger, text: "#fff", hover: "#ef4444" },
    success: { bg: COLORS.success, text: "#000", hover: "#10b981" },
    default: { bg: COLORS.surfaceActive, text: COLORS.text, hover: COLORS.borderLight },
    ghost: { bg: "transparent", text: COLORS.textMuted, hover: COLORS.surfaceHover },
  };
  const s = styles[variant];
  const [hover, setHover] = useState(false);
  return (
    <button
      onClick={onClick}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        display: "inline-flex", alignItems: "center", gap: 6,
        padding: small ? "5px 12px" : "8px 16px",
        borderRadius: 8, border: variant === "ghost" ? `1px solid ${COLORS.border}` : "none",
        background: hover ? s.hover : s.bg, color: s.text,
        fontSize: small ? 12 : 13, fontWeight: 600, fontFamily: FONTS.sans,
        cursor: "pointer", transition: "all 0.15s ease",
      }}
    >
      {icon && <span style={{ fontSize: small ? 13 : 15 }}>{icon}</span>}
      {children}
    </button>
  );
};

const TableRow = ({ cells, onClick, highlight }) => {
  const [hover, setHover] = useState(false);
  return (
    <tr
      onClick={onClick}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        background: hover ? COLORS.surfaceHover : highlight ? COLORS.surfaceHover + "80" : "transparent",
        cursor: onClick ? "pointer" : "default", transition: "background 0.15s",
      }}
    >
      {cells.map((cell, i) => (
        <td key={i} style={{
          padding: "12px 16px", borderBottom: `1px solid ${COLORS.border}`,
          fontSize: 13, fontFamily: FONTS.sans, color: COLORS.text,
        }}>{cell}</td>
      ))}
    </tr>
  );
};

// --- Dashboard Page ---
const DashboardPage = () => {
  const [time, setTime] = useState(new Date());
  useEffect(() => { const t = setInterval(() => setTime(new Date()), 1000); return () => clearInterval(t); }, []);

  const logs = [
    { time: "14:23", status: "success", msg: "환불처리 ORD-0042", actions: "Write(DB) + Notify(Slack, 카카오톡, WS)" },
    { time: "14:22", status: "success", msg: "문의응답 CHAT-1129", actions: "Notify(WebSocket)" },
    { time: "14:21", status: "warning", msg: "환불처리 ORD-0099 ₩350,000", actions: "⏳ 승인대기 → #cs-managers" },
    { time: "14:20", status: "danger", msg: "카카오톡 발송 실패", actions: "재시도 2/3 진행 중" },
    { time: "14:18", status: "success", msg: "재고확인 + 교환처리 ORD-0038", actions: "Write(DB) + Notify(WS)" },
    { time: "14:15", status: "purple", msg: "PII 마스킹 적용", actions: "Policy: pii_masking → 전화번호 마스킹" },
    { time: "14:12", status: "success", msg: "배송문의 응답 CHAT-1128", actions: "Tool(query_order) + Notify(WS)" },
    { time: "14:10", status: "danger", msg: "DB 연결 타임아웃", actions: "자동 재연결 완료 (2.1s)" },
  ];

  const models = [
    { name: "Claude Sonnet", count: 847, cost: "$12.40", pct: 72 },
    { name: "Claude Haiku", count: 312, cost: "$0.89", pct: 26 },
    { name: "GPT-4o (폴백)", count: 23, cost: "$1.20", pct: 2 },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: 0 }}>대시보드</h1>
          <p style={{ fontSize: 12, color: COLORS.textMuted, fontFamily: FONTS.mono, margin: "4px 0 0" }}>
            {time.toLocaleTimeString("ko-KR")} · 실시간 업데이트
          </p>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          <ActionButton variant="ghost" icon="📊" small>리포트</ActionButton>
          <ActionButton variant="ghost" icon="⚙️" small>설정</ActionButton>
        </div>
      </div>

      <div style={{ display: "flex", gap: 16, marginBottom: 28, flexWrap: "wrap" }}>
        <StatCard label="총 요청" value="1,182" sub="전일 대비 +12%" color={COLORS.accent} />
        <StatCard label="처리 완료" value="1,156" sub="성공률 97.8%" color={COLORS.success} />
        <StatCard label="승인 대기" value="3" sub="평균 대기 8분" color={COLORS.warning} />
        <StatCard label="오늘 비용" value="$14.49" sub="예산 대비 62%" color={COLORS.purple} />
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 380px", gap: 20 }}>
        {/* Action Log */}
        <div style={{ background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, overflow: "hidden" }}>
          <div style={{ padding: "16px 20px", borderBottom: `1px solid ${COLORS.border}`, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>실시간 액션 로그</span>
            <Badge color="success" pulse>LIVE</Badge>
          </div>
          <div style={{ maxHeight: 400, overflowY: "auto" }}>
            {logs.map((log, i) => (
              <div key={i} style={{
                padding: "12px 20px", borderBottom: `1px solid ${COLORS.border}08`,
                display: "flex", gap: 12, alignItems: "flex-start",
                animation: i === 0 ? "fadeIn 0.5s ease" : undefined,
              }}>
                <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim, minWidth: 40, paddingTop: 2 }}>{log.time}</span>
                <Badge color={log.status}>
                  {log.status === "success" ? "✓" : log.status === "warning" ? "⏳" : log.status === "danger" ? "✕" : "🛡"}
                </Badge>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 13, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 2 }}>{log.msg}</div>
                  <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>{log.actions}</div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Right Panel */}
        <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
          {/* Model Usage */}
          <div style={{ background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, padding: 20 }}>
            <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 16 }}>모델별 사용량</div>
            {models.map((m, i) => (
              <div key={i} style={{ marginBottom: 14 }}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                  <span style={{ fontSize: 12, fontFamily: FONTS.sans, color: COLORS.text }}>{m.name}</span>
                  <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted }}>{m.count}건 · {m.cost}</span>
                </div>
                <div style={{ height: 6, background: COLORS.surfaceActive, borderRadius: 3, overflow: "hidden" }}>
                  <div style={{
                    height: "100%", borderRadius: 3,
                    width: `${m.pct}%`,
                    background: i === 0 ? COLORS.accent : i === 1 ? COLORS.purple : COLORS.warning,
                    transition: "width 1s ease",
                  }} />
                </div>
              </div>
            ))}
          </div>

          {/* Pending Approvals */}
          <div style={{ background: COLORS.surface, border: `1px solid ${COLORS.warning}30`, borderRadius: 12, padding: 20 }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
              <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>승인 대기</span>
              <Badge color="warning">{3}건</Badge>
            </div>
            {[
              { id: "ORD-0099", amount: "₩350,000", approver: "cs_manager", wait: "12분" },
              { id: "ORD-0105", amount: "₩1,200,000", approver: "ops_director", wait: "3분" },
              { id: "ORD-0112", amount: "₩280,000", approver: "cs_manager", wait: "1분" },
            ].map((item, i) => (
              <div key={i} style={{
                padding: "10px 12px", borderRadius: 8, marginBottom: 8,
                background: COLORS.surfaceHover, border: `1px solid ${COLORS.border}`,
              }}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                  <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.accent }}>{item.id}</span>
                  <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.warning }}>{item.amount}</span>
                </div>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ fontSize: 11, color: COLORS.textDim }}>{item.approver} · {item.wait} 대기</span>
                  <div style={{ display: "flex", gap: 4 }}>
                    <ActionButton variant="success" small icon="✓">승인</ActionButton>
                    <ActionButton variant="danger" small icon="✕">거부</ActionButton>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

// --- Connections Page ---
const ConnectionsPage = () => {
  const [showModal, setShowModal] = useState(false);
  const [selectedType, setSelectedType] = useState("database");
  const connections = [
    { id: "shop-main-db", adapter: "PostgreSQL", type: "Write", status: "connected", detail: "db.myshop.com:5432", health: "3초 전 ✅", icon: "🐘" },
    { id: "slack-workspace", adapter: "Slack", type: "Notify", status: "connected", detail: "#cs-general (기본)", health: "5초 전 ✅", icon: "💬" },
    { id: "kakaotalk-biz", adapter: "카카오톡", type: "Notify", status: "warning", detail: "일일 한도: 847/1,000", health: "2초 전 ⚠️", icon: "💛" },
    { id: "anthropic-api", adapter: "Claude", type: "LLM", status: "connected", detail: "claude-sonnet (기본)", health: "오늘 $12.40", icon: "🤖" },
    { id: "openai-api", adapter: "OpenAI", type: "LLM", status: "connected", detail: "gpt-4o (폴백)", health: "오늘 $1.20", icon: "🧠" },
    { id: "ws-internal", adapter: "WebSocket", type: "Notify", status: "connected", detail: "wss://realtime.myshop.com", health: "12 subscribers", icon: "🔌" },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: 0 }}>연결 관리</h1>
        <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>새 연결</ActionButton>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(340px, 1fr))", gap: 16 }}>
        {connections.map((conn) => (
          <div key={conn.id} style={{
            background: COLORS.surface, border: `1px solid ${COLORS.border}`,
            borderRadius: 12, padding: 20, position: "relative",
            borderLeft: `3px solid ${conn.status === "connected" ? COLORS.success : COLORS.warning}`,
          }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 12 }}>
              <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                <span style={{ fontSize: 24 }}>{conn.icon}</span>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.mono, color: COLORS.text }}>{conn.id}</div>
                  <div style={{ fontSize: 12, color: COLORS.textMuted }}>{conn.adapter}</div>
                </div>
              </div>
              <Badge color={conn.type === "Write" ? "accent" : conn.type === "Notify" ? "purple" : "warning"}>{conn.type}</Badge>
            </div>
            <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textDim, marginBottom: 8 }}>{conn.detail}</div>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>{conn.health}</span>
              <div style={{ display: "flex", gap: 4 }}>
                <ActionButton variant="ghost" small>테스트</ActionButton>
                <ActionButton variant="ghost" small>편집</ActionButton>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* New Connection Modal */}
      {showModal && (
        <div style={{
          position: "fixed", inset: 0, background: "rgba(0,0,0,0.7)", display: "flex",
          alignItems: "center", justifyContent: "center", zIndex: 1000, backdropFilter: "blur(4px)",
        }} onClick={() => setShowModal(false)}>
          <div style={{
            background: COLORS.surface, border: `1px solid ${COLORS.border}`,
            borderRadius: 16, padding: 32, width: 520, maxHeight: "80vh", overflowY: "auto",
          }} onClick={e => e.stopPropagation()}>
            <h2 style={{ fontSize: 18, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: "0 0 24px" }}>새 연결 추가</h2>

            <div style={{ marginBottom: 20 }}>
              <label style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1 }}>연결 유형</label>
              <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
                {[
                  { id: "database", icon: "🗄", label: "Database" },
                  { id: "messaging", icon: "💬", label: "Messaging" },
                  { id: "llm", icon: "🤖", label: "LLM" },
                  { id: "realtime", icon: "🔌", label: "Realtime" },
                ].map(t => (
                  <button key={t.id} onClick={() => setSelectedType(t.id)} style={{
                    flex: 1, padding: "12px 8px", borderRadius: 10,
                    border: `1px solid ${selectedType === t.id ? COLORS.accent : COLORS.border}`,
                    background: selectedType === t.id ? COLORS.accentDim + "30" : COLORS.surfaceHover,
                    color: selectedType === t.id ? COLORS.accent : COLORS.textMuted,
                    cursor: "pointer", textAlign: "center", transition: "all 0.15s",
                  }}>
                    <div style={{ fontSize: 20, marginBottom: 4 }}>{t.icon}</div>
                    <div style={{ fontSize: 11, fontFamily: FONTS.mono, fontWeight: 600 }}>{t.label}</div>
                  </button>
                ))}
              </div>
            </div>

            {selectedType === "database" && (
              <>
                <div style={{ marginBottom: 16 }}>
                  <label style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1 }}>어댑터</label>
                  <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
                    {["PostgreSQL", "MySQL", "MongoDB", "Redis"].map(a => (
                      <button key={a} style={{
                        padding: "8px 16px", borderRadius: 8, border: `1px solid ${COLORS.border}`,
                        background: a === "PostgreSQL" ? COLORS.accentDim + "30" : COLORS.surfaceHover,
                        color: a === "PostgreSQL" ? COLORS.accent : COLORS.textMuted,
                        cursor: "pointer", fontSize: 12, fontFamily: FONTS.mono,
                      }}>{a}</button>
                    ))}
                  </div>
                </div>
                {["연결 ID", "호스트", "포트", "데이터베이스", "사용자", "비밀번호"].map(field => (
                  <div key={field} style={{ marginBottom: 12 }}>
                    <label style={{ display: "block", fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, marginBottom: 6, textTransform: "uppercase", letterSpacing: 1 }}>{field}</label>
                    <input
                      type={field === "비밀번호" ? "password" : "text"}
                      placeholder={field === "포트" ? "5432" : field === "연결 ID" ? "my-database" : ""}
                      style={{
                        width: "100%", padding: "10px 14px", borderRadius: 8,
                        border: `1px solid ${COLORS.border}`, background: COLORS.surfaceActive,
                        color: COLORS.text, fontSize: 13, fontFamily: FONTS.mono,
                        outline: "none", boxSizing: "border-box",
                      }}
                    />
                  </div>
                ))}
                <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 20 }}>
                  <input type="checkbox" defaultChecked style={{ accentColor: COLORS.accent }} />
                  <span style={{ fontSize: 12, color: COLORS.textMuted }}>SSL 사용</span>
                </div>
              </>
            )}

            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
              <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
              <ActionButton variant="default" icon="🔍">연결 테스트</ActionButton>
              <ActionButton variant="primary" icon="💾">저장</ActionButton>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// --- Policies Page ---
const PoliciesPage = () => {
  const [expandedPolicy, setExpandedPolicy] = useState(null);
  const [simAmount, setSimAmount] = useState("");
  const [simResult, setSimResult] = useState(null);

  const policies = [
    {
      id: "refund_approval", name: "환불 금액별 승인", priority: 100, status: true, domain: "ecommerce",
      match: "process_refund → postgresql", ruleCount: 3, triggered: 47,
      rules: [
        { condition: "환불금액 < 200,000", action: "✅ 자동 승인", color: "success" },
        { condition: "200,000 ≤ 환불금액 < 1,000,000", action: "⏳ CS 팀장 승인", color: "warning" },
        { condition: "환불금액 ≥ 1,000,000", action: "⏳ 운영 총괄 승인", color: "danger" },
      ],
    },
    {
      id: "prod_db_protect", name: "프로덕션 DB 보호", priority: 200, status: true, domain: "system",
      match: "* → shop-main-db", ruleCount: 3, triggered: 12,
      rules: [
        { condition: "DROP / TRUNCATE 명령", action: "🚫 차단", color: "danger" },
        { condition: "조건 없는 DELETE", action: "🚫 차단", color: "danger" },
        { condition: "시간당 500건 초과", action: "⏳ Rate Limit", color: "warning" },
      ],
    },
    {
      id: "pii_masking", name: "개인정보 마스킹", priority: 300, status: true, domain: "compliance",
      match: "notify → slack", ruleCount: 1, triggered: 234,
      rules: [
        { condition: "phone, email, address 필드 감지", action: "🔒 마스킹 변환", color: "purple" },
      ],
    },
    {
      id: "noti_rate_limit", name: "고객 알림 발송 제한", priority: 100, status: true, domain: "ecommerce",
      match: "notify → kakaotalk", ruleCount: 1, triggered: 5,
      rules: [
        { condition: "고객 1명당 24시간 내 5건 초과", action: "🚫 차단", color: "danger" },
      ],
    },
  ];

  const runSimulation = () => {
    const amt = parseInt(simAmount.replace(/,/g, ""));
    if (isNaN(amt)) { setSimResult({ type: "error", msg: "금액을 입력해주세요" }); return; }
    if (amt < 200000) setSimResult({ type: "success", msg: `₩${amt.toLocaleString()} → ✅ 자동 승인` });
    else if (amt < 1000000) setSimResult({ type: "warning", msg: `₩${amt.toLocaleString()} → ⏳ CS 팀장 승인 필요 (slack:#cs-managers, 2시간 타임아웃)` });
    else setSimResult({ type: "danger", msg: `₩${amt.toLocaleString()} → ⏳ 운영 총괄 승인 필요 (slack:#ops-approvals, 24시간 타임아웃)` });
  };

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: 0 }}>정책 관리</h1>
        <ActionButton variant="primary" icon="+">새 정책</ActionButton>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {policies.map((policy) => (
          <div key={policy.id} style={{
            background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, overflow: "hidden",
          }}>
            <div
              onClick={() => setExpandedPolicy(expandedPolicy === policy.id ? null : policy.id)}
              style={{
                padding: "16px 20px", cursor: "pointer", display: "flex", justifyContent: "space-between", alignItems: "center",
              }}
            >
              <div style={{ display: "flex", gap: 16, alignItems: "center" }}>
                <div style={{
                  width: 8, height: 8, borderRadius: "50%",
                  background: policy.status ? COLORS.success : COLORS.textDim,
                }} />
                <div>
                  <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>{policy.name}</div>
                  <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim, marginTop: 2 }}>
                    {policy.match} · 규칙 {policy.ruleCount}개 · 오늘 {policy.triggered}회 발동
                  </div>
                </div>
              </div>
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                <Badge color="muted">P{policy.priority}</Badge>
                <Badge color="accent">{policy.domain}</Badge>
                <span style={{ color: COLORS.textDim, fontSize: 18, transform: expandedPolicy === policy.id ? "rotate(180deg)" : "", transition: "transform 0.2s" }}>▾</span>
              </div>
            </div>

            {expandedPolicy === policy.id && (
              <div style={{ padding: "0 20px 20px", borderTop: `1px solid ${COLORS.border}` }}>
                <div style={{ padding: "16px 0" }}>
                  <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textMuted, marginBottom: 12, textTransform: "uppercase", letterSpacing: 1 }}>규칙</div>
                  {policy.rules.map((rule, i) => (
                    <div key={i} style={{
                      display: "flex", alignItems: "center", gap: 12, padding: "10px 16px",
                      background: COLORS.surfaceHover, borderRadius: 8, marginBottom: 6,
                      borderLeft: `3px solid ${COLORS[rule.color]}`,
                    }}>
                      <div style={{ flex: 1 }}>
                        <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textMuted }}>IF </span>
                        <span style={{ fontSize: 13, fontFamily: FONTS.sans, color: COLORS.text }}>{rule.condition}</span>
                      </div>
                      <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textMuted }}>THEN</span>
                      <Badge color={rule.color}>{rule.action}</Badge>
                    </div>
                  ))}
                </div>

                {policy.id === "refund_approval" && (
                  <div style={{
                    padding: 16, background: COLORS.surfaceActive, borderRadius: 10,
                    border: `1px dashed ${COLORS.border}`,
                  }}>
                    <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textMuted, marginBottom: 10, textTransform: "uppercase", letterSpacing: 1 }}>🧪 시뮬레이션</div>
                    <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                      <span style={{ fontSize: 13, color: COLORS.textMuted }}>환불 금액:</span>
                      <input
                        type="text" value={simAmount} onChange={e => setSimAmount(e.target.value)}
                        placeholder="350,000"
                        style={{
                          padding: "8px 12px", borderRadius: 8, border: `1px solid ${COLORS.border}`,
                          background: COLORS.surface, color: COLORS.text, fontSize: 13, fontFamily: FONTS.mono,
                          width: 160, outline: "none",
                        }}
                        onKeyDown={e => e.key === "Enter" && runSimulation()}
                      />
                      <span style={{ fontSize: 13, color: COLORS.textDim }}>원</span>
                      <ActionButton variant="primary" small onClick={runSimulation}>테스트</ActionButton>
                    </div>
                    {simResult && (
                      <div style={{
                        marginTop: 10, padding: "8px 12px", borderRadius: 6,
                        background: COLORS[simResult.type + "Dim"] + "40",
                        fontSize: 13, fontFamily: FONTS.mono, color: COLORS[simResult.type] || COLORS.text,
                      }}>{simResult.msg}</div>
                    )}
                  </div>
                )}

                <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
                  <ActionButton variant="ghost" small icon="✏️">편집</ActionButton>
                  <ActionButton variant="ghost" small icon="📋">복제</ActionButton>
                  <ActionButton variant="ghost" small icon="📊">로그</ActionButton>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};

// --- Workflows Page ---
const WorkflowsPage = () => {
  const steps = [
    { id: "lookup", label: "🔍 주문 조회", type: "Tool Call", tool: "query_order", status: "success" },
    { id: "judge", label: "🤖 환불 판단", type: "LLM Call", model: "claude-sonnet", status: "success" },
    { id: "gate", label: "❓ 분기", type: "Condition", status: "active" },
    { id: "refund", label: "💰 환불 실행", type: "Parallel", children: ["DB 저장", "Slack 알림", "카톡 알림", "WS 갱신"], status: "pending" },
    { id: "alt", label: "🔄 대안 제시", type: "LLM Call", status: "disabled" },
    { id: "escalate", label: "👤 상담원 연결", type: "Notify", status: "disabled" },
  ];

  const workflows = [
    { id: "cs_refund", name: "CS 환불 처리", trigger: "intent: refund", steps: 6, runs: 47, success: "95.7%", active: true },
    { id: "cs_exchange", name: "CS 교환 처리", trigger: "intent: exchange", steps: 5, runs: 23, success: "100%", active: true },
    { id: "cs_inquiry", name: "일반 문의 응답", trigger: "intent: inquiry", steps: 3, runs: 312, success: "99.4%", active: true },
    { id: "daily_report", name: "일일 리포트 생성", trigger: "cron: 09:00", steps: 4, runs: 22, success: "100%", active: true },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: 0 }}>워크플로우</h1>
        <ActionButton variant="primary" icon="+">새 워크플로우</ActionButton>
      </div>

      {/* Workflow List */}
      <div style={{ background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, overflow: "hidden", marginBottom: 24 }}>
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: COLORS.surfaceHover }}>
              {["이름", "트리거", "스텝", "실행 수", "성공률", "상태", ""].map((h, i) => (
                <th key={i} style={{
                  padding: "10px 16px", textAlign: "left", fontSize: 11, fontFamily: FONTS.mono,
                  color: COLORS.textDim, textTransform: "uppercase", letterSpacing: 1,
                  borderBottom: `1px solid ${COLORS.border}`,
                }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {workflows.map(wf => (
              <TableRow key={wf.id} cells={[
                <span style={{ fontWeight: 600 }}>{wf.name}</span>,
                <span style={{ fontFamily: FONTS.mono, fontSize: 12, color: COLORS.textMuted }}>{wf.trigger}</span>,
                wf.steps,
                wf.runs,
                <Badge color={parseFloat(wf.success) > 99 ? "success" : "warning"}>{wf.success}</Badge>,
                <Badge color={wf.active ? "success" : "muted"} pulse={wf.active}>{wf.active ? "활성" : "비활성"}</Badge>,
                <ActionButton variant="ghost" small>편집</ActionButton>,
              ]} />
            ))}
          </tbody>
        </table>
      </div>

      {/* Visual Flow Editor Preview */}
      <div style={{
        background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, padding: 24,
      }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 20 }}>
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>CS 환불 처리</div>
            <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textDim, marginTop: 2 }}>cs_refund_flow · 6 steps · trigger: intent match</div>
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <ActionButton variant="default" small icon="▶">테스트 실행</ActionButton>
            <ActionButton variant="primary" small icon="💾">저장</ActionButton>
          </div>
        </div>

        {/* Visual Flow */}
        <div style={{
          background: COLORS.bg, borderRadius: 12, padding: 32,
          border: `1px solid ${COLORS.border}`, position: "relative", overflow: "hidden",
        }}>
          {/* Grid Background */}
          <div style={{
            position: "absolute", inset: 0, opacity: 0.05,
            backgroundImage: `radial-gradient(${COLORS.textDim} 1px, transparent 1px)`,
            backgroundSize: "24px 24px",
          }} />

          <div style={{ position: "relative", display: "flex", flexDirection: "column", alignItems: "center", gap: 0 }}>
            {steps.map((step, i) => (
              <div key={step.id} style={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
                <div style={{
                  padding: "12px 24px", borderRadius: 10, minWidth: 200, textAlign: "center",
                  background: step.status === "active" ? COLORS.accentDim + "30" : step.status === "disabled" ? COLORS.surfaceHover : COLORS.surface,
                  border: `1px solid ${step.status === "active" ? COLORS.accent : step.status === "success" ? COLORS.success + "60" : COLORS.border}`,
                  opacity: step.status === "disabled" ? 0.4 : 1,
                  boxShadow: step.status === "active" ? `0 0 20px ${COLORS.accent}20` : "none",
                }}>
                  <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 4 }}>
                    {step.label}
                  </div>
                  <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>
                    {step.type}{step.tool ? ` · ${step.tool}` : ""}{step.model ? ` · ${step.model}` : ""}
                  </div>
                  {step.children && (
                    <div style={{ display: "flex", gap: 6, marginTop: 8, justifyContent: "center", flexWrap: "wrap" }}>
                      {step.children.map(c => (
                        <span key={c} style={{
                          padding: "3px 8px", borderRadius: 4, fontSize: 10,
                          background: COLORS.surfaceActive, color: COLORS.textMuted,
                          fontFamily: FONTS.mono,
                        }}>{c}</span>
                      ))}
                    </div>
                  )}
                </div>

                {i < steps.length - 1 && (
                  <div style={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
                    <div style={{ width: 1, height: i === 2 ? 12 : 20, background: COLORS.border }} />
                    {i === 2 ? (
                      <div style={{ display: "flex", gap: 40, padding: "8px 0" }}>
                        {["환불 가능", "환불 불가", "판단 불가"].map((label, j) => (
                          <div key={j} style={{ textAlign: "center" }}>
                            <div style={{ fontSize: 10, fontFamily: FONTS.mono, color: j === 0 ? COLORS.success : COLORS.textDim, marginBottom: 4 }}>{label}</div>
                            <div style={{ width: 1, height: 12, background: COLORS.border, margin: "0 auto" }} />
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div style={{ color: COLORS.textDim, fontSize: 10 }}>▼</div>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>

          {/* Node Palette */}
          <div style={{
            marginTop: 24, padding: "12px 16px", background: COLORS.surface, borderRadius: 10,
            border: `1px solid ${COLORS.border}`, display: "flex", gap: 8, justifyContent: "center", flexWrap: "wrap",
          }}>
            <span style={{ fontSize: 11, color: COLORS.textDim, fontFamily: FONTS.mono, alignSelf: "center", marginRight: 8 }}>노드 팔레트:</span>
            {["🤖 LLM 호출", "🔧 Tool 호출", "💾 Write", "📢 Notify", "❓ 조건분기", "⏸ 승인대기", "🔀 병렬실행", "👤 사람입력"].map(n => (
              <span key={n} style={{
                padding: "6px 12px", borderRadius: 6, fontSize: 12,
                background: COLORS.surfaceActive, color: COLORS.textMuted,
                border: `1px dashed ${COLORS.border}`, cursor: "grab",
                fontFamily: FONTS.sans,
              }}>{n}</span>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

// --- Prompts Page ---
const PromptsPage = () => {
  const [activePrompt, setActivePrompt] = useState("ecommerce_cs_agent");
  const prompts = [
    { id: "ecommerce_cs_agent", name: "CS 에이전트", domain: "ecommerce", version: 3, abTest: true },
    { id: "refund_confirm_kakao", name: "환불 확인 알림 (카카오톡)", domain: "ecommerce", version: 2, abTest: false },
    { id: "exchange_guide", name: "교환 안내", domain: "ecommerce", version: 1, abTest: false },
    { id: "daily_report_gen", name: "일일 보고서 생성", domain: "analytics", version: 4, abTest: true },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: 0 }}>프롬프트 관리</h1>
        <ActionButton variant="primary" icon="+">새 프롬프트</ActionButton>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "280px 1fr", gap: 20 }}>
        {/* Prompt List */}
        <div style={{
          background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, overflow: "hidden",
        }}>
          <div style={{
            padding: "12px 16px", borderBottom: `1px solid ${COLORS.border}`,
            fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim, textTransform: "uppercase", letterSpacing: 1,
          }}>프롬프트 목록</div>
          {prompts.map(p => (
            <div
              key={p.id}
              onClick={() => setActivePrompt(p.id)}
              style={{
                padding: "12px 16px", cursor: "pointer",
                background: activePrompt === p.id ? COLORS.surfaceActive : "transparent",
                borderLeft: activePrompt === p.id ? `2px solid ${COLORS.accent}` : "2px solid transparent",
                borderBottom: `1px solid ${COLORS.border}08`,
              }}
            >
              <div style={{ fontSize: 13, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>{p.name}</div>
              <div style={{ display: "flex", gap: 6, marginTop: 4 }}>
                <Badge color="muted">v{p.version}</Badge>
                <Badge color="accent">{p.domain}</Badge>
                {p.abTest && <Badge color="warning">A/B</Badge>}
              </div>
            </div>
          ))}
        </div>

        {/* Editor */}
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          <div style={{
            background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, overflow: "hidden",
          }}>
            <div style={{
              padding: "12px 20px", borderBottom: `1px solid ${COLORS.border}`,
              display: "flex", justifyContent: "space-between", alignItems: "center",
            }}>
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>CS 에이전트</span>
                <Badge color="success">v3 운영중</Badge>
              </div>
              <div style={{ display: "flex", gap: 8 }}>
                <ActionButton variant="ghost" small>버전 비교</ActionButton>
                <ActionButton variant="primary" small icon="💾">저장 (v4)</ActionButton>
              </div>
            </div>
            <div style={{
              padding: 20, fontFamily: FONTS.mono, fontSize: 13, lineHeight: 1.7,
              color: COLORS.textMuted, background: COLORS.bg, minHeight: 280,
              whiteSpace: "pre-wrap",
            }}>
{`당신은 '마이쇼핑몰'의 CS 에이전트입니다.

## 역할
- 고객 문의를 친절하고 정확하게 처리합니다
- 주문 조회, 환불, 교환, 배송 문의를 처리할 수 있습니다

## 사용 가능한 Tool
- query_order: 주문번호로 주문 정보 조회
- get_customer: 고객 ID로 고객 정보 조회
- check_refund_eligibility: 환불 가능 여부 확인

## 환불 규칙
- 배송 완료 후 7일 이내: 전액 환불 가능
- 7~14일: 수수료 10% 차감 후 환불
- 14일 초과: 환불 불가 (교환만 가능)

## 응답 규칙
- 반드시 주문 조회를 먼저 수행한 후 판단하세요
- 고객에게는 존칭을 사용합니다 (고객님)`}
            </div>
            <div style={{
              padding: "10px 20px", borderTop: `1px solid ${COLORS.border}`,
              display: "flex", gap: 6, flexWrap: "wrap",
            }}>
              <span style={{ fontSize: 11, color: COLORS.textDim, fontFamily: FONTS.mono, alignSelf: "center", marginRight: 4 }}>변수 삽입:</span>
              {["{{shop_name}}", "{{refund_policy}}", "{{available_tools}}", "{{tone_guide}}"].map(v => (
                <span key={v} style={{
                  padding: "4px 10px", borderRadius: 4, fontSize: 11,
                  background: COLORS.surfaceActive, color: COLORS.accent,
                  fontFamily: FONTS.mono, cursor: "pointer", border: `1px solid ${COLORS.border}`,
                }}>{v}</span>
              ))}
            </div>
          </div>

          {/* A/B Test Panel */}
          <div style={{
            background: COLORS.surface, border: `1px solid ${COLORS.warning}30`, borderRadius: 12, padding: 20,
          }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>A/B 테스트</span>
                <Badge color="warning" pulse>진행 중</Badge>
              </div>
              <ActionButton variant="ghost" small>테스트 종료</ActionButton>
            </div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
              {[
                { label: "A (v3 현재)", pct: 60, satisfaction: 4.2, time: "23초", color: COLORS.accent },
                { label: "B (v3 실험)", pct: 40, satisfaction: 4.5, time: "28초", color: COLORS.warning },
              ].map((v, i) => (
                <div key={i} style={{
                  padding: 16, borderRadius: 10, background: COLORS.surfaceHover,
                  border: `1px solid ${COLORS.border}`,
                }}>
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 10 }}>
                    <span style={{ fontSize: 13, fontWeight: 600, color: v.color, fontFamily: FONTS.mono }}>{v.label}</span>
                    <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textDim }}>{v.pct}% 트래픽</span>
                  </div>
                  <div style={{ display: "flex", justifyContent: "space-between" }}>
                    <div>
                      <div style={{ fontSize: 11, color: COLORS.textDim }}>만족도</div>
                      <div style={{ fontSize: 20, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text }}>{v.satisfaction}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: 11, color: COLORS.textDim }}>처리시간</div>
                      <div style={{ fontSize: 20, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text }}>{v.time}</div>
                    </div>
                  </div>
                  {i === 1 && (
                    <ActionButton variant="success" small icon="⬆" onClick={() => {}}>B를 승격</ActionButton>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

// --- Schemas Page ---
const SchemasPage = () => {
  const schemas = [
    {
      id: "refund_request", version: 1, domain: "ecommerce", fields: 5,
      desc: "환불 처리 시 orders 테이블에 기록할 데이터",
      sample: `{ "order_id": "ORD-20250218-0042", "status": "refund_requested", "refund_amount": 159000, "refund_reason": "사이즈 불일치" }`,
    },
    { id: "cs_log_entry", version: 1, domain: "ecommerce", fields: 6, desc: "CS 상담 로그" },
    { id: "kakao_notification", version: 1, domain: "ecommerce", fields: 2, desc: "카카오톡 알림 템플릿 데이터" },
    { id: "daily_report", version: 2, domain: "analytics", fields: 8, desc: "일일 분석 보고서 데이터" },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: 0 }}>스키마 관리</h1>
        <ActionButton variant="primary" icon="+">새 스키마</ActionButton>
      </div>

      <div style={{ background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, overflow: "hidden" }}>
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: COLORS.surfaceHover }}>
              {["ID", "설명", "도메인", "버전", "필드 수", ""].map((h, i) => (
                <th key={i} style={{
                  padding: "10px 16px", textAlign: "left", fontSize: 11, fontFamily: FONTS.mono,
                  color: COLORS.textDim, textTransform: "uppercase", letterSpacing: 1,
                  borderBottom: `1px solid ${COLORS.border}`,
                }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {schemas.map(s => (
              <TableRow key={s.id} cells={[
                <span style={{ fontFamily: FONTS.mono, color: COLORS.accent, fontWeight: 600 }}>{s.id}</span>,
                s.desc,
                <Badge color="accent">{s.domain}</Badge>,
                <Badge color="muted">v{s.version}</Badge>,
                s.fields,
                <div style={{ display: "flex", gap: 4 }}>
                  <ActionButton variant="ghost" small>편집</ActionButton>
                  <ActionButton variant="ghost" small>검증 테스트</ActionButton>
                </div>,
              ]} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

// --- MCP Page ---
const MCPPage = () => {
  const servers = [
    {
      id: "order-service", name: "주문 관리 MCP", status: "connected", transport: "HTTP",
      url: "https://mcp.myshop.com/orders",
      tools: ["query_order", "cancel_order", "get_order_history", "update_order_status"],
    },
    {
      id: "customer-service", name: "고객 정보 MCP", status: "connected", transport: "HTTP",
      url: "https://mcp.myshop.com/customers",
      tools: ["get_customer", "get_purchase_history", "check_refund_eligibility"],
    },
    {
      id: "inventory-service", name: "재고 관리 MCP", status: "connected", transport: "stdio",
      url: "node /opt/mcp-servers/inventory/index.js",
      tools: ["check_stock", "reserve_item", "release_item"],
    },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: 0 }}>MCP / Tool 관리</h1>
        <ActionButton variant="primary" icon="+">MCP 서버 등록</ActionButton>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        {servers.map(server => (
          <div key={server.id} style={{
            background: COLORS.surface, border: `1px solid ${COLORS.border}`, borderRadius: 12, padding: 20,
            borderLeft: `3px solid ${COLORS.success}`,
          }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 12 }}>
              <div>
                <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 4 }}>
                  <span style={{ fontSize: 16, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>{server.name}</span>
                  <Badge color="success" pulse>연결됨</Badge>
                  <Badge color="muted">{server.transport}</Badge>
                </div>
                <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textDim }}>{server.url}</div>
              </div>
              <div style={{ display: "flex", gap: 4 }}>
                <ActionButton variant="ghost" small>새로고침</ActionButton>
                <ActionButton variant="ghost" small>편집</ActionButton>
              </div>
            </div>
            <div style={{
              fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim, marginBottom: 10,
              textTransform: "uppercase", letterSpacing: 1,
            }}>사용 가능한 Tool ({server.tools.length})</div>
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
              {server.tools.map(tool => (
                <span key={tool} style={{
                  padding: "6px 12px", borderRadius: 6, fontSize: 12,
                  background: COLORS.surfaceActive, color: COLORS.accent,
                  fontFamily: FONTS.mono, border: `1px solid ${COLORS.accentDim}40`,
                  cursor: "pointer",
                }}>🔧 {tool}</span>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

// --- Main App ---
const PAGES = [
  { id: "dashboard", icon: "📋", label: "대시보드" },
  { id: "connections", icon: "🔌", label: "연결 관리" },
  { id: "mcp", icon: "🔧", label: "MCP / Tool" },
  { id: "schemas", icon: "📝", label: "스키마" },
  { id: "policies", icon: "🛡️", label: "정책" },
  { id: "prompts", icon: "💬", label: "프롬프트" },
  { id: "workflows", icon: "⚡", label: "워크플로우" },
];

export default function App() {
  const [page, setPage] = useState("dashboard");

  const renderPage = () => {
    switch (page) {
      case "dashboard": return <DashboardPage />;
      case "connections": return <ConnectionsPage />;
      case "policies": return <PoliciesPage />;
      case "workflows": return <WorkflowsPage />;
      case "prompts": return <PromptsPage />;
      case "schemas": return <SchemasPage />;
      case "mcp": return <MCPPage />;
      default: return <DashboardPage />;
    }
  };

  return (
    <div style={{ display: "flex", height: "100vh", background: COLORS.bg, fontFamily: FONTS.sans, color: COLORS.text, overflow: "hidden" }}>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&family=Space+Grotesk:wght@400;500;600;700&display=swap');
        * { margin: 0; padding: 0; box-sizing: border-box; }
        ::-webkit-scrollbar { width: 6px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: ${COLORS.border}; border-radius: 3px; }
        ::-webkit-scrollbar-thumb:hover { background: ${COLORS.borderLight}; }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
        input:focus { border-color: ${COLORS.accent} !important; box-shadow: 0 0 0 2px ${COLORS.accentDim}40; }
      `}</style>

      {/* Sidebar */}
      <div style={{
        width: 220, background: COLORS.surface, borderRight: `1px solid ${COLORS.border}`,
        display: "flex", flexDirection: "column", flexShrink: 0,
      }}>
        {/* Logo */}
        <div style={{
          padding: "20px 20px 16px", borderBottom: `1px solid ${COLORS.border}`,
        }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <div style={{
              width: 32, height: 32, borderRadius: 8,
              background: `linear-gradient(135deg, ${COLORS.accent}, ${COLORS.purple})`,
              display: "flex", alignItems: "center", justifyContent: "center",
              fontSize: 16, fontWeight: 700,
            }}>⚡</div>
            <div>
              <div style={{ fontSize: 14, fontWeight: 700, fontFamily: FONTS.display, letterSpacing: -0.5 }}>LLM Platform</div>
              <div style={{ fontSize: 10, fontFamily: FONTS.mono, color: COLORS.textDim }}>v1.0.0</div>
            </div>
          </div>
        </div>

        {/* Navigation */}
        <nav style={{ flex: 1, padding: "12px 8px", display: "flex", flexDirection: "column", gap: 2 }}>
          {PAGES.map(p => (
            <button
              key={p.id}
              onClick={() => setPage(p.id)}
              style={{
                display: "flex", alignItems: "center", gap: 10,
                padding: "10px 12px", borderRadius: 8, border: "none",
                background: page === p.id ? COLORS.surfaceActive : "transparent",
                color: page === p.id ? COLORS.text : COLORS.textMuted,
                cursor: "pointer", fontSize: 13, fontFamily: FONTS.sans,
                fontWeight: page === p.id ? 600 : 400,
                transition: "all 0.15s",
                borderLeft: page === p.id ? `2px solid ${COLORS.accent}` : "2px solid transparent",
              }}
            >
              <span style={{ fontSize: 16, width: 24, textAlign: "center" }}>{p.icon}</span>
              {p.label}
            </button>
          ))}
        </nav>

        {/* Footer */}
        <div style={{
          padding: "16px 16px", borderTop: `1px solid ${COLORS.border}`,
          display: "flex", alignItems: "center", gap: 10,
        }}>
          <div style={{
            width: 32, height: 32, borderRadius: 8, background: COLORS.surfaceActive,
            display: "flex", alignItems: "center", justifyContent: "center",
            fontSize: 13, fontWeight: 600, color: COLORS.accent,
          }}>관</div>
          <div>
            <div style={{ fontSize: 12, fontWeight: 600, color: COLORS.text }}>관리자</div>
            <div style={{ fontSize: 10, fontFamily: FONTS.mono, color: COLORS.textDim }}>admin@myshop.com</div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div style={{ flex: 1, overflow: "auto", padding: 32 }}>
        {renderPage()}
      </div>
    </div>
  );
}
