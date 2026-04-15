package com.platform.api;

import com.platform.config.WorkspaceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * CR-045: 워크스페이스 목록 API.
 * 화이트리스트 루트 하위 1단계 디렉토리를 나열한다. FE 채팅 UI의 새 대화 모달에서 사용.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "Workspaces", description = "작업 디렉토리 조회 API")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceProperties workspaceProperties;

    public WorkspaceController(WorkspaceProperties workspaceProperties) {
        this.workspaceProperties = workspaceProperties;
    }

    @GetMapping
    @Operation(summary = "워크스페이스 목록", description = "화이트리스트 루트 하위 1단계 디렉토리를 나열.")
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Path root : workspaceProperties.whitelistPaths()) {
            if (!Files.isDirectory(root)) {
                log.debug("화이트리스트 루트가 디렉토리 아님 (건너뜀): {}", root);
                continue;
            }
            try (var stream = Files.list(root)) {
                stream
                        .filter(Files::isDirectory)
                        .forEach(dir -> items.add(Map.of(
                                "name", dir.getFileName().toString(),
                                "path", dir.toAbsolutePath().normalize().toString(),
                                "modifiedAt", lastModified(dir)
                        )));
            } catch (IOException e) {
                log.warn("워크스페이스 루트 스캔 실패 root={} err={}", root, e.getMessage());
            }
        }
        items.sort(Comparator.comparing(m -> ((String) m.get("name"))));
        return ApiResponse.ok(items);
    }

    private static String lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toInstant().toString();
        } catch (IOException e) {
            return Instant.EPOCH.toString();
        }
    }
}
