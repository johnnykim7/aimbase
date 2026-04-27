package com.platform.tool.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-044 PRD-280: 세션별 Aimbase MCP 설정 파일 동적 생성기.
 *
 * Claude CLI에 주입할 --mcp-config 파일을 /tmp 에 생성한다.
 * 설정은 aimbase-tool-sdk-mcp(aimbase-agent.jar)를 stdio 프로세스로 기동하는 내용이다.
 *
 * stdio 방식을 쓰는 이유:
 * - SSE는 네트워크 타이밍 이슈로 -p 모드에서 50% 연결 실패 (FlowGuard 동일 사례 확인)
 * - stdio는 Claude CLI가 자식 프로세스를 직접 관리 → 타이밍 이슈 없음
 *
 * jar 경로 결정 순서 (CR-071: ClaudeCodeToolConfig 의존 제거):
 * 1. {@link #configure(String)} 로 주입된 경로 (호출처: Phase 4 ClaudeCliAdapter)
 * 2. 환경변수 AIMBASE_MCP_JAR
 * 3. /opt/aimbase/aimbase-agent.jar (기본값)
 */
public class AimbaseMcpConfigGenerator {

    private static final Logger log = LoggerFactory.getLogger(AimbaseMcpConfigGenerator.class);

    private static final String DEFAULT_JAR_PATH = "/opt/aimbase/aimbase-agent.jar";
    private static final String ENV_JAR_PATH = "AIMBASE_MCP_JAR";

    /** 세션 ID → 생성된 임시 파일 경로 캐시 (세션 재사용 시 중복 생성 방지) */
    private static final ConcurrentHashMap<String, String> sessionConfigCache = new ConcurrentHashMap<>();

    /** 설정값에서 주입받은 jar 경로 (null이면 환경변수/기본값 사용) */
    private static volatile String configuredJarPath = null;

    static {
        // JVM 종료 시 생성된 임시 파일 정리
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (String path : sessionConfigCache.values()) {
                try {
                    Files.deleteIfExists(Path.of(path));
                } catch (IOException ignored) {
                }
            }
            log.debug("AimbaseMcpConfigGenerator: 세션 MCP 설정 파일 {} 개 정리 완료",
                    sessionConfigCache.size());
        }, "aimbase-mcp-config-cleanup"));
    }

    private AimbaseMcpConfigGenerator() {}

    /**
     * jar 경로를 주입받아 초기화. CR-071 Phase 4 ClaudeCliAdapter 등 호출처가 application 설정 값을 전달.
     */
    public static void configure(String mcpServerJar) {
        if (mcpServerJar != null && !mcpServerJar.isBlank()) {
            configuredJarPath = mcpServerJar;
            log.info("AimbaseMcpConfigGenerator jar 경로 설정: {}", mcpServerJar);
        }
    }

    /**
     * 세션별 MCP 설정 파일을 생성하고 파일 경로를 반환한다.
     * 동일 sessionId로 이미 생성된 파일이 있으면 재사용한다.
     *
     * @param sessionId 세션 식별자 (파일명 구분용)
     * @return 생성된 JSON 파일의 절대 경로, 또는 jar가 없어 생성 불가 시 null
     */
    public static String generateSessionConfig(String sessionId) {
        String safeSessionId = (sessionId != null && !sessionId.isBlank()) ? sessionId : "default";

        return sessionConfigCache.computeIfAbsent(safeSessionId, sid -> {
            String jarPath = resolveJarPath();
            if (jarPath == null) {
                log.warn("Aimbase MCP jar를 찾을 수 없어 tool_bridge 설정 파일 생성 건너뜀. "
                        + "claude-code.mcp-server-jar 또는 AIMBASE_MCP_JAR 환경변수를 설정하세요.");
                return null;
            }

            String json = buildMcpConfigJson(jarPath);
            try {
                Path configFile = Files.createTempFile("aimbase-mcp-" + sid + "-", ".json");
                Files.writeString(configFile, json);
                log.info("Aimbase MCP 설정 파일 생성: {} (jar={})", configFile, jarPath);
                return configFile.toAbsolutePath().toString();
            } catch (IOException e) {
                log.error("Aimbase MCP 설정 파일 생성 실패: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * 세션 종료 시 수동으로 설정 파일을 삭제한다. (선택적 호출).
     */
    public static void cleanupSession(String sessionId) {
        String path = sessionConfigCache.remove(sessionId);
        if (path != null) {
            try {
                Files.deleteIfExists(Path.of(path));
                log.debug("세션 MCP 설정 파일 삭제: {}", path);
            } catch (IOException e) {
                log.warn("세션 MCP 설정 파일 삭제 실패: {} ({})", path, e.getMessage());
            }
        }
    }

    // ── 내부 메서드 ──

    private static String resolveJarPath() {
        // 1. application.yml에서 주입된 경로
        if (configuredJarPath != null) {
            return Files.exists(Path.of(configuredJarPath)) ? configuredJarPath : null;
        }
        // 2. 환경변수
        String envPath = System.getenv(ENV_JAR_PATH);
        if (envPath != null && !envPath.isBlank() && Files.exists(Path.of(envPath))) {
            return envPath;
        }
        // 3. 기본 경로
        if (Files.exists(Path.of(DEFAULT_JAR_PATH))) {
            return DEFAULT_JAR_PATH;
        }
        return null;
    }

    private static String buildMcpConfigJson(String jarPath) {
        // Claude CLI --mcp-config 포맷 (stdio 방식)
        // aimbase-agent.jar를 --mcp-stdio 플래그로 기동하면 stdin/stdout으로 MCP 통신
        return """
                {
                  "mcpServers": {
                    "aimbase": {
                      "command": "java",
                      "args": ["-jar", "%s", "--mcp-stdio"]
                    }
                  }
                }
                """.formatted(jarPath);
    }
}
