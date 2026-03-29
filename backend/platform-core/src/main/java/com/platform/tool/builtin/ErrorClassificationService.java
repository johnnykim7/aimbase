package com.platform.tool.builtin;

import com.platform.domain.master.ClaudeCodeErrorPatternEntity;
import com.platform.repository.master.ClaudeCodeErrorPatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CLI 출력에서 에러를 분류하는 서비스.
 * DB에서 패턴을 로드하고, priority 순으로 contains 매칭.
 * 캐시를 유지하여 매 호출마다 DB 조회를 피한다.
 */
@Service
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class ErrorClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ErrorClassificationService.class);

    private final ClaudeCodeErrorPatternRepository patternRepository;
    private volatile List<ClaudeCodeErrorPatternEntity> cachedPatterns = new CopyOnWriteArrayList<>();

    public ErrorClassificationService(ClaudeCodeErrorPatternRepository patternRepository) {
        this.patternRepository = patternRepository;
        refreshPatterns();
    }

    /** DB에서 활성 패턴을 리로드 (API에서 패턴 추가/삭제 후 호출) */
    public void refreshPatterns() {
        try {
            cachedPatterns = patternRepository.findByIsActiveTrueOrderByPriorityDesc();
            log.info("에러 패턴 로드 완료: {}건", cachedPatterns.size());
        } catch (Exception e) {
            log.warn("에러 패턴 로드 실패 (기존 캐시 유지): {}", e.getMessage());
        }
    }

    /**
     * CLI 출력(stdout+stderr)에서 에러를 분류한다.
     * priority 내림차순으로 contains 매칭, 첫 매칭에서 즉시 반환.
     */
    public ErrorClassification classify(String output) {
        if (output == null || output.isBlank()) {
            return ErrorClassification.unknown();
        }

        String lowerOutput = output.toLowerCase();
        for (ClaudeCodeErrorPatternEntity pattern : cachedPatterns) {
            if (lowerOutput.contains(pattern.getPattern().toLowerCase())) {
                log.debug("에러 패턴 매칭: pattern='{}', type={}, action={}",
                        pattern.getPattern(), pattern.getErrorType(), pattern.getAction());
                return new ErrorClassification(
                        pattern.getErrorType(),
                        pattern.getAction(),
                        pattern.getPattern()
                );
            }
        }

        return ErrorClassification.unknown();
    }
}
