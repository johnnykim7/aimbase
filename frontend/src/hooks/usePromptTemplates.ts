import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { promptTemplatesApi, type PromptTemplate } from "../api/promptTemplates";
import { projectInstructionsApi } from "../api/projectInstructions";

type Scope = "GLOBAL" | "TENANT" | "PROJECT";

/** CR-049 PRD-305: scope 필터 기반 목록 조회 */
export const usePromptTemplatesByScope = (
  scope: Scope,
  projectId?: string,
) =>
  useQuery({
    queryKey: ["prompt-templates", "scope", scope, projectId ?? null],
    queryFn: () => promptTemplatesApi.list({ scope, projectId }).then((r) => r.data.data ?? []),
    retry: false,
  });

/** CR-049 PRD-305: cascade 병합 미리보기 */
export const usePromptPreview = (key: string | null, projectId?: string) =>
  useQuery({
    queryKey: ["prompt-templates", "preview", key, projectId ?? null],
    queryFn: () => promptTemplatesApi.preview(key!, projectId).then((r) => r.data.data!),
    enabled: !!key,
    retry: false,
  });

/** CR-049 PRD-305: prompt 템플릿 생성 (scope 포함) */
export const useUpsertPromptTemplate = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<PromptTemplate>) => promptTemplatesApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["prompt-templates"] }),
  });
};

/** CR-049 PRD-305: 특정 프로젝트 지침 조회 */
export const useProjectInstructions = (projectId: string | undefined) =>
  useQuery({
    queryKey: ["project-instructions", projectId],
    queryFn: () =>
      projectInstructionsApi.list(projectId!).then((r) => r.data.data),
    enabled: !!projectId,
    retry: false,
  });

/** CR-049 PRD-305: 프로젝트 지침 저장 */
export const useUpsertProjectInstruction = (projectId: string | undefined) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { key: string; template: string; language?: string; is_active?: boolean }) =>
      projectInstructionsApi.upsert(projectId!, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["project-instructions", projectId] });
      qc.invalidateQueries({ queryKey: ["prompt-templates"] });
    },
  });
};
