package com.platform.action.model;

import java.util.Map;

public record ActionPayload(
        String schema,
        Map<String, Object> data,
        String format
) {}
