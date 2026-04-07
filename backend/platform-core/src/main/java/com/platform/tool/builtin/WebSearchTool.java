package com.platform.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.repository.ConnectionRepository;
import com.platform.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * CR-037 PRD-243: 에이전트 자율 웹 검색 도구.
 * Tavily API 우선 → DuckDuckGo HTML 폴백.
 * Connection 테이블에서 type=SEARCH, adapter=tavily 조회.
 */
@Component
public class WebSearchTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final int MAX_RESULTS = 10;
    private static final int DEFAULT_RESULTS = 5;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebSearchTool(ConnectionRepository connectionRepository, ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "web_search",
                "Search the web for information. Returns structured results with title, URL, and snippet. " +
                        "Uses Tavily API if configured, falls back to DuckDuckGo. " +
                        "Useful for finding current information, documentation, or answering factual questions.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "Search query string"),
                                "max_results", Map.of("type", "integer",
                                        "description", "Maximum number of results (default: 5, max: 10)"),
                                "search_depth", Map.of("type", "string",
                                        "enum", List.of("basic", "advanced"),
                                        "description", "Search depth: basic (fast) or advanced (thorough, Tavily only)")
                        ),
                        "required", List.of("query")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "web_search", "1.0", ToolScope.BUILTIN,
                PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE,
                List.of("web", "search", "information"),
                List.of("read", "search")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return ValidationResult.fail("query is required.");
        }
        if (query.length() > 1000) {
            return ValidationResult.fail("query too long (max 1000 chars).");
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String query = (String) input.get("query");
        int maxResults = getMaxResults(input);
        String searchDepth = (String) input.getOrDefault("search_depth", "basic");

        // Tavily API Key 조회 시도
        String tavilyApiKey = findApiKey("SEARCH", "tavily");
        if (tavilyApiKey == null) {
            tavilyApiKey = findApiKey("SEARCH", "search");
        }

        try {
            List<Map<String, String>> results;
            String provider;

            if (tavilyApiKey != null) {
                results = searchTavily(query, maxResults, searchDepth, tavilyApiKey);
                provider = "tavily";
            } else {
                results = searchDuckDuckGo(query, maxResults);
                provider = "duckduckgo";
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", query);
            output.put("provider", provider);
            output.put("results", results);
            output.put("num_results", results.size());

            String summary = results.size() + " results from " + provider + " for: " + truncate(query, 80);

            return new ToolResult(true, output, summary,
                    List.of(), List.of(),
                    Map.of("query", query, "provider", provider, "numResults", results.size()),
                    null, System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.error("WebSearchTool failed for query '{}': {}", query, e.getMessage());
            return ToolResult.error("Search failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private List<Map<String, String>> searchTavily(String query, int maxResults,
                                                    String searchDepth, String apiKey) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("max_results", maxResults);
        body.put("search_depth", searchDepth);
        body.put("include_answer", false);

        String json = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(HTTP_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Tavily API error: " + response.statusCode() + " " + truncate(response.body(), 200));
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode resultsNode = root.get("results");

        List<Map<String, String>> results = new ArrayList<>();
        if (resultsNode != null && resultsNode.isArray()) {
            for (JsonNode item : resultsNode) {
                Map<String, String> result = new LinkedHashMap<>();
                result.put("title", getTextOrEmpty(item, "title"));
                result.put("url", getTextOrEmpty(item, "url"));
                result.put("snippet", getTextOrEmpty(item, "content"));
                results.add(result);
            }
        }
        return results;
    }

    private List<Map<String, String>> searchDuckDuckGo(String query, int maxResults)
            throws IOException, InterruptedException {
        // DuckDuckGo Instant Answer JSON API (lite, no API key needed)
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1&skip_disambig=1");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Aimbase/1.0")
                .GET()
                .timeout(HTTP_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("DuckDuckGo API error: " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        List<Map<String, String>> results = new ArrayList<>();

        // Abstract (주요 결과)
        String abstractText = getTextOrEmpty(root, "Abstract");
        String abstractUrl = getTextOrEmpty(root, "AbstractURL");
        if (!abstractText.isEmpty()) {
            results.add(Map.of(
                    "title", getTextOrEmpty(root, "Heading"),
                    "url", abstractUrl,
                    "snippet", abstractText
            ));
        }

        // Related Topics
        JsonNode relatedTopics = root.get("RelatedTopics");
        if (relatedTopics != null && relatedTopics.isArray()) {
            for (JsonNode topic : relatedTopics) {
                if (results.size() >= maxResults) break;
                String text = getTextOrEmpty(topic, "Text");
                String firstUrl = getTextOrEmpty(topic, "FirstURL");
                if (!text.isEmpty() && !firstUrl.isEmpty()) {
                    results.add(Map.of(
                            "title", truncate(text, 100),
                            "url", firstUrl,
                            "snippet", text
                    ));
                }
            }
        }

        return results;
    }

    private String findApiKey(String type, String adapter) {
        try {
            var connections = connectionRepository.findByType(type);
            for (var conn : connections) {
                if (adapter.equalsIgnoreCase(conn.getAdapter()) && conn.getConfig() != null) {
                    Object key = conn.getConfig().get("api_key");
                    if (key instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find API key for type={}, adapter={}: {}", type, adapter, e.getMessage());
        }
        return null;
    }

    private int getMaxResults(Map<String, Object> input) {
        Object val = input.get("max_results");
        if (val instanceof Number n) {
            return Math.min(Math.max(n.intValue(), 1), MAX_RESULTS);
        }
        return DEFAULT_RESULTS;
    }

    private String getTextOrEmpty(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && child.isTextual() ? child.asText() : "";
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
