package com.platform.tool.builtin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Claude Code CLI 도구 설정.
 *
 * application.yml의 claude-code.* 프로퍼티를 바인딩.
 *
 * 인증 방식:
 * - 로컬/개발: `claude login`으로 OAuth 토큰 사용 (api-key 불필요)
 * - 서버/AWS: ANTHROPIC_API_KEY 환경변수 또는 api-key 설정
 *
 * 예시 설정:
 * <pre>
 * claude-code:
 *   enabled: true
 *   executable: claude
 *   api-key: ${CLAUDE_CODE_API_KEY:}       # AWS Secrets Manager 등에서 주입
 *   timeout-seconds: 300
 *   max-turns: 10
 *   default-allowed-tools: Read,Grep,Glob
 *   working-directory: /data/workspace
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "claude-code")
public class ClaudeCodeToolConfig {

    /** 도구 활성화 여부 (기본: false) */
    private boolean enabled = false;

    /** Claude Code CLI 실행 파일 경로 (기본: claude) */
    private String executable = "claude";

    /**
     * Anthropic API 키 (종량제 배포 시 사용).
     * 설정하면 --bare 모드 + ANTHROPIC_API_KEY 환경변수로 프로세스에 전달.
     * 미설정 시 CLAUDE_CONFIG_DIR 내 OAuth 인증 사용 (setup-token 방식).
     */
    private String apiKey;

    /** 프로세스 실행 타임아웃 (초, 기본: 300) */
    private int timeoutSeconds = 300;

    /** 에이전트 루프 최대 턴 수 (기본: 50, VS Code 플러그인과 동일) */
    private int maxTurns = 50;

    /** 기본 허용 도구 (쉼표 구분, 빈 값이면 CLI 기본값 사용 = 제한 없음) */
    private String defaultAllowedTools = "";

    /** 기본 작업 디렉토리 */
    private String workingDirectory;

    /** 기본 시스템 프롬프트 (CLI에 --append-system-prompt로 전달) */
    private String defaultSystemPrompt = "";

    // ── Getters & Setters ──

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getExecutable() { return executable; }
    public void setExecutable(String executable) { this.executable = executable; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public String getDefaultAllowedTools() { return defaultAllowedTools; }
    public void setDefaultAllowedTools(String defaultAllowedTools) { this.defaultAllowedTools = defaultAllowedTools; }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
    public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }
}
