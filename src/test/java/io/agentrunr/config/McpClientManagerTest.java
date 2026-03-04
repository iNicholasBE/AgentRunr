package io.agentrunr.config;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.core.ToolRegistry;
import io.agentrunr.setup.CredentialStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for McpClientManager — URI parsing, config validation, lifecycle,
 * dynamic server management, and health checking.
 */
class McpClientManagerTest {

    private ToolRegistry toolRegistry;
    private CredentialStore credentialStore;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(new ObjectMapper());
        credentialStore = mock(CredentialStore.class);
        objectMapper = new ObjectMapper();
    }

    // --- URI parsing (pure/static — no network) ---

    @Test
    void shouldParseSseUrlEndingWithSse() {
        var parts = McpClientManager.parseSseUri("https://example.com/mcp/sse");
        assertEquals("https://example.com/mcp/", parts.baseUri());
        assertEquals("sse", parts.sseEndpoint());
    }

    @Test
    void shouldParseSseUrlEndingWithSlash() {
        var parts = McpClientManager.parseSseUri("https://example.com/mcp/");
        assertEquals("https://example.com/mcp/", parts.baseUri());
        assertEquals("sse", parts.sseEndpoint());
    }

    @Test
    void shouldParseSseUrlWithoutTrailingSlash() {
        var parts = McpClientManager.parseSseUri("https://example.com/mcp");
        assertEquals("https://example.com/mcp/", parts.baseUri());
        assertEquals("sse", parts.sseEndpoint());
    }

    @Test
    void shouldParseSseUrlRootPath() {
        var parts = McpClientManager.parseSseUri("https://example.com/");
        assertEquals("https://example.com/", parts.baseUri());
        assertEquals("sse", parts.sseEndpoint());
    }

    @Test
    void shouldParseSseUrlRootPathWithSse() {
        var parts = McpClientManager.parseSseUri("https://example.com/sse");
        assertEquals("https://example.com/", parts.baseUri());
        assertEquals("sse", parts.sseEndpoint());
    }

    @Test
    void shouldParseSseUrlWithPort() {
        var parts = McpClientManager.parseSseUri("http://localhost:3000/api/sse");
        assertEquals("http://localhost:3000/api/", parts.baseUri());
        assertEquals("sse", parts.sseEndpoint());
    }

    @Test
    void shouldParseSseUrlWithDeepPath() {
        var parts = McpClientManager.parseSseUri("https://n8n.example.com/webhook/mcp/calendar/sse");
        assertEquals("https://n8n.example.com/webhook/mcp/calendar/", parts.baseUri());
        assertEquals("sse", parts.sseEndpoint());
    }

    // --- Config validation (no network) ---

    @Test
    void shouldRejectSseServerWithNoUrl() {
        var config = new McpProperties.McpServerConfig(
                "bad", null, null, null, true, "sse", null, null, null, null);
        var manager = createManager(List.of());
        assertThrows(IllegalStateException.class, () -> manager.createClient(config));
    }

    @Test
    void shouldRejectSseServerWithBlankUrl() {
        var config = new McpProperties.McpServerConfig(
                "bad", "  ", null, null, true, "sse", null, null, null, null);
        var manager = createManager(List.of());
        assertThrows(IllegalStateException.class, () -> manager.createClient(config));
    }

    @Test
    void shouldRejectStdioServerWithNoCommand() {
        var config = new McpProperties.McpServerConfig(
                "bad", null, null, null, true, "stdio", null, null, null, null);
        var manager = createManager(List.of());
        assertThrows(IllegalStateException.class, () -> manager.createClient(config));
    }

    @Test
    void shouldRejectStdioServerWithBlankCommand() {
        var config = new McpProperties.McpServerConfig(
                "bad", null, null, null, true, "stdio", "  ", null, null, null);
        var manager = createManager(List.of());
        assertThrows(IllegalStateException.class, () -> manager.createClient(config));
    }

    // --- Disabled/missing servers (no network) ---

    @Test
    void shouldSkipDisabledServers() {
        var config = new McpProperties.McpServerConfig(
                "disabled-server", "http://localhost/sse", null,
                null, false, null, null, null, null, null);
        var manager = createManager(List.of(config));
        assertTrue(manager.getStatuses().isEmpty());
    }

    @Test
    void shouldSkipServersWithNoName() {
        var config = new McpProperties.McpServerConfig(
                null, "http://localhost/sse", null,
                null, true, null, null, null, null, null);
        var manager = createManager(List.of(config));
        assertTrue(manager.getStatuses().isEmpty());
    }

    @Test
    void shouldSkipServersWithBlankName() {
        var config = new McpProperties.McpServerConfig(
                "  ", "http://localhost/sse", null,
                null, true, null, null, null, null, null);
        var manager = createManager(List.of(config));
        assertTrue(manager.getStatuses().isEmpty());
    }

    // --- Dynamic server names (no network) ---

    @Test
    void shouldReturnEmptyDynamicServerNames() {
        when(credentialStore.getApiKey("mcp_servers")).thenReturn(null);
        var manager = createManager(List.of());
        assertTrue(manager.getDynamicServerNames().isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankDynamicServerNames() {
        when(credentialStore.getApiKey("mcp_servers")).thenReturn("  ");
        var manager = createManager(List.of());
        assertTrue(manager.getDynamicServerNames().isEmpty());
    }

    @Test
    void shouldParseDynamicServerNames() {
        when(credentialStore.getApiKey("mcp_servers")).thenReturn("cal, hubspot ,crm");
        var manager = createManager(List.of());
        assertEquals(List.of("cal", "hubspot", "crm"), manager.getDynamicServerNames());
    }

    // --- No-network health/tools/reconnect checks ---

    @Test
    void shouldReturnUnhealthyForUnknownServer() {
        var manager = createManager(List.of());
        assertFalse(manager.isHealthy("nonexistent"));
    }

    @Test
    void shouldReturnEmptyToolsForUnknownServer() {
        var manager = createManager(List.of());
        assertTrue(manager.getToolsForServer("nonexistent").isEmpty());
    }

    @Test
    void shouldFailReconnectForUnknownServer() {
        var manager = createManager(List.of());
        assertFalse(manager.reconnect("nonexistent"));
    }

    // --- McpServerStatus record ---

    @Test
    void shouldCreateMcpServerStatus() {
        var status = new McpClientManager.McpServerStatus(
                "test", true, 5, "http://localhost/sse", false, "sse");
        assertEquals("test", status.name());
        assertTrue(status.connected());
        assertEquals(5, status.toolCount());
        assertEquals("http://localhost/sse", status.url());
        assertFalse(status.dynamic());
        assertEquals("sse", status.transport());
    }

    /**
     * Tests that require a single "broken" server connection. Grouped together
     * to share the ~10s timeout cost via @TestInstance(PER_CLASS).
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BrokenServerTests {

        private McpClientManager brokenManager;

        @BeforeAll
        void initBrokenManager() {
            var toolRegistry = new ToolRegistry(new ObjectMapper());
            var credentialStore = mock(CredentialStore.class);
            var config = new McpProperties.McpServerConfig(
                    "broken", "http://0.0.0.0:1/sse", null,
                    null, true, "sse", null, null, null, 1);
            var props = new McpProperties(List.of(config));
            brokenManager = new McpClientManager(props, credentialStore, toolRegistry, new ObjectMapper());
            brokenManager.init();
        }

        @Test
        void shouldReportFailedServerAsNotConnected() {
            var statuses = brokenManager.getStatuses();
            assertEquals(1, statuses.size());
            assertEquals("broken", statuses.get(0).name());
            assertFalse(statuses.get(0).connected());
            assertEquals(0, statuses.get(0).toolCount());
        }

        @Test
        void shouldTrackTransportTypeInStatus() {
            var statuses = brokenManager.getStatuses();
            assertEquals("sse", statuses.get(0).transport());
        }

        @Test
        void shouldReportDynamicFlagCorrectlyForConfigServer() {
            var statuses = brokenManager.getStatuses();
            assertFalse(statuses.get(0).dynamic());
        }

        @Test
        void shouldReturnUnhealthyForFailedServer() {
            assertFalse(brokenManager.isHealthy("broken"));
        }

        @Test
        void shouldReturnEmptyToolsForFailedServer() {
            assertTrue(brokenManager.getToolsForServer("broken").isEmpty());
        }

        @Test
        void shouldReturnEmptyConnectedServersWhenNoneConnected() {
            assertTrue(brokenManager.getConnectedServerNames().isEmpty());
        }

        @Test
        void shouldIncludeUrlInStatus() {
            var statuses = brokenManager.getStatuses();
            assertEquals("http://0.0.0.0:1/sse", statuses.get(0).url());
        }
    }

    // --- Helper ---

    private McpClientManager createManager(List<McpProperties.McpServerConfig> servers) {
        var props = new McpProperties(servers);
        var manager = new McpClientManager(props, credentialStore, toolRegistry, objectMapper);
        manager.init();
        return manager;
    }
}
