package com.platform.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * CR-184: OpenAI 그림 만들기(images/generations)와 고치기(images/edits) 호출. 한 번에 한 장, 크기는 공급처가 고른다.
 * 응답 대기는 부르는 쪽이 한다. 돌려준 future 를 취소하면 요청을 끊는다(응답 중지, 시간 상한).
 */
@Component
public class OpenAiImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);

    static final String SIZE = "auto";
    private static final Set<String> REJECT_CODES = Set.of("moderation_blocked", "content_policy_violation");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public OpenAiImageClient(ObjectMapper objectMapper,
                             @Value("${aimbase.image.openai-base-url}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 바탕 그림 한 장. 고치기 호출의 image[] 파일 하나가 된다. */
    public record SourceImage(byte[] bytes, String mediaType, String filename) {}

    /** 만든 그림과 공급처가 알린 토큰. */
    public record Result(byte[] bytes, int inputTokens, int outputTokens) {}

    public CompletableFuture<HttpResponse<byte[]>> request(ImageConnection conn, String prompt,
                                                           List<SourceImage> sources, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .timeout(timeout)
                .header("Authorization", "Bearer " + conn.apiKey());
        if (sources == null || sources.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", conn.model());
            body.put("prompt", prompt);
            body.put("size", SIZE);
            body.put("quality", conn.quality());
            body.put("n", 1);
            b.uri(URI.create(baseUrl + "/images/generations"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json(body)));
        } else {
            String boundary = "aimbase-" + UUID.randomUUID();
            b.uri(URI.create(baseUrl + "/images/edits"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, conn, prompt, sources)));
        }
        log.info("Image request: connection={} model={} quality={} sources={}",
                conn.connectionId(), conn.model(), conn.quality(), sources == null ? 0 : sources.size());
        return httpClient.sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 공급처 응답을 그림으로 바꾼다. 실패면 위젯이 문구로 바꿀 코드로 던진다. */
    public Result toResult(HttpResponse<byte[]> resp) {
        int status = resp.statusCode();
        JsonNode body = parse(resp.body());
        if (status / 100 != 2) {
            String code = body != null ? body.path("error").path("code").asText("") : "";
            String message = body != null ? body.path("error").path("message").asText("") : "";
            log.warn("Image provider error: status={} code={} message={}", status, code, abbreviate(message));
            if (status == 401 || status == 403) throw new ImageProviderException(ImageProviderException.AUTH, code);
            if (status == 429) throw new ImageProviderException(ImageProviderException.QUOTA, code);
            if (status == 400 && REJECT_CODES.contains(code)) {
                throw new ImageProviderException(ImageProviderException.REJECTED, code);
            }
            throw new ImageProviderException(ImageProviderException.FAILED, status + " " + code);
        }
        String b64 = body != null ? body.path("data").path(0).path("b64_json").asText("") : "";
        if (b64.isEmpty()) {
            throw new ImageProviderException(ImageProviderException.FAILED, "no b64_json");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new ImageProviderException(ImageProviderException.FAILED, "bad b64_json");
        }
        JsonNode usage = body.path("usage");
        return new Result(bytes, usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
    }

    private byte[] json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] multipart(String boundary, ImageConnection conn, String prompt, List<SourceImage> sources) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, boundary, "model", conn.model());
        field(out, boundary, "prompt", prompt);
        field(out, boundary, "size", SIZE);
        field(out, boundary, "quality", conn.quality());
        field(out, boundary, "n", "1");
        for (SourceImage s : sources) {
            write(out, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"image[]\"; filename=\"" + safeName(s.filename()) + "\"\r\n"
                    + "Content-Type: " + s.mediaType() + "\r\n\r\n");
            out.writeBytes(s.bytes());
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeName(String name) {
        return name == null ? "image" : name.replaceAll("[\"\\r\\n]", "_");
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
