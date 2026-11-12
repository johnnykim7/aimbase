package com.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * CR-178: 위젯 사용자가 만드는 대화 묶음(BIZ-171). 관리 화면 프로젝트(projects, CR-021)와 다른 테이블이다.
 */
@Entity
@Table(name = "chat_projects")
public class ChatProjectEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "owner_ref", length = 200, nullable = false)
    private String ownerRef;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getOwnerRef() { return ownerRef; }
    public void setOwnerRef(String ownerRef) { this.ownerRef = ownerRef; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean hasInstructions() {
        return instructions != null && !instructions.isBlank();
    }
}
