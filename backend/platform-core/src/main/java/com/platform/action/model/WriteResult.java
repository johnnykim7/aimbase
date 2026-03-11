package com.platform.action.model;

public record WriteResult(
        boolean success,
        Integer affectedRows,
        String recordId,
        String error
) {
    public static WriteResult success(String recordId) {
        return new WriteResult(true, 1, recordId, null);
    }

    public static WriteResult success(int affectedRows) {
        return new WriteResult(true, affectedRows, null, null);
    }

    public static WriteResult failure(String error) {
        return new WriteResult(false, 0, null, error);
    }
}
