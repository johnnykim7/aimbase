import { useEffect, useState } from "react";
import Markdown from "react-markdown";
import { Modal } from "../common/Modal";
import { Badge } from "../common/Badge";
import { ActionButton } from "../common/ActionButton";
import { LoadingSpinner } from "../common/LoadingSpinner";
import { Button } from "../ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../ui/tabs";
import { SkillFileTree } from "./SkillFileTree";
import {
  useSetSkillBundleActive,
  useSkillBundle,
  useSkillBundleFile,
  useSkillBundleVersion,
} from "../../hooks/useSkillBundles";
import type { SkillBundleBase } from "../../api/skillBundles";
import type { SkillScope } from "../../types/skillBundle";
import { SCOPE_LABEL, bundleStatus, formatBytes, formatDateTime, warningText } from "./skillBundleText";

export interface SkillBundleTarget {
  name: string;
  scope: SkillScope;
}

interface SkillBundleDetailDialogProps {
  base: SkillBundleBase;
  target: SkillBundleTarget | null;
  onClose: () => void;
  onUploadNew: () => void;
}

// react-markdown 은 스타일이 없어 안내서 본문에 쓰는 요소만 맞춘다
const MARKDOWN_CLASS =
  "text-[13px] leading-relaxed text-foreground " +
  "[&_h1]:text-lg [&_h1]:font-bold [&_h1]:mb-3 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:mt-5 [&_h2]:mb-2 " +
  "[&_h3]:text-sm [&_h3]:font-semibold [&_h3]:mt-4 [&_h3]:mb-1.5 [&_p]:my-2 " +
  "[&_ul]:list-disc [&_ul]:pl-5 [&_ol]:list-decimal [&_ol]:pl-5 [&_li]:my-0.5 " +
  "[&_code]:font-mono [&_code]:text-xs [&_code]:bg-muted [&_code]:px-1 [&_code]:rounded " +
  "[&_pre]:bg-muted [&_pre]:p-3 [&_pre]:rounded-md [&_pre]:overflow-x-auto [&_a]:text-primary";

