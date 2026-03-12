package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.MCPServerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MCPSafetyClient 단위 테스트.
 *
 * Sprint 18: MCP 클라이언트 통합 — Safety MCP 호출 검증.
 */
@ExtendWith(MockitoExtension.class)
class MCPSafetyClientTest {

    @Mock private MCPServerClient mcpServerClient;

    private MCPSafetyClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        client = new MCPSafetyClient(objectMapper);

        Field mcpClientField = MCPSafetyClient.class.getDeclaredField("mcpClient");
        mcpClientField.setAccessible(true);
        mcpClientField.set(client, mcpServerClient);

        Field connectedField = MCPSafetyClient.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        connectedField.set(client, true);

        Field enabledField = MCPSafetyClient.class.getDeclaredField("mcpEnabled");
        enabledField.setAccessible(true);
        enabledField.set(client, true);
    }

    @Test
    void isAvailable_whenEnabledAndConnected_returnsTrue() {
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_whenNotConnected_returnsFalse() throws Exception {
        Field connectedField = MCPSafetyClient.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        connectedField.set(client, false);

        assertThat(client.isAvailable()).isFalse();
    }

    // ─── detectPii ───────────────────────────────────────────────────

    @Test
    void detectPii_shouldCallToolAndParseResult() {
        // given
        String response = """
                {
                  "detections": [
                    {"entity_type": "PHONE_NUMBER", "start": 4, "end": 17, "score": 0.95, "text": "010-1234-5678"}
                  ],
                  "count": 1
                }
                """;
        when(mcpServerClient.callTool(eq("detect_pii"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.detectPii("전화: 010-1234-5678");

        // then
        assertThat(result).containsEntry("count", 1);
        assertThat(result).containsKey("detections");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detections = (List<Map<String, Object>>) result.get("detections");
        assertThat(detections).hasSize(1);
        assertThat(detections.get(0)).containsEntry("entity_type", "PHONE_NUMBER");
        verify(mcpServerClient).callTool(eq("detect_pii"), any());
    }

    @Test
    void detectPii_noPiiFound_shouldReturnEmptyDetections() {
        // given
        String response = """
                {"detections": [], "count": 0}
                """;
        when(mcpServerClient.callTool(eq("detect_pii"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.detectPii("오늘 날씨가 좋습니다");

        // then
        assertThat(result).containsEntry("count", 0);
        @SuppressWarnings("unchecked")
        List<?> detections = (List<?>) result.get("detections");
        assertThat(detections).isEmpty();
    }

    // ─── maskPii ─────────────────────────────────────────────────────

    @Test
    void maskPii_shouldCallToolAndReturnMaskedText() {
        // given
        String response = """
                {
                  "masked_text": "전화: ***-****-****",
                  "detections": [{"entity_type": "PHONE_NUMBER", "text": "010-1234-5678"}],
                  "pii_found": true
                }
                """;
        when(mcpServerClient.callTool(eq("mask_pii"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.maskPii("전화: 010-1234-5678");

        // then
        assertThat(result).containsEntry("masked_text", "전화: ***-****-****");
        assertThat(result).containsEntry("pii_found", true);
        verify(mcpServerClient).callTool(eq("mask_pii"), any());
    }

    @Test
    void maskPii_withMultiplePii_shouldMaskAll() {
        // given
        String response = """
                {
                  "masked_text": "이름: *** 주민번호: ******-*******",
                  "detections": [
                    {"entity_type": "PERSON", "text": "김철수"},
                    {"entity_type": "RESIDENT_ID", "text": "900101-1234567"}
                  ],
                  "pii_found": true
                }
                """;
        when(mcpServerClient.callTool(eq("mask_pii"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.maskPii("이름: 김철수 주민번호: 900101-1234567");

        // then
        assertThat(result).containsEntry("pii_found", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detections = (List<Map<String, Object>>) result.get("detections");
        assertThat(detections).hasSize(2);
    }

    // ─── validateOutput ──────────────────────────────────────────────

    @Test
    void validateOutput_safe_shouldReturnSafeTrue() {
        // given
        String response = """
                {"safe": true, "violations": [], "violation_count": 0}
                """;
        when(mcpServerClient.callTool(eq("validate_output"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.validateOutput("안전한 응답입니다");

        // then
        assertThat(result).containsEntry("safe", true);
        assertThat(result).containsEntry("violation_count", 0);
        verify(mcpServerClient).callTool(eq("validate_output"), any());
    }

    @Test
    void validateOutput_unsafe_shouldReturnViolations() {
        // given
        String response = """
                {
                  "safe": false,
                  "violations": [{"type": "pii_leak", "text": "010-1234-5678", "severity": "high"}],
                  "violation_count": 1
                }
                """;
        when(mcpServerClient.callTool(eq("validate_output"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.validateOutput("연락처: 010-1234-5678");

        // then
        assertThat(result).containsEntry("safe", false);
        assertThat(result).containsEntry("violation_count", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).containsEntry("type", "pii_leak");
    }

    // ─── 에러 핸들링 ──────────────────────────────────────────────────

    @Test
    void callTool_whenMcpFails_shouldThrowException() {
        when(mcpServerClient.callTool(eq("detect_pii"), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> client.detectPii("test"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void callTool_whenInvalidJson_shouldThrowException() {
        when(mcpServerClient.callTool(eq("mask_pii"), any()))
                .thenReturn("{invalid");

        assertThatThrownBy(() -> client.maskPii("test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Safety MCP response");
    }
}
