package io.agentrunr.tool;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.core.AgentResult;
import io.agentrunr.core.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionServiceTest {

    private ToolRegistry toolRegistry;
    private ToolResultStore resultStore;
    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(new ObjectMapper());
        resultStore = new ToolResultStore();
        service = new ToolExecutionService(toolRegistry, resultStore);
    }

    @Test
    void shouldExecuteToolAndStoreResult() {
        toolRegistry.registerAgentTool("echo", (args, ctx) ->
                AgentResult.of("Echo: " + args.getOrDefault("msg", "empty")));

        service.executeToolJob("track-1", "echo", "{\"msg\":\"hello\"}", Map.of());

        assertTrue(resultStore.isComplete("track-1"));
        assertEquals("Echo: hello", resultStore.get("track-1").value());
    }

    @Test
    void shouldHandleToolError() {
        toolRegistry.registerAgentTool("fail", (args, ctx) -> {
            throw new RuntimeException("Boom!");
        });

        service.executeToolJob("track-2", "fail", "{}", Map.of());

        assertTrue(resultStore.isComplete("track-2"));
        assertTrue(resultStore.get("track-2").value().contains("Error"));
    }

    @Test
    void shouldPassContextVariables() {
        toolRegistry.registerAgentTool("ctx_tool", (args, ctx) ->
                AgentResult.of("User: " + ctx.get("user_name", "unknown")));

        service.executeToolJob("track-3", "ctx_tool", "{}", Map.of("user_name", "Alice"));

        assertEquals("User: Alice", resultStore.get("track-3").value());
    }
}
