package com.platform.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 파일을 임시 디렉토리에 압축 해제하는 내장 도구.
 */
public class ZipExtractTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** ZIP 내 개별 파일 최대 크기 (100MB) — Zip Bomb 방어 */
    private static final long MAX_ENTRY_SIZE = 100L * 1024 * 1024;
    /** ZIP 내 최대 파일 수 — Zip Bomb 방어 */
    private static final int MAX_ENTRIES = 10_000;
    /** 임시 디렉토리 접두사 */
    private static final String TEMP_PREFIX = "aimbase-zip-";

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "zip_extract",
            "ZIP 파일을 임시 디렉토리에 압축 해제하고 경로를 반환합니다. "
                    + "소스 코드 분석, DSL 생성 등 후속 도구(claude_code)의 입력으로 사용됩니다.",
            Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<>(Map.of(
                            "zip_path", Map.of(
                                    "type", "string",
                                    "description", "압축 해제할 ZIP 파일의 절대 경로"
                            ),
                            "sub_directory", Map.of(
                                    "type", "string",
                                    "description", "ZIP 내 특정 하위 디렉토리만 추출 (선택, 미지정 시 전체 추출)"
                            )
                    )),
                    "required", List.of("zip_path")
            )
    );

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String zipPath = (String) input.get("zip_path");
        if (zipPath == null || zipPath.isBlank()) {
            return toError("INVALID_INPUT", "zip_path는 필수 파라미터입니다.");
        }

        String subDirectory = (String) input.getOrDefault("sub_directory", null);

        Path zipFile = Path.of(zipPath);
        if (!Files.exists(zipFile)) {
            return toError("FILE_NOT_FOUND", "ZIP 파일을 찾을 수 없습니다: " + zipPath);
        }

        try {
            Path tempDir = Files.createTempDirectory(TEMP_PREFIX);
            log.info("ZIP 압축 해제 시작: {} → {}", zipPath, tempDir);

            int entryCount = 0;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (++entryCount > MAX_ENTRIES) {
                        cleanup(tempDir);
                        return toError("TOO_MANY_ENTRIES",
                                "ZIP 내 파일 수가 %d개를 초과합니다.".formatted(MAX_ENTRIES));
                    }

                    String entryName = entry.getName();

                    if (subDirectory != null && !subDirectory.isBlank()) {
                        if (!entryName.startsWith(subDirectory)) {
                            zis.closeEntry();
                            continue;
                        }
                    }

                    Path targetPath = tempDir.resolve(entryName).normalize();
                    if (!targetPath.startsWith(tempDir)) {
                        log.warn("Zip Slip 공격 감지, 엔트리 건너뜀: {}", entryName);
                        zis.closeEntry();
                        continue;
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        long size = extractWithSizeLimit(zis, targetPath);
                        if (size < 0) {
                            cleanup(tempDir);
                            return toError("ENTRY_TOO_LARGE",
                                    "ZIP 내 파일이 %dMB를 초과합니다: %s"
                                            .formatted(MAX_ENTRY_SIZE / (1024 * 1024), entryName));
                        }
                    }
                    zis.closeEntry();
                }
            }

            log.info("ZIP 압축 해제 완료: {} 파일, 경로={}", entryCount, tempDir);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("temp_path", tempDir.toAbsolutePath().toString());
            result.put("entry_count", entryCount);
            result.put("status", "extracted");
            return objectMapper.writeValueAsString(result);

        } catch (IOException e) {
            log.error("ZIP 압축 해제 실패: {}", e.getMessage(), e);
            return toError("EXTRACT_FAILED", "압축 해제 실패: " + e.getMessage());
        }
    }

    private long extractWithSizeLimit(InputStream zis, Path target) throws IOException {
        long totalRead = 0;
        byte[] buffer = new byte[8192];
        try (var out = Files.newOutputStream(target)) {
            int len;
            while ((len = zis.read(buffer)) > 0) {
                totalRead += len;
                if (totalRead > MAX_ENTRY_SIZE) {
                    return -1;
                }
                out.write(buffer, 0, len);
            }
        }
        return totalRead;
    }

    private void cleanup(Path dir) {
        try (var walker = Files.walk(dir)) {
            walker.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("임시 디렉토리 정리 실패: {}", dir, e);
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
