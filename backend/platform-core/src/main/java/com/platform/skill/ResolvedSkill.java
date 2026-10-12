package com.platform.skill;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CR-147: 이름 해석 결과(BIZ-120). 단계 시작 때 한 번 고정하고 그 단계는 이 판만 쓴다.
 */
public record ResolvedSkill(String name, SkillScope scope, int version, String description, UUID versionId) {

    /** run 작업장 안에서 이 판을 풀어 두는 상대 위치. 실행 컨테이너도 같은 경로로 본다. */
    public String workspaceDir() {
        return ".skills/" + name + "/v" + version;
    }

    /** STEP_START payload 와 CLI 머리글에 싣는 모양. */
    public Map<String, Object> toRef() {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("name", name);
        ref.put("scope", scope.value());
        ref.put("version", version);
        return ref;
    }
}
