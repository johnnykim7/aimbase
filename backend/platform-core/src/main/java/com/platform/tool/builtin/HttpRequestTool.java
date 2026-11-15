package com.platform.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.PermissionLevel;
import com.platform.tool.RetryPolicy;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolResult;
import com.platform.tool.ToolScope;
import com.platform.tool.ValidationResult;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CR-054: 범용 HTTP 요청 도구.
 *
 * connections.type="HTTP" 레코드의 baseUrl/인증 메타를 사용해 임의 REST API를 호출한다.
 * 4xx/5xx는 예외가 아닌 정상 status 반환 — 워크플로우 CONDITION 분기가 동작하도록.
 *
 * 후속 CR-055에서 FlowGuard Connection seed + L2 자동 등록 워크플로우에 소비된다.
 */
@Component
public class HttpRequestTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestTool.class);

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> MASKED_HEADERS = Set.of("authorization", "x-api-key", "cookie", "proxy-authorization");
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_TIMEOUT_MS = 120_000;
    private static final int AUDIT_BODY_SUMMARY_LIMIT = 1024;

    private ConnectionRepository connectionRepository;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;

    protected HttpRequestTool() {
        this.connectionRepository = null;
        this.objectMapper = null;
        this.httpClient = null;
    }

    /** CR-164: 생성자가 여럿이면 Spring 은 표시가 없을 때 기본 생성자를 골라 연결 조회가 비어 버린다. */
    @Autowired
    public HttpRequestTool(ConnectionRepository connectionRepository, ObjectMapper objectMapper) {
        this(connectionRepository, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    HttpRequestTool(ConnectionRepository connectionRepository, ObjectMapper objectMapper, HttpClient httpClient) {
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "http_request",
                "Invoke an arbitrary REST endpoint using a preconfigured Connection (type=HTTP). " +
                        "Supports GET/POST/PUT/PATCH/DELETE with JSON body, query/headers/timeout. " +
                        "4xx/5xx responses are returned as-is (not thrown) so callers can branch on status.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "connection_id", Map.of("type", "string",
                                        "description", "connections.id referencing a type=HTTP connection"),
                                "method", Map.of("type", "string",
                                        "enum", List.of("GET", "POST", "PUT", "PATCH", "DELETE"),
                                        "description", "HTTP method"),
                                "path", Map.of("type", "string",
                                        "description", "Path appended to Connection baseUrl (omit query string)"),
                                "query", Map.of("type", "object",
                                        "description", "Query parameters (key-value map, values are stringified)"),
                                "headers", Map.of("type", "object",
                                        "description", "Extra headers (auth header is auto-injected from Connection)"),
                                // CR-068: Object.class.getSimpleName() == "Object" (대문자) — JSON Schema draft 2020-12 위반.
                                // 정확한 표현은 type 배열 ["object","array","string"] 또는 단순 "object". 가장 가까운 의미는 multi-type.
                                "body", Map.of("type", List.of("object", "array", "string"),
                                        "description", "Request body: object/array is JSON-serialized, string is sent as-is"),
                                "timeout_ms", Map.of("type", "integer",
                                        "description", "Request timeout in ms (default 30000, max 120000)")
                        ),
                        "required", List.of("connection_id", "method", "path")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "http_request", "1.0", ToolScope.BUILTIN,
                PermissionLevel.RESTRICTED_WRITE,
                true, true, false, true,
                RetryPolicy.NONE,
                List.of("http", "rest", "network"),
                List.of("read", "write", "network")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        Object connectionId = input.get("connection_id");
        if (!(connectionId instanceof String s) || s.isBlank()) {
            return ValidationResult.fail("connection_id is required.");
        }
        Object method = input.get("method");
        if (!(method instanceof String m) || !ALLOWED_METHODS.contains(m.toUpperCase(Locale.ROOT))) {
            return ValidationResult.fail("method must be one of " + ALLOWED_METHODS);
        }
        Object path = input.get("path");
        if (!(path instanceof String p) || p.isBlank()) {
            return ValidationResult.fail("path is required.");
        }
        Object timeout = input.get("timeout_ms");
        if (timeout instanceof Number n) {
            int t = n.intValue();
            if (t <= 0 || t > MAX_TIMEOUT_MS) {
                return ValidationResult.fail("timeout_ms must be in (0, " + MAX_TIMEOUT_MS + "]");
            }
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String connectionId = (String) input.get("connection_id");
        String method = ((String) input.get("method")).toUpperCase(Locale.ROOT);
        String path = (String) input.get("path");

        ConnectionEntity connection = connectionRepository.findById(connectionId).orElse(null);
        if (connection == null) {
            return ToolResult.error("Connection not found: " + connectionId)
                    .withDuration(System.currentTimeMillis() - start);
        }
        if (!"HTTP".equalsIgnoreCase(connection.getType())) {
            return ToolResult.error("Connection type must be HTTP but was: " + connection.getType())
                    .withDuration(System.currentTimeMillis() - start);
        }

        Map<String, Object> config = Optional.ofNullable(connection.getConfig()).orElse(Map.of());
        Object baseUrlRaw = config.get("baseUrl");
        if (!(baseUrlRaw instanceof String baseUrl) || baseUrl.isBlank()) {
            return ToolResult.error("Connection " + connectionId + " has no baseUrl.")
                    .withDuration(System.currentTimeMillis() - start);
        }

        Map<String, String> query = new LinkedHashMap<>(asStringMap(input.get("query")));
        String authQueryName = apiKeyName(config, "query");
        putAuthQuery(query, config, authQueryName);
        // CR-197: 실제 호출 주소에는 키가 실리므로 기록과 오류 문구에는 키를 가린 주소만 쓴다.
        String displayUri = buildUrl(baseUrl, path, maskQuery(query, authQueryName));

        URI uri;
        try {
            uri = URI.create(buildUrl(baseUrl, path, query));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid URL: Malformed URL: " + displayUri)
                    .withDuration(System.currentTimeMillis() - start);
        }

        int timeoutMs = resolveTimeout(input, config);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs));

        Map<String, String> requestHeaders = new LinkedHashMap<>();
        Map<String, Object> userHeaders = asStringKeyMap(input.get("headers"));
        userHeaders.forEach((k, v) -> {
            if (k != null && v != null) {
                String hv = v.toString();
                builder.header(k, hv);
                requestHeaders.put(k, hv);
            }
        });

        injectAuth(builder, displayUri, config, requestHeaders);

        String bodyString = null;
        Object body = input.get("body");
        if (body != null && !"GET".equals(method) && !"DELETE".equals(method)) {
            bodyString = serializeBody(body);
            if (bodyString == null) {
                return ToolResult.error("Failed to serialize body as JSON.")
                        .withDuration(System.currentTimeMillis() - start);
            }
            if (!requestHeaders.containsKey("Content-Type") && !requestHeaders.containsKey("content-type")) {
                builder.header("Content-Type", "application/json");
                requestHeaders.put("Content-Type", "application/json");
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            return httpFailure("timeout", e.getMessage(), displayUri, method, System.currentTimeMillis() - start);
        } catch (java.io.IOException e) {
            return httpFailure("io_error", e.getMessage(), displayUri, method, System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return httpFailure("interrupted", e.getMessage(), displayUri, method, System.currentTimeMillis() - start);
        }

        int status = response.statusCode();
        // CR-197: 받는 쪽이 요청 주소를 응답에 되돌려 주면(법제처 상세 링크의 OC=<키>) 키가 단계 결과와
        // 실행 조회 API 로 바깥에 나간다. 연결 인증값과 같은 글자는 응답 머리값, 본문, 감사 요약에서 가린다.
        List<String> secrets = authSecrets(config);
        String responseBody = redact(response.body(), secrets);
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        flattenHeaders(response.headers().map()).forEach((k, v) -> responseHeaders.put(k, redact(v, secrets)));
        String contentType = responseHeaders.getOrDefault("content-type", responseHeaders.getOrDefault("Content-Type", ""));
        Object bodyObject;
        boolean bodyRaw;

        if (contentType.toLowerCase(Locale.ROOT).contains("application/json")
                || contentType.toLowerCase(Locale.ROOT).contains("+json")) {
            try {
                // JSON 은 글자 이스케이프(\/ 등) 때문에 원문에서 바꾸면 놓칠 수 있어 읽은 뒤 값마다 가린다.
                JsonNode node = objectMapper.readTree(response.body());
                bodyObject = redactTree(objectMapper.convertValue(node, Object.class), secrets);
                bodyRaw = false;
                if (!secrets.isEmpty()) {
                    responseBody = objectMapper.writeValueAsString(bodyObject);
                }
            } catch (Exception parseError) {
                bodyObject = responseBody;
                bodyRaw = true;
            }
        } else {
            bodyObject = responseBody;
            bodyRaw = true;
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);
        output.put("headers", responseHeaders);
        output.put("body", bodyObject);
        output.put("bodyRaw", bodyRaw);
        output.put("duration_ms", System.currentTimeMillis() - start);
        output.put("error", null);

        boolean success = status >= 200 && status < 400;
        String summary = String.format("%s %s -> %d (%dms)",
                method, truncate(displayUri, 160), status, System.currentTimeMillis() - start);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("method", method);
        auditPayload.put("uri", displayUri);
        auditPayload.put("status", status);
        auditPayload.put("requestHeaders", maskHeaders(requestHeaders, apiKeyName(config, "header")));
        auditPayload.put("requestBody", bodyString == null ? null : truncate(bodyString, AUDIT_BODY_SUMMARY_LIMIT));
        auditPayload.put("responseSummary", truncate(responseBody, AUDIT_BODY_SUMMARY_LIMIT));

        return new ToolResult(success, output, summary, List.of(), List.of(),
                auditPayload, null, System.currentTimeMillis() - start);
    }

    private String buildUrl(String baseUrl, String path, Map<String, String> query) {
        String trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        StringBuilder sb = new StringBuilder(trimmedBase).append(normalizedPath);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getKey() == null) continue;
                if (!first) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
                sb.append('=');
                sb.append(e.getValue() == null ? "" : URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        return sb.toString();
    }

    /**
     * CR-197: 받는 쪽이 이름의 대소문자를 가리지 않을 수 있어, 입력 query 의 같은 이름은
     * 대소문자와 상관없이 지운다. 워크플로우가 연결의 인증키를 덮어쓰지 못하게 하기 위해서다.
     */
    @SuppressWarnings("unchecked")
    private void putAuthQuery(Map<String, String> query, Map<String, Object> config, String name) {
        if (name == null) {
            return;
        }
        String value = resolveAuthValue((Map<String, Object>) config.get("auth"));
        if (value == null || value.isBlank()) {
            log.debug("Auth value missing for API_KEY in=query (value_env unset?)");
            return;
        }
        query.keySet().removeIf(k -> k.equalsIgnoreCase(name));
        query.put(name, value);
    }

    private Map<String, String> maskQuery(Map<String, String> query, String secretName) {
        if (secretName == null) {
            return query;
        }
        Map<String, String> masked = new LinkedHashMap<>();
        query.forEach((k, v) -> masked.put(k, k.equalsIgnoreCase(secretName) ? "***" : v));
        return masked;
    }

    private int resolveTimeout(Map<String, Object> input, Map<String, Object> config) {
        Object fromInput = input.get("timeout_ms");
        if (fromInput instanceof Number n) {
            return Math.min(Math.max(n.intValue(), 1), MAX_TIMEOUT_MS);
        }
        Object fromConfig = config.get("readTimeoutMs");
        if (fromConfig instanceof Number n) {
            return Math.min(Math.max(n.intValue(), 1), MAX_TIMEOUT_MS);
        }
        return DEFAULT_TIMEOUT_MS;
    }

    @SuppressWarnings("unchecked")
    private void injectAuth(HttpRequest.Builder builder, String displayUri, Map<String, Object> config,
                             Map<String, String> requestHeaders) {
        Object authRaw = config.get("auth");
        if (!(authRaw instanceof Map<?, ?> authMap)) {
            return;
        }
        Map<String, Object> auth = (Map<String, Object>) authMap;
        String authType = Optional.ofNullable(auth.get("type")).map(Object::toString).orElse("NONE").toUpperCase(Locale.ROOT);

        String value = resolveAuthValue(auth);
        if (value == null || value.isBlank()) {
            if (!"NONE".equals(authType)) {
                log.debug("Auth value missing for type={} (value_env unset?)", authType);
            }
            return;
        }

        switch (authType) {
            case "API_KEY" -> {
                String in = Optional.ofNullable(auth.get("in")).map(Object::toString).orElse("header").toLowerCase(Locale.ROOT);
                String name = Optional.ofNullable(auth.get("name")).map(Object::toString).orElse("X-Api-Key");
                // CR-197: in=query 는 주소를 만들 때 이미 실었다(putAuthQuery).
                if (!"query".equals(in)) {
                    builder.header(name, value);
                    requestHeaders.put(name, value);
                }
            }
            case "BEARER" -> {
                builder.header("Authorization", "Bearer " + value);
                requestHeaders.put("Authorization", "Bearer " + value);
            }
            case "BASIC" -> {
                String encoded = java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + encoded);
                requestHeaders.put("Authorization", "Basic " + encoded);
            }
            case "NONE" -> { /* no-op */ }
            default -> log.warn("Unknown auth type: {} on URI {}", authType, displayUri);
        }
    }

    /**
     * CR-197: 응답에서 찾을 연결 인증값. 주소에 실린 키는 URL 인코딩된 꼴로, BASIC 은 Base64 꼴로
     * 되돌아올 수 있어 그 꼴도 넣는다. 긴 것부터 바꿔야 겹친 꼴이 반쯤 남지 않는다.
     */
    @SuppressWarnings("unchecked")
    private List<String> authSecrets(Map<String, Object> config) {
        if (!(config.get("auth") instanceof Map<?, ?> authMap)) {
            return List.of();
        }
        Map<String, Object> auth = (Map<String, Object>) authMap;
        String authType = Optional.ofNullable(auth.get("type")).map(Object::toString).orElse("NONE").toUpperCase(Locale.ROOT);
        if (!Set.of("API_KEY", "BEARER", "BASIC").contains(authType)) {
            return List.of();
        }
        String value = resolveAuthValue(auth);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> forms = new java.util.LinkedHashSet<>();
        forms.add(value);
        forms.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        if ("BASIC".equals(authType)) {
            forms.add(java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        return forms.stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private String redact(String text, List<String> secrets) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : secrets) {
            out = out.replace(secret, "***");
        }
        return out;
    }

    private Object redactTree(Object node, List<String> secrets) {
        if (secrets.isEmpty()) {
            return node;
        }
        if (node instanceof String s) {
            return redact(s, secrets);
        }
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k instanceof String ks ? redact(ks, secrets) : k, redactTree(v, secrets)));
            return out;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(v -> redactTree(v, secrets)).collect(java.util.stream.Collectors.toList());
        }
        return node;
    }

    private String resolveAuthValue(Map<String, Object> auth) {
        Object envName = auth.get("value_env");
        if (envName instanceof String envKey && !envKey.isBlank()) {
            String fromEnv = System.getenv(envKey);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
        }
        Object plain = auth.get("value");
        if (plain instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private String serializeBody(Object body) {
        if (body instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("Body serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> flattenHeaders(Map<String, List<String>> headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (v == null || v.isEmpty()) return;
            flat.put(k.toLowerCase(Locale.ROOT), v.size() == 1 ? v.get(0) : String.join(",", v));
        });
        return flat;
    }

    /**
     * CR-164 BIZ-132, CR-197: 연결 auth 가 API_KEY 면 그 이름도 비밀이다(머리값이든 주소 값이든).
     * 값이 비어 싣지 않았더라도 입력 headers, query 로 같은 이름을 실을 수 있으니 이름만으로 정한다.
     */
    @SuppressWarnings("unchecked")
    private String apiKeyName(Map<String, Object> config, String wantedIn) {
        if (!(config.get("auth") instanceof Map<?, ?> authMap)) {
            return null;
        }
        Map<String, Object> auth = (Map<String, Object>) authMap;
        String authType = Optional.ofNullable(auth.get("type")).map(Object::toString).orElse("NONE").toUpperCase(Locale.ROOT);
        String in = Optional.ofNullable(auth.get("in")).map(Object::toString).orElse("header").toLowerCase(Locale.ROOT);
        if (!"API_KEY".equals(authType) || !wantedIn.equals(in)) {
            return null;
        }
        return Optional.ofNullable(auth.get("name")).map(Object::toString).orElse("X-Api-Key");
    }

    private Map<String, String> maskHeaders(Map<String, String> headers, String authHeaderName) {
        String secretName = authHeaderName == null ? null : authHeaderName.toLowerCase(Locale.ROOT);
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (k == null) return;
            String lower = k.toLowerCase(Locale.ROOT);
            if (MASKED_HEADERS.contains(lower) || lower.equals(secretName)) {
                masked.put(k, "***");
            } else {
                masked.put(k, v);
            }
        });
        return masked;
    }

    private Map<String, String> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null) out.put(k.toString(), v == null ? "" : v.toString());
        });
        return out;
    }

    private Map<String, Object> asStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null) out.put(k.toString(), v);
        });
        return out;
    }

    private ToolResult httpFailure(String kind, String message, String displayUri, String method, long duration) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", null);
        output.put("headers", Map.of());
        output.put("body", null);
        output.put("bodyRaw", false);
        output.put("duration_ms", duration);
        output.put("error", Map.of("kind", kind, "message", message == null ? "" : message));
        String summary = String.format("%s %s -> %s (%s)", method, truncate(displayUri, 160), kind, message);
        return new ToolResult(false, output, summary, List.of(), List.of(),
                Map.of("method", method, "uri", displayUri, "errorKind", kind),
                null, duration);
    }

    private String truncate(String s, int limit) {
        if (s == null) return "";
        return s.length() <= limit ? s : s.substring(0, limit) + "...";
    }
}
