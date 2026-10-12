package com.platform.domain.master;

import com.platform.domain.SkillBundleBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** CR-147: 플랫폼 공용 스킬 묶음 (Master DB). */
@Entity
@Table(name = "platform_skill_bundles")
public class PlatformSkillBundleEntity extends SkillBundleBase {
}
