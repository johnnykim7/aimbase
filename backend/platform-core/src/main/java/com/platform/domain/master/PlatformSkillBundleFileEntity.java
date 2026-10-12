package com.platform.domain.master;

import com.platform.domain.SkillBundleFileBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** CR-147: 플랫폼 공용 스킬 묶음 파일 (Master DB). */
@Entity
@Table(name = "platform_skill_bundle_files")
public class PlatformSkillBundleFileEntity extends SkillBundleFileBase {
}
