package com.platform.policy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 정규식 기반 PII(개인식별정보) 탐지 및 마스킹 컴포넌트.
 *
 * 지원 패턴:
 * - EMAIL        : example@domain.com → ***@***.***
 * - PHONE_KR     : 010-1234-5678 → 010-****-****
 * - CREDIT_CARD  : 1234-5678-9012-3456 → ****-****-****-****
 * - SSN_KR       : 901234-1234567 → ******-*******
 */
@Component
public class PIIMasker {

    private static final Pattern EMAIL = Pattern.compile(
            "[\\w._%+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_KR = Pattern.compile(
            "01[0-9][\\-. ]?\\d{3,4}[\\-. ]?\\d{4}");

    private static final Pattern CREDIT_CARD = Pattern.compile(
            "\\d{4}[\\- ]?\\d{4}[\\- ]?\\d{4}[\\- ]?\\d{4}");

    // 주민등록번호: 6자리-7자리 (뒷자리 1~4로 시작)
    private static final Pattern SSN_KR = Pattern.compile(
            "\\d{6}-[1-4]\\d{6}");

    /**
     * 텍스트에서 PII 패턴을 탐지하고 마스킹된 문자열 반환.
     *
     * @param text 원본 텍스트 (null이면 null 반환)
     * @return 마스킹된 텍스트
     */
    public String mask(String text) {
        if (text == null) return null;

        String result = text;
        result = CREDIT_CARD.matcher(result).replaceAll("****-****-****-****");
        result = SSN_KR.matcher(result).replaceAll("******-*******");
        result = EMAIL.matcher(result).replaceAll("***@***.***");
        result = PHONE_KR.matcher(result).replaceAll("010-****-****");
        return result;
    }

    /**
     * Map 내 String 값에 대해 PII 마스킹 수행. 중첩 Map은 재귀 처리.
     *
     * @param data 원본 데이터 Map (null이면 빈 Map 반환)
     * @return 마스킹된 새 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> maskMap(Map<String, Object> data) {
        if (data == null) return Map.of();

        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                masked.put(entry.getKey(), mask(s));
            } else if (value instanceof Map) {
                masked.put(entry.getKey(), maskMap((Map<String, Object>) value));
            } else {
                masked.put(entry.getKey(), value);
            }
        }
        return masked;
    }
}
