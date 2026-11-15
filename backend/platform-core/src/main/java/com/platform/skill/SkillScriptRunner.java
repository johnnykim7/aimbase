package com.platform.skill;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 스킬 묶음 스크립트를 실행 컨테이너에서 돌리는 공통 부분(BIZ-122). skill_run 도구와
 * 검사 반복의 코드 대조(CR-193, BIZ-195)가 같은 경로 확인과 시간 한도를 쓰게 한다.
 */
@Component
public class SkillScriptRunner {

    private final SkillWorkspaceInstaller installer;
    private final SkillSandboxClient sandbox;
    private final SkillBundleSettings settings;

    public SkillScriptRunner(SkillWorkspaceInstaller installer, SkillSandboxClient sandbox,
                             SkillBundleSettings settings) {
        this.installer = installer;
        this.sandbox = sandbox;
        this.settings = settings;
    }

    /**
     * 묶음을 작업장에 풀고 묶음 안 스크립트의 절대 경로를 돌려준다.
     * 경로가 묶음 밖을 가리키면 IllegalArgumentException 이다.
     */
    public Path installBundleScript(ResolvedSkill skill, Path workspace, String script) {
        Path bundleDir = installer.install(skill, workspace);
        Path scriptPath = bundleDir.resolve(script.trim()).normalize();
        if (!scriptPath.startsWith(bundleDir)) {
            throw new IllegalArgumentException("BIZ-122: 스크립트 경로가 스킬 묶음 밖입니다: " + script);
        }
        return scriptPath;
    }

    /** 요청한 시간이 없으면 기본값, 상한을 넘으면 상한으로 줄인다. */
    public int timeoutSeconds(Integer requested) {
        int timeout = requested == null ? settings.scriptTimeoutSeconds() : requested;
        return Math.max(1, Math.min(timeout, settings.scriptTimeoutMaxSeconds()));
    }

    public int outputMaxBytes() {
        return settings.scriptOutputMaxBytes();
    }

    public SkillSandboxClient.RunResult run(Path workspace, Path script, List<String> args, int timeoutSeconds) {
        return sandbox.run(new SkillSandboxClient.RunRequest(workspace.toString(), script.toString(),
                args, timeoutSeconds, settings.scriptOutputMaxBytes()));
    }
}
