package com.platform.tool.workspace;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CR-029: 워크스페이스 접근 정책.
 * 글로벌 → 테넌트 → 프로젝트 3단계 계층으로 적용.
 */
public record WorkspacePolicy(
        List<String> allowedRoots,
        List<String> deniedPaths,
        List<String> allowedExtensions,
        List<String> deniedExtensions,
        long maxFileSize,
        boolean denyBinary,
        List<Pattern> secretPatterns
) {
    /** 기본 정책 (모든 접근 허용, 바이너리/시크릿만 차단) */
    public static WorkspacePolicy defaultPolicy() {
        return new WorkspacePolicy(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                10 * 1024 * 1024,  // 10MB
                true,
                List.of(
                        Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*[=:]\\s*['\"][^'\"]+['\"]"),
                        Pattern.compile("-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----")
                )
        );
    }
}
