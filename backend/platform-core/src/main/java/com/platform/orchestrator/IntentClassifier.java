package com.platform.orchestrator;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 의도 분류기 (PRD-124).
 *
 * 사용자 요청의 복잡도를 규칙 기반으로 분류한다.
 * SIMPLE: 인사, 단답 (Haiku 적합)
 * MODERATE: 일반 Q&A, 설명 요청 (Sonnet 적합)
 * COMPLEX: 추론, 코딩, 분석 (Opus 적합)
 *
 * Phase 1: 규칙 기반 (LLM 호출 없음)
 * Phase 2: LLM 기반 2차 분류 확장점 제공
 */
@Component
public class IntentClassifier {

    public enum Complexity { SIMPLE, MODERATE, COMPLEX }

    private static final Set<String> SIMPLE_PATTERNS = Set.of(
            "안녕", "hello", "hi", "감사", "thank", "네", "아니요", "예",
            "좋아", "ok", "ㅎㅎ", "ㅋㅋ", "bye", "잘가"
    );

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*```");
    private static final Pattern CODE_KEYWORD_PATTERN = Pattern.compile(
            "(?i)(코드|code|구현|implement|리팩토링|refactor|디버그|debug|함수|function|클래스|class|알고리즘|algorithm)"
    );
    private static final Pattern ANALYSIS_KEYWORD_PATTERN = Pattern.compile(
            "(?i)(분석|analyze|비교|compare|설계|design|아키텍처|architecture|최적화|optimize|장단점|trade.?off)"
    );

    private static final int SHORT_MESSAGE_THRESHOLD = 20;
    private static final int LONG_MESSAGE_THRESHOLD = 200;

    /**
     * 사용자 메시지의 복잡도를 분류한다.
     */
    public Complexity classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Complexity.SIMPLE;
        }

        String trimmed = userMessage.trim();

        // 짧은 메시지 + 단순 패턴 → SIMPLE
        if (trimmed.length() <= SHORT_MESSAGE_THRESHOLD) {
            String lower = trimmed.toLowerCase();
            for (String pattern : SIMPLE_PATTERNS) {
                if (lower.contains(pattern)) {
                    return Complexity.SIMPLE;
                }
            }
        }

        // 코드 블록 포함 → COMPLEX
        if (CODE_BLOCK_PATTERN.matcher(trimmed).find()) {
            return Complexity.COMPLEX;
        }

        // 코딩/분석 키워드 → COMPLEX
        if (CODE_KEYWORD_PATTERN.matcher(trimmed).find()
                || ANALYSIS_KEYWORD_PATTERN.matcher(trimmed).find()) {
            return Complexity.COMPLEX;
        }

        // 긴 메시지 → COMPLEX
        if (trimmed.length() > LONG_MESSAGE_THRESHOLD) {
            return Complexity.COMPLEX;
        }

        return Complexity.MODERATE;
    }
}
