package com.platform.extension;

import com.platform.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 플랫폼 Extension 레지스트리.
 *
 * Spring이 모든 {@link Extension} 빈을 자동 수집하여 주입.
 * {@code @PostConstruct} 시 각 Extension의 Tool을 ToolRegistry에 등록.
 */
@Component
public class ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final List<Extension> extensions;
    private final ToolRegistry toolRegistry;

    public ExtensionRegistry(List<Extension> extensions, ToolRegistry toolRegistry) {
        this.extensions = extensions;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    void loadAll() {
        for (Extension ext : extensions) {
            try {
                ext.getTools().forEach(toolRegistry::register);
                ext.onLoad();
                log.info("Loaded extension: {} v{} — {} tool(s)",
                        ext.getName(), ext.getVersion(), ext.getTools().size());
            } catch (Exception e) {
                log.error("Failed to load extension '{}': {}", ext.getId(), e.getMessage());
            }
        }
    }

    /**
     * 로드된 Extension 목록 반환.
     * GET /api/v1/extensions 응답에 사용.
     */
    public List<Map<String, Object>> listExtensions() {
        return extensions.stream()
                .map(ext -> Map.<String, Object>of(
                        "id", ext.getId(),
                        "name", ext.getName(),
                        "version", ext.getVersion(),
                        "description", ext.getDescription(),
                        "toolCount", ext.getTools().size(),
                        "tools", ext.getTools().stream()
                                .map(t -> t.getDefinition().name())
                                .toList()
                ))
                .toList();
    }
}
