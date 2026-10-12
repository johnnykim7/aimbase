package com.platform.skill;

import com.platform.config.PlatformSettingsService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CR-147: 스킬 묶음 설정값 (global_config, Master V69 seed).
 * 코드 기본값은 설정 행이 없을 때만 쓰며 V69 seed 와 같아야 한다.
 */
@Component
public class SkillBundleSettings {

    static final long DEFAULT_BUNDLE_MAX_BYTES = 20_971_520L;
    static final int DEFAULT_BUNDLE_MAX_FILES = 500;
    static final long DEFAULT_FILE_MAX_BYTES = 10_485_760L;
    static final int DEFAULT_DESCRIPTION_MAX_CHARS = 1024;
    static final List<String> DEFAULT_IGNORED_ENTRIES = List.of("__MACOSX/", ".DS_Store");
    static final List<String> DEFAULT_RUNNABLE_EXTENSIONS = List.of(".py", ".js", ".mjs", ".sh");
    static final int DEFAULT_READ_MAX_BYTES = 262_144;
    static final int DEFAULT_INLINE_MAX_BYTES = 262_144;
    static final int DEFAULT_SCRIPT_TIMEOUT_SECONDS = 120;
    static final int DEFAULT_SCRIPT_TIMEOUT_MAX_SECONDS = 600;
    static final int DEFAULT_SCRIPT_OUTPUT_MAX_BYTES = 65_536;

    private final PlatformSettingsService settings;

    public SkillBundleSettings(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public long bundleMaxBytes() { return settings.getLong("skills.bundle-max-bytes", DEFAULT_BUNDLE_MAX_BYTES); }

    public int bundleMaxFiles() { return settings.getInt("skills.bundle-max-files", DEFAULT_BUNDLE_MAX_FILES); }

    public long fileMaxBytes() { return settings.getLong("skills.file-max-bytes", DEFAULT_FILE_MAX_BYTES); }

    public int descriptionMaxChars() {
        return settings.getInt("skills.description-max-chars", DEFAULT_DESCRIPTION_MAX_CHARS);
    }

    public List<String> ignoredEntries() {
        return settings.getStringList("skills.ignored-entries", DEFAULT_IGNORED_ENTRIES);
    }

    public List<String> runnableExtensions() {
        return settings.getStringList("skills.runnable-extensions", DEFAULT_RUNNABLE_EXTENSIONS);
    }

    public int readMaxBytes() { return settings.getInt("skills.read-max-bytes", DEFAULT_READ_MAX_BYTES); }

    public int inlineMaxBytes() { return settings.getInt("skills.inline-max-bytes", DEFAULT_INLINE_MAX_BYTES); }

    public int scriptTimeoutSeconds() {
        return settings.getInt("skills.script-timeout-seconds", DEFAULT_SCRIPT_TIMEOUT_SECONDS);
    }

    public int scriptTimeoutMaxSeconds() {
        return settings.getInt("skills.script-timeout-max-seconds", DEFAULT_SCRIPT_TIMEOUT_MAX_SECONDS);
    }

    public int scriptOutputMaxBytes() {
        return settings.getInt("skills.script-output-max-bytes", DEFAULT_SCRIPT_OUTPUT_MAX_BYTES);
    }
}
