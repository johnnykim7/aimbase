import { Button } from "../ui/button";
import { CHIP_LABEL, type ChipState } from "./accountTokenState";

export type ChipFilter = ChipState | "all";

interface TokenStateChipsProps {
  states: ChipState[];
  counts: Partial<Record<ChipState, number>>;
  total: number;
  selected: ChipFilter;
  onSelect: (f: ChipFilter) => void;
}

/** CR-196: 상태 칩. 건수가 0 인 칩은 숨기되 지금 고른 칩은 남긴다. */
export function TokenStateChips({ states, counts, total, selected, onSelect }: TokenStateChipsProps) {
  const chip = (key: ChipFilter, label: string, n: number) => (
    <Button
      key={key}
      size="sm"
      shape="circle"
      variant={selected === key ? "primary" : "outline"}
      className="px-3"
      onClick={() => onSelect(key)}
    >
      {label} {n}
    </Button>
  );
  return (
    <div className="flex flex-wrap gap-1.5">
      {chip("all", "전체", total)}
      {states.filter((s) => (counts[s] ?? 0) > 0 || selected === s).map((s) => chip(s, CHIP_LABEL[s], counts[s] ?? 0))}
    </div>
  );
}
