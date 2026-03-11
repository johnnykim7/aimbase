package com.platform.action.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ActionRequest(
        String id,
        String intent,
        ActionType type,
        List<ActionTarget> targets,
        ActionPayload payload,
        ActionPolicy policy,
        ActionMetadata metadata
) {
    public enum ActionType {
        WRITE, NOTIFY, WRITE_AND_NOTIFY
    }

    public static ActionRequest of(String intent, ActionType type, List<ActionTarget> targets,
                                    Map<String, Object> data) {
        return new ActionRequest(
                UUID.randomUUID().toString(),
                intent,
                type,
                targets,
                new ActionPayload(null, data, null),
                new ActionPolicy(false, null, 3, 3000L, true),
                new ActionMetadata(null, null, null)
        );
    }
}
