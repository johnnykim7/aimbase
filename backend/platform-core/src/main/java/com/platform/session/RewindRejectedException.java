package com.platform.session;

/**
 * CR-175 BIZ-148: 되감기를 받을 수 없는 요청.
 */
public class RewindRejectedException extends RuntimeException {

    public enum Reason {
        /** 그 세션에 답이 도는 중이다. 위젯은 먼저 멈추고 다시 보낸다. */
        SESSION_BUSY,
        /** 그 순번이 없거나 대화 요청 한 번이 저장한 메시지 묶음의 첫 순번이 아니다. */
        NOT_TURN_START,
        /** 그 순번 뒤에 압축 경계가 있다. 압축으로 요약된 앞 대화는 되돌릴 수 없다. */
        BEFORE_COMPACTION
    }

    private final Reason reason;

    public RewindRejectedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
