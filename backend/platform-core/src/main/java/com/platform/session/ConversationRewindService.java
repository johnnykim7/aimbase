package com.platform.session;

import com.platform.chatdoc.ChatDocumentService;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.repository.ChatGeneratedImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CR-175 BIZ-148: 다시 만들기와 질문 고치기의 되감기.
 *
 * <p>Claude CLI 연결은 실행기 작업자가 대화를 따로 기억하고 둘째 요청부터는 마지막 질문만 받는다.
 * 대화 기록만 되감으면 작업자는 지운 답을 계속 기억하므로, 되감은 뒤 그 세션 작업자를 닫는다.
 * 다음 요청은 새 작업자가 남은 기록 전체를 받아 이어 간다.
 */
@Service
public class ConversationRewindService {

    private static final Logger log = LoggerFactory.getLogger(ConversationRewindService.class);

    private final SessionStore sessionStore;
    private final CancellationRegistry cancellationRegistry;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final ConnectionGroupSelector connectionGroupSelector;
    private final ChatGeneratedImageRepository imageRepository;
    private final ChatDocumentService chatDocumentService;

    public ConversationRewindService(SessionStore sessionStore,
                                     CancellationRegistry cancellationRegistry,
                                     ConnectionAdapterFactory connectionAdapterFactory,
                                     ConnectionGroupSelector connectionGroupSelector,
                                     ChatGeneratedImageRepository imageRepository,
                                     ChatDocumentService chatDocumentService) {
        this.sessionStore = sessionStore;
        this.cancellationRegistry = cancellationRegistry;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.connectionGroupSelector = connectionGroupSelector;
        this.imageRepository = imageRepository;
        this.chatDocumentService = chatDocumentService;
    }

    /**
     * @param connectionId      이번 요청 연결. 없으면 테넌트 기본 CLI 연결을 본다
     * @param connectionGroupId 이번 요청 연결 묶음. 있으면 묶음의 모든 연결을 본다
     * @throws RewindRejectedException 답이 도는 중이거나, 묶음 첫 순번이 아니거나, 압축 경계보다 앞일 때
     */
    public void rewind(String sessionId, int fromSeq, String connectionId, String connectionGroupId) {
        if (cancellationRegistry.isActive(sessionId)) {
            throw new RewindRejectedException(RewindRejectedException.Reason.SESSION_BUSY);
        }
        // CR-181 BIZ-202: 지운 답이 만든 문서 판도 같은 트랜잭션에서 지우고, 파일은 남은 마지막 판으로 되돌린다.
        Set<UUID> touchedDocuments = new HashSet<>();
        sessionStore.rewind(sessionId, fromSeq,
                (turnIds, ts) -> touchedDocuments.addAll(chatDocumentService.deleteEditsOfTurns(turnIds, ts)));
        if (!touchedDocuments.isEmpty()) chatDocumentService.restoreFilesToLatest(touchedDocuments);
        softDeleteRewoundImages(sessionId);
        for (String connId : candidateConnections(connectionId, connectionGroupId)) {
            if (connectionAdapterFactory.isCliConnection(connId)) {
                connectionAdapterFactory.getAdapter(connId).cleanupSession(sessionId);
                log.info("Rewind: CLI worker for session {} closed on connection {}", sessionId, connId);
            }
        }
    }

    /**
     * CR-184 BIZ-231: 되감긴 묶음의 그림에도 지운 표시를 한다. 대화 기록은 이미 되감겼으므로 여기서 실패해도
     * 요청을 실패로 돌리지 않는다. 남은 그림은 다음 되감기나 대화 지우기가 다시 지운다.
     */
    private void softDeleteRewoundImages(String sessionId) {
        try {
            int n = imageRepository.softDeleteOrphanedBySessionId(sessionId, OffsetDateTime.now());
            if (n > 0) log.info("Rewind: {} generated image(s) of session {} soft-deleted", n, sessionId);
        } catch (RuntimeException e) {
            log.warn("Rewind: generated images of session {} not soft-deleted: {}", sessionId, e.getMessage());
        }
    }

    private List<String> candidateConnections(String connectionId, String connectionGroupId) {
        List<String> ids = new ArrayList<>();
        if (connectionGroupId != null && !connectionGroupId.isBlank()) {
            ids.addAll(connectionGroupSelector.memberConnectionIds(connectionGroupId));
        } else if (connectionId != null && !connectionId.isBlank()) {
            ids.add(connectionId);
        } else {
            String defaultCli = connectionAdapterFactory.findDefaultCliConnectionForCurrentTenant();
            if (defaultCli != null) ids.add(defaultCli);
        }
        return ids;
    }
}
