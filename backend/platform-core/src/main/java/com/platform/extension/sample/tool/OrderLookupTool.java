package com.platform.extension.sample.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 이커머스 주문 조회 도구.
 *
 * 주문 ID로 주문 상태와 배송 정보를 반환합니다 (모의 데이터).
 *
 * 사용 예:
 * LLM이 "ORD-12345 주문 확인해줘" 요청을 받으면 이 도구를 호출.
 */
public class OrderLookupTool implements ToolExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "lookup_order",
            "주문 ID로 주문 상태, 고객 정보, 상품 목록, 예상 배송일을 조회합니다.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "order_id", Map.of(
                                    "type", "string",
                                    "description", "주문 ID (예: ORD-12345)"
                            )
                    ),
                    "required", List.of("order_id")
            )
    );

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String orderId = (String) input.get("order_id");
        if (orderId == null || orderId.isBlank()) {
            return "{\"error\": \"order_id가 필요합니다.\"}";
        }

        // 모의 주문 데이터 생성
        Map<String, Object> order = buildMockOrder(orderId.toUpperCase());
        try {
            return MAPPER.writeValueAsString(order);
        } catch (Exception e) {
            return "{\"error\": \"주문 조회 중 오류가 발생했습니다.\"}";
        }
    }

    private Map<String, Object> buildMockOrder(String orderId) {
        // 마지막 숫자로 상태 분기 (모의)
        int lastDigit = orderId.chars().filter(Character::isDigit)
                .reduce(0, (a, b) -> b) - '0';

        String status = switch (lastDigit % 4) {
            case 0 -> "배송완료";
            case 1 -> "배송중";
            case 2 -> "상품준비중";
            default -> "주문접수";
        };

        String estimatedDelivery = LocalDate.now().plusDays(lastDigit % 3 + 1).toString();

        return Map.of(
                "orderId", orderId,
                "status", status,
                "customer", Map.of(
                        "name", "홍길동",
                        "email", "hong@example.com",
                        "phone", "010-1234-5678"
                ),
                "items", List.of(
                        Map.of("name", "무선 이어폰 Pro", "quantity", 1, "price", 89000),
                        Map.of("name", "스마트폰 케이스", "quantity", 2, "price", 15000)
                ),
                "totalAmount", 119000,
                "currency", "KRW",
                "estimatedDelivery", estimatedDelivery,
                "trackingNumber", "CJ" + orderId.replaceAll("[^0-9]", "") + "001"
        );
    }
}
