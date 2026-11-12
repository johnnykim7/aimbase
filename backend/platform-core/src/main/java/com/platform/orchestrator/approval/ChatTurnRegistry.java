package com.platform.orchestrator.approval;

import com.platform.auth.UserPrincipal;
import com.platform.llm.adapter.RequestContext;
import com.platform.orchestrator.stream.StreamEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * CR-185 PRD-560: 지금 스트림으로 답하고 있는 대화 턴과, 그 턴에서 결정을 기다리는 승인을 둔다.
 *
 * <p>도구 관문은 세션 번호로 턴을 찾아 그 스트림에 승인 요청을 보내고, 결정 API 는 승인 번호로 기다림을 깨운다.
 * 응답 중지 등록부(CancellationRegistry)처럼 한 서버 안에서만 동작한다.
 */
@Component
public class ChatTurnRegistry {

    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";

    public static final class ChatTurn {
        private final String sessionId;
        private final String requester;
        private final Consumer<StreamEvent> sink;
        private final AtomicBoolean cancelled;
        private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();

        ChatTurn(String sessionId, String requester, Consumer<StreamEvent> sink, AtomicBoolean cancelled) {
            this.sessionId = sessionId;
            this.requester = requester;
            this.sink = sink;
            this.cancelled = cancelled;
        }

        public String sessionId() { return sessionId; }
        public String requester() { return requester; }
        public AtomicBoolean cancelled() { return cancelled; }

        /** 보내다 실패하면 false. 스트림이 끊긴 것으로 본다. */
        boolean emit(StreamEvent event) {
            try {
                sink.accept(event);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /**
     * 지금 요청을 보낸 사람. 위젯 토큰은 user_ref, 없으면 인증 주체 id. 턴을 연 사람과 결정하는 사람을 같은 기준으로 견준다.
     */
    public static String currentActor() {
        String userRef = RequestContext.getUserRef();
        if (userRef != null && !userRef.isBlank()) return userRef;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) return p.getId();
        return auth != null ? auth.getName() : null;
    }

    private final ConcurrentHashMap<String, ChatTurn> turns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<String>> decisions = new ConcurrentHashMap<>();

    public ChatTurn open(String sessionId, String requester, Consumer<StreamEvent> sink, AtomicBoolean cancelled) {
        ChatTurn turn = new ChatTurn(sessionId, requester, sink, cancelled);
        ChatTurn previous = turns.put(sessionId, turn);
        if (previous != null) release(previous);
        return turn;
    }

    public ChatTurn get(String sessionId) {
        return sessionId == null ? null : turns.get(sessionId);
    }

    /** 그 턴일 때만 내린다. 앞 턴이 늦게 끝나며 같은 세션의 새 턴을 내리면 새 턴의 승인 요청이 갈 곳을 잃는다. */
    public void close(String sessionId, ChatTurn turn) {
        if (sessionId == null || turn == null) return;
        turns.remove(sessionId, turn);
        release(turn);
    }

    CompletableFuture<String> await(ChatTurn turn, UUID approvalId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        decisions.put(approvalId, future);
        turn.waiting.add(approvalId);
        return future;
    }

    void forget(ChatTurn turn, UUID approvalId) {
        decisions.remove(approvalId);
        turn.waiting.remove(approvalId);
    }

    /** 기다리는 쪽이 이 서버에 있으면 깨운다. 없으면(턴이 이미 끝남) false. */
    public boolean resolve(UUID approvalId, String status) {
        CompletableFuture<String> future = decisions.get(approvalId);
        return future != null && future.complete(status);
    }

    private void release(ChatTurn turn) {
        for (UUID id : turn.waiting) {
            CompletableFuture<String> future = decisions.remove(id);
            if (future != null) future.complete(CANCELLED);
        }
        turn.waiting.clear();
    }
}
