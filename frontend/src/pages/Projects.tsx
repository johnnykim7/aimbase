import { useState } from "react";
import { FolderOpen } from "lucide-react";
import { Page } from "../components/layout/Page";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { Badge } from "../components/common/Badge";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import {
  useProjects,
  useCreateProject,
  useUpdateProject,
  useDeleteProject,
} from "../hooks/useProjects";
import type { Project, ProjectRequest } from "../types/project";

export default function Projects() {
  const { data: projects = [], isLoading } = useProjects();
  const createProject = useCreateProject();
  const updateProject = useUpdateProject();
  const deleteProject = useDeleteProject();

  const [showModal, setShowModal] = useState(false);
  const [editing, setEditing] = useState<Project | null>(null);
  const [form, setForm] = useState<ProjectRequest>({ name: "", description: "" });

  const openCreate = () => {
    setEditing(null);
    setForm({ name: "", description: "" });
    setShowModal(true);
  };

  const openEdit = (p: Project) => {
    setEditing(p);
    setForm({ name: p.name, description: p.description ?? "" });
    setShowModal(true);
  };

  const handleSave = async () => {
    if (editing) {
      await updateProject.mutateAsync({ id: editing.id, data: form });
    } else {
      await createProject.mutateAsync(form);
    }
    setShowModal(false);
  };

  const handleDelete = async (id: string) => {
    if (confirm("정말 삭제하시겠습니까?")) {
      await deleteProject.mutateAsync(id);
    }
  };

  if (isLoading) return <LoadingSpinner />;

  return (
    <Page
      actions={<ActionButton variant="primary" onClick={openCreate}>+ 프로젝트 생성</ActionButton>}
    >
      {projects.length === 0 ? (
        <EmptyState
          icon={<FolderOpen className="size-6" />}
          title="프로젝트 없음"
          description="프로젝트를 생성하여 리소스를 그룹핑하세요."
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 mt-5">
          {projects.map((p) => (
            <div
              key={p.id}
              className="bg-card border border-border rounded-xl p-5 flex flex-col gap-3"
            >
              <div className="flex justify-between items-center">
                <div className="text-[15px] font-semibold text-foreground">
                  {p.name}
                </div>
                <Badge color={p.isActive ? "success" : "muted"}>
                  {p.isActive ? "활성" : "비활성"}
                </Badge>
              </div>

              {p.description && (
                <div className="text-[13px] text-muted-foreground">
                  {p.description}
                </div>
              )}

              <div className="text-[11px] text-muted-foreground/40 font-mono">
                ID: {p.id}
              </div>

              <div className="flex gap-2 mt-auto">
                <ActionButton small variant="default" onClick={() => openEdit(p)}>
                  수정
                </ActionButton>
                <ActionButton small variant="danger" onClick={() => handleDelete(p.id)}>
                  삭제
                </ActionButton>
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal
        open={showModal}
        onClose={() => setShowModal(false)}
        title={editing ? "프로젝트 수정" : "프로젝트 생성"}
      >
        <div className="flex flex-col gap-4">
          <FormField label="이름">
            <input
              style={inputStyle}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="프로젝트 이름"
            />
          </FormField>
          <FormField label="설명">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: "vertical" }}
              value={form.description ?? ""}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder="프로젝트 설명 (선택)"
            />
          </FormField>
          <div className="flex justify-end gap-2">
            <ActionButton variant="ghost" onClick={() => setShowModal(false)}>
              취소
            </ActionButton>
            <ActionButton onClick={handleSave} disabled={!form.name.trim()}>
              {editing ? "수정" : "생성"}
            </ActionButton>
          </div>
        </div>
      </Modal>
    </Page>
  );
}
