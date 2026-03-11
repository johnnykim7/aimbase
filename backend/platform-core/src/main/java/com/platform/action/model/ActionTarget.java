package com.platform.action.model;

public record ActionTarget(
        String adapter,
        String connection,
        String destination
) {}
