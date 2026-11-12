import { Checkbox } from "../ui/checkbox";
import { Textarea } from "../ui/textarea";
import { ScrollArea } from "../ui/scroll-area";
import { FormField } from "../common/FormField";
import { useTools } from "../../hooks/useTools";

export interface ToolApprovalValue {
  tools: string[];
  message: string;
}

interface ToolApprovalFieldsProps {
  value: ToolApprovalValue;
  onChange: (next: ToolApprovalValue) => void;
  toolsError?: string;
}

/** CR-185 FE-130: 「도구 실행 전 승인」 규칙의 대상 도구와 안내 문구. */
export function ToolApprovalFields({ value, onChange, toolsError }: ToolApprovalFieldsProps) {
  const { data: tools = [], isLoading } = useTools();

  const toggle = (name: string, on: boolean) => {
    const next = on ? [...value.tools, name] : value.tools.filter((t) => t !== name);
    onChange({ ...value, tools: next });
  };

  return (
    <div className="bg-accent rounded-lg px-3.5 py-3 mb-1 flex flex-col gap-2">
      <FormField label="대상 도구">
        <ScrollArea className="h-48 rounded-md border border-border bg-background">
          <div className="flex flex-col p-2">
            {isLoading && <div className="text-xs text-muted-foreground px-1 py-1">도구 목록을 불러오는 중</div>}
            {!isLoading && tools.length === 0 && (
              <div className="text-xs text-muted-foreground px-1 py-1">등록된 도구가 없습니다</div>
            )}
            {tools.map((t) => (
              <label key={t.name} className="flex items-start gap-2 px-1 py-1.5 rounded hover:bg-accent cursor-pointer">
                <Checkbox
                  size="sm"
                  className="mt-0.5"
                  checked={value.tools.includes(t.name)}
                  onCheckedChange={(c) => toggle(t.name, c === true)}
                />
                <span className="flex flex-col min-w-0">
                  <span className="text-xs font-mono text-foreground">{t.name}</span>
                  {t.description && (
                    <span className="text-[11px] text-muted-foreground truncate">{t.description.split("\n")[0]}</span>
                  )}
                </span>
              </label>
            ))}
          </div>
        </ScrollArea>
        {toolsError && <div className="text-[11px] text-destructive mt-1">{toolsError}</div>}
      </FormField>
      <FormField label="안내 문구 (선택)">
        <Textarea
          rows={2}
          placeholder="승인 요청 카드의 안내 칸에 보입니다"
          value={value.message}
          onChange={(e) => onChange({ ...value, message: e.target.value })}
        />
      </FormField>
    </div>
  );
}
