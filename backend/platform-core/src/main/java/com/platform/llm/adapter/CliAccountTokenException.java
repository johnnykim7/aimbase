package com.platform.llm.adapter;

import com.platform.llm.ConnectionGroupSelector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CR-196 BIZ-283: CLI 계정에 1년 토큰이 없거나 기한이 지나 실행기가 CLI 를 띄우지 않았다.
 * 실행기에는 닿았으므로 {@code cli_runner_unreachable:} 로 감싸지 않는다.
 *
 * <p>메시지는 실행기가 낸 머리 그대로다. {@code cli_account_token_missing:<계정 폴더>} 또는
 * {@code cli_account_token_expired:<계정 폴더>:expires_at=<시각>} (API 가이드 § 25-3).
 * 연결 묶음은 이 머리로 그 계정을 빼고 다음 멤버로 넘기고, 단일 커넥션 비실시간은 503 이다.
 */
public class CliAccountTokenException extends RuntimeException {

    private static final Pattern HEAD = Pattern.compile("(?:"
            + String.join("|", ConnectionGroupSelector.ACCOUNT_TOKEN_PREFIXES.stream().map(Pattern::quote).toList())
            + ")[^\\s\"\\\\]*");

    public CliAccountTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 예외 사슬의 메시지에서 토큰 머리를 찾는다. 실행기 오류 본문(JSON)에 싸여 와도 찾는다. 없으면 null. */
    static CliAccountTokenException from(Throwable failure) {
        Throwable cur = failure;
        for (int i = 0; i < 10 && cur != null; i++) {
            String msg = cur.getMessage();
            if (msg != null) {
                Matcher m = HEAD.matcher(msg);
                if (m.find()) return new CliAccountTokenException(m.group(), failure);
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return null;
    }
}
