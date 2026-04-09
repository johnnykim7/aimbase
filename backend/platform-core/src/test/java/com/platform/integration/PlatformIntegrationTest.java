package com.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import com.platform.repository.PolicyRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.onboarding.TenantOnboardingRequest;
import com.platform.tenant.onboarding.TenantOnboardingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * v4 Multi-Tenancy 통합 테스트.
 *
 * 구성:
 *  - PostgreSQL (pgvector/pgvector:pg16): Master DB + 테넌트 DB 공용 서버
 *    → Master DB:     platform_test_master
 *    → Tenant A DB:   aimbase_tenanta  (TenantOnboardingService가 @BeforeAll에서 생성)
 *    → Tenant B DB:   aimbase_tenantb
 *  - Redis (redis:7-alpine): 세션 스토어
 *
 * 검증 항목:
 *  1. 기존 CRUD API — 테넌트 컨텍스트(X-Tenant-Id 헤더) 하에서 정상 동작
 *  2. 테넌트 격리 — Tenant A 데이터가 Tenant B에서 보이지 않음
 *  3. 테넌트 프로비저닝 — POST /api/v1/platform/tenants 로 새 테넌트 생성
 *  4. 플랫폼 관리 API — Master DB 기반 테넌트 목록 조회
 */
@SpringBootTest(properties = "security.enabled=false")
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)  // @BeforeAll을 인스턴스 메서드로, @Autowired 사용 가능
class PlatformIntegrationTest {

    // ─── TestContainers ───────────────────────────────────────────────

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("platform_test_master")
            .withUsername("platform")
            .withPassword("platform123");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    // @TestInstance(PER_CLASS) + @SpringBootTest 조합에서 Spring Context가 @DynamicPropertySource 람다를
    // BeforeAllCallback(컨테이너 시작) 보다 먼저 resolve 하기 때문에, static 초기화 블록에서 먼저 시작한다.
    static {
        postgres.start();
        redis.start();
    }

    private static final String TENANT_A = "tenanta";
    private static final String TENANT_B = "tenantb";
    private static final String TENANT_NEW = "tenantnew";

    // ─── Spring Properties (컨테이너 시작 후 설정) ─────────────────────

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Master DB
        registry.add("spring.datasource.master.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.master.username", postgres::getUsername);
        registry.add("spring.datasource.master.password", postgres::getPassword);
        registry.add("spring.datasource.master.driver-class-name", () -> "org.postgresql.Driver");

