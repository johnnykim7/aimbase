package com.platform.session;

import com.platform.config.WorkspaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 채팅 세션의 작업 폴더 {@code {base}/chat/{session_id}} 를 잡는다(CR-132).
 *
 * <p>CLI 연결은 대화를 시작할 때, 파일 만들기 도구(CR-182)는 API 연결에서 처음 부를 때 쓴다. 이미 잡힌
 * 폴더가 있으면 그것을 돌려준다(BIZ-091, 세션 도중 바꾸지 않는다).
 */
@Component
public class ChatWorkspaceAssigner {

    private static final Logger log = LoggerFactory.getLogger(ChatWorkspaceAssigner.class);

    private final SessionStore sessionStore;
    private final WorkspaceProperties workspaceProperties;

    public ChatWorkspaceAssigner(SessionStore sessionStore, WorkspaceProperties workspaceProperties) {
        this.sessionStore = sessionStore;
        this.workspaceProperties = workspaceProperties;
    }

    /** @return 작업 폴더 절대경로. 잡지 못하면 null(대화는 계속된다). */
    public String assign(String sessionId) {
        return assign(sessionId, null);
    }

    /**
     * CR-187: 대화를 받을 실행기가 작업 폴더를 따로 붙였으면 그 폴더 아래 {@code chat/{session_id}} 에 잡는다.
     * 공용 base 에 잡으면 그 실행기 안에는 그 경로가 없어 실행기가 만든 파일이 대화에 올라오지 않는다.
     *
     * @param runnerName 연결 설정 {@code agent_name}. 없거나 따로 붙인 실행기가 아니면 base 아래에 잡는다.
     */
    public String assign(String sessionId, String runnerName) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String existing = sessionStore.getWorkspaceRef(sessionId);
        if (existing != null && !existing.isBlank()) return existing;

        String runnerBase = workspaceProperties.runnerBase(runnerName);
        String base = runnerBase != null ? runnerBase : workspaceProperties.getBase();
        if (base == null || base.isBlank()) return null;
        try {
            Path chatRoot = Path.of(base, "chat").toAbsolutePath().normalize();
            Path dir = chatRoot.resolve(sessionId).normalize();
            // 세션 id 는 위젯이 보낸 값이라, 상위 참조나 경로 구분자로 chat 밖에 폴더를 만들지 못하게 한다.
            if (!chatRoot.equals(dir.getParent())) {
                log.warn("[CR182] 채팅 작업 폴더 거절 — chat 바로 아래가 아님: {}", dir);
                return null;
            }
            if (!workspaceProperties.isInsideWhitelist(dir)) {
                log.warn("[CR132] 채팅 작업 폴더 거절 — 허용 폴더 밖: {}", dir);
                return null;
            }
            Files.createDirectories(dir);
            String assigned = dir.toString();
            sessionStore.setWorkspaceRefIfAbsent(sessionId, assigned);
            log.info("[CR132] 채팅 작업 폴더 잡음: session={}, path={}, runner={}", sessionId, assigned,
                    runnerBase != null ? runnerName : "-");
            return sessionStore.getWorkspaceRef(sessionId);
        } catch (Exception e) {
            log.warn("[CR132] 채팅 작업 폴더 잡기 실패 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }
}
