import { useState } from "react";
import { cn } from "@/lib/utils";
import { ActionButton } from "../common/ActionButton";

interface StudioToolbarProps {
  name: string;
  description?: string;
  onNameChange: (name: string) => void;
  onDescriptionChange?: (desc: string) => void;
  onSave: () => void;
  onRun: () => void;
  onAutoLayout: () => void;
  onBack: () => void;
  onToggleSchema?: () => void;
  schemaActive?: boolean;
  saving?: boolean;
  running?: boolean;
  dirty?: boolean;
  /** 임베드 모드: 이름/설명/뒤로가기 숨기고 액션 버튼만 표시 */
  compact?: boolean;
}

export function StudioToolbar({
  name,
  description,
  onNameChange,
  onDescriptionChange,
  onSave,
  onRun,
  onAutoLayout,
  onBack,
  onToggleSchema,
  schemaActive,
  saving,
  running,
  dirty,
  compact,
}: StudioToolbarProps) {
  const [descOpen, setDescOpen] = useState(false);

  const descPreview = (description ?? "").length > 0
    ? (description!.length > 40 ? description!.slice(0, 40) + "…" : description!)
    : "설명 추가";

  return (
    <div className="bg-card border-b border-border shrink-0">
      <div
        className={cn(
          "flex items-center px-4 gap-3",
          compact ? "h-10" : "h-[52px]"
        )}
      >
        {!compact && (
          <>
            <button
              onClick={onBack}
              className="bg-transparent border-none cursor-pointer text-lg text-muted-foreground p-1 px-2 rounded-md hover:bg-accent"
            >
              ←
            </button>

            <div className="flex items-center gap-2 min-w-0">
              <input
                value={name}
                onChange={(e) => onNameChange(e.target.value)}
                className="text-[15px] font-semibold text-foreground border-none bg-transparent outline-none w-60"
                placeholder="워크플로우 이름"
              />
              {dirty && (
                <span className="text-[10px] font-mono text-warning">
                  변경됨
                </span>
              )}
            </div>

            <button
              onClick={() => setDescOpen((o) => !o)}
              className="bg-transparent border-none cursor-pointer text-[11px] text-muted-foreground/40 py-0.5 px-2 rounded flex items-center gap-1 hover:text-muted-foreground"
              title="설명 펼치기/접기"
            >
              <span className={cn("text-[9px] transition-transform", descOpen && "rotate-180")}>▼</span>
              {!descOpen && <span className="text-muted-foreground">{descPreview}</span>}
            </button>
          </>
        )}

        {compact && dirty && (
          <span className="text-[10px] font-mono text-warning">
            변경됨
          </span>
        )}

        <div className="flex-1" />

        <ActionButton small variant="ghost" onClick={onAutoLayout}>
          정렬
        </ActionButton>
        {onToggleSchema && (
          <ActionButton
            small
            variant={schemaActive ? "primary" : "ghost"}
            onClick={onToggleSchema}
          >
            출력 스키마
          </ActionButton>
        )}
        {!compact && (
          <ActionButton small variant="default" onClick={onRun} disabled={running}>
            {running ? "실행 중..." : "실행"}
          </ActionButton>
        )}
        <ActionButton small variant="primary" onClick={onSave} disabled={saving}>
          {saving ? "저장 중..." : "저장"}
        </ActionButton>
      </div>

      {/* 접이식 설명 영역 (compact 모드에서는 숨김) */}
      {!compact && descOpen && (
        <div className="px-4 pb-3 pl-14 transition-all">
          <textarea
            value={description ?? ""}
            onChange={(e) => onDescriptionChange?.(e.target.value)}
            rows={4}
            autoFocus
            className="w-full max-w-[600px] text-xs text-foreground border border-border rounded-lg bg-background outline-none py-2 px-3 resize-y leading-relaxed"
            placeholder="워크플로우에 대한 상세 설명을 입력하세요"
          />
        </div>
      )}
    </div>
  );
}