        // Tenant DB 서버 기본값 (테스트에서는 Master와 같은 PostgreSQL 인스턴스 사용)
        registry.add("platform.default-db-host", postgres::getHost);
        registry.add("platform.default-db-port", () -> postgres.getMappedPort(5432).toString());
        registry.add("platform.default-db-username", postgres::getUsername);
        registry.add("platform.default-db-password", postgres::getPassword);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());

        // Spring AI — 테스트에서 외부 LLM 호출 없이 실행
        registry.add("spring.ai.anthropic.api-key", () -> "test-key");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    // ─── Spring Beans ─────────────────────────────────────────────────

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TenantOnboardingService onboardingService;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private PolicyRepository policyRepository;

    // ─── 테스트 픽스처 설정 ────────────────────────────────────────────

    /**
     * 전체 테스트 실행 전 1회: Tenant A, B 프로비저닝
     * - Master DB에 tenant 레코드 생성
     * - PostgreSQL에 aimbase_tenanta / aimbase_tenantb DB 생성
     * - Flyway 마이그레이션 실행 (17개 테이블)
     * - 초기 Admin 계정 시드
     */
    @BeforeAll
    void provisionTestTenants() {
        String host = postgres.getHost();
        int port = postgres.getMappedPort(5432);
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        onboardingService.provision(new TenantOnboardingRequest(
                TENANT_A, null, "Alpha Corp", "admin@alpha.test", "TestPass1!", "starter",
                host, port, user, pass));

        onboardingService.provision(new TenantOnboardingRequest(
                TENANT_B, null, "Beta Corp", "admin@beta.test", "TestPass1!", "starter",
                host, port, user, pass));
    }

    /**
     * 각 테스트 전: 양 테넌트의 connections / policies 초기화
     */
    @BeforeEach
    void cleanEachTenantData() {
        TenantContext.setTenantId(TENANT_A);
        connectionRepository.deleteAll();
        policyRepository.deleteAll();
        TenantContext.clear();

        TenantContext.setTenantId(TENANT_B);
        connectionRepository.deleteAll();
        policyRepository.deleteAll();
        TenantContext.clear();
    }

    // ─── Application Context ──────────────────────────────────────────

    @Test
    void contextLoads() {
        assertThat(mockMvc).isNotNull();
        assertThat(connectionRepository).isNotNull();
    }

    // ─── Connection CRUD (X-Tenant-Id 헤더 사용) ──────────────────────

    @Test
    void createConnection_shouldReturn201() throws Exception {
        var request = Map.of(
                "id", "test-postgres-conn",
                "name", "Test PostgreSQL",
                "adapter", "postgresql",
                "type", "write",
                "config", Map.of("host", "localhost", "port", 5432, "database", "testdb")
        );

        mockMvc.perform(post("/api/v1/connections")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("test-postgres-conn"))
                .andExpect(jsonPath("$.data.status").value("disconnected"));
    }

    @Test
    void listConnections_shouldReturnPagedResult() throws Exception {
        seedConnection("conn-1", "postgresql");
        seedConnection("conn-2", "websocket");

        mockMvc.perform(get("/api/v1/connections")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.pagination").exists());
    }

    @Test
    void getConnection_shouldReturn200() throws Exception {
        seedConnection("existing-conn", "postgresql");

        mockMvc.perform(get("/api/v1/connections/existing-conn")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("existing-conn"));
    }

    @Test
    void getConnection_notFound_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/connections/nonexistent")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteConnection_shouldReturn204() throws Exception {
        seedConnection("conn-to-delete", "postgresql");

        mockMvc.perform(delete("/api/v1/connections/conn-to-delete")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isNoContent());

        TenantContext.setTenantId(TENANT_A);
        assertThat(connectionRepository.findById("conn-to-delete")).isEmpty();
        TenantContext.clear();
    }

    // ─── Policy 테스트 ─────────────────────────────────────────────────

    @Test
    void createPolicy_shouldReturn201() throws Exception {
        var request = Map.of(
                "id", "test-deny-policy",
                "name", "Test Deny Policy",
                "priority", 10,
                "matchRules", Map.of("intents", List.of("delete_all")),
                "rules", List.of(Map.of("type", "DENY", "message", "Dangerous operation"))
        );

        mockMvc.perform(post("/api/v1/policies")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("test-deny-policy"))
                .andExpect(jsonPath("$.data.name").value("Test Deny Policy"));
    }

    @Test
    void simulatePolicy_noActivePolicies_shouldReturnAllow() throws Exception {
        var request = Map.of(
                "intent", "read_data",
                "adapter", "postgresql"
        );

        mockMvc.perform(post("/api/v1/policies/simulate")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("ALLOW"));
    }

    // ─── Schema 테스트 ─────────────────────────────────────────────────

    @Test
    void createAndValidateSchema_shouldWork() throws Exception {
        var schemaRequest = Map.of(
                "id", "user-profile",
                "version", 1,
                "description", "User profile schema",
                "jsonSchema", Map.of(
                        "type", "object",
                        "required", List.of("name", "email"),
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "email", Map.of("type", "string")
                        )
                )
        );

        mockMvc.perform(post("/api/v1/schemas")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(schemaRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pk.id").value("user-profile"))
                .andExpect(jsonPath("$.data.pk.version").value(1));

        var validData = Map.of("name", "Test User", "email", "test@example.com");
        mockMvc.perform(post("/api/v1/schemas/user-profile/1/validate")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validData)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        var invalidData = Map.of("email", "test@example.com");
        mockMvc.perform(post("/api/v1/schemas/user-profile/1/validate")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidData)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false));
    }

    // ─── Knowledge Source 테스트 ──────────────────────────────────────

    @Test
    void createKnowledgeSource_shouldReturn201() throws Exception {
        var request = Map.of(
                "id", "test-docs",
                "name", "Test Documentation",
                "type", "file",
                "config", Map.of("path", "/docs")
        );

        mockMvc.perform(post("/api/v1/knowledge-sources")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("test-docs"))
                .andExpect(jsonPath("$.data.status").value("idle"));
    }

    // ─── 모델 목록 테스트 ─────────────────────────────────────────────

    @Test
    void listModels_shouldReturnAvailableModels() throws Exception {
        mockMvc.perform(get("/api/v1/models")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ─── Actuator 헬스체크 ────────────────────────────────────────────

    @Test
    void actuatorHealth_shouldReturn200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // ─── Multi-Tenancy: 테넌트 격리 검증 ──────────────────────────────

    /**
     * Tenant A에 생성한 Connection이 Tenant B에서 조회되지 않음을 검증.
     */
    @Test
    void tenantIsolation_connectionInTenantA_notVisibleInTenantB() throws Exception {
        var request = Map.of(
                "id", "iso-conn",
                "name", "Isolation Test Connection",
                "adapter", "postgresql",
                "type", "write",
                "config", Map.of()
        );

        // Tenant A에 생성
        mockMvc.perform(post("/api/v1/connections")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Tenant A에서 조회 — 성공
        mockMvc.perform(get("/api/v1/connections/iso-conn")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("iso-conn"));

        // Tenant B에서 조회 — 404
        mockMvc.perform(get("/api/v1/connections/iso-conn")
                        .header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound());
    }

    /**
     * 각 테넌트의 목록 API가 자신의 데이터만 반환함을 검증.
     */
    @Test
    void tenantIsolation_listConnections_showsOnlyOwnTenantData() throws Exception {
        // Tenant A에 2개 생성
        TenantContext.setTenantId(TENANT_A);
        seedConnectionEntity("conn-a-1", "postgresql");
        seedConnectionEntity("conn-a-2", "websocket");
        TenantContext.clear();

        // Tenant A 목록: 2개
        mockMvc.perform(get("/api/v1/connections").header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));

        // Tenant B 목록: 0개 (Tenant B에 데이터 없음)
        mockMvc.perform(get("/api/v1/connections").header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ─── Multi-Tenancy: 테넌트 프로비저닝 ─────────────────────────────

    /**
     * POST /api/v1/platform/tenants 로 새 테넌트를 생성하고,
     * 해당 테넌트에서 Connection API가 정상 작동하는지 검증.
     */
    @Test
    void tenantOnboarding_createNewTenantViaPlatformApi_shouldReturn201() throws Exception {
        var request = Map.of(
                "tenantId", TENANT_NEW,
                "name", "New Company",
                "adminEmail", "admin@newco.test",
                "initialAdminPassword", "SecurePass1!",
                "plan", "starter"
        );

        mockMvc.perform(post("/api/v1/platform/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_NEW));
    }

    /**
     * 동일 tenantId로 중복 생성 시 409 Conflict 반환 검증.
     */
    @Test
    void tenantOnboarding_duplicateTenantId_shouldReturn409() throws Exception {
        var request = Map.of(
                "tenantId", TENANT_A,  // 이미 @BeforeAll에서 생성된 테넌트
                "name", "Duplicate",
                "adminEmail", "dup@test.com",
                "initialAdminPassword", "Pass1!",
                "plan", "free"
        );

        mockMvc.perform(post("/api/v1/platform/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // ─── Multi-Tenancy: 플랫폼 관리 API ───────────────────────────────

    @Test
    void platformApi_listTenants_shouldIncludeProvisionedTenants() throws Exception {
        mockMvc.perform(get("/api/v1/platform/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[*].id", hasItem(TENANT_A)))
                .andExpect(jsonPath("$.data[*].id", hasItem(TENANT_B)));
    }

    @Test
    void platformApi_usageDashboard_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/platform/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTenants").isNumber())
                .andExpect(jsonPath("$.data.activeTenants").isNumber());
    }

    // ─── 헬퍼 메서드 ──────────────────────────────────────────────────

    /** API를 통해 Tenant A에 Connection 생성 */
    private void seedConnection(String id, String adapter) throws Exception {
        var request = Map.of(
                "id", id,
                "name", "Test " + id,
                "adapter", adapter,
                "type", "write",
                "config", Map.of()
        );
        mockMvc.perform(post("/api/v1/connections")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    /** TenantContext가 설정된 상태에서 직접 Repository를 통해 Connection 생성 */
    private void seedConnectionEntity(String id, String adapter) {
        ConnectionEntity entity = new ConnectionEntity();
        entity.setId(id);
        entity.setName("Test " + id);
        entity.setAdapter(adapter);
        entity.setType("write");
        entity.setConfig(Map.of());
        entity.setStatus("disconnected");
        connectionRepository.save(entity);
    }
}
