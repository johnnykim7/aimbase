import { ArrowDown, ArrowUp, Plus, X } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { FormField } from "../common/FormField";

export interface WidgetModel {
  id: string;
  label: string;
}

export interface WidgetModelsErrors {
  rows: Record<number, string>;
  defaultModel?: string;
}

export function parseWidgetModels(raw: unknown): WidgetModel[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((m): m is Record<string, unknown> => typeof m === "object" && m !== null)
    .map((m) => ({ id: String(m.id ?? ""), label: String(m.label ?? "") }));
}

// 이름을 비우면 id 를 그대로 보여 준다. 목록이 비면 config 에서 models 를 빼 위젯이 기본 모델 하나로 답하게 한다.
export function serializeWidgetModels(rows: WidgetModel[]): WidgetModel[] | undefined {
  const cleaned = rows
    .map((r) => ({ id: r.id.trim(), label: r.label.trim() }))
    .filter((r) => r.id.length > 0)
    .map((r) => ({ id: r.id, label: r.label || r.id }));
  return cleaned.length > 0 ? cleaned : undefined;
}

export function validateWidgetModels(rows: WidgetModel[], defaultModel: string): WidgetModelsErrors {
  const errors: WidgetModelsErrors = { rows: {} };
  const seen = new Set<string>();
  rows.forEach((r, i) => {
    const id = r.id.trim();
    if (!id) {
      errors.rows[i] = "모델 id 를 적으세요";
    } else if (seen.has(id)) {
      errors.rows[i] = "같은 모델이 두 번 있습니다";
    }
    seen.add(id);
  });
  const ids = rows.map((r) => r.id.trim()).filter(Boolean);
  if (ids.length > 0 && !ids.includes(defaultModel.trim())) {
    errors.defaultModel = "기본 모델을 목록에서 선택하세요";
  }
  return errors;
}

export function hasWidgetModelsErrors(errors: WidgetModelsErrors): boolean {
  return Object.keys(errors.rows).length > 0 || Boolean(errors.defaultModel);
}

interface WidgetModelsFieldProps {
  rows: WidgetModel[];
  defaultModel: string;
  errors?: WidgetModelsErrors;
  onRowsChange: (rows: WidgetModel[]) => void;
  onDefaultChange: (id: string) => void;
}

export function WidgetModelsField({ rows, defaultModel, errors, onRowsChange, onDefaultChange }: WidgetModelsFieldProps) {
  const update = (index: number, patch: Partial<WidgetModel>) => {
    const next = rows.map((r, i) => (i === index ? { ...r, ...patch } : r));
    if (patch.id !== undefined && rows[index].id.trim() === defaultModel.trim() && defaultModel.trim()) {
      onDefaultChange(patch.id);
    }
    onRowsChange(next);
  };

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= rows.length) return;
    const next = [...rows];
    [next[index], next[target]] = [next[target], next[index]];
    onRowsChange(next);
  };

  const remove = (index: number) => onRowsChange(rows.filter((_, i) => i !== index));

  return (
    <FormField label="허용 모델" hint="비워 두면 위젯은 기본 모델 하나로만 답하고 모델 선택 칸을 숨깁니다.">
      <div className="flex flex-col gap-2">
        {rows.map((row, i) => {
          const isDefault = row.id.trim() !== "" && row.id.trim() === defaultModel.trim();
          const rowError = errors?.rows[i];
          return (
            <div key={i} className="flex flex-col gap-1">
              <div className="flex items-center gap-1.5">
                <Input
                  variant="sm"
                  className="flex-[3] font-mono"
                  placeholder="claude-sonnet-5-5"
                  value={row.id}
                  aria-invalid={Boolean(rowError)}
                  onChange={(e) => update(i, { id: e.target.value })}
                />
                <Input
                  variant="sm"
                  className="flex-[2]"
                  placeholder="이름"
                  value={row.label}
                  onChange={(e) => update(i, { label: e.target.value })}
                />
                <Button
                  type="button"
                  size="sm"
                  variant={isDefault ? "primary" : "outline"}
                  disabled={!row.id.trim()}
                  onClick={() => onDefaultChange(row.id.trim())}
                >
                  기본
                </Button>
                <Button type="button" size="icon" variant="ghost" aria-label="위로" disabled={i === 0} onClick={() => move(i, -1)}>
                  <ArrowUp />
                </Button>
                <Button
                  type="button"
                  size="icon"
                  variant="ghost"
                  aria-label="아래로"
                  disabled={i === rows.length - 1}
                  onClick={() => move(i, 1)}
                >
                  <ArrowDown />
                </Button>
                <Button type="button" size="icon" variant="ghost" aria-label="삭제" onClick={() => remove(i)}>
                  <X />
                </Button>
              </div>
              {rowError && <div className="text-[11px] text-destructive">{rowError}</div>}
            </div>
          );
        })}
        {errors?.defaultModel && <div className="text-[11px] text-destructive">{errors.defaultModel}</div>}
        <div>
          <Button type="button" size="sm" variant="dashed" onClick={() => onRowsChange([...rows, { id: "", label: "" }])}>
            <Plus />
            추가
          </Button>
        </div>
      </div>
    </FormField>
  );
}
