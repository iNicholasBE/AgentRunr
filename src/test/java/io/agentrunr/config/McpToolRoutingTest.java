package io.agentrunr.config;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.core.AgentContext;
import io.agentrunr.core.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MCP tool routing through the ToolRegistry.
 * Verifies that MCP tools are registered as function callbacks
 * and can be executed through the standard tool execution flow.
 */
class McpToolRoutingTest {

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(new ObjectMapper());
    }

    @Test
    void shouldRegisterMcpToolAsFunctionCallback() {
        var mcpTool = createMockToolCallback("mcp_calendar_list_events");
        toolRegistry.registerFunctionCallback("mcp_calendar_list_events", mcpTool);

        var callbacks = toolRegistry.getToolCallbacks(List.of("mcp_calendar_list_events"));
        assertEquals(1, callbacks.size());
        assertSame(mcpTool, callbacks.get(0));
    }

    @Test
    void shouldExecuteMcpToolThroughRegistry() {
        var mcpTool = createMockToolCallback("mcp_create_event");
        when(mcpTool.call("{\"title\":\"Meeting\"}")).thenReturn("{\"status\":\"created\"}");
        toolRegistry.registerFunctionCallback("mcp_create_event", mcpTool);

        var result = toolRegistry.executeTool("mcp_create_event", "{\"title\":\"Meeting\"}", new AgentContext());
        assertEquals("{\"status\":\"created\"}", result.value());
        assertNull(result.handoffAgent());
    }

    @Test
    void shouldHandleMcpToolError() {
        var mcpTool = createMockToolCallback("mcp_failing_tool");
        when(mcpTool.call(anyString())).thenThrow(new RuntimeException("Server timeout"));
        toolRegistry.registerFunctionCallback("mcp_failing_tool", mcpTool);

        var result = toolRegistry.executeTool("mcp_failing_tool", "{}", new AgentContext());
        assertTrue(result.value().contains("Error"));
        assertTrue(result.value().contains("Server timeout"));
    }

    @Test
    void shouldPrioritizeAgentToolsOverMcpTools() {
        // Register same name as both agent tool and MCP callback
        toolRegistry.registerAgentTool("dual_tool", (args, ctx) ->
                io.agentrunr.core.AgentResult.of("agent-result"));
        var mcpTool = createMockToolCallback("dual_tool");
        when(mcpTool.call(anyString())).thenReturn("mcp-result");
        toolRegistry.registerFunctionCallback("dual_tool", mcpTool);

        // Agent tools have priority
        var result = toolRegistry.executeTool("dual_tool", "{}", new AgentContext());
        assertEquals("agent-result", result.value());
    }

    @Test
    void shouldPrioritizeSpringToolsOverMcpTools() {
        // Register same name as both @Tool callback and MCP function callback
        var springTool = createMockToolCallback("overlap_tool");
        when(springTool.call(anyString())).thenReturn("spring-result");
        toolRegistry.registerToolCallback("overlap_tool", springTool);

        var mcpTool = createMockToolCallback("overlap_tool");
        when(mcpTool.call(anyString())).thenReturn("mcp-result");
        toolRegistry.registerFunctionCallback("overlap_tool", mcpTool);

        // Spring @Tool callbacks have priority over MCP
        var result = toolRegistry.executeTool("overlap_tool", "{}", new AgentContext());
        assertEquals("spring-result", result.value());
    }

    @Test
    void shouldIncludeMcpToolsInAllToolCallbacks() {
        var springTool = createMockToolCallback("spring_tool");
        var mcpTool = createMockToolCallback("mcp_tool");
        toolRegistry.registerToolCallback("spring_tool", springTool);
        toolRegistry.registerFunctionCallback("mcp_tool", mcpTool);

        var all = toolRegistry.getAllToolCallbacks();
        assertEquals(2, all.size());
        assertTrue(all.contains(springTool));
        assertTrue(all.contains(mcpTool));
    }

    @Test
    void shouldIncludeMcpToolsInAllToolNames() {
        toolRegistry.registerAgentTool("agent_t", (a, c) -> io.agentrunr.core.AgentResult.of("ok"));
        toolRegistry.registerToolCallback("spring_t", createMockToolCallback("spring_t"));
        toolRegistry.registerFunctionCallback("mcp_t", createMockToolCallback("mcp_t"));

        var names = toolRegistry.getAllToolNames();
        assertEquals(3, names.size());
        assertTrue(names.contains("agent_t"));
        assertTrue(names.contains("spring_t"));
        assertTrue(names.contains("mcp_t"));
    }

    @Test
    void shouldReturnErrorForNonexistentMcpTool() {
        var result = toolRegistry.executeTool("mcp_nonexistent", "{}", new AgentContext());
        assertTrue(result.value().contains("not found"));
    }

    @Test
    void shouldHandleNullArgumentsInMcpTool() {
        var mcpTool = createMockToolCallback("mcp_null_args");
        when(mcpTool.call(null)).thenReturn("ok");
        toolRegistry.registerFunctionCallback("mcp_null_args", mcpTool);

        // ToolRegistry falls through to function callback for non-agent tools
        // null arguments are passed as-is to ToolCallback.call()
        assertDoesNotThrow(() ->
                toolRegistry.executeTool("mcp_null_args", null, new AgentContext()));
    }

    @Test
    void shouldHandleEmptyJsonArguments() {
        var mcpTool = createMockToolCallback("mcp_empty");
        when(mcpTool.call("{}")).thenReturn("{\"result\":\"done\"}");
        toolRegistry.registerFunctionCallback("mcp_empty", mcpTool);

        var result = toolRegistry.executeTool("mcp_empty", "{}", new AgentContext());
        assertEquals("{\"result\":\"done\"}", result.value());
    }

    @Test
    void shouldHandleComplexJsonArguments() {
        var mcpTool = createMockToolCallback("mcp_complex");
        String args = "{\"query\":\"meeting\",\"filters\":{\"date\":\"2026-03-01\"},\"limit\":10}";
        when(mcpTool.call(args)).thenReturn("{\"results\":[]}");
        toolRegistry.registerFunctionCallback("mcp_complex", mcpTool);

        var result = toolRegistry.executeTool("mcp_complex", args, new AgentContext());
        assertEquals("{\"results\":[]}", result.value());
    }

    @Test
    void shouldFindMcpToolsWhenMixedWithOtherTypes() {
        toolRegistry.registerAgentTool("handoff", (a, c) -> io.agentrunr.core.AgentResult.of("h"));
        toolRegistry.registerToolCallback("shell_exec", createMockToolCallback("shell_exec"));
        toolRegistry.registerFunctionCallback("mcp_calendar", createMockToolCallback("mcp_calendar"));
        toolRegistry.registerFunctionCallback("mcp_hubspot", createMockToolCallback("mcp_hubspot"));

        // Request mix of types
        var callbacks = toolRegistry.getToolCallbacks(
                List.of("handoff", "shell_exec", "mcp_calendar", "mcp_hubspot"));

        // Agent tools are not returned as ToolCallbacks
        assertEquals(3, callbacks.size());
    }

    private ToolCallback createMockToolCallback(String name) {
        var callback = mock(ToolCallback.class);
        var toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(toolDef);
        return callback;
    }
}
