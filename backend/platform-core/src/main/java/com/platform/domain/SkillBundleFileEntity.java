package com.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** CR-147: 테넌트 전용 스킬 묶음 파일 (Tenant DB). */
@Entity
@Table(name = "skill_bundle_files")
public class SkillBundleFileEntity extends SkillBundleFileBase {
}
