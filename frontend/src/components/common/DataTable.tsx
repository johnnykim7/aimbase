import { useState } from "react";
import { COLORS, FONTS } from "../../theme";
import { LoadingSpinner } from "./LoadingSpinner";

export interface Column<T> {
  header: string;
  render: (row: T) => React.ReactNode;
  width?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  onRowClick?: (row: T) => void;
  keyExtractor: (row: T) => string;
  loading?: boolean;
  emptyMessage?: string;
}

export function DataTable<T>({
  columns,
  data,
  onRowClick,
  keyExtractor,
  loading,
  emptyMessage = "데이터가 없습니다",
}: DataTableProps<T>) {
  const [hoveredRow, setHoveredRow] = useState<string | null>(null);

  if (loading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div
      style={{
        background: COLORS.surface,
        border: `1px solid ${COLORS.border}`,
        borderRadius: 12,
        overflow: "hidden",
      }}
    >
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ borderBottom: `1px solid ${COLORS.border}` }}>
            {columns.map((col, i) => (
              <th
                key={i}
                style={{
                  padding: "12px 16px",
                  textAlign: "left",
                  fontSize: 11,
                  fontFamily: FONTS.mono,
                  color: COLORS.textMuted,
                  textTransform: "uppercase",
                  letterSpacing: 1,
                  fontWeight: 600,
                  width: col.width,
                  background: COLORS.surfaceHover,
                }}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.length === 0 ? (
            <tr>
              <td
                colSpan={columns.length}
                style={{
                  padding: 32,
                  textAlign: "center",
                  color: COLORS.textDim,
                  fontFamily: FONTS.mono,
                  fontSize: 13,
                }}
              >
                {emptyMessage}
              </td>
            </tr>
          ) : (
            data.map((row) => {
              const key = keyExtractor(row);
              return (
                <tr
                  key={key}
                  onClick={() => onRowClick?.(row)}
                  onMouseEnter={() => setHoveredRow(key)}
                  onMouseLeave={() => setHoveredRow(null)}
                  style={{
                    background: hoveredRow === key ? COLORS.surfaceHover : "transparent",
                    cursor: onRowClick ? "pointer" : "default",
                    transition: "background 0.15s",
                  }}
                >
                  {columns.map((col, i) => (
                    <td
                      key={i}
                      style={{
                        padding: "12px 16px",
                        borderBottom: `1px solid ${COLORS.border}`,
                        fontSize: 13,
                        fontFamily: FONTS.sans,
                        color: COLORS.text,
                      }}
                    >
                      {col.render(row)}
                    </td>
                  ))}
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
