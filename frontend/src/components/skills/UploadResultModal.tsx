import { Modal } from "../common/Modal";
import { ActionButton } from "../common/ActionButton";
import type { SkillBundleUploadResult } from "../../types/skillBundle";
import { warningText } from "./skillBundleText";

export type UploadState =
  | { kind: "done"; fileName: string; result: SkillBundleUploadResult }
  | { kind: "rejected"; fileName: string; message: string };

interface UploadResultModalProps {
  state: UploadState | null;
  onClose: () => void;
  onOpenDetail: (result: SkillBundleUploadResult) => void;
}

export function UploadResultModal({ state, onClose, onOpenDetail }: UploadResultModalProps) {
  if (!state) return null;

  if (state.kind === "rejected") {
    return (
      <Modal open onClose={onClose} title="묶음을 올리지 못했습니다">
        <p className="text-xs text-muted-foreground mb-2">{state.fileName}</p>
        <p className="text-[13px] text-destructive leading-relaxed">{state.message}</p>
        <div className="mt-5 flex justify-end">
          <ActionButton onClick={onClose}>닫기</ActionButton>
        </div>
      </Modal>
    );
  }

  const r = state.result;
  return (
    <Modal open onClose={onClose} title="묶음을 올렸습니다">
      <div className="flex flex-col gap-2 text-[13px] leading-relaxed">
        <p>
          {r.created
            ? `${r.name} 판 ${r.version} 을 올렸습니다.`
            : `직전 판과 내용이 같아 새 판을 만들지 않았습니다 (판 ${r.version}).`}
        </p>
        {r.reactivated && <p>비활성이던 스킬을 다시 활성으로 바꿨습니다.</p>}
        {r.warnings.length > 0 && (
          <div className="mt-2 rounded-lg border border-warning/40 bg-warning/5 px-4 py-3">
            <div className="text-xs font-semibold text-warning mb-1.5">경고 {r.warnings.length}건</div>
            <ul className="flex flex-col gap-1">
              {r.warnings.map((w, i) => (
                <li key={i} className="text-xs">
                  {warningText(w)}
                  {w.path && <span className="ml-2 font-mono text-muted-foreground">{w.path}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      <div className="mt-5 flex justify-end gap-2">
        <ActionButton onClick={onClose}>닫기</ActionButton>
        <ActionButton variant="primary" onClick={() => onOpenDetail(r)}>상세 보기</ActionButton>
      </div>
    </Modal>
  );
}
