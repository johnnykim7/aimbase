package com.platform.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class PromptTemplateEntityId implements Serializable {

    private String key;
    private Integer version;

    public PromptTemplateEntityId() {}

    public PromptTemplateEntityId(String key, Integer version) {
        this.key = key;
        this.version = version;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PromptTemplateEntityId that)) return false;
        return Objects.equals(key, that.key) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, version);
    }
}
