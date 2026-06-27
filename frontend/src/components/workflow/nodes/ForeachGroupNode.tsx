import { memo } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";

/**
 * CR-120 FE 2단계 — FOREACH 그룹 노드.
 * 캔버스에 그룹 컨테이너로 렌더하고, body(중첩 스텝)는 parentId/extent="parent" 서브노드로
 * 이 그룹 안에 그린다. body 서브노드가 없으면 안내 플레이스홀더를 보여준다.
 *
 * 직렬화: flowToWorkflow 가 서브노드를 부모 config.body 로 접고,
 *        workflowToFlow 가 config.body 를 서브노드로 펼친다 (round-trip 무손실).
 */
const COLOR = "#ca8a04";

function ForeachGroupNode({ data, selected }: NodeProps) {
  const status = data.status as string | undefined;
  const borderColor =
    status === "running" ? "#2563eb"
    : status === "completed" ? "#059669"
    : status === "failed" ? "#dc2626"
    : COLOR;
  const config = (data.config as Record<string, unknown>) ?? {};
  const items = typeof config.items === "string" ? config.items : "";
  const hasBody = data.hasBody as boolean | undefined;

  return (
    <div
      className="rounded-xl"
      style={{
        width: "100%",
        height: "100%",
        border: `2px dashed ${borderColor}`,
        background: COLOR + "0a",
        boxShadow: selected ? `0 0 0 2px ${COLOR}40` : "none",
        boxSizing: "border-box",
      }}
    >
      <Handle type="target" position={Position.Top} style={{ background: borderColor, width: 8, height: 8 }} />

      {/* 헤더 */}
      <div className="flex items-center gap-2 px-3 pt-2.5 pb-1">
        <span
          className="w-6 h-6 rounded-md flex items-center justify-center text-xs shrink-0"
          style={{ background: COLOR + "22" }}
        >
          🔂
        </span>
        <div className="flex-1 min-w-0">
          <div className="text-[11px] font-semibold text-foreground overflow-hidden text-ellipsis whitespace-nowrap">
            {(data.label as string) ?? "FOREACH"}
          </div>
          <div className="text-[9px] font-mono uppercase tracking-wider" style={{ color: COLOR }}>
            FOREACH
            {items && <span className="ml-1 normal-case text-muted-foreground/70">· {items}</span>}
            {status && (
              <span className="ml-1.5" style={{ color: borderColor }}>• {status}</span>
            )}
          </div>
        </div>
      </div>

      {/* body 없을 때 안내 (있으면 서브노드가 이 위에 겹쳐 그려짐) */}
      {!hasBody && (
        <div className="absolute left-3 right-3 bottom-3 top-12 rounded-lg border border-dashed border-border/60 flex items-center justify-center text-[10px] text-muted-foreground/60 text-center px-2 pointer-events-none">
          body 스텝을 팔레트에서 이 안으로 드래그하세요
        </div>
      )}

      <Handle type="source" position={Position.Bottom} style={{ background: borderColor, width: 8, height: 8 }} />
    </div>
  );
}

export default memo(ForeachGroupNode);
