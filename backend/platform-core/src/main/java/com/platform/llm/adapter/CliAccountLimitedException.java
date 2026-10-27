package com.platform.llm.adapter;

import com.platform.llm.ConnectionGroupSelector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CR-161: CLI 계정이 사용 한도에 걸렸다. 실행기에는 닿았으므로 {@code cli_runner_unreachable:} 로 감싸지 않는다.
 *
 * <p>메시지는 {@code cli_account_limited:resets_at=<초>:type=<종류>:} 머리만 둔다(API 가이드 § 25-3).
 * CLI 가 낸 원문은 cause 에 남는다. 연결 묶음은 이 머리로 다음 멤버로 넘기고, 단일 커넥션 비실시간은 503 이다.
 */
public class CliAccountLimitedException extends RuntimeException {

    private static final Pattern HEAD = Pattern.compile(
            Pattern.quote(ConnectionGroupSelector.ACCOUNT_LIMITED_PREFIX) + "resets_at=\\d*:type=[^:\"\\\\]*:");

    public CliAccountLimitedException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 예외 사슬의 메시지에서 한도 머리를 찾는다. 실행기 오류 본문(JSON)에 싸여 와도 찾는다. 없으면 null. */
    static CliAccountLimitedException from(Throwable failure) {
        Throwable cur = failure;
        for (int i = 0; i < 10 && cur != null; i++) {
            String msg = cur.getMessage();
            if (msg != null) {
                Matcher m = HEAD.matcher(msg);
                if (m.find()) return new CliAccountLimitedException(m.group(), failure);
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return null;
    }
}
