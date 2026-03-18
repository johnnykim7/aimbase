package com.platform.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "model_pricing")
public class ModelPricingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "model_name", length = 100, nullable = false, unique = true)
    private String modelName;

    @Column(length = 50, nullable = false)
    private String provider;

    @Column(name = "input_price_per_1k", nullable = false, precision = 10, scale = 6)
    private BigDecimal inputPricePer1k;

    @Column(name = "output_price_per_1k", nullable = false, precision = 10, scale = 6)
    private BigDecimal outputPricePer1k;

    @Column(length = 10)
    private String currency = "USD";

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public BigDecimal getInputPricePer1k() { return inputPricePer1k; }
    public void setInputPricePer1k(BigDecimal inputPricePer1k) { this.inputPricePer1k = inputPricePer1k; }
    public BigDecimal getOutputPricePer1k() { return outputPricePer1k; }
    public void setOutputPricePer1k(BigDecimal outputPricePer1k) { this.outputPricePer1k = outputPricePer1k; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
