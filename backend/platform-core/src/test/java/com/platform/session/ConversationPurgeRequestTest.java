package com.platform.session;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-204 TC-CHT-162: 일괄 완전 삭제 조건 검증. */
class ConversationPurgeRequestTest {

    private static ConversationPurgeRequest of(String userRef, String shareKey, String userRefPrefix,
                                               String shareKeyPrefix, Integer limit) {
        return new ConversationPurgeRequest(userRef, shareKey, userRefPrefix, shareKeyPrefix, null, limit);
    }

    private static void rejected(ConversationPurgeRequest r, String code) {
        assertThatThrownBy(r::validated)
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage(code)
                .extracting(e -> ((ChatRejectedException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void noCriteriaIsRejected() {
        rejected(of(null, null, null, null, null), ConversationPurgeRequest.INVALID_CRITERIA);
    }

    @Test
    void shareKeyMustFollowKeyRule() {
        rejected(of(null, "lexflow case", null, null, null), ConversationPurgeRequest.INVALID_SHARE_KEY);
        rejected(of(null, null, null, "", null), ConversationPurgeRequest.INVALID_SHARE_KEY_PREFIX);
        rejected(of(null, null, null, "a".repeat(129), null), ConversationPurgeRequest.INVALID_SHARE_KEY_PREFIX);
        assertThat(of(null, "lexflow:case:office-12:/case/[id]:7", null, "lexflow:case:office-12:", null)
                .validated().shareKeyPrefix()).isEqualTo("lexflow:case:office-12:");
    }

    @Test
    void userRefMustBeNonBlankAndWithinLength() {
        rejected(of(" ", null, null, null, null), ConversationPurgeRequest.INVALID_USER_REF);
        rejected(of("a".repeat(201), null, null, null, null), ConversationPurgeRequest.INVALID_USER_REF);
        rejected(of(null, null, "", null, null), ConversationPurgeRequest.INVALID_USER_REF);
        assertThat(of("office-12:user-7", null, null, null, null).validated().hasUserCriteria()).isTrue();
        assertThat(of(null, "k", null, null, null).validated().hasUserCriteria()).isFalse();
    }

    @Test
    void limitMustBePositive() {
        rejected(of("u", null, null, null, 0), ConversationPurgeRequest.INVALID_LIMIT);
        assertThat(of("u", null, null, null, 500).validated().limit()).isEqualTo(500);
    }

    @Test
    void criteriaKeepsOnlyGivenFields() {
        assertThat(of("u", null, null, "p:", null).criteria())
                .containsExactly(java.util.Map.entry("user_ref", "u"), java.util.Map.entry("share_key_prefix", "p:"));
        assertThat(new ConversationPurgeRequest("u", null, null, null, true, null).isDryRun()).isTrue();
        assertThat(of("u", null, null, null, null).isDryRun()).isFalse();
    }
}
