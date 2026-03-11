package com.platform.domain.master;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TenantUsageSummaryEntityId implements Serializable {

    @Column(name = "tenant_id", length = 100)
    private String tenantId;

    @Column(name = "year_month", length = 7) // e.g. "2026-02"
    private String yearMonth;

    public TenantUsageSummaryEntityId() {}

    public TenantUsageSummaryEntityId(String tenantId, String yearMonth) {
        this.tenantId = tenantId;
        this.yearMonth = yearMonth;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getYearMonth() { return yearMonth; }
    public void setYearMonth(String yearMonth) { this.yearMonth = yearMonth; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantUsageSummaryEntityId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(yearMonth, that.yearMonth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, yearMonth);
    }
}
