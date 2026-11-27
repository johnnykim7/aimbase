package com.platform.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-204 BIZ-294: 일괄 완전 삭제 조건. 네 조건 중 하나 이상이고 둘 이상이면 합집합이다.
 * 주지 않은 칸(null)만 조건이 없는 것이고, 빈 글자는 준 것으로 보고 거절한다.
 */
public record ConversationPurgeRequest(
        @JsonProperty("user_ref") String userRef,
        @JsonProperty("share_key") String shareKey,
        @JsonProperty("user_ref_prefix") String userRefPrefix,
        @JsonProperty("share_key_prefix") String shareKeyPrefix,
        @JsonProperty("dry_run") Boolean dryRun,
        Integer limit) {

    public static final int USER_REF_MAX = 200;
    public static final String INVALID_CRITERIA = "purge_invalid:criteria";
    public static final String INVALID_SHARE_KEY = "purge_invalid:share_key";
    public static final String INVALID_SHARE_KEY_PREFIX = "purge_invalid:share_key_prefix";
    public static final String INVALID_USER_REF = "purge_invalid:user_ref";
    public static final String INVALID_LIMIT = "purge_invalid:limit";

    public ConversationPurgeRequest validated() {
        if (userRef == null && shareKey == null && userRefPrefix == null && shareKeyPrefix == null) {
            reject(INVALID_CRITERIA);
        }
        if (userRef != null && (userRef.isBlank() || userRef.length() > USER_REF_MAX)) reject(INVALID_USER_REF);
        if (userRefPrefix != null && (userRefPrefix.isBlank() || userRefPrefix.length() > USER_REF_MAX)) {
            reject(INVALID_USER_REF);
        }
        if (shareKey != null && !ChatKeys.isValid(shareKey)) reject(INVALID_SHARE_KEY);
        if (shareKeyPrefix != null && !ChatKeys.isValid(shareKeyPrefix)) reject(INVALID_SHARE_KEY_PREFIX);
        if (limit != null && limit < 1) reject(INVALID_LIMIT);
        return this;
    }

    public boolean isDryRun() {
        return Boolean.TRUE.equals(dryRun);
    }

    /** 사용자 자료(선호, 기억, 프로젝트, 평가)까지 지우는 조건인가. */
    public boolean hasUserCriteria() {
        return userRef != null || userRefPrefix != null;
    }

    /** 감사 기록과 로그에 남기는 조건. 준 칸만 담는다. */
    public Map<String, Object> criteria() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (userRef != null) m.put("user_ref", userRef);
        if (shareKey != null) m.put("share_key", shareKey);
        if (userRefPrefix != null) m.put("user_ref_prefix", userRefPrefix);
        if (shareKeyPrefix != null) m.put("share_key_prefix", shareKeyPrefix);
        return m;
    }

    private static void reject(String code) {
        throw new ChatRejectedException(HttpStatus.BAD_REQUEST, code);
    }
}
