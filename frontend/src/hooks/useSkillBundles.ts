import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { skillBundlesApi, type SkillBundleBase } from "../api/skillBundles";
import type { SkillScope } from "../types/skillBundle";

const KEY = "skillBundles";

export const useSkillBundles = (base: SkillBundleBase) =>
  useQuery({
    queryKey: [KEY, base, "list"],
    queryFn: () => skillBundlesApi.list(base).then((r) => r.data.data ?? []),
  });

export const useSkillBundle = (base: SkillBundleBase, name?: string, scope?: SkillScope) =>
  useQuery({
    queryKey: [KEY, base, "detail", name, scope],
    queryFn: () => skillBundlesApi.get(base, name!, scope).then((r) => r.data.data),
    enabled: !!name,
  });

export const useSkillBundleVersion = (
  base: SkillBundleBase,
  name?: string,
  version?: number,
  scope?: SkillScope,
) =>
  useQuery({
    queryKey: [KEY, base, "version", name, version, scope],
    queryFn: () => skillBundlesApi.getVersion(base, name!, version!, scope).then((r) => r.data.data),
    // 판은 고치지 않으므로(BIZ-119) 한 번 받은 내용을 다시 받을 까닭이 없다
    staleTime: Infinity,
    enabled: !!name && !!version,
  });

export const useSkillBundleFile = (
  base: SkillBundleBase,
  name?: string,
  version?: number,
  filePath?: string,
  scope?: SkillScope,
) =>
  useQuery({
    queryKey: [KEY, base, "file", name, version, filePath, scope],
    queryFn: () => skillBundlesApi.getFile(base, name!, version!, filePath!, scope).then((r) => r.data.data),
    staleTime: Infinity,
    enabled: !!name && !!version && !!filePath,
  });

export const useUploadSkillBundle = (base: SkillBundleBase) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => skillBundlesApi.upload(base, file).then((r) => r.data.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
};

export const useSetSkillBundleActive = (base: SkillBundleBase) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, isActive }: { name: string; isActive: boolean }) =>
      skillBundlesApi.setActive(base, name, isActive),
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
};
