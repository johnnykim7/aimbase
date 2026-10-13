package com.platform.workflow.model;

import java.util.Locale;

/** CR-084 워크플로우 실행 모드. DB 와 API 에는 소문자로 적는다(workflows.graph_mode). */
public enum GraphMode {
    DAG,
    CYCLIC;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 비어 있으면 DAG, 모르는 값이면 null. */
    public static GraphMode parse(String raw) {
        if (raw == null || raw.isBlank()) return DAG;
        for (GraphMode mode : values()) {
            if (mode.value().equalsIgnoreCase(raw.trim())) return mode;
        }
        return null;
    }
}
