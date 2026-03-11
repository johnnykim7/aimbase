package com.platform.policy.model;

import java.util.List;

public record PolicyMatch(
        List<String> intents,
        List<String> actions,
        List<String> adapters,
        List<String> connections,
        List<String> destinations,
        List<String> roles
) {}
