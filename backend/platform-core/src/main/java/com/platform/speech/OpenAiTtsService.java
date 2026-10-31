package com.platform.speech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionApiKeys;
import com.platform.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PRD-127, CR-165: OpenAI 음성 합성 호출.
 *
 * <p>테넌트의 OpenAI 연결에서 키를 찾아 요청을 중계한다. CR-165 에서 컨트롤러에 있던 것을 옮겼다.
 * OpenAI 가 돌려준 오류 상태와 본문은 바꾸지 않고 호출자에게 돌려준다.
 */
@Service
public class OpenAiTtsService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTtsService.class);

    static final String DEFAULT_MODEL = "tts-1";
    static final String DEFAULT_VOICE = "alloy";

    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;
    private final URI openaiTtsUri;
    private final HttpClient httpClient;

    public OpenAiTtsService(ConnectionRepository connectionRepository,
                            ObjectMapper objectMapper,
                            @Value("${aimbase.speech.openai-tts-url}") String openaiTtsUrl) {
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
        this.openaiTtsUri = URI.create(openaiTtsUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** OpenAI 의 응답 상태와 본문. 200 이면 본문이 MP3 다. */
    public record Result(int status, byte[] body) {}

    /**
     * @throws SpeechException 쓸 수 있는 OpenAI 연결이 없을 때 {@code PROVIDER_UNAVAILABLE},
     *                         요청을 보내지 못했을 때 {@code UPSTREAM_ERROR}
     */
    public Result synthesize(String text, String model, String voice, Double speed) {
        String apiKey = resolveOpenAIKey();
        if (apiKey == null) {
            throw SpeechException.providerUnavailable();
        }
        try {
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(openaiTtsUri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildTtsBody(text, model, voice, speed)))
                    .build();

            HttpResponse<byte[]> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) {
                log.warn("TTS API error: HTTP {}", resp.statusCode());
            }
            return new Result(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechException(SpeechException.ErrorCode.UPSTREAM_ERROR, "TTS proxy interrupted", e);
        } catch (Exception e) {
            log.error("TTS proxy failed: {}", e.getMessage());
            throw new SpeechException(SpeechException.ErrorCode.UPSTREAM_ERROR, "TTS proxy failed: " + e.getMessage(), e);
        }
    }

    /**
     * CR-165: 글에 역슬래시, 탭, 캐리지 리턴 같은 제어 문자가 들어도 JSON 이 깨지지 않게 직렬화기로 만든다.
     */
    String buildTtsBody(String text, String model, String voice, Double speed) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model != null ? model : DEFAULT_MODEL);
        body.put("input", text);
        body.put("voice", voice != null ? voice : DEFAULT_VOICE);
        body.put("speed", speed != null ? speed : TtsVoiceCatalog.NORMAL_SPEED);
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 테넌트의 OpenAI 연결에서 API 키 조회 (TTS 전용).
     *
     * <p>CR-165: 처음 만든 모양({@code type=openai}, {@code config.api_key})을 먼저 본다. 그 모양으로
     * 등록해 쓰는 곳이 있으면 그대로 돈다. 없으면 관리 화면과 연결 API 가 만드는 OpenAI LLM 연결을 본다.
     */
    private String resolveOpenAIKey() {
        List<ConnectionEntity> connected = connectionRepository.findAll().stream()
                .filter(c -> "connected".equalsIgnoreCase(c.getStatus()))
                .toList();

        String legacyKey = connected.stream()
                .filter(c -> "openai".equalsIgnoreCase(c.getType()))
                .findFirst()
                .map(c -> c.getConfig() != null ? (String) c.getConfig().get("api_key") : null)
                .orElse(null);
        if (legacyKey != null) {
            return legacyKey;
        }

        return connected.stream()
                .filter(c -> "llm".equalsIgnoreCase(c.getType())
                        && "openai".equals(ConnectionAdapterFactory.normalizeAdapterType(c.getAdapter())))
                .map(c -> ConnectionApiKeys.resolve(c.getConfig()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
