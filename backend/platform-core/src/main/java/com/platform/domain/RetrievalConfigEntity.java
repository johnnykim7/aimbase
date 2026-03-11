package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "retrieval_config")
public class RetrievalConfigEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(name = "top_k")
    private int topK = 5;

    @Column(name = "similarity_threshold", precision = 3, scale = 2)
    private BigDecimal similarityThreshold = new BigDecimal("0.70");

    @Column(name = "max_context_tokens")
    private int maxContextTokens = 4000;

    @Column(name = "search_type", length = 20)
    private String searchType = "hybrid";

    @Type(JsonBinaryType.class)
    @Column(name = "source_filters", columnDefinition = "jsonb")
    private Map<String, Object> sourceFilters;

    @Type(JsonBinaryType.class)
    @Column(name = "query_processing", columnDefinition = "jsonb")
    private Map<String, Object> queryProcessing;

    @Column(name = "context_template", columnDefinition = "text")
    private String contextTemplate;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public BigDecimal getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(BigDecimal similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }
    public String getSearchType() { return searchType; }
    public void setSearchType(String searchType) { this.searchType = searchType; }
    public Map<String, Object> getSourceFilters() { return sourceFilters; }
    public void setSourceFilters(Map<String, Object> sourceFilters) { this.sourceFilters = sourceFilters; }
    public Map<String, Object> getQueryProcessing() { return queryProcessing; }
    public void setQueryProcessing(Map<String, Object> queryProcessing) { this.queryProcessing = queryProcessing; }
    public String getContextTemplate() { return contextTemplate; }
    public void setContextTemplate(String contextTemplate) { this.contextTemplate = contextTemplate; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
