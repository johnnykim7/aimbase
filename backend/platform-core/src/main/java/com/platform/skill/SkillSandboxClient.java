package com.platform.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-147: 실행 컨테이너 POST /run 호출(T3-17 3.7). 스크립트가 0 이 아닌 코드로 끝난 것은 실패가 아니라 결과다.
 */
@Component
public class SkillSandboxClient {

    public record RunRequest(String workdir, String script, List<String> args, int timeoutSeconds, int maxOutputBytes) {}

    public record RunResult(int exitCode, boolean timedOut, String stdout, String stderr,
                            boolean stdoutTruncated, boolean stderrTruncated, long durationMs,
                            List<String> changedFiles, int changedFilesTotal) {}

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final SkillSandboxProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public SkillSandboxClient(SkillSandboxProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                .build();
    }

    public RunResult run(RunRequest req) {
        String url = props.getUrl();
        String key = props.getApiKey();
        if (url == null || url.isBlank() || key == null || key.isBlank()) {
            throw new SkillSandboxException("BIZ-122: 실행 컨테이너 주소나 열쇠가 설정되지 않았습니다");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workdir", req.workdir());
        body.put("script", req.script());
        body.put("args", req.args());
        body.put("timeout_seconds", req.timeoutSeconds());
        body.put("max_output_bytes", req.maxOutputBytes());

        HttpResponse<String> resp;
        try {
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/run"))
                    .timeout(Duration.ofSeconds((long) req.timeoutSeconds() + props.getResponseGraceSeconds()))
                    .header("Content-Type", "application/json")
                    .header("X-Sandbox-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillSandboxException("실행 컨테이너 호출이 중단됐습니다", e);
        } catch (IOException e) {
            throw new SkillSandboxException("실행 컨테이너에 닿지 못했습니다: " + e, e);
        }

        Map<String, Object> json = parse(resp.body());
        if (resp.statusCode() != 200) {
            Object error = json.get("error");
            throw new SkillSandboxException("실행 컨테이너가 거부했습니다(" + resp.statusCode() + "): "
                    + (error != null ? error : resp.body()));
        }
        return new RunResult(
                ((Number) json.getOrDefault("exit_code", -1)).intValue(),
                Boolean.TRUE.equals(json.get("timed_out")),
                (String) json.getOrDefault("stdout", ""),
                (String) json.getOrDefault("stderr", ""),
                Boolean.TRUE.equals(json.get("stdout_truncated")),
                Boolean.TRUE.equals(json.get("stderr_truncated")),
                ((Number) json.getOrDefault("duration_ms", 0)).longValue(),
                stringList(json.get("changed_files")),
                ((Number) json.getOrDefault("changed_files_total", 0)).intValue());
    }

    private Map<String, Object> parse(String body) {
        try {
            return body == null || body.isBlank() ? Map.of() : mapper.readValue(body, MAP);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
