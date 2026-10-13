// CR-147 스킬 묶음. 응답 칸은 T3-17 3.9.

export type SkillScope = "tenant" | "platform";

export type SkillBundleWarningCode = "MISSING_REFERENCE" | "FOLDER_NAME_MISMATCH" | "UNSUPPORTED_SCRIPT";

export interface SkillBundleWarning {
  code: SkillBundleWarningCode | string;
  path?: string;
}

export interface SkillBundleSummary {
  name: string;
  description: string;
  scope: SkillScope;
  latest_version: number;
  is_active: boolean;
  /** 플랫폼 공용인데 같은 이름의 활성 테넌트 전용이 있어 쓰이지 않는다 */
  shadowed?: boolean;
  created_at: string;
  updated_at: string;
}

export interface SkillBundleVersionSummary {
  version: number;
  description: string;
  file_count: number;
  total_bytes: number;
  content_sha256: string;
  warnings: SkillBundleWarning[];
  uploaded_by: string | null;
  created_at: string;
}

export interface SkillBundleDetail extends SkillBundleSummary {
  versions: SkillBundleVersionSummary[];
}

export interface SkillBundleFileEntry {
  path: string;
  size_bytes: number;
  is_text: boolean;
}

export interface SkillBundleVersionDetail extends SkillBundleVersionSummary {
  name: string;
  scope: SkillScope;
  instructions: string;
  files: SkillBundleFileEntry[];
}

export interface SkillBundleFile extends SkillBundleFileEntry {
  content?: string;
  truncated?: boolean;
}

export interface SkillBundleUploadResult {
  name: string;
  scope: SkillScope;
  version: number;
  /** false 면 직전 판과 내용이 같아 새 판을 만들지 않았다(BIZ-119) */
  created: boolean;
  reactivated: boolean;
  warnings: SkillBundleWarning[];
}

/** 워크플로우 단계 config.skills 한 항목(T3-17 3.6). version 이 없으면 최신 판 */
export interface SkillPin {
  name: string;
  version?: number;
  files?: string[];
}
