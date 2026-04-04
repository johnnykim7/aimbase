import { useState } from "react";
import ReactMarkdown from "react-markdown";
import { COLORS, FONTS } from "../theme";
import { ActionButton } from "../components/common/ActionButton";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { EmptyState } from "../components/common/EmptyState";
import { PageHeader } from "../components/layout/PageHeader";
import { useGuides, useGuide } from "../hooks/usePlatform";

const markdownComponents = {
  h1: ({ children, ...props }: React.HTMLAttributes<HTMLHeadingElement>) => (
    <h1 style={{ fontSize: 24, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: "32px 0 16px", borderBottom: `2px solid ${COLORS.border}`, paddingBottom: 8 }} {...props}>{children}</h1>
  ),
  h2: ({ children, ...props }: React.HTMLAttributes<HTMLHeadingElement>) => (
    <h2 style={{ fontSize: 20, fontWeight: 700, fontFamily: FONTS.display, color: COLORS.text, margin: "28px 0 12px", borderBottom: `1px solid ${COLORS.border}`, paddingBottom: 6 }} {...props}>{children}</h2>
  ),
  h3: ({ children, ...props }: React.HTMLAttributes<HTMLHeadingElement>) => (
    <h3 style={{ fontSize: 16, fontWeight: 600, fontFamily: FONTS.display, color: COLORS.text, margin: "20px 0 8px" }} {...props}>{children}</h3>
  ),
  p: ({ children, ...props }: React.HTMLAttributes<HTMLParagraphElement>) => (
    <p style={{ fontSize: 14, lineHeight: 1.7, color: COLORS.text, margin: "8px 0" }} {...props}>{children}</p>
  ),
  code: ({ children, className, ...props }: React.HTMLAttributes<HTMLElement>) => {
    const isBlock = className?.includes("language-");
    if (isBlock) {
      return (
        <code style={{ fontSize: 12, fontFamily: FONTS.mono, lineHeight: 1.6 }} className={className} {...props}>
          {children}
        </code>
      );
    }
    return (
      <code style={{
        fontSize: 12, fontFamily: FONTS.mono, background: COLORS.surfaceActive,
        padding: "2px 6px", borderRadius: 4, color: COLORS.accent,
      }} {...props}>
        {children}
      </code>
    );
  },
  pre: ({ children, ...props }: React.HTMLAttributes<HTMLPreElement>) => (
    <pre style={{
      background: COLORS.surfaceActive, border: `1px solid ${COLORS.border}`,
      borderRadius: 8, padding: 16, overflowX: "auto", margin: "12px 0",
    }} {...props}>
      {children}
    </pre>
  ),
  table: ({ children, ...props }: React.HTMLAttributes<HTMLTableElement>) => (
    <div style={{ overflowX: "auto", margin: "12px 0" }}>
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }} {...props}>
        {children}
      </table>
    </div>
  ),
  th: ({ children, ...props }: React.HTMLAttributes<HTMLTableCellElement>) => (
    <th style={{
      textAlign: "left", padding: "8px 12px", borderBottom: `2px solid ${COLORS.border}`,
      fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textMuted, textTransform: "uppercase",
      letterSpacing: 0.5, background: COLORS.surfaceHover,
    }} {...props}>
      {children}
    </th>
  ),
  td: ({ children, ...props }: React.HTMLAttributes<HTMLTableCellElement>) => (
    <td style={{ padding: "6px 12px", borderBottom: `1px solid ${COLORS.border}`, fontSize: 13 }} {...props}>
      {children}
    </td>
  ),
  blockquote: ({ children, ...props }: React.HTMLAttributes<HTMLQuoteElement>) => (
    <blockquote style={{
      borderLeft: `3px solid ${COLORS.accent}`, paddingLeft: 16, margin: "12px 0",
      color: COLORS.textMuted, fontSize: 13,
    }} {...props}>
      {children}
    </blockquote>
  ),
  a: ({ children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a style={{ color: COLORS.accent, textDecoration: "underline" }} {...props}>{children}</a>
  ),
  ul: ({ children, ...props }: React.HTMLAttributes<HTMLUListElement>) => (
    <ul style={{ paddingLeft: 24, margin: "8px 0", fontSize: 14, lineHeight: 1.7 }} {...props}>{children}</ul>
  ),
  ol: ({ children, ...props }: React.HTMLAttributes<HTMLOListElement>) => (
    <ol style={{ paddingLeft: 24, margin: "8px 0", fontSize: 14, lineHeight: 1.7 }} {...props}>{children}</ol>
  ),
  hr: (props: React.HTMLAttributes<HTMLHRElement>) => (
    <hr style={{ border: "none", borderTop: `1px solid ${COLORS.border}`, margin: "24px 0" }} {...props} />
  ),
};

export default function Guides() {
  const { data: guides = [], isLoading: loadingList } = useGuides();
  const [selectedSlug, setSelectedSlug] = useState<string | null>(null);
  const { data: guide, isLoading: loadingGuide } = useGuide(selectedSlug || "");

  if (loadingList) return <LoadingSpinner fullPage />;

  // 상세 뷰
  if (selectedSlug) {
    return (
      <div>
        <PageHeader
          title={guide?.title || "로딩 중..."}
          subtitle="매뉴얼"
          actions={
            <ActionButton variant="ghost" onClick={() => setSelectedSlug(null)}>
              ← 목록으로
            </ActionButton>
          }
        />
        {loadingGuide ? (
          <LoadingSpinner />
        ) : guide?.content ? (
          <div style={{
            background: COLORS.surface, borderRadius: 12,
            border: `1px solid ${COLORS.border}`, padding: "24px 32px",
            maxWidth: 900,
          }}>
            <ReactMarkdown components={markdownComponents}>
              {guide.content}
            </ReactMarkdown>
          </div>
        ) : (
          <EmptyState icon="📄" title="내용을 불러올 수 없습니다" />
        )}
      </div>
    );
  }

  // 목록 뷰
  return (
    <div>
      <PageHeader
        title="매뉴얼 / 가이드"
        subtitle="운영 가이드, API 사용법, 설치 매뉴얼 등"
      />
      {guides.length === 0 ? (
        <EmptyState icon="📖" title="등록된 가이드가 없습니다" description="docs/guides/ 디렉토리에 마크다운 파일을 추가하세요" />
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 16 }}>
          {guides.map((g) => (
            <div
              key={g.slug}
              onClick={() => setSelectedSlug(g.slug)}
              style={{
                background: COLORS.surface, border: `1px solid ${COLORS.border}`,
                borderRadius: 12, padding: "20px 24px", cursor: "pointer",
                transition: "all 0.15s",
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.borderColor = COLORS.accent;
                e.currentTarget.style.boxShadow = `0 2px 8px ${COLORS.accentDim}`;
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.borderColor = COLORS.border;
                e.currentTarget.style.boxShadow = "none";
              }}
            >
              <div style={{ fontSize: 24, marginBottom: 12 }}>📖</div>
              <div style={{ fontSize: 15, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 6 }}>
                {g.title}
              </div>
              <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textDim }}>
                {g.filename}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
