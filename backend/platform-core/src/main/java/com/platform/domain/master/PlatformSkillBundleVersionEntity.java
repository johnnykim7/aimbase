package com.platform.domain.master;

import com.platform.domain.SkillBundleVersionBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** CR-147: 플랫폼 공용 스킬 묶음 판 (Master DB). */
@Entity
@Table(name = "platform_skill_bundle_versions")
public class PlatformSkillBundleVersionEntity extends SkillBundleVersionBase {
}
