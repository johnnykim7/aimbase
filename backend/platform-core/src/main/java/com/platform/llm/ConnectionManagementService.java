package com.platform.llm;

import com.platform.domain.master.SubscriptionEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 커넥션 관리 모드 서비스 (PRD-134).
 *
 * 구독 플랜의 connection_management_mode에 따라:
 * - PLATFORM_MANAGED: 테넌트 커넥션 CUD 차단, App DB 커넥션만 사용
 * - TENANT_MANAGED: 테넌트 자체 관리 (기존 동작)
 * - HYBRID: 병용 (Tenant DB 우선, App DB fallback)
 */
@Service
public class ConnectionManagementService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManagementService.class);

    private final SubscriptionRepository subscriptionRepository;

    public ConnectionManagementService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * 현재 테넌트의 커넥션 관리 모드를 반환한다.
     * 테넌트 컨텍스트가 없거나 구독 정보가 없으면 기본값 TENANT_MANAGED.
     */
    public String getMode() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) return "TENANT_MANAGED";

        return subscriptionRepository.findByTenantId(tenantId)
                .map(SubscriptionEntity::getConnectionManagementMode)
                .orElse("TENANT_MANAGED");
    }

    /**
     * 테넌트가 커넥션을 생성/수정/삭제할 수 있는지 확인한다.
     * PLATFORM_MANAGED 모드에서는 403 예외를 던진다.
     */
    public void checkTenantCanModifyConnections() {
        String mode = getMode();
        if ("PLATFORM_MANAGED".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Connection management is platform-managed. "
                            + "Contact your administrator to modify connections.");
        }
    }

    /**
     * 커넥션 조회 시 모드에 따라 소스를 결정한다.
     * - TENANT_MANAGED: Tenant DB만
     * - PLATFORM_MANAGED: App DB만
     * - HYBRID: Tenant DB + App DB
     */
    public boolean shouldIncludeAppConnections() {
        String mode = getMode();
        return "PLATFORM_MANAGED".equals(mode) || "HYBRID".equals(mode);
    }

    public boolean shouldIncludeTenantConnections() {
        String mode = getMode();
        return "TENANT_MANAGED".equals(mode) || "HYBRID".equals(mode);
    }
}
