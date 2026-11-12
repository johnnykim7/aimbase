package com.platform.chatdoc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** CR-181: 고치기 결과. 스트림 이벤트 document 와 비스트리밍 result.document 의 본문. */
public record DocumentEditResult(UUID documentId, Status status, Integer version) {

    public enum Status { UPDATED, UNCHANGED, FAILED }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document_id", documentId.toString());
        m.put("status", status.name());
        if (version != null) m.put("version", version);
        return m;
    }
}
