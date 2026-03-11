package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "routing_config")
public class RoutingConfigEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 30, nullable = false)
    private String strategy;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> rules;

    @Type(JsonBinaryType.class)
    @Column(name = "fallback_chain", columnDefinition = "jsonb")
    private List<String> fallbackChain;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public List<Map<String, Object>> getRules() { return rules; }
    public void setRules(List<Map<String, Object>> rules) { this.rules = rules; }
    public List<String> getFallbackChain() { return fallbackChain; }
    public void setFallbackChain(List<String> fallbackChain) { this.fallbackChain = fallbackChain; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
