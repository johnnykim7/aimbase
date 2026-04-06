package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * CR-029 (PRD-178): SafeEditTool이 생성한 patch를 승인 후 적용.
 * confirm=false면 preview만 반환, confirm=true면 실제 파일 수정.
 */
@Component
public class PatchApplyTool implements EnhancedToolExecutor {

    private final SafeEditTool safeEditTool;

    public PatchApplyTool(SafeEditTool safeEditTool) {
        this.safeEditTool = safeEditTool;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_patch_apply",
                "SafeEdit으로 생성한 패치를 적용합니다. confirm=false면 미리보기, true면 실제 적용.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "patch_id", Map.of("type", "string", "description", "SafeEdit에서 반환된 patchId"),
                                "confirm", Map.of("type", "boolean", "default", false, "description", "true면 실제 적용")
                        ),
                        "required", List.of("patch_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "builtin_patch_apply", "1.0", ToolScope.NATIVE,
                PermissionLevel.FULL, true, false, false, false,
                RetryPolicy.NONE, List.of("filesystem", "edit", "apply"), List.of("write", "apply")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String patchId = (String) input.get("patch_id");
        if (patchId == null || patchId.isBlank()) {
            return ValidationResult.fail("patch_id는 필수입니다.");
        }
        SafeEditTool.PatchData patch = safeEditTool.getPatch(patchId);
        if (patch == null) {
            return ValidationResult.fail("패치를 찾을 수 없습니다: " + patchId);
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String patchId = (String) input.get("patch_id");
        boolean confirm = Boolean.TRUE.equals(input.get("confirm"));

        SafeEditTool.PatchData patch = safeEditTool.getPatch(patchId);
        if (patch == null) {
            return ToolResult.error("패치를 찾을 수 없습니다: " + patchId)
                    .withDuration(System.currentTimeMillis() - start);
        }

        // Preview 모드
        if (!confirm) {
            Map<String, Object> output = Map.of(
                    "patchId", patchId,
                    "file_path", patch.filePath(),
                    "diff", patch.diff(),
                    "applied", false,
                    "message", "confirm=true로 호출하면 실제 적용됩니다."
            );
            return new ToolResult(true, output,
                    String.format("패치 미리보기: %s (patchId=%s)", patch.filePath(), patchId),
                    List.of(), List.of(), Map.of(), null, System.currentTimeMillis() - start);
        }

        // 실제 적용
        try {
            Path target = Path.of(patch.absolutePath());

            // 원본 비교 (conflict 감지)
            String currentContent = Files.readString(target);
            if (!currentContent.equals(patch.oldContent())) {
                return ToolResult.error("파일이 패치 생성 이후 변경됨 (conflict). 새로운 SafeEdit을 실행하세요.")
                        .withDuration(System.currentTimeMillis() - start);
            }

            // 적용
            Files.writeString(target, patch.newContent());
            safeEditTool.removePatch(patchId);

            Map<String, Object> output = Map.of(
                    "patchId", patchId,
                    "file_path", patch.filePath(),
                    "applied", true,
                    "appliedDiff", patch.diff()
            );

            return new ToolResult(true, output,
                    String.format("패치 적용 완료: %s", patch.filePath()),
                    List.of(new ToolArtifact("file_modified", patch.filePath(), null)),
                    List.of("file_written:" + patch.filePath()),
                    Map.of("file_path", patch.filePath(), "patchId", patchId),
                    null, System.currentTimeMillis() - start);

        } catch (IOException e) {
            return ToolResult.error("패치 적용 실패: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }
}
