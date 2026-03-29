package com.platform.domain.master;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "apps")
public class AppEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 20, nullable = false)
    private String status = "active";

    @Column(name = "db_host", length = 255, nullable = false)
    private String dbHost;

    @Column(name = "db_port", nullable = false)
    private Integer dbPort = 5432;

    @Column(name = "db_name", length = 100, nullable = false)
    private String dbName;

    @Column(name = "db_username", length = 100, nullable = false)
    private String dbUsername;

    @Column(name = "db_password_encrypted", nullable = false, columnDefinition = "TEXT")
    private String dbPasswordEncrypted;

    @Column(name = "owner_email", length = 255)
    private String ownerEmail;

    @Column(name = "max_tenants", nullable = false)
    private Integer maxTenants = 100;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }
    public Integer getDbPort() { return dbPort; }
    public void setDbPort(Integer dbPort) { this.dbPort = dbPort; }
    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }
    public String getDbUsername() { return dbUsername; }
    public void setDbUsername(String dbUsername) { this.dbUsername = dbUsername; }
    public String getDbPasswordEncrypted() { return dbPasswordEncrypted; }
    public void setDbPasswordEncrypted(String dbPasswordEncrypted) { this.dbPasswordEncrypted = dbPasswordEncrypted; }
    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }
    public Integer getMaxTenants() { return maxTenants; }
    public void setMaxTenants(Integer maxTenants) { this.maxTenants = maxTenants; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
