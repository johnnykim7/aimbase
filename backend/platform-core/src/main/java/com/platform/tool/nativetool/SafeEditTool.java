package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspacePolicy;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CR-029 (PRD-177): diff/patch 생성 전용 도구.
 * 즉시 수정이 아니라 diff를 생성하고, 실제 적용은 PatchApplyTool이 담당.
 * dryRun=true 기본.
 */
@Component
public class SafeEditTool implements EnhancedToolExecutor {

    private final WorkspaceResolver workspaceResolver;
    private final WorkspacePolicyEngine policyEngine;

    // 메모리 내 patch 저장소 (실제 운영에서는 DB로 교체)
    private final Map<String, PatchData> patchStore = new LinkedHashMap<>();

    public SafeEditTool(WorkspaceResolver workspaceResolver, WorkspacePolicyEngine policyEngine) {
        this.workspaceResolver = workspaceResolver;
        this.policyEngine = policyEngine;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_safe_edit",
                "Generates a diff/patch for file changes. Does NOT modify the actual file.\n\n- old_string must appear exactly once in the file (uniqueness validation)\n- Use replace_all=true to replace all occurrences\n- Returns a patchId. Use builtin_patch_apply to actually apply the change",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "File path to edit (absolute)"),
                                "old_string", Map.of("type", "string", "description", "String to find"),
                                "new_string", Map.of("type", "string", "description", "Replacement string"),
                                "replace_all", Map.of("type", "boolean", "default", false, "description", "Replace all occurrences")
                        ),
                        "required", List.of("file_path", "old_string", "new_string")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "builtin_safe_edit", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE, false, false, false, false,
                RetryPolicy.NONE, List.of("filesystem", "edit"), List.of("write", "diff")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ValidationResult.fail("file_path is required.");
        }
        String oldString = (String) input.get("old_string");
        if (oldString == null || oldString.isEmpty()) {
            return ValidationResult.fail("old_string is required.");
        }
        return policyEngine.validatePath(ctx, WorkspacePolicy.defaultPolicy(), filePath);
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));

        Path resolved = workspaceResolver.resolve(ctx, filePath);
        if (!Files.exists(resolved)) {
            return ToolResult.error("File not found: " + filePath)
                    .withDuration(System.currentTimeMillis() - start);
        }

        try {
            String content = Files.readString(resolved);

            // old_string 유니크 검증 (replace_all이 아닌 경우)
            if (!replaceAll) {
                int count = countOccurrences(content, oldString);
                if (count == 0) {
                    return ToolResult.error("old_string not found: " + truncate(oldString, 100))
                            .withDuration(System.currentTimeMillis() - start);
                }
                if (count > 1) {
                    return ToolResult.error(
                            "old_string이 " + count + "occurrences found. Use replace_all=true or specify a more unique string.")
                            .withDuration(System.currentTimeMillis() - start);
                }
            }

            // diff 생성
            String newContent = replaceAll
                    ? content.replace(oldString, newString)
                    : content.replaceFirst(java.util.regex.Pattern.quote(oldString), java.util.regex.Matcher.quoteReplacement(newString));

            boolean didChange = !content.equals(newContent);
            if (!didChange) {
                return ToolResult.ok(Map.of("didChange", false), "No changes")
                        .withDuration(System.currentTimeMillis() - start);
            }

            // Unified diff 생성
            String diff = generateUnifiedDiff(filePath, content, newContent);

            // Patch 저장
            String patchId = "patch-" + UUID.randomUUID().toString().substring(0, 8);
            patchStore.put(patchId, new PatchData(
                    ctx.sessionId(), filePath, resolved.toString(), content, newContent, diff));

            Map<String, Object> output = Map.of(
                    "file_path", filePath,
                    "diff", diff,
                    "didChange", true,
                    "patchId", patchId
            );

            return new ToolResult(true, output,
                    String.format("Diff generated: %s (patchId=%s)", filePath, patchId),
                    List.of(new ToolArtifact("diff", filePath, diff)),
                    List.of(),
                    Map.of("file_path", filePath, "patchId", patchId),
                    null, System.currentTimeMillis() - start);

        } catch (IOException e) {
            return ToolResult.error("File read failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    /** PatchApplyTool에서 사용 */
    public PatchData getPatch(String patchId) {
        return patchStore.get(patchId);
    }

    public void removePatch(String patchId) {
        patchStore.remove(patchId);
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    private String generateUnifiedDiff(String filePath, String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(filePath).append("\n");
        diff.append("+++ b/").append(filePath).append("\n");

        // 간단한 라인별 diff (변경된 부분만)
        int maxLines = Math.max(oldLines.length, newLines.length);
        int diffStart = -1;
        for (int i = 0; i < maxLines; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : "";
            String newLine = i < newLines.length ? newLines[i] : "";
            if (!oldLine.equals(newLine)) {
                if (diffStart < 0) diffStart = i;
                diff.append("-").append(oldLine).append("\n");
                diff.append("+").append(newLine).append("\n");
            }
        }

        return diff.toString();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public record PatchData(
            String sessionId,
            String filePath,
            String absolutePath,
            String oldContent,
            String newContent,
            String diff
    ) {}
}
