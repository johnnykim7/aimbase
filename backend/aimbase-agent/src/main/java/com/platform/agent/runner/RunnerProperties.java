package com.platform.agent.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CR-071 Phase 2: ClaudeCliRunner HTTP 서비스 설정.
 *
 * <p>application.yml 의 {@code aimbase.runner.*} 또는 {@code --runner-mode} 진입 시
 * 시스템 프로퍼티({@code aimbase.runner.api-key} 등) 로 주입된다.
 */
@ConfigurationProperties(prefix = "aimbase.runner")
public class RunnerProperties {

    /** Runner HTTP 서비스 활성화 여부. {@code --runner-mode} 진입 시 true 로 설정됨. */
    private boolean enabled = false;

    /** Runner 인증 API Key. 호출자(Aimbase 서버 ClaudeCliAdapter) 가 X-Api-Key 헤더로 송신. */
    private String apiKey;

    /** 기본 Claude CLI 모델 (connection.config.model 미지정 시 폴백). */
    private String defaultModel = "claude-sonnet-4-5";

    /** Claude CLI 바이너리 경로 (없으면 PATH 의 'claude'). */
    private String claudeBinary = "claude";

    /** 최대 동시 워커 수 (BIZ-100). */
    private int maxWorkers = 5;

    /** Aimbase MCP 서버 jar 경로 (AIMBASE/HYBRID tool-mode 일 때). */
    private String aimbaseMcpJar;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getClaudeBinary() { return claudeBinary; }
    public void setClaudeBinary(String claudeBinary) { this.claudeBinary = claudeBinary; }
    public int getMaxWorkers() { return maxWorkers; }
    public void setMaxWorkers(int maxWorkers) { this.maxWorkers = maxWorkers; }
    public String getAimbaseMcpJar() { return aimbaseMcpJar; }
    public void setAimbaseMcpJar(String aimbaseMcpJar) { this.aimbaseMcpJar = aimbaseMcpJar; }
}
