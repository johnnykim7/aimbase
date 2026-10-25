package com.platform.auth;

import com.platform.domain.master.ApiKeyEntity;
import com.platform.repository.RoleRepository;
import com.platform.repository.UserRepository;
import com.platform.repository.master.ApiKeyRepository;
import com.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CR-154: API 키를 X-API-Key 와 Authorization: ApiKey 두 머리글로 받는다.
 */
class ApiKeyAuthenticationFilterTest {

    private static final String RAW_KEY = "plat-cr154-test-key";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final FilterChain chain = mock(FilterChain.class);

    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(userRepository, roleRepository, apiKeyRepository);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void stubTenantKey() throws Exception {
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId("key-1");
        key.setTenantId("tenant-a");
        key.setActive(true);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(RAW_KEY.getBytes(StandardCharsets.UTF_8));
        when(apiKeyRepository.findByKeyHashAndIsActiveTrue(HexFormat.of().formatHex(hash)))
                .thenReturn(Optional.of(key));
    }

    private void runWith(String header, String value) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader(header, value);
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("IT-01-010 1 Authorization_ApiKey_머리글의_키로_그_테넌트로_인증한다")
    void authenticates_withAuthorizationApiKey() throws Exception {
        stubTenantKey();

        runWith("Authorization", "ApiKey " + RAW_KEY);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("IT-01-010 2 스킴_이름은_대소문자를_가리지_않는다")
    void authenticates_withLowercaseScheme() throws Exception {
        stubTenantKey();

        runWith("Authorization", "apikey " + RAW_KEY);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("IT-01-010 3 Bearer_값은_API_키로_보지_않는다")
    void ignores_bearerAuthorization() throws Exception {
        runWith("Authorization", "Bearer " + RAW_KEY);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(apiKeyRepository, userRepository);
    }

    @Test
    @DisplayName("IT-01-010 4 X_API_Key_머리글도_그대로_인증한다")
    void authenticates_withXApiKey() throws Exception {
        stubTenantKey();

        runWith("X-API-Key", RAW_KEY);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-a");
    }
}
