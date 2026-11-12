package com.platform.image;

import com.platform.domain.ConnectionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionApiKeys;
import com.platform.repository.ConnectionRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CR-184 BIZ-230: 대화 연결 config {@code image_connection_id} 가 가리키는 같은 테넌트의 OpenAI 연결을 그림 연결로 찾는다.
 * 그 연결에 {@code image_model} 과 키가 있어야 한다. 연결 상태는 보지 않는다. OpenAI 연결 시험은 대화 API 로 핑을 보내
 * 크레딧이 없으면 실패로 남는데, 그림 호출은 따로 실패 이유를 돌려주기 때문이다.
 */
@Component
public class ImageConnectionResolver {

    public static final String CONFIG_IMAGE_CONNECTION_ID = "image_connection_id";
    public static final String CONFIG_IMAGE_MODEL = "image_model";
    public static final String CONFIG_IMAGE_QUALITY = "image_quality";

    static final Set<String> QUALITIES = Set.of("low", "medium", "high", "auto");
    /** 설계서 T3-1 connections.config 의 처음 값. 그림 한 장 값이 품질로 갈려 가장 비싼 값을 기본으로 두지 않는다. */
    static final String DEFAULT_QUALITY = "medium";

    private static final String OPENAI = "openai";

    private final ConnectionRepository connectionRepository;

    public ImageConnectionResolver(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    public Optional<ImageConnection> resolve(String chatConnectionId) {
        if (chatConnectionId == null || chatConnectionId.isBlank()) return Optional.empty();
        ConnectionEntity chat = connectionRepository.findById(chatConnectionId).orElse(null);
        if (chat == null) return Optional.empty();
        String imageConnectionId = text(chat.getConfig(), CONFIG_IMAGE_CONNECTION_ID);
        if (imageConnectionId == null) return Optional.empty();
        return connectionRepository.findById(imageConnectionId).flatMap(ImageConnectionResolver::asImageConnection);
    }

    public boolean isConfigured(String chatConnectionId) {
        return resolve(chatConnectionId).isPresent();
    }

    static Optional<ImageConnection> asImageConnection(ConnectionEntity c) {
        if (!OPENAI.equals(ConnectionAdapterFactory.normalizeAdapterType(c.getAdapter()))) return Optional.empty();
        Map<String, Object> config = c.getConfig();
        String model = text(config, CONFIG_IMAGE_MODEL);
        if (model == null) return Optional.empty();
        String key = ConnectionApiKeys.resolve(config);
        if (key == null) return Optional.empty();
        String quality = text(config, CONFIG_IMAGE_QUALITY);
        if (quality == null || !QUALITIES.contains(quality)) quality = DEFAULT_QUALITY;
        return Optional.of(new ImageConnection(c.getId(), model, quality, key));
    }

    private static String text(Map<String, Object> config, String key) {
        if (config == null) return null;
        return config.get(key) instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
