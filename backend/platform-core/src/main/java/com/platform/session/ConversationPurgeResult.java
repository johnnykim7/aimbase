package com.platform.session;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * CR-204 PRD-650: 일괄 완전 삭제 한 번의 결과. {@code remaining} 이 0 이 될 때까지 호출하는 쪽이 같은 본문으로 다시 부른다.
 * 미리 보기({@code dry_run})는 sessions.matched 와 users.matched 만 채운다.
 */
public record ConversationPurgeResult(
        @JsonProperty("dry_run") boolean dryRun,
        boolean completed,
        Sessions sessions,
        Rows rows,
        Files files,
        Users users) {

    public record Sessions(long matched, int deleted, long remaining, int failed,
                           @JsonProperty("failed_session_ids") List<String> failedSessionIds) {
    }

    public record Rows(int messages, int attachments,
                       @JsonProperty("generated_images") int generatedImages,
                       int documents,
                       @JsonProperty("document_versions") int documentVersions,
                       int shares, int feedback, int memories, int briefs, int approvals,
                       @JsonProperty("tool_results") int toolResults,
                       int traces,
                       @JsonProperty("tool_logs") int toolLogs) {
    }

    public record Files(int deleted, int failed) {
    }

    public record Users(int matched, int preferences, int memories, int projects,
                        @JsonProperty("project_files") int projectFiles,
                        int feedback,
                        @JsonProperty("asker_names_cleared") int askerNamesCleared) {
    }
}
