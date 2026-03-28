import { cn } from "@/lib/utils";
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
  if (loading) {
    return (
      <div className="flex justify-center py-10">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div className="bg-card border border-border rounded-xl shadow-xs shadow-black/5 overflow-hidden">
      <table className="w-full border-collapse">
        <thead>
          <tr className="border-b border-border">
            {columns.map((col, i) => (
              <th
                key={i}
                className="px-4 py-3 text-left text-xs font-medium text-muted-foreground"
                style={{ width: col.width }}
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
                className="p-8 text-center text-muted-foreground/60 text-sm"
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
                  className={cn(
                    "transition-colors duration-150 border-b border-border last:border-b-0",
                    onRowClick ? "cursor-pointer" : "cursor-default",
                    "hover:bg-muted/50"
                  )}
                >
                  {columns.map((col, i) => (
                    <td
                      key={i}
                      className="px-4 py-3 text-[13px] text-foreground"
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
