package com.platform.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CR-147: 스킬 실행 컨테이너 주소와 열쇠(T3-17 3.7). 환경마다 달라 global_config 가 아니라 환경설정에 둔다.
 */
@Component
@ConfigurationProperties(prefix = "aimbase.skill-sandbox")
public class SkillSandboxProperties {

    private String url;
    private String apiKey;
    private int connectTimeoutSeconds = 5;
    /** 스크립트 시간 상한에 더해 기다리는 시간. 실행 컨테이너가 자리를 기다리거나 결과를 정리하는 몫이다. */
    private int responseGraceSeconds = 30;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }

    public int getResponseGraceSeconds() { return responseGraceSeconds; }
    public void setResponseGraceSeconds(int responseGraceSeconds) { this.responseGraceSeconds = responseGraceSeconds; }
}
