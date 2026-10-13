import { DataTable, type Column } from "../common/DataTable";
import { Badge } from "../common/Badge";
import type { SkillBundleSummary } from "../../types/skillBundle";
import { SCOPE_LABEL, bundleStatus, formatDateTime } from "./skillBundleText";

interface SkillBundleTableProps {
  bundles: SkillBundleSummary[];
  loading?: boolean;
  /** 플랫폼 화면은 범위가 하나라 범위 칸을 뺀다 */
  showScope: boolean;
  onSelect: (bundle: SkillBundleSummary) => void;
}

// 같은 이름이 범위별로 두 줄 나올 수 있어 범위까지 묶어 구분한다
export const bundleKey = (b: Pick<SkillBundleSummary, "scope" | "name">) => `${b.scope}:${b.name}`;

export function SkillBundleTable({ bundles, loading, showScope, onSelect }: SkillBundleTableProps) {
  const columns: Column<SkillBundleSummary>[] = [
    {
      header: "이름",
      width: "200px",
      render: (b) => <span className="font-semibold">{b.name}</span>,
    },
    {
      header: "설명",
      render: (b) => (
        <span className="block max-w-[520px] truncate text-muted-foreground" title={b.description}>
          {b.description}
        </span>
      ),
    },
    ...(showScope
      ? [
          {
            header: "범위",
            width: "100px",
            render: (b: SkillBundleSummary) => (
              <Badge color={b.scope === "tenant" ? "accent" : "purple"}>{SCOPE_LABEL[b.scope]}</Badge>
            ),
          },
        ]
      : []),
    {
      header: "최신 판",
      width: "90px",
      render: (b) => <span className="font-mono">판 {b.latest_version}</span>,
    },
    {
      header: "상태",
      width: "150px",
      render: (b) => {
        const s = bundleStatus(b);
        return <Badge color={s.color}>{s.label}</Badge>;
      },
    },
    {
      header: "고친 때",
      width: "150px",
      render: (b) => <span className="text-muted-foreground">{formatDateTime(b.updated_at)}</span>,
    },
  ];

  return (
    <DataTable
      columns={columns}
      data={bundles}
      loading={loading}
      keyExtractor={bundleKey}
      onRowClick={onSelect}
    />
  );
}
