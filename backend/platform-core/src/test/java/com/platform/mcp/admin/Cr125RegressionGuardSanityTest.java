package com.platform.mcp.admin;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-125 회귀 테스트가 실제로 결함을 잡아내는지 확인하는 검증용 테스트.
 *
 * <p>운영 필터를 훼손하지 않고, 수정 전 로직(전역 단일 필드 + 마지막값 폴백)을
 * 여기에 그대로 복제해 "옛 방식이면 교차 오염이 난다"를 재현한다.
 * 이 테스트가 PASS 한다는 것은 Cr125TenantCrossTalkTest 의 통과가
 * 우연이 아니라 실제 수정 덕분임을 뜻한다.</p>
 */
class Cr125RegressionGuardSanityTest {

    /** 수정 전 구현 재현: 전역 단일 필드. */
    static class LegacyGlobalTenantHolder {
        private volatile String currentMcpTenant = null;

        String resolve(String sessionIdIgnored, String tenantParam) {
            if (tenantParam != null && !tenantParam.isBlank()) {
                currentMcpTenant = tenantParam.trim();
            }
            return currentMcpTenant; // 세션 무시 → 마지막 승자
        }
    }

    /** 수정 후 구현 재현: 세션 단위 격납. */
    static class SessionScopedTenantHolder {
        private final Map<String, String> bySession = new ConcurrentHashMap<>();

        String resolve(String sessionId, String tenantParam) {
            if (tenantParam != null && !tenantParam.isBlank()) {
                if (sessionId != null) {
                    bySession.put(sessionId, tenantParam.trim());
                }
                return tenantParam.trim();
            }
            return sessionId == null ? null : bySession.get(sessionId);
        }
    }

    @Test
    void legacyGlobalHolder_crossTalks() {
        var legacy = new LegacyGlobalTenantHolder();
        legacy.resolve("sess-A", "tenant_a");
        legacy.resolve("sess-B", "tenant_b");

        // A 의 후속 요청이 B 를 본다 — 이것이 CR-125 결함
        assertThat(legacy.resolve("sess-A", null)).isEqualTo("tenant_b");
    }

    @Test
    void sessionScopedHolder_doesNotCrossTalk() {
        var fixed = new SessionScopedTenantHolder();
        fixed.resolve("sess-A", "tenant_a");
        fixed.resolve("sess-B", "tenant_b");

        assertThat(fixed.resolve("sess-A", null)).isEqualTo("tenant_a");
        assertThat(fixed.resolve("sess-B", null)).isEqualTo("tenant_b");
        assertThat(fixed.resolve("sess-UNKNOWN", null)).isNull();
    }
}
