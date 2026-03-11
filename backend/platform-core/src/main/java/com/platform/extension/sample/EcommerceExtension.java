package com.platform.extension.sample;

import com.platform.extension.Extension;
import com.platform.extension.sample.tool.OrderLookupTool;
import com.platform.extension.sample.tool.ProductSearchTool;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 이커머스 CS(고객서비스) 샘플 Extension.
 *
 * 제공 도구:
 * - {@code lookup_order}    : 주문 ID로 주문 상태 조회
 * - {@code search_products} : 키워드로 상품 검색
 *
 * ExtensionRegistry가 @PostConstruct 시 이 Extension의 Tool을 ToolRegistry에 자동 등록.
 * actionsEnabled=true로 채팅 시 LLM이 이 도구들을 호출할 수 있음.
 */
@Component
public class EcommerceExtension implements Extension {

    private static final Logger log = LoggerFactory.getLogger(EcommerceExtension.class);

    @Override
    public String getId() {
        return "ecommerce-cs";
    }

    @Override
    public String getName() {
        return "E-Commerce CS Extension";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "이커머스 고객서비스 도우미 — 주문 조회 및 상품 검색 도구를 제공합니다.";
    }

    @Override
    public List<ToolExecutor> getTools() {
        return List.of(new OrderLookupTool(), new ProductSearchTool());
    }

    @Override
    public void onLoad() {
        log.info("EcommerceExtension loaded: providing lookup_order, search_products tools");
    }
}
