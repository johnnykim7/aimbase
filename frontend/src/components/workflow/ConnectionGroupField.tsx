import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../ui/select";
import { useConnectionGroups } from "../../hooks/useConnectionGroups";
import { strategyLabel } from "../connectionGroups/strategies";

// Radix Select 는 빈 글을 항목 값으로 받지 않는다.
const NONE = "__none__";

interface ConnectionGroupFieldProps {
  value: string;
  onChange: (groupId: string) => void;
}

/** CR-161: 단계 config.connection_group_id (T3-4 「단계 커넥션 그룹 지정」). */
export function ConnectionGroupField({ value, onChange }: ConnectionGroupFieldProps) {
  const { data: groups = [], isLoading, isError } = useConnectionGroups();
  const saved = value ? groups.find((g) => g.id === value) : undefined;
  // 목록을 못 받았거나 지워진 그룹이어도 저장된 값은 지우지 않고 보인다.
  const unknown = !!value && !saved && !isLoading;

  return (
    <div className="flex flex-col gap-1">
      <Select value={value || NONE} onValueChange={(v) => onChange(v === NONE ? "" : v)} disabled={isError}>
        <SelectTrigger size="sm">
          <SelectValue placeholder={isLoading ? "불러오는 중" : undefined} />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={NONE}>사용 안 함</SelectItem>
          {groups
            .filter((g) => g.is_active)
            .map((g) => (
              <SelectItem key={g.id} value={g.id}>
                {g.name} ({strategyLabel(g.strategy)})
              </SelectItem>
            ))}
          {saved && !saved.is_active && <SelectItem value={saved.id}>{saved.name} (꺼짐)</SelectItem>}
          {unknown && <SelectItem value={value}>{value} ({isError ? "불러오지 못함" : "없음"})</SelectItem>}
        </SelectContent>
      </Select>
      {isError && <div className="text-[10px] text-destructive">커넥션 그룹을 불러오지 못했습니다</div>}
    </div>
  );
}
