package com.platform.config;

import com.platform.session.CompactionThresholds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 압축 임계값 설정 (PRD-206, CR-030 Phase 5).
 *
 * 우선순위: 환경변수 > application.yml > 기본값.
 * 환경변수: PLATFORM_SNIP_PCT, PLATFORM_MICRO_COMPACT_PCT,
 *          PLATFORM_SESSION_MEMORY_PCT, PLATFORM_AUTO_COMPACT_PCT,
 *          PLATFORM_BLOCKING_LIMIT_PCT
 */
@Configuration
public class CompactionConfig {

    @Bean
    public CompactionThresholds compactionThresholds(
            @Value("${platform.compaction.snip-percent:70}") double snipPct,
            @Value("${platform.compaction.micro-compact-percent:85}") double microPct,
            @Value("${platform.compaction.session-memory-percent:91}") double sessionMemoryPct,
            @Value("${platform.compaction.auto-compact-percent:93}") double autoPct,
            @Value("${platform.compaction.blocking-limit-percent:98}") double blockPct,
            @Value("${PLATFORM_SNIP_PCT:}") String envSnip,
            @Value("${PLATFORM_MICRO_COMPACT_PCT:}") String envMicro,
            @Value("${PLATFORM_SESSION_MEMORY_PCT:}") String envSessionMemory,
            @Value("${PLATFORM_AUTO_COMPACT_PCT:}") String envAuto,
            @Value("${PLATFORM_BLOCKING_LIMIT_PCT:}") String envBlock) {

        double finalSnip = resolve(envSnip, snipPct);
        double finalMicro = resolve(envMicro, microPct);
        double finalSessionMemory = resolve(envSessionMemory, sessionMemoryPct);
        double finalAuto = resolve(envAuto, autoPct);
        double finalBlock = resolve(envBlock, blockPct);

        return new CompactionThresholds(finalSnip, finalMicro, finalSessionMemory, finalAuto, finalBlock);
    }

    private double resolve(String envValue, double defaultPct) {
        if (envValue != null && !envValue.isBlank()) {
            return Double.parseDouble(envValue) / 100.0;
        }
        return defaultPct / 100.0;
    }
}
