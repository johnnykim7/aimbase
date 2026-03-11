package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "policies")
public class PolicyEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 50)
    private String domain;

    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Type(JsonBinaryType.class)
    @Column(name = "match_rules", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> matchRules;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> rules;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Map<String, Object> getMatchRules() { return matchRules; }
    public void setMatchRules(Map<String, Object> matchRules) { this.matchRules = matchRules; }
    public List<Map<String, Object>> getRules() { return rules; }
    public void setRules(List<Map<String, Object>> rules) { this.rules = rules; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
