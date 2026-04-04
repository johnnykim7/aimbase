package com.platform.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 임시 디렉토리를 안전하게 삭제하는 내장 도구.
 *
 * ZipExtractTool이 생성한 임시 경로를 정리하는 용도.
 * 보안: aimbase-zip- 접두사 임시 디렉토리만 삭제 허용.
 */
@Component
public class TempCleanupTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TempCleanupTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 삭제 허용 디렉토리 접두사 — ZipExtractTool이 생성한 것만 허용 */
    private static final String ALLOWED_PREFIX = "aimbase-zip-";

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "temp_cleanup",
            "ZipExtractTool이 생성한 임시 디렉토리를 안전하게 삭제합니다. "
                    + "aimbase-zip- 접두사 경로만 삭제를 허용합니다.",
            Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<>(Map.of(
                            "temp_path", Map.of(
                                    "type", "string",
                                    "description", "삭제할 임시 디렉토리 절대 경로 (zip_extract의 출력값)"
                            )
                    )),
                    "required", List.of("temp_path")
            )
    );

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String tempPath = (String) input.get("temp_path");
        if (tempPath == null || tempPath.isBlank()) {
            return toError("INVALID_INPUT", "temp_path는 필수 파라미터입니다.");
        }

        Path dir = Path.of(tempPath).normalize();

        // 보안: aimbase-zip- 접두사 디렉토리만 삭제 허용
        String dirName = dir.getFileName().toString();
        if (!dirName.startsWith(ALLOWED_PREFIX)) {
            log.warn("삭제 거부 — 허용되지 않은 경로: {}", dir);
            return toError("NOT_ALLOWED",
                    "aimbase-zip- 접두사가 아닌 경로는 삭제할 수 없습니다: " + dirName);
        }

        if (!Files.exists(dir)) {
            log.info("이미 삭제된 경로: {}", dir);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "already_deleted");
            result.put("temp_path", tempPath);
            return toJson(result);
        }

        if (!Files.isDirectory(dir)) {
            return toError("NOT_DIRECTORY", "디렉토리가 아닙니다: " + tempPath);
        }

        try (var walker = Files.walk(dir)) {
            long deletedCount = walker
                    .sorted(Comparator.reverseOrder())
                    .peek(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("파일 삭제 실패: {}", path, e);
                        }
                    })
                    .count();

            log.info("임시 디렉토리 삭제 완료: {} ({} 항목)", dir, deletedCount);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "deleted");
            result.put("temp_path", tempPath);
            result.put("deleted_count", deletedCount);
            return toJson(result);

        } catch (IOException e) {
            log.error("임시 디렉토리 삭제 실패: {}", dir, e);
            return toError("CLEANUP_FAILED", "삭제 실패: " + e.getMessage());
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"unknown\"}";
        }
    }

    private String toError(String code, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("error", code);
        error.put("message", message);
        try {
            return objectMapper.writeValueAsString(error);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"" + code + "\",\"message\":\"직렬화 실패\"}";
        }
    }
}
