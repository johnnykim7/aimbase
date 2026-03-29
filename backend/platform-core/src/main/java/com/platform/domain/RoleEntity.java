package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.util.Map;

@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @Column(length = 50)
    private String id;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(columnDefinition = "varchar(50)[]")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
    private String[] inherits;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> permissions;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String[] getInherits() { return inherits; }
    public void setInherits(String[] inherits) { this.inherits = inherits; }
    public Map<String, Object> getPermissions() { return permissions; }
    public void setPermissions(Map<String, Object> permissions) { this.permissions = permissions; }
}
