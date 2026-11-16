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
    /** CR-196: 플랫폼 관리자가 임퍼소네이션(CR-096) 토큰으로 들어왔는가. 1년 토큰을 누가 넣었는지 가른다(BIZ-289). */
    private final boolean impersonating;

    public UserPrincipal(String id, String email, String tenantId, String role, Map<String, Object> permissions) {
        this(id, email, tenantId, role, permissions, false);
    }

    public UserPrincipal(String id, String email, String tenantId, String role, Map<String, Object> permissions,
                         boolean impersonating) {
        this.id = id;
        this.email = email;
        this.tenantId = tenantId;
        this.role = role;
        this.permissions = permissions != null ? permissions : Map.of();
        this.impersonating = impersonating;
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public Map<String, Object> getPermissions() { return permissions; }
    public boolean isImpersonating() { return impersonating; }

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
