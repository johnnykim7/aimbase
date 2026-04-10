package com.platform.tool.lsp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CR-039 PRD-268: Language Server Protocol 클라이언트 매니저.
 *
 * 언어별 Language Server 프로세스를 관리한다.
 * - Lazy 초기화: 첫 요청 시 해당 언어 LS 기동 + initialize 핸드셰이크
 * - BIZ-076: 5분 미사용 시 자동 종료
 * - BIZ-077: 초기 지원 언어 3개 (java, typescript, python)
 * - JSON-RPC 2.0 통신 (stdin/stdout)
 */
@Component
public class LSPClientManager {

    private static final Logger log = LoggerFactory.getLogger(LSPClientManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5분
    private static final long REQUEST_TIMEOUT_MS = 30_000; // 30초

    /** 언어별 LS 명령어 매핑 */
    private static final Map<String, List<String>> LANGUAGE_SERVER_COMMANDS = Map.of(
            "java", List.of("jdtls"),
            "typescript", List.of("typescript-language-server", "--stdio"),
            "python", List.of("pylsp")
    );

    /** 활성 LS 클라이언트 (언어 → 클라이언트) */
    private final Map<String, LSPClient> activeClients = new ConcurrentHashMap<>();

    /**
     * 지원 언어 목록.
     */
    public Set<String> getSupportedLanguages() {
        return LANGUAGE_SERVER_COMMANDS.keySet();
    }

    /**
     * 활성 클라이언트 상태 목록.
     */
    public List<Map<String, Object>> getActiveStatus() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : activeClients.entrySet()) {
            LSPClient client = entry.getValue();
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("language", entry.getKey());
            status.put("alive", client.isAlive());
            status.put("idle_ms", System.currentTimeMillis() - client.lastUsedAt);
            result.add(status);
        }
        return result;
    }

    /**
     * textDocument/definition 요청.
     */
    public Map<String, Object> definition(String language, String filePath, int line, int character)
            throws IOException, InterruptedException {
        LSPClient client = getOrStartClient(language, filePath);
        return client.request("textDocument/definition", buildTextDocumentPositionParams(filePath, line, character));
    }

    /**
     * textDocument/references 요청.
     */
    public Map<String, Object> references(String language, String filePath, int line, int character)
            throws IOException, InterruptedException {
        LSPClient client = getOrStartClient(language, filePath);
        Map<String, Object> params = buildTextDocumentPositionParams(filePath, line, character);
        params.put("context", Map.of("includeDeclaration", true));
        return client.request("textDocument/references", params);
    }

    /**
     * textDocument/hover 요청.
     */
    public Map<String, Object> hover(String language, String filePath, int line, int character)
            throws IOException, InterruptedException {
        LSPClient client = getOrStartClient(language, filePath);
        return client.request("textDocument/hover", buildTextDocumentPositionParams(filePath, line, character));
    }

    /**
     * 특정 언어 LS 종료.
     */
    public void stopClient(String language) {
        LSPClient client = activeClients.remove(language);
        if (client != null) {
            client.shutdown();
            log.info("LSP client stopped: language={}", language);
        }
    }

    /**
     * 모든 LS 종료 (세션 종료 시).
     */
    public void stopAll() {
        for (String lang : new ArrayList<>(activeClients.keySet())) {
            stopClient(lang);
        }
    }

    /**
     * BIZ-076: 5분 미사용 자동 종료 스캔 (30초 간격).
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void scanIdleClients() {
        long now = System.currentTimeMillis();
        for (var entry : activeClients.entrySet()) {
            LSPClient client = entry.getValue();
            if (!client.isAlive() || (now - client.lastUsedAt) > IDLE_TIMEOUT_MS) {
                stopClient(entry.getKey());
                log.info("Idle LSP client removed: language={}", entry.getKey());
            }
        }
    }

    // ── 내부 로직 ──

    private LSPClient getOrStartClient(String language, String filePath) throws IOException, InterruptedException {
        String lang = language.toLowerCase();
        if (!LANGUAGE_SERVER_COMMANDS.containsKey(lang)) {
            throw new IllegalArgumentException("Unsupported language: " + lang
                    + ". Supported: " + LANGUAGE_SERVER_COMMANDS.keySet());
        }

        LSPClient client = activeClients.get(lang);
        if (client != null && client.isAlive()) {
            client.lastUsedAt = System.currentTimeMillis();
            return client;
        }

        // 새 LS 프로세스 기동
        List<String> command = LANGUAGE_SERVER_COMMANDS.get(lang);
        log.info("Starting LSP server: language={}, command={}", lang, command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        // 워크스페이스 루트를 파일 경로의 상위 디렉토리로 추정
        String workspaceRoot = inferWorkspaceRoot(filePath);
        if (workspaceRoot != null) {
            pb.directory(new File(workspaceRoot));
        }

        Process process = pb.start();
        client = new LSPClient(process, lang);
        client.initialize(workspaceRoot != null ? workspaceRoot : System.getProperty("user.dir"));
        activeClients.put(lang, client);
        log.info("LSP server started: language={}, pid={}", lang, process.pid());
        return client;
    }

    private String inferWorkspaceRoot(String filePath) {
        File file = new File(filePath);
        File dir = file.isDirectory() ? file : file.getParentFile();
        while (dir != null) {
            // git root, package.json, build.gradle 등으로 추정
            if (new File(dir, ".git").exists()
                    || new File(dir, "package.json").exists()
                    || new File(dir, "build.gradle").exists()
                    || new File(dir, "build.gradle.kts").exists()
                    || new File(dir, "pom.xml").exists()
                    || new File(dir, "setup.py").exists()
                    || new File(dir, "pyproject.toml").exists()) {
                return dir.getAbsolutePath();
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private Map<String, Object> buildTextDocumentPositionParams(String filePath, int line, int character) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", Map.of("uri", "file://" + filePath));
        params.put("position", Map.of("line", line, "character", character));
        return params;
    }

    // ── LSP 클라이언트 (JSON-RPC 2.0) ──

    static class LSPClient {
        private final Process process;
        private final OutputStream stdin;
        private final InputStream stdout;
        private final String language;
        private final AtomicInteger requestId = new AtomicInteger(1);
        volatile long lastUsedAt = System.currentTimeMillis();

        LSPClient(Process process, String language) {
            this.process = process;
            this.stdin = process.getOutputStream();
            this.stdout = process.getInputStream();
            this.language = language;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void initialize(String workspaceRoot) throws IOException, InterruptedException {
            Map<String, Object> initParams = new LinkedHashMap<>();
            initParams.put("processId", ProcessHandle.current().pid());
            initParams.put("rootUri", "file://" + workspaceRoot);
            initParams.put("capabilities", Map.of(
                    "textDocument", Map.of(
                            "definition", Map.of("dynamicRegistration", false),
                            "references", Map.of("dynamicRegistration", false),
                            "hover", Map.of("dynamicRegistration", false)
                    )
            ));

            Map<String, Object> result = request("initialize", initParams);
            // initialized 노티피케이션 전송
            sendNotification("initialized", Map.of());
            log.info("LSP initialized: language={}, capabilities={}", language,
                    result != null ? result.keySet() : "null");
        }

        Map<String, Object> request(String method, Map<String, Object> params)
                throws IOException, InterruptedException {
            int id = requestId.getAndIncrement();
            lastUsedAt = System.currentTimeMillis();

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);

            sendMessage(request);
            return readResponse(id);
        }

        void sendNotification(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            notification.put("params", params);
            sendMessage(notification);
        }

        private synchronized void sendMessage(Map<String, Object> message) throws IOException {
            String json = mapper.writeValueAsString(message);
            byte[] content = json.getBytes(StandardCharsets.UTF_8);
            String header = "Content-Length: " + content.length + "\r\n\r\n";
            stdin.write(header.getBytes(StandardCharsets.UTF_8));
            stdin.write(content);
            stdin.flush();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readResponse(int expectedId) throws IOException, InterruptedException {
            long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;

            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("LSP process terminated unexpectedly");
                }

                if (stdout.available() == 0) {
                    Thread.sleep(50);
                    continue;
                }

                // Content-Length 헤더 파싱
                String headerLine = readLine();
                if (headerLine == null || headerLine.isBlank()) continue;

                int contentLength = -1;
                if (headerLine.startsWith("Content-Length:")) {
                    contentLength = Integer.parseInt(headerLine.substring("Content-Length:".length()).trim());
                }

                // 빈 줄까지 헤더 소비
                while (true) {
                    String line = readLine();
                    if (line == null || line.isEmpty()) break;
                    if (line.startsWith("Content-Length:") && contentLength < 0) {
                        contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                    }
                }

                if (contentLength <= 0) continue;

                // JSON 본문 읽기
                byte[] body = stdout.readNBytes(contentLength);
                Map<String, Object> response = mapper.readValue(body, new TypeReference<>() {});

                // 노티피케이션(id 없음)은 스킵
                Object respId = response.get("id");
                if (respId == null) continue;

                int responseId = respId instanceof Number n ? n.intValue() : Integer.parseInt(respId.toString());
                if (responseId == expectedId) {
                    if (response.containsKey("error")) {
                        Map<String, Object> error = (Map<String, Object>) response.get("error");
                        throw new IOException("LSP error: " + error.getOrDefault("message", error));
                    }
                    Object result = response.get("result");
                    if (result instanceof Map<?,?> m) {
                        return (Map<String, Object>) m;
                    }
                    // result가 List나 null인 경우
                    Map<String, Object> wrapped = new LinkedHashMap<>();
                    wrapped.put("result", result);
                    return wrapped;
                }
            }

            throw new IOException("LSP request timed out after " + REQUEST_TIMEOUT_MS + "ms");
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = stdout.read()) != -1) {
                if (c == '\n') {
                    if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\r') {
                        sb.setLength(sb.length() - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) c);
            }
            return sb.isEmpty() ? null : sb.toString();
        }

        void shutdown() {
            try {
                if (process.isAlive()) {
                    sendNotification("shutdown", Map.of());
                    sendNotification("exit", Map.of());
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("LSP graceful shutdown failed: language={}", language, e);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}
