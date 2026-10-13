import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type {
  SkillBundleDetail,
  SkillBundleFile,
  SkillBundleSummary,
  SkillBundleUploadResult,
  SkillBundleVersionDetail,
  SkillScope,
} from "../types/skillBundle";

/** 테넌트 화면은 테넌트 전용과 플랫폼 공용을 같이 보고, 플랫폼 화면은 공용만 본다 */
export type SkillBundleBase = "/skill-bundles" | "/platform/skill-bundles";

// 묶음은 20MB 까지라 기본 15초로는 올리기가 끊긴다
const UPLOAD_TIMEOUT_MS = 120_000;

const path = (base: SkillBundleBase, name: string) => `${base}/${encodeURIComponent(name)}`;

// 플랫폼 경로는 범위가 하나라 scope 를 받지 않는다
const scopeParams = (base: SkillBundleBase, scope?: SkillScope) =>
  base === "/skill-bundles" && scope ? { scope } : undefined;

export const skillBundlesApi = {
  list: (base: SkillBundleBase) =>
    apiClient.get<ApiResponse<SkillBundleSummary[]>>(base),

  upload: (base: SkillBundleBase, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return apiClient.post<ApiResponse<SkillBundleUploadResult>>(base, form, {
      headers: { "Content-Type": "multipart/form-data" },
      timeout: UPLOAD_TIMEOUT_MS,
    });
  },

  get: (base: SkillBundleBase, name: string, scope?: SkillScope) =>
    apiClient.get<ApiResponse<SkillBundleDetail>>(path(base, name), {
      params: scopeParams(base, scope),
    }),

  getVersion: (base: SkillBundleBase, name: string, version: number, scope?: SkillScope) =>
    apiClient.get<ApiResponse<SkillBundleVersionDetail>>(`${path(base, name)}/versions/${version}`, {
      params: scopeParams(base, scope),
    }),

  getFile: (base: SkillBundleBase, name: string, version: number, filePath: string, scope?: SkillScope) =>
    apiClient.get<ApiResponse<SkillBundleFile>>(`${path(base, name)}/versions/${version}/file`, {
      params: { path: filePath, ...scopeParams(base, scope) },
    }),

  setActive: (base: SkillBundleBase, name: string, isActive: boolean) =>
    apiClient.patch<ApiResponse<SkillBundleSummary>>(path(base, name), { is_active: isActive }),
};
