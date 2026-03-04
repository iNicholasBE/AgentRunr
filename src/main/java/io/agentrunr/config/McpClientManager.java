package io.agentrunr.config;

import io.agentrunr.core.ToolRegistry;
import io.agentrunr.setup.CredentialStore;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production-quality MCP client manager. Handles lifecycle for all MCP servers:
 *
 * <ol>
 *   <li><strong>Config-driven servers</strong> — defined in application.yml under {@code agent.mcp.servers}</li>
 *   <li><strong>Dynamic servers</strong> — added at runtime via the admin API (stored in CredentialStore)</li>
 * </ol>
 *
 * <p>Each server's tools are registered in {@link ToolRegistry} as function callbacks.
 * The manager tracks which tools belong to which server for proper routing and lifecycle.</p>
 */
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final McpProperties mcpProperties;
    private final CredentialStore credentialStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Map<String, ManagedServer> servers = new ConcurrentHashMap<>();

    public McpClientManager(
            McpProperties mcpProperties,
            CredentialStore credentialStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this.mcpProperties = mcpProperties;
        this.credentialStore = credentialStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        connectConfiguredServers();
        loadWorkspaceServers();
        loadDynamicServers();
        logSummary();
    }

    @PreDestroy
    void shutdown() {
        for (ManagedServer server : servers.values()) {
            closeClient(server);
        }
        servers.clear();
    }

    // --- Public API ---

    /**
     * Returns status of all tracked MCP servers (config + dynamic).
     */
    public List<McpServerStatus> getStatuses() {
        List<McpServerStatus> statuses = new ArrayList<>();
        for (ManagedServer server : servers.values()) {
            statuses.add(checkStatus(server));
        }
        return statuses;
    }

    /**
     * Returns the names of all connected servers.
     */
    public List<String> getConnectedServerNames() {
        return servers.values().stream()
                .filter(s -> s.client != null)
                .map(s -> s.name)
                .toList();
    }

    /**
     * Returns the set of tool names registered from a specific server.
     */
    public Set<String> getToolsForServer(String serverName) {
        ManagedServer server = servers.get(serverName);
        return server != null ? Set.copyOf(server.toolNames) : Set.of();
    }

    /**
     * Checks health of a specific server by calling listTools.
     */
    public boolean isHealthy(String serverName) {
        ManagedServer server = servers.get(serverName);
        if (server == null || server.client == null) return false;
        try {
            server.client.listTools();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reconnects a specific server (useful after transient failures).
     */
    public boolean reconnect(String serverName) {
        ManagedServer server = servers.get(serverName);
        if (server == null || server.config == null) {
            log.warn("Cannot reconnect '{}': no config available", serverName);
            return false;
        }

        closeClient(server);
        try {
            McpSyncClient client = createClient(server.config);
            List<String> toolNames = registerTools(serverName, client);
            servers.put(serverName, new ManagedServer(
                    serverName, client, server.config, server.dynamic, toolNames));
            log.info("Reconnected MCP server '{}' ({} tools)", serverName, toolNames.size());
            return true;
        } catch (Exception e) {
            log.error("Failed to reconnect MCP server '{}': {}", serverName, e.getMessage());
            servers.put(serverName, new ManagedServer(
                    serverName, null, server.config, server.dynamic, List.of()));
            return false;
        }
    }

    /**
     * Enables a configured (but disabled) MCP server at runtime.
     * Connects the server and registers its tools.
     */
    public boolean enableServer(String name) {
        ManagedServer existing = servers.get(name);
        if (existing != null && existing.client != null) {
            log.info("MCP server '{}' is already connected", name);
            return true;
        }

        McpProperties.McpServerConfig config = getConfiguredServerByName(name);
        if (config == null) {
            log.warn("No configured MCP server found with name '{}'", name);
            return false;
        }

        try {
            McpSyncClient client = createClient(config);
            List<String> toolNames = registerTools(name, client);
            servers.put(name, new ManagedServer(name, client, config, false, toolNames));
            log.info("Enabled MCP server '{}' ({} tools)", name, toolNames.size());
            return true;
        } catch (Exception e) {
            log.error("Failed to enable MCP server '{}': {}", name, e.getMessage());
            servers.put(name, new ManagedServer(name, null, config, false, List.of()));
            return false;
        }
    }

    /**
     * Disables a running MCP server at runtime.
     * Disconnects the server and unregisters its tools.
     */
    public boolean disableServer(String name) {
        ManagedServer server = servers.get(name);
        if (server == null) {
            log.warn("MCP server '{}' not found", name);
            return false;
        }

        // Unregister tools
        if (!server.toolNames.isEmpty()) {
            toolRegistry.unregisterFunctionCallbacks(server.toolNames);
        }

        closeClient(server);
        // Keep the entry with null client so it shows as disabled
        servers.put(name, new ManagedServer(
                name, null, server.config, server.dynamic, List.of()));
        log.info("Disabled MCP server '{}'", name);
        return true;
    }

    /**
     * Finds a configured MCP server by name from the application properties.
     */
    public McpProperties.McpServerConfig getConfiguredServerByName(String name) {
        for (McpProperties.McpServerConfig config : mcpProperties.servers()) {
            if (name.equals(config.name())) {
                return config;
            }
        }
        return null;
    }

    /**
     * Returns true if the named server is connected.
     */
    public boolean isConnected(String name) {
        ManagedServer server = servers.get(name);
        return server != null && server.client != null;
    }

    // --- Dynamic server management (via admin API) ---

    /**
     * Returns the list of dynamic server names from CredentialStore.
     */
    public List<String> getDynamicServerNames() {
        String names = credentialStore.getApiKey("mcp_servers");
        if (names == null || names.isBlank()) return List.of();
        return Arrays.stream(names.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Saves and connects a dynamic MCP server.
     */
    public void saveDynamicServer(String name, String url, String authHeader, String authValue) throws Exception {
        credentialStore.setApiKey("mcp_" + name + "_url", url);
        if (authHeader != null && !authHeader.isBlank()) {
            credentialStore.setApiKey("mcp_" + name + "_auth_header", authHeader);
        }
        if (authValue != null && !authValue.isBlank()) {
            credentialStore.setApiKey("mcp_" + name + "_auth_value", authValue);
        }

        List<String> existing = new ArrayList<>(getDynamicServerNames());
        if (!existing.contains(name)) {
            existing.add(name);
            credentialStore.setApiKey("mcp_servers", String.join(",", existing));
        }
        credentialStore.save();

        // Connect immediately
        try {
            var config = new McpProperties.McpServerConfig(
                    name, url, null,
                    authHeader != null ? Map.of(authHeader, authValue != null ? authValue : "") : Map.of(),
                    true, "sse", null, null, null, 30);
            McpSyncClient client = createClient(config);
            List<String> toolNames = registerTools(name, client);
            servers.put(name, new ManagedServer(name, client, config, true, toolNames));
            log.info("Connected dynamic MCP server '{}' ({} tools)", name, toolNames.size());
        } catch (Exception e) {
            log.warn("Saved dynamic MCP server '{}' but connection failed: {}", name, e.getMessage());
        }
    }

    /**
     * Removes a dynamic MCP server.
     */
    public void removeDynamicServer(String name) throws Exception {
        ManagedServer server = servers.remove(name);
        if (server != null) {
            closeClient(server);
        }

        credentialStore.setApiKey("mcp_" + name + "_url", null);
        credentialStore.setApiKey("mcp_" + name + "_auth_header", null);
        credentialStore.setApiKey("mcp_" + name + "_auth_value", null);

        List<String> existing = new ArrayList<>(getDynamicServerNames());
        existing.remove(name);
        if (existing.isEmpty()) {
            credentialStore.setApiKey("mcp_servers", null);
        } else {
            credentialStore.setApiKey("mcp_servers", String.join(",", existing));
        }
        credentialStore.save();
        log.info("Removed dynamic MCP server: {}", name);
    }

    // --- Internal: config-driven servers ---

    private void connectConfiguredServers() {
        for (McpProperties.McpServerConfig config : mcpProperties.servers()) {
            if (!config.enabled()) {
                log.debug("MCP server '{}' is disabled, skipping", config.name());
                continue;
            }
            if (config.name() == null || config.name().isBlank()) {
                log.warn("MCP server config missing 'name', skipping");
                continue;
            }

            try {
                McpSyncClient client = createClient(config);
                List<String> toolNames = registerTools(config.name(), client);
                servers.put(config.name(), new ManagedServer(
                        config.name(), client, config, false, toolNames));
                log.info("Connected MCP server '{}' via {} ({} tools)",
                        config.name(), config.transport(), toolNames.size());
            } catch (Exception e) {
                log.error("Failed to connect MCP server '{}': {}", config.name(), e.getMessage());
                servers.put(config.name(), new ManagedServer(
                        config.name(), null, config, false, List.of()));
            }
        }
    }

    // --- Internal: workspace servers (mcp-servers.json) ---

    private void loadWorkspaceServers() {
        if (!credentialStore.isSetupCompleted()) return;

        Path mcpFile = Path.of(credentialStore.getWorkspacePath(), "mcp-servers.json");
        if (!Files.exists(mcpFile)) {
            log.debug("No workspace MCP config found at {}", mcpFile);
            return;
        }

        try {
            var tree = objectMapper.readTree(Files.readString(mcpFile));
            var serversNode = tree.get("servers");
            if (serversNode == null || !serversNode.isArray()) return;

            for (var node : serversNode) {
                String name = node.has("name") ? node.get("name").stringValue() : null;
                if (name == null || name.isBlank()) continue;
                if (servers.containsKey(name)) {
                    log.debug("Workspace MCP server '{}' already loaded, skipping", name);
                    continue;
                }

                boolean enabled = true; // default to enabled
                if (node.has("enabled")) {
                    var enabledNode = node.get("enabled");
                    enabled = enabledNode.isBoolean() ? enabledNode.booleanValue() : true;
                }
                if (!enabled) {
                    log.debug("Workspace MCP server '{}' is disabled, skipping", name);
                    continue;
                }

                String transport = node.has("transport") ? node.get("transport").stringValue() : "sse";
                if (transport == null) transport = "sse";
                String url = node.has("url") ? node.get("url").stringValue() : null;
                String password = node.has("password") ? node.get("password").stringValue() : null;
                String command = node.has("command") ? node.get("command").stringValue() : null;

                List<String> args = new ArrayList<>();
                if (node.has("args") && node.get("args").isArray()) {
                    for (var arg : node.get("args")) args.add(arg.stringValue());
                }

                Map<String, String> headers = new java.util.HashMap<>();
                if (node.has("headers") && node.get("headers").isObject()) {
                    var headerNode = node.get("headers");
                    for (var entry : headerNode.properties()) {
                        headers.put(entry.getKey(), entry.getValue().stringValue());
                    }
                }

                Map<String, String> env = new java.util.HashMap<>();
                if (node.has("env") && node.get("env").isObject()) {
                    var envNode = node.get("env");
                    for (var entry : envNode.properties()) {
                        env.put(entry.getKey(), entry.getValue().stringValue());
                    }
                }

                int timeout = node.has("requestTimeoutSeconds")
                        ? node.get("requestTimeoutSeconds").intValue() : 30;

                var config = new McpProperties.McpServerConfig(
                        name, url, password, headers, true, transport,
                        command, args, env, timeout);

                try {
                    McpSyncClient client = createClient(config);
                    List<String> toolNames = registerTools(name, client);
                    servers.put(name, new ManagedServer(name, client, config, false, toolNames));
                    log.info("Connected workspace MCP server '{}' via {} ({} tools)",
                            name, transport, toolNames.size());
                } catch (Exception e) {
                    log.error("Failed to connect workspace MCP server '{}': {}", name, e.getMessage());
                    servers.put(name, new ManagedServer(name, null, config, false, List.of()));
                }
            }
        } catch (IOException e) {
            log.error("Failed to read workspace MCP config {}: {}", mcpFile, e.getMessage());
        }
    }

    // --- Internal: dynamic servers (CredentialStore) ---

    private void loadDynamicServers() {
        for (String name : getDynamicServerNames()) {
            if (servers.containsKey(name)) {
                log.debug("Dynamic server '{}' already loaded from config, skipping", name);
                continue;
            }

            String url = credentialStore.getApiKey("mcp_" + name + "_url");
            if (url == null || url.isBlank()) {
                log.warn("Dynamic MCP server '{}' has no URL, skipping", name);
                continue;
            }

            String authHeader = credentialStore.getApiKey("mcp_" + name + "_auth_header");
            String authValue = credentialStore.getApiKey("mcp_" + name + "_auth_value");

            Map<String, String> headers = (authHeader != null && !authHeader.isBlank())
                    ? Map.of(authHeader, authValue != null ? authValue : "")
                    : Map.of();

            var config = new McpProperties.McpServerConfig(
                    name, url, null, headers, true, "sse", null, null, null, 30);

            try {
                McpSyncClient client = createClient(config);
                List<String> toolNames = registerTools(name, client);
                servers.put(name, new ManagedServer(name, client, config, true, toolNames));
                log.info("Connected dynamic MCP server '{}' ({} tools)", name, toolNames.size());
            } catch (Exception e) {
                log.error("Failed to connect dynamic MCP server '{}': {}", name, e.getMessage());
                servers.put(name, new ManagedServer(name, null, config, true, List.of()));
            }
        }
    }

    // --- Client creation ---

    /**
     * Creates an MCP client from server config. Supports SSE and stdio transports.
     * Package-private for testing.
     */
    McpSyncClient createClient(McpProperties.McpServerConfig config) {
        McpClientTransport transport;

        if (config.isStdio()) {
            transport = createStdioTransport(config);
        } else {
            transport = createSseTransport(config);
        }

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .initializationTimeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .build();
        client.initialize();
        return client;
    }

    private McpClientTransport createSseTransport(McpProperties.McpServerConfig config) {
        if (config.url() == null || config.url().isBlank()) {
            throw new IllegalStateException("MCP server '" + config.name() + "' requires a URL for SSE transport");
        }

        SseUriParts parts = parseSseUri(config.url());

        var requestBuilder = HttpRequest.newBuilder()
                .header("Content-Type", "application/json");

        // Apply merged headers (includes password shorthand)
        for (var entry : config.resolvedHeaders().entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }

        return HttpClientSseClientTransport.builder(parts.baseUri)
                .sseEndpoint(parts.sseEndpoint)
                .clientBuilder(HttpClient.newBuilder())
                .requestBuilder(requestBuilder)
                .build();
    }

    private McpClientTransport createStdioTransport(McpProperties.McpServerConfig config) {
        if (config.command() == null || config.command().isBlank()) {
            throw new IllegalStateException("MCP server '" + config.name()
                    + "' requires a 'command' for stdio transport");
        }

        var paramsBuilder = ServerParameters.builder(config.command());
        if (!config.args().isEmpty()) {
            paramsBuilder.args(config.args());
        }
        if (!config.env().isEmpty()) {
            paramsBuilder.env(config.env());
        }

        // Use the default Jackson 3 mapper from MCP SDK
        return new StdioClientTransport(
                paramsBuilder.build(),
                io.modelcontextprotocol.json.McpJsonMapper.getDefault());
    }

    // --- Tool registration ---

    private List<String> registerTools(String serverName, McpSyncClient client) {
        List<String> toolNames = new ArrayList<>();
        try {
            var provider = new SyncMcpToolCallbackProvider(client);
            for (ToolCallback callback : provider.getToolCallbacks()) {
                String toolName = callback.getToolDefinition().name();
                toolRegistry.registerFunctionCallback(toolName, callback);
                toolNames.add(toolName);
                log.debug("Registered MCP tool '{}' from server '{}'", toolName, serverName);
            }
        } catch (Exception e) {
            log.warn("Failed to register tools for MCP server '{}': {}", serverName, e.getMessage());
        }
        return toolNames;
    }

    // --- Status & health ---

    private McpServerStatus checkStatus(ManagedServer server) {
        if (server.client == null) {
            return new McpServerStatus(server.name, false, 0,
                    server.config != null ? server.config.url() : null,
                    server.dynamic, server.config != null ? server.config.transport() : "sse");
        }
        try {
            var result = server.client.listTools();
            int toolCount = result.tools() == null ? 0 : result.tools().size();
            return new McpServerStatus(server.name, true, toolCount,
                    server.config.url(), server.dynamic, server.config.transport());
        } catch (Exception e) {
            log.debug("Health check failed for MCP server '{}': {}", server.name, e.getMessage());
            return new McpServerStatus(server.name, false, 0,
                    server.config != null ? server.config.url() : null,
                    server.dynamic, server.config != null ? server.config.transport() : "sse");
        }
    }

    // --- Lifecycle helpers ---

    private void closeClient(ManagedServer server) {
        if (server.client != null) {
            try {
                server.client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client '{}': {}", server.name, e.getMessage());
            }
        }
    }

    private void logSummary() {
        long connected = servers.values().stream().filter(s -> s.client != null).count();
        long total = servers.size();
        int totalTools = servers.values().stream().mapToInt(s -> s.toolNames.size()).sum();

        if (total == 0) {
            log.info("No MCP servers configured");
        } else {
            log.info("MCP: {}/{} servers connected, {} tools registered", connected, total, totalTools);
            for (ManagedServer server : servers.values()) {
                if (server.client != null) {
                    log.info("  [OK] {} ({}) — {} tools: {}",
                            server.name, server.config.transport(),
                            server.toolNames.size(), server.toolNames);
                } else {
                    log.warn("  [FAIL] {} — not connected", server.name);
                }
            }
        }
    }

    // --- URI parsing ---

    /**
     * Parses an SSE URL into base URI and SSE endpoint.
     * Handles the URI.resolve() gotcha: leading slash resets to root.
     * Package-private for testing.
     */
    static SseUriParts parseSseUri(String url) {
        URI uri = URI.create(url);
        String fullPath = uri.getPath() == null ? "" : uri.getPath();

        String baseUri;
        String sseEndpoint;

        if (fullPath.endsWith("/sse")) {
            // URL like https://host/path/sse → base = https://host/path/, endpoint = sse
            String basePath = fullPath.substring(0, fullPath.length() - "sse".length());
            baseUri = uri.getScheme() + "://" + uri.getAuthority() + basePath;
            sseEndpoint = "sse";
        } else {
            // URL like https://host/path → base = https://host/path/, endpoint = sse
            baseUri = uri.getScheme() + "://" + uri.getAuthority() + fullPath;
            if (!baseUri.endsWith("/")) baseUri += "/";
            sseEndpoint = "sse";
        }

        return new SseUriParts(baseUri, sseEndpoint);
    }

    // --- Inner types ---

    record SseUriParts(String baseUri, String sseEndpoint) {}

    private static class ManagedServer {
        final String name;
        final McpSyncClient client;
        final McpProperties.McpServerConfig config;
        final boolean dynamic;
        final List<String> toolNames;

        ManagedServer(String name, McpSyncClient client, McpProperties.McpServerConfig config,
                      boolean dynamic, List<String> toolNames) {
            this.name = name;
            this.client = client;
            this.config = config;
            this.dynamic = dynamic;
            this.toolNames = toolNames != null ? toolNames : List.of();
        }
    }

    public record McpServerStatus(
            String name,
            boolean connected,
            int toolCount,
            String url,
            boolean dynamic,
            String transport
    ) {}
}