export function SkillBundleDetailDialog({ base, target, onClose, onUploadNew }: SkillBundleDetailDialogProps) {
  const { data: bundle, isLoading } = useSkillBundle(base, target?.name, target?.scope);
  const [version, setVersion] = useState<number>();
  const [filePath, setFilePath] = useState<string>();
  const [confirmOff, setConfirmOff] = useState(false);
  const setActive = useSetSkillBundleActive(base);

  useEffect(() => {
    setVersion(undefined);
    setFilePath(undefined);
  }, [target?.name, target?.scope]);

  const shownVersion = version ?? bundle?.latest_version;
  const { data: detail, isLoading: versionLoading } = useSkillBundleVersion(
    base, target?.name, shownVersion, target?.scope,
  );
  const { data: file, isLoading: fileLoading } = useSkillBundleFile(
    base, target?.name, shownVersion, filePath, target?.scope,
  );

  if (!target) return null;

  // 테넌트 화면에서 공용 스킬은 보기만 한다
  const editable = base === "/platform/skill-bundles" || target.scope === "tenant";
  const status = bundle ? bundleStatus(bundle) : null;
  const selectedFile = detail?.files.find((f) => f.path === filePath);

  const changeActive = (isActive: boolean) =>
    setActive.mutate({ name: target.name, isActive }, { onSuccess: () => setConfirmOff(false) });

  return (
    <Modal open onClose={onClose} title={target.name} width={1080}>
      {isLoading || !bundle ? (
        <LoadingSpinner />
      ) : (
        <div className="flex flex-col gap-4">
          <div className="flex items-start gap-4">
            <div className="flex-1 min-w-0 flex flex-col gap-2">
              <div className="flex gap-1.5">
                {base === "/skill-bundles" && (
                  <Badge color={bundle.scope === "tenant" ? "accent" : "purple"}>{SCOPE_LABEL[bundle.scope]}</Badge>
                )}
                {status && <Badge color={status.color}>{status.label}</Badge>}
                <Badge color="muted">최신 판 {bundle.latest_version}</Badge>
              </div>
              <p className="text-[13px] text-muted-foreground leading-relaxed">{bundle.description}</p>
            </div>
            <div className="flex gap-2 shrink-0">
              {editable && (
                <>
                  <ActionButton small onClick={onUploadNew}>새 판 올리기</ActionButton>
                  {bundle.is_active ? (
                    <ActionButton small variant="danger" onClick={() => setConfirmOff(true)}>
                      비활성으로
                    </ActionButton>
                  ) : (
                    <ActionButton small variant="success" disabled={setActive.isPending} onClick={() => changeActive(true)}>
                      활성으로
                    </ActionButton>
                  )}
                </>
              )}
              <ActionButton small variant="ghost" onClick={onClose}>닫기</ActionButton>
            </div>
          </div>

          {!editable && (
            <div className="rounded-lg border border-border bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
              플랫폼 공용 스킬이라 보기만 할 수 있습니다.
              {bundle.shadowed && " 같은 이름의 우리 전용 스킬이 있어 이 스킬은 쓰이지 않습니다."}
            </div>
          )}
          {setActive.error && !confirmOff && (
            <div className="text-xs text-destructive">{setActive.error.message}</div>
          )}

          <div className="grid grid-cols-[220px_1fr] gap-5">
            <div className="flex flex-col gap-1">
              <div className="text-xs font-medium text-muted-foreground mb-1">판 목록</div>
              {bundle.versions.map((v) => (
                <Button
                  key={v.version}
                  variant="ghost"
                  selected={v.version === shownVersion}
                  className="h-auto py-2 flex-col items-start gap-0.5 whitespace-normal text-left"
                  onClick={() => {
                    setVersion(v.version);
                    setFilePath(undefined);
                  }}
                >
                  <span className="flex w-full items-center gap-1.5 font-semibold">
                    판 {v.version}
                    {v.warnings.length > 0 && <Badge color="warning">경고 {v.warnings.length}</Badge>}
                  </span>
                  <span className="text-[11px] text-muted-foreground">{formatDateTime(v.created_at)}</span>
                  <span className="text-[11px] text-muted-foreground">
                    {v.uploaded_by ?? "--"} | 파일 {v.file_count}개 | {formatBytes(v.total_bytes)}
                  </span>
                </Button>
              ))}
            </div>

            <div className="min-w-0">
              {versionLoading || !detail ? (
                <LoadingSpinner />
              ) : (
                <Tabs defaultValue="instructions" key={`${target.scope}:${target.name}:${shownVersion}`}>
                  <TabsList variant="line">
                    <TabsTrigger value="instructions">안내서</TabsTrigger>
                    <TabsTrigger value="files">파일 {detail.files.length}</TabsTrigger>
                    <TabsTrigger value="warnings">경고 {detail.warnings.length}</TabsTrigger>
                  </TabsList>

                  <TabsContent value="instructions" className="h-[52vh] overflow-y-auto pr-2 pt-3">
                    <div className={MARKDOWN_CLASS}>
                      <Markdown>{detail.instructions}</Markdown>
                    </div>
                  </TabsContent>

                  <TabsContent value="files" className="pt-3">
                    <div className="grid grid-cols-[300px_1fr] gap-3 h-[52vh]">
                      <div className="overflow-y-auto border border-border rounded-lg p-1">
                        <SkillFileTree
                          files={detail.files}
                          selectedPath={filePath}
                          onSelect={(f) => setFilePath(f.path)}
                        />
                      </div>
                      <div className="overflow-auto border border-border rounded-lg bg-muted/30">
                        {!selectedFile ? (
                          <div className="p-4 text-xs text-muted-foreground">파일을 선택하면 내용이 보입니다</div>
                        ) : !selectedFile.is_text ? (
                          <div className="p-4 text-xs text-muted-foreground">
                            미리 볼 수 없는 파일입니다 ({formatBytes(selectedFile.size_bytes)})
                          </div>
                        ) : fileLoading || !file ? (
                          <LoadingSpinner />
                        ) : (
                          <>
                            {file.truncated && (
                              <div className="px-4 pt-3 text-[11px] text-warning">
                                길어서 앞부분만 보입니다
                              </div>
                            )}
                            <pre className="p-4 text-xs font-mono whitespace-pre-wrap break-all">{file.content}</pre>
                          </>
                        )}
                      </div>
                    </div>
                  </TabsContent>

                  <TabsContent value="warnings" className="pt-3">
                    {detail.warnings.length === 0 ? (
                      <div className="text-xs text-muted-foreground">경고 없음</div>
                    ) : (
                      <ul className="flex flex-col gap-2">
                        {detail.warnings.map((w, i) => (
                          <li key={i} className="text-[13px]">
                            {warningText(w)}
                            {w.path && <span className="ml-2 font-mono text-xs text-muted-foreground">{w.path}</span>}
                          </li>
                        ))}
                      </ul>
                    )}
                  </TabsContent>
                </Tabs>
              )}
            </div>
          </div>
        </div>
      )}

      <Modal open={confirmOff} onClose={() => setConfirmOff(false)} title={`${target.name} 비활성으로 바꾸기`}>
        <p className="text-[13px] leading-relaxed text-foreground">
          {base === "/platform/skill-bundles"
            ? "비활성으로 바꾸면 모든 테넌트에서 이 스킬이 쓰이지 않습니다. 같은 이름의 테넌트 전용 스킬이 없는 테넌트는 이 스킬을 지정한 워크플로우 단계가 실패합니다."
            : "비활성으로 바꾸면 이 스킬은 쓰이지 않습니다. 같은 이름의 공용 스킬이 있으면 그 스킬로 바뀌고, 없으면 이 스킬을 지정한 워크플로우 단계가 실패합니다."}
        </p>
        <p className="mt-2 text-xs text-muted-foreground">다시 올리거나 활성으로 바꾸면 돌아옵니다.</p>
        {setActive.error && <div className="mt-3 text-xs text-destructive">{setActive.error.message}</div>}
        <div className="mt-5 flex justify-end gap-2">
          <ActionButton onClick={() => setConfirmOff(false)}>취소</ActionButton>
          <ActionButton variant="danger" disabled={setActive.isPending} onClick={() => changeActive(false)}>
            비활성으로 바꾸기
          </ActionButton>
        </div>
      </Modal>
    </Modal>
  );
}
