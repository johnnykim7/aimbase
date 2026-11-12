package com.platform.orchestrator.approval;

import com.platform.session.ChatRejectedException;
import org.springframework.http.HttpStatus;

/**
 * CR-185: 승인 결정을 받지 않은 이유. 이미 정해진 승인(409)은 지금 상태를 같이 돌려줘 위젯이 카드를 그 상태로 바꾼다.
 */
public class ApprovalDecisionException extends ChatRejectedException {

    private final String currentStatus;

    public ApprovalDecisionException(HttpStatus status, String code) {
        this(status, code, null);
    }

    public ApprovalDecisionException(HttpStatus status, String code, String currentStatus) {
        super(status, code);
        this.currentStatus = currentStatus;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
