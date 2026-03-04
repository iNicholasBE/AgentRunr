package io.agentrunr.core;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for agent tools. Manages tool callbacks and dispatches tool executions.
 *
 * <p>Tools can be registered as:</p>
 * <ul>
 *   <li>Spring AI ToolCallback instances (from @Tool annotations or MCP)</li>
 *   <li>Custom AgentTool implementations for agent-specific logic (e.g., handoffs)</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolCallback> toolCallbacks = new HashMap<>();
    private final Map<String, ToolCallback> functionCallbacks = new HashMap<>();
    private final Map<String, AgentTool> agentTools = new HashMap<>();
    private final Map<String, ToolCallback> agentToolCallbacks = new HashMap<>();
    private final ObjectMapper objectMapper;

    public ToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Registers a Spring AI tool callback.
     */
    public void registerToolCallback(String name, ToolCallback callback) {
        toolCallbacks.put(name, callback);
        log.debug("Registered tool callback: {}", name);
    }

    /**
     * Registers a Spring AI function callback (e.g., from MCP providers).
     */
    public void registerFunctionCallback(String name, ToolCallback callback) {
        functionCallbacks.put(name, callback);
        log.debug("Registered function callback: {}", name);
    }

    /**
     * Unregisters a function callback by name.
     */
    public void unregisterFunctionCallback(String name) {
        functionCallbacks.remove(name);
        log.debug("Unregistered function callback: {}", name);
    }

    /**
     * Unregisters multiple function callbacks by name.
     */
    public void unregisterFunctionCallbacks(List<String> names) {
        for (String name : names) {
            functionCallbacks.remove(name);
        }
        log.debug("Unregistered {} function callbacks", names.size());
    }

    /**
     * Registers a custom agent tool (supports handoffs and context variable updates).
     * The tool will NOT be visible to the LLM unless registered with a description via
     * {@link #registerAgentTool(String, String, String, AgentTool)}.
     */
    public void registerAgentTool(String name, AgentTool tool) {
        agentTools.put(name, tool);
        log.debug("Registered agent tool: {}", name);
    }

    /**
     * Registers a custom agent tool with description and JSON schema, making it
     * visible to the LLM as a callable function.
     *
     * @param name        tool name
     * @param description human-readable description for the LLM
     * @param inputSchema JSON Schema string for the tool's parameters
     * @param tool        the tool implementation
     */
    public void registerAgentTool(String name, String description, String inputSchema, AgentTool tool) {
        agentTools.put(name, tool);
        // Create a ToolCallback wrapper so the LLM can discover and call this tool
        ToolCallback callback = new AgentToolCallback(name, description, inputSchema, tool, this);
        agentToolCallbacks.put(name, callback);
        log.debug("Registered agent tool: {}", name);
    }

    /**
     * Returns tool callbacks for the given tool names.
     * Searches both @Tool callbacks and MCP function callbacks.
     */
    public List<ToolCallback> getToolCallbacks(List<String> toolNames) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (String name : toolNames) {
            ToolCallback cb = agentToolCallbacks.get(name);
            if (cb == null) cb = toolCallbacks.get(name);
            if (cb == null) cb = functionCallbacks.get(name);
            if (cb != null) {
                callbacks.add(cb);
            } else if (!agentTools.containsKey(name)) {
                log.warn("Tool '{}' not found in registry", name);
            }
        }
        return callbacks;
    }

    /**
     * Returns all registered tool callbacks (agent tools + @Tool + MCP).
     * Used when an agent has no explicit tool list, meaning "use everything available."
     */
    public List<ToolCallback> getAllToolCallbacks() {
        List<ToolCallback> all = new ArrayList<>(agentToolCallbacks.values());
        all.addAll(toolCallbacks.values());
        all.addAll(functionCallbacks.values());
        return all;
    }

    /**
     * Returns all registered tool names across all categories (agent tools, @Tool, MCP).
     */
    public List<String> getAllToolNames() {
        List<String> names = new ArrayList<>();
        names.addAll(agentTools.keySet());
        names.addAll(toolCallbacks.keySet());
        names.addAll(functionCallbacks.keySet());
        return names;
    }

    /**
     * Executes a tool by name with the given arguments.
     *
     * @param toolName  the tool to execute
     * @param arguments JSON string of arguments
     * @param context   the current agent context
     * @return the tool execution result
     */
    public AgentResult executeTool(String toolName, String arguments, AgentContext context) {
        // Check agent tools first (support handoffs)
        AgentTool agentTool = agentTools.get(toolName);
        if (agentTool != null) {
            try {
                Map<String, Object> args = parseArguments(arguments);
                return agentTool.execute(args, context);
            } catch (Exception e) {
                log.error("Error executing agent tool '{}': {}", toolName, e.getMessage(), e);
                return AgentResult.of("Error: " + e.getMessage());
            }
        }

        // Fall back to Spring AI tool callbacks
        ToolCallback callback = toolCallbacks.get(toolName);
        if (callback != null) {
            try {
                String result = callback.call(arguments);
                return AgentResult.of(result);
            } catch (Exception e) {
                log.error("Error executing tool '{}': {}", toolName, e.getMessage(), e);
                return AgentResult.of("Error: " + e.getMessage());
            }
        }

        // Fall back to function callbacks (MCP)
        ToolCallback functionCallback = functionCallbacks.get(toolName);
        if (functionCallback != null) {
            try {
                String result = functionCallback.call(arguments);
                return AgentResult.of(result);
            } catch (Exception e) {
                log.error("Error executing MCP tool '{}': {}", toolName, e.getMessage(), e);
                return AgentResult.of("Error: " + e.getMessage());
            }
        }

        log.error("Tool '{}' not found", toolName);
        return AgentResult.of("Error: Tool '" + toolName + "' not found.");
    }

    /**
     * Parses a JSON arguments string into a map.
     */
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", arguments, e);
            return Map.of();
        }
    }

    /**
     * Functional interface for agent-aware tools that can trigger handoffs
     * and update context variables.
     */
    @FunctionalInterface
    public interface AgentTool {
        /**
         * Executes the tool with parsed arguments and access to the agent context.
         *
         * @param arguments parsed arguments map
         * @param context   the current agent context
         * @return the tool result (may include handoff or context updates)
         */
        AgentResult execute(Map<String, Object> arguments, AgentContext context);
    }

    /**
     * Wraps an AgentTool as a Spring AI ToolCallback so the LLM can discover and invoke it.
     */
    static class AgentToolCallback implements ToolCallback {

        private final String name;
        private final String description;
        private final String inputSchema;
        private final AgentTool tool;
        private final ToolRegistry registry;

        AgentToolCallback(String name, String description, String inputSchema, AgentTool tool, ToolRegistry registry) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.tool = tool;
            this.registry = registry;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            try {
                Map<String, Object> args = registry.parseArguments(toolInput);
                AgentContext ctx = new AgentContext();
                AgentResult result = tool.execute(args, ctx);
                return result.value();
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
    }
}
