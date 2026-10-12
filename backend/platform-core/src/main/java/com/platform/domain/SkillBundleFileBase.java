package com.platform.domain;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

/**
 * CR-147: 스킬 묶음 판의 파일 하나. 내용은 BYTEA.
 * 목록에는 내용이 필요 없으므로 리포지토리는 내용을 뺀 SkillBundleFileInfo 로 따로 읽는다.
 */
@MappedSuperclass
public abstract class SkillBundleFileBase implements Persistable<SkillBundleFileId> {

    @EmbeddedId
    private SkillBundleFileId id;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 64, nullable = false)
    private String sha256;

    @Column(name = "is_text", nullable = false)
    private boolean isText;

    @Column(nullable = false)
    private byte[] content;

    @Transient
    private boolean newEntity = true;

    @PostLoad
    @PostPersist
    void markPersisted() { this.newEntity = false; }

    @Override
    public boolean isNew() { return newEntity; }

    @Override
    public SkillBundleFileId getId() { return id; }
    public void setId(SkillBundleFileId id) { this.id = id; }

    public String getPath() { return id.getPath(); }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public boolean isText() { return isText; }
    public void setText(boolean text) { isText = text; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
