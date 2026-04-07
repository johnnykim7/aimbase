import { useState } from "react";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, textareaStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { Sparkles, Trash2, Pencil } from "lucide-react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { skillsApi, type Skill } from "../api/skills";

const emptyForm = {
  name: "",
  description: "",
  system_prompt: "",
  tools: "",
  tags: "",
};

export default function Skills() {
  const queryClient = useQueryClient();
  const { data: skills = [], isLoading } = useQuery({
    queryKey: ["skills"],
    queryFn: () => skillsApi.list().then((r) => r.data.data ?? []),
  });

  const createSkill = useMutation({
    mutationFn: (form: typeof emptyForm) =>
      skillsApi.create({
        name: form.name,
        description: form.description,
        system_prompt: form.system_prompt,
        tools: form.tools.split(",").map((t) => t.trim()).filter(Boolean),
        tags: form.tags.split(",").map((t) => t.trim()).filter(Boolean),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["skills"] });
      setShowModal(false);
    },
  });

  const deleteSkill = useMutation({
    mutationFn: skillsApi.delete,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["skills"] }),
  });

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState(emptyForm);

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={
        <ActionButton variant="primary" icon="+" onClick={() => { setForm(emptyForm); setShowModal(true); }}>
          새 스킬
        </ActionButton>
      }
    >
      {skills.length === 0 ? (
        <EmptyState
          icon={<Sparkles className="size-6" />}
          title="등록된 스킬이 없습니다"
          description="프롬프트와 도구 조합을 재사용 가능한 스킬로 등록하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              스킬 추가
            </ActionButton>
          }
        />
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {skills.map((skill: Skill) => (
            <div
              key={skill.id}
              className="bg-card border border-border rounded-xl p-5 flex flex-col gap-3"
            >
              <div className="flex items-center justify-between">
                <h3 className="font-semibold text-base">{skill.name}</h3>
                <div className="flex gap-1">
                  <button
                    className="p-1.5 rounded hover:bg-destructive/10"
                    onClick={() => {
                      if (confirm(`'${skill.name}' 스킬을 삭제하시겠습니까?`))
                        deleteSkill.mutate(skill.id);
                    }}
                  >
                    <Trash2 className="size-4 text-destructive" />
                  </button>
                </div>
              </div>
              {skill.description && (
                <p className="text-sm text-muted-foreground line-clamp-2">{skill.description}</p>
              )}
              <div className="flex flex-wrap gap-1">
                {skill.tags?.map((tag) => (
                  <Badge key={tag} variant="outline">{tag}</Badge>
                ))}
              </div>
              {skill.tools?.length > 0 && (
                <div className="text-xs text-muted-foreground">
                  도구: {skill.tools.join(", ")}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 생성 모달 */}
      <Modal
        open={showModal}
        onClose={() => setShowModal(false)}
        title="새 스킬 추가"
        footer={
          <ActionButton
            variant="primary"
            onClick={() => createSkill.mutate(form)}
            isLoading={createSkill.isPending}
          >
            생성
          </ActionButton>
        }
      >
        <div className="space-y-4">
          <FormField label="이름">
            <input
              className={inputStyle}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="코드 리뷰 스킬"
            />
          </FormField>
          <FormField label="설명">
            <input
              className={inputStyle}
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder="코드를 리뷰하고 개선 사항을 제안합니다"
            />
          </FormField>
          <FormField label="시스템 프롬프트">
            <textarea
              className={textareaStyle}
              rows={5}
              value={form.system_prompt}
              onChange={(e) => setForm({ ...form, system_prompt: e.target.value })}
              placeholder="당신은 시니어 소프트웨어 엔지니어입니다..."
            />
          </FormField>
          <FormField label="허용 도구" hint="쉼표로 구분 (예: builtin_file_read, builtin_grep)">
            <input
              className={inputStyle}
              value={form.tools}
              onChange={(e) => setForm({ ...form, tools: e.target.value })}
            />
          </FormField>
          <FormField label="태그" hint="쉼표로 구분">
            <input
              className={inputStyle}
              value={form.tags}
              onChange={(e) => setForm({ ...form, tags: e.target.value })}
            />
          </FormField>
        </div>
      </Modal>
    </Page>
  );
}
