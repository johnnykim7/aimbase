package com.platform.speech;

import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * CR-186: 지금 테넌트의 맥 받아쓰기 서버 연결. 누르고 말하기({@link LocalWhisperSpeechService})와 같이
 * 활성 {@code stt_local} 연결 중 첫 번째를 쓴다.
 */
@Component
public class SttLocalConnections {

    private final ConnectionRepository connectionRepository;

    public SttLocalConnections(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    public Optional<ConnectionEntity> findActive() {
        return connectionRepository.findAll().stream()
                .filter(c -> LocalWhisperSpeechService.CONNECTION_TYPE.equalsIgnoreCase(c.getType()))
                .filter(c -> !"disabled".equalsIgnoreCase(String.valueOf(c.getStatus())))
                .findFirst();
    }

    public Optional<ConnectionEntity> findById(String id) {
        return id == null ? Optional.empty() : connectionRepository.findById(id);
    }

    /** 연결 config 의 받아쓰기 서버 키. 값({@code api_key})이 우선이고, 없으면 환경변수 이름 방식도 받는다. */
    public static String apiKey(Map<String, Object> cfg) {
        Object v = cfg.get("api_key");
        if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        return com.platform.llm.ConnectionApiKeys.resolve(cfg);
    }
}
