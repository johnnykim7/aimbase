package com.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_resources",
       uniqueConstraints = @UniqueConstraint(name = "uq_project_resources", columnNames = {"project_id", "resource_type", "resource_id"}))
public class ProjectResourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", length = 100, nullable = false)
    private String projectId;

    @Column(name = "resource_type", length = 50, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 100, nullable = false)
    private String resourceId;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
