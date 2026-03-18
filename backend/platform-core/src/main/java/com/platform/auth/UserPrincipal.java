package com.platform.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Spring Security UserDetails 구현.
 * JWT/API Key 인증 후 SecurityContext에 저장된다.
 */
public class UserPrincipal implements UserDetails {

    private final String id;
    private final String email;
    private final String tenantId;
    private final String role;
    private final Map<String, Object> permissions;

    public UserPrincipal(String id, String email, String tenantId, String role, Map<String, Object> permissions) {
        this.id = id;
        this.email = email;
        this.tenantId = tenantId;
        this.role = role;
        this.permissions = permissions != null ? permissions : Map.of();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public Map<String, Object> getPermissions() { return permissions; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
    }

    @Override
    public String getPassword() { return null; }

    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
