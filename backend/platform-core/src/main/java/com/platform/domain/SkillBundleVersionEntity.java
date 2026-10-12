package com.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** CR-147: 테넌트 전용 스킬 묶음 판 (Tenant DB). */
@Entity
@Table(name = "skill_bundle_versions")
public class SkillBundleVersionEntity extends SkillBundleVersionBase {
}
