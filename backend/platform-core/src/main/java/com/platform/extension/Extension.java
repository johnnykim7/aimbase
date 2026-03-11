package com.platform.extension;

import com.platform.tool.ToolExecutor;

import java.util.List;

/**
 * 플랫폼 Extension 인터페이스.
 *
 * Extension은 여러 Tool을 묶어 플랫폼에 등록하는 번들 단위.
 * @Component를 붙여 Spring 빈으로 등록하면 ExtensionRegistry가 자동으로 수집.
 *
 * 예시:
 * <pre>
 * {@literal @}Component
 * public class MyExtension implements Extension {
 *     {@literal @}Override public String getId() { return "my-ext"; }
 *     {@literal @}Override public String getName() { return "My Extension"; }
 *     {@literal @}Override public String getVersion() { return "1.0.0"; }
 *     {@literal @}Override public List<ToolExecutor> getTools() {
 *         return List.of(new MyTool());
 *     }
 * }
 * </pre>
 */
public interface Extension {

    /** 고유 식별자 (kebab-case, 예: "ecommerce-cs") */
    String getId();

    /** 사람이 읽을 수 있는 이름 */
    String getName();

    /** 시맨틱 버전 (예: "1.0.0") */
    String getVersion();

    /** Extension 설명 */
    default String getDescription() {
        return "";
    }

    /**
     * 이 Extension이 제공하는 Tool 목록.
     * ExtensionRegistry가 {@code @PostConstruct} 시 ToolRegistry에 자동 등록.
     */
    default List<ToolExecutor> getTools() {
        return List.of();
    }

    /** Extension 로드 시 호출 (초기화 훅) */
    default void onLoad() {}

    /** Extension 언로드 시 호출 (정리 훅) */
    default void onUnload() {}
}
