package com.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** CR-147: 테넌트 전용 스킬 묶음 (Tenant DB). */
@Entity
@Table(name = "skill_bundles")
public class SkillBundleEntity extends SkillBundleBase {
}
