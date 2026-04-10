package com.platform.agent.config;

import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CR-042: disabled-tools 설정에 따라 도구를 필터링한다.
 */
public class ToolFilterService {

    private static final Logger log = LoggerFactory.getLogger(ToolFilterService.class);

    private final Set<String> disabledTools;

    public ToolFilterService(List<String> disabledTools) {
        this.disabledTools = new HashSet<>(disabledTools);
    }

    public List<ToolExecutor> filter(List<ToolExecutor> allTools) {
        if (disabledTools.isEmpty()) {
            log.info("All {} tools enabled", allTools.size());
            return allTools;
        }

        List<ToolExecutor> filtered = allTools.stream()
                .filter(t -> !disabledTools.contains(t.getDefinition().name()))
                .toList();

        log.info("Tool filtering: {} total, {} disabled ({}), {} active",
                allTools.size(), disabledTools.size(), disabledTools, filtered.size());
        return filtered;
    }
}
