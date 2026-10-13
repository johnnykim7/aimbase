import { useRef, useState } from "react";
import { Package } from "lucide-react";
import { EmptyState } from "../common/EmptyState";
import { ActionButton } from "../common/ActionButton";
import { SkillBundleTable } from "./SkillBundleTable";
import { SkillBundleDetailDialog, type SkillBundleTarget } from "./SkillBundleDetailDialog";
import { UploadResultModal, type UploadState } from "./UploadResultModal";
import { useSkillBundles, useUploadSkillBundle } from "../../hooks/useSkillBundles";
import type { SkillBundleBase } from "../../api/skillBundles";

/**
 * 묶음 올리기. 올리기 단추는 페이지 머리에 있고 결과는 목록 아래 창에 보여서
 * 페이지와 목록이 같은 상태를 나눠 쓴다.
 */
export function useSkillBundleUpload(base: SkillBundleBase) {
  const inputRef = useRef<HTMLInputElement>(null);
  const mutation = useUploadSkillBundle(base);
  const [state, setState] = useState<UploadState | null>(null);

  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // 같은 파일을 다시 골라도 change 가 나도록 비운다
    e.target.value = "";
    if (!file) return;
    mutation.mutate(file, {
      onSuccess: (result) => setState({ kind: "done", fileName: file.name, result }),
      onError: (err) => setState({ kind: "rejected", fileName: file.name, message: err.message }),
    });
  };

  return {
    pickFile: () => inputRef.current?.click(),
    uploading: mutation.isPending,
    state,
    clear: () => setState(null),
    // 파일 고르기 창은 file input 으로만 열린다. 화면에는 보이지 않는다.
    input: <input ref={inputRef} type="file" accept=".skill,.zip" className="hidden" onChange={onFile} />,
  };
}

export type SkillBundleUpload = ReturnType<typeof useSkillBundleUpload>;

export function UploadBundleButton({ upload }: { upload: SkillBundleUpload }) {
  return (
    <ActionButton variant="primary" icon="+" disabled={upload.uploading} onClick={upload.pickFile}>
      {upload.uploading ? "올리는 중" : "묶음 올리기"}
    </ActionButton>
  );
}

interface SkillBundleBoardProps {
  base: SkillBundleBase;
  upload: SkillBundleUpload;
}

export function SkillBundleBoard({ base, upload }: SkillBundleBoardProps) {
  const { data: bundles = [], isLoading, error } = useSkillBundles(base);
  const [target, setTarget] = useState<SkillBundleTarget | null>(null);
  const isPlatform = base === "/platform/skill-bundles";

  return (
    <div className="flex flex-col gap-4">
      <p className="text-xs text-muted-foreground">
        {isPlatform
          ? "여기 올린 스킬은 모든 테넌트가 같이 씁니다. 테넌트가 같은 이름을 올리면 그 테넌트에서는 테넌트 것이 먼저 쓰입니다."
          : "같은 이름이면 우리 전용 스킬이 공용 스킬보다 먼저 쓰입니다."}
      </p>

      {error ? (
        <div className="text-xs text-destructive">{error.message}</div>
      ) : !isLoading && bundles.length === 0 ? (
        <EmptyState
          icon={<Package className="size-6" />}
          title="올린 스킬 묶음이 없습니다"
          description="Claude Code 스킬 묶음(.skill, .zip)을 그대로 올리면 모든 모델과 연결이 같은 방식으로 씁니다"
          action={<UploadBundleButton upload={upload} />}
        />
      ) : (
        <SkillBundleTable
          bundles={bundles}
          loading={isLoading}
          showScope={!isPlatform}
          onSelect={(b) => setTarget({ name: b.name, scope: b.scope })}
        />
      )}

      {upload.input}
      <SkillBundleDetailDialog
        base={base}
        target={target}
        onClose={() => setTarget(null)}
        onUploadNew={upload.pickFile}
      />
      {/* 상세 창에서 새 판을 올려도 결과가 위에 보이도록 상세 창 뒤에 둔다 */}
      <UploadResultModal
        state={upload.state}
        onClose={upload.clear}
        onOpenDetail={(r) => {
          upload.clear();
          setTarget({ name: r.name, scope: r.scope });
        }}
      />
    </div>
  );
}
