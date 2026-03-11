package com.platform.extension.sample.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 이커머스 상품 검색 도구.
 *
 * 키워드로 상품 목록을 검색합니다 (모의 데이터).
 *
 * 사용 예:
 * LLM이 "노트북 추천해줘" 요청을 받으면 이 도구를 호출.
 */
public class ProductSearchTool implements ToolExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "search_products",
            "키워드로 상품을 검색하여 이름, 가격, 재고 정보를 반환합니다.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of(
                                    "type", "string",
                                    "description", "검색 키워드 (예: 노트북, 스마트폰)"
                            ),
                            "limit", Map.of(
                                    "type", "integer",
                                    "description", "반환할 최대 상품 수 (기본값: 5)",
                                    "default", 5
                            )
                    ),
                    "required", List.of("query")
            )
    );

    // 모의 상품 카탈로그
    private static final List<Map<String, Object>> CATALOG = List.of(
            Map.of("id", "PRD-001", "name", "노트북 Pro 15", "category", "컴퓨터",
                    "price", 1_290_000, "inStock", true, "rating", 4.7,
                    "description", "고성능 M3 칩 탑재, 15인치 레티나 디스플레이"),
            Map.of("id", "PRD-002", "name", "노트북 Air 13", "category", "컴퓨터",
                    "price", 890_000, "inStock", true, "rating", 4.5,
                    "description", "초경량 1.2kg, 18시간 배터리"),
            Map.of("id", "PRD-003", "name", "스마트폰 Galaxy S25", "category", "스마트폰",
                    "price", 1_100_000, "inStock", true, "rating", 4.6,
                    "description", "200MP 카메라, 5G 지원"),
            Map.of("id", "PRD-004", "name", "스마트폰 iPhone 16", "category", "스마트폰",
                    "price", 1_350_000, "inStock", false, "rating", 4.8,
                    "description", "A18 Bionic 칩, ProMotion 디스플레이"),
            Map.of("id", "PRD-005", "name", "무선 이어폰 Pro", "category", "오디오",
                    "price", 89_000, "inStock", true, "rating", 4.4,
                    "description", "ANC 노이즈 캔슬링, 30시간 재생"),
            Map.of("id", "PRD-006", "name", "블루투스 스피커", "category", "오디오",
                    "price", 59_000, "inStock", true, "rating", 4.2,
                    "description", "방수 IPX7, 12시간 재생"),
            Map.of("id", "PRD-007", "name", "태블릿 iPad Air", "category", "태블릿",
                    "price", 780_000, "inStock", true, "rating", 4.6,
                    "description", "M2 칩, Apple Pencil 지원"),
            Map.of("id", "PRD-008", "name", "스마트워치 Galaxy Watch 7", "category", "웨어러블",
                    "price", 299_000, "inStock", true, "rating", 4.3,
                    "description", "건강 모니터링, GPS, LTE 지원")
    );

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "{\"error\": \"query가 필요합니다.\"}";
        }

        int limit = 5;
        if (input.get("limit") instanceof Number n) {
            limit = Math.max(1, Math.min(20, n.intValue()));
        }

        String lowerQuery = query.toLowerCase();
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> product : CATALOG) {
            String name = ((String) product.get("name")).toLowerCase();
            String category = ((String) product.get("category")).toLowerCase();
            String description = ((String) product.get("description")).toLowerCase();

            if (name.contains(lowerQuery) || category.contains(lowerQuery)
                    || description.contains(lowerQuery)) {
                results.add(product);
                if (results.size() >= limit) break;
            }
        }

        Map<String, Object> response = Map.of(
                "query", query,
                "totalFound", results.size(),
                "products", results
        );

        try {
            return MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"error\": \"상품 검색 중 오류가 발생했습니다.\"}";
        }
    }
}
