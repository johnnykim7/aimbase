package com.platform.tool.builtin;

import com.platform.agent.AgentMessageBus;
import com.platform.domain.AgentMessageEntity;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-034 PRD-228: 에이전트 간 메시지 전송 도구.
 *
 * 사용 시나리오:
 * - 1:1 메시지: to에 에이전트 ID/이름 지정
 * - 브로드캐스트: to에 "*" 지정
 * - 결과 전달: message_type을 "RESULT"로 지정
 *
 * BIZ-057: 메시지 본문 최대 32KB, 세션당 메시지 최대 500개.
 */
@Component
public class SendMessageTool implements EnhancedToolExecutor {

    private final AgentMessageBus messageBus;
    private final HookDispatcher hookDispatcher;
    private final com.platform.config.PlatformSettingsService platformSettings;

    public SendMessageTool(AgentMessageBus messageBus, HookDispatcher hookDispatcher,
                           com.platform.config.PlatformSettingsService platformSettings) {
        this.messageBus = messageBus;
        this.hookDispatcher = hookDispatcher;
        this.platformSettings = platformSettings;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "send_message",
                "에이전트 간 메시지를 전송합니다. 다른 에이전트에게 작업 지시, 결과 전달, 상태 알림 등에 사용합니다. " +
                        "to에 에이전트 ID를 지정하면 1:1, \"*\"를 지정하면 브로드캐스트입니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "to", Map.of("type", "string",
                                        "description", "수신 에이전트 ID 또는 이름. \"*\"은 브로드캐스트"),
                                "content", Map.of("type", "string",
                                        "description", "메시지 본문"),
                                "message_type", Map.of("type", "string",
                                        "enum", List.of("TEXT", "COMMAND", "RESULT", "ERROR"),
                                        "description", "메시지 유형 (기본: TEXT)")
                        ),
                        "required", List.of("to", "content")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "send_message", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("agent-collaboration", "messaging"),
                List.of("send", "message")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String to = (String) input.get("to");
        String content = (String) input.get("content");
        String messageType = (String) input.getOrDefault("message_type", "TEXT");

        // 검증
        if (to == null || to.isBlank()) {
            return ToolResult.error("'to' is required");
        }
        if (content == null || content.isBlank()) {
            return ToolResult.error("'content' is required");
        }
        int maxContentLength = platformSettings.getInt("session.message-body-max-bytes", 32768);
        if (content.length() > maxContentLength) {
            return ToolResult.error("Message content exceeds maximum length of " + maxContentLength + " bytes");
        }

        // 세션 메시지 수 제한
        String sessionId = ctx.sessionId();
        int maxMessages = platformSettings.getInt("session.max-messages-per-session", 500);
        long sessionMsgCount = messageBus.getSessionMessages(sessionId).size();
        if (sessionMsgCount >= maxMessages) {
            return ToolResult.denied("Session message limit reached (" + maxMessages + ")");
        }

        // 발신자 결정: 서브에이전트면 자신의 세션ID, 아니면 "orchestrator"
        String fromAgentId = ctx.sessionId();

        // 메타데이터 구성
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) input.get("metadata");

        AgentMessageEntity saved = messageBus.send(sessionId, fromAgentId, to, messageType, content, metadata);

        // CR-034: MESSAGE_SENT 훅 발행
        try {
            hookDispatcher.dispatch(HookEvent.MESSAGE_SENT,
                    HookInput.of(HookEvent.MESSAGE_SENT, sessionId,
                            Map.of("messageId", saved.getId().toString(),
                                    "from", fromAgentId, "to", to,
                                    "messageType", messageType),
                            Map.of()));
        } catch (Exception ignored) {
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", saved.getId().toString());
        result.put("from", fromAgentId);
        result.put("to", to);
        result.put("message_type", messageType);
        result.put("status", "sent");

        return ToolResult.ok(result, "Message sent to " + to);
    }
}
