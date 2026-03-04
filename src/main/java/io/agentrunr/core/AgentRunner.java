package io.agentrunr.core;

import io.agentrunr.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/**
 * The agent execution engine, inspired by OpenAI Swarm's core run loop.
 *
 * <p>Executes an agent by repeatedly:</p>
 * <ol>
 *   <li>Sending the conversation to the LLM with available tools</li>
 *   <li>Processing any tool calls the LLM makes</li>
 *   <li>Handling agent handoffs when a tool returns a new agent</li>
 *   <li>Looping until the LLM responds without tool calls, or max turns is reached</li>
 * </ol>
 *
 * <p>Supports multiple LLM providers (OpenAI, Ollama, Anthropic) via the ModelRouter.
 * Each agent can specify its preferred model using provider prefixes:</p>
 * <ul>
 *   <li>{@code "gpt-4.1"} — OpenAI (default)</li>
 *   <li>{@code "ollama:llama3"} — local Ollama</li>
 *   <li>{@code "anthropic:claude-sonnet-4-20250514"} — Anthropic</li>
 * </ul>
 *
 * <p>This is the Java equivalent of Swarm's {@code client.run()} method.</p>
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final int DEFAULT_MAX_TURNS = 10;

    private final ModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final SystemPromptBuilder systemPromptBuilder;

    public AgentRunner(ModelRouter modelRouter, ToolRegistry toolRegistry, SystemPromptBuilder systemPromptBuilder) {
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.systemPromptBuilder = systemPromptBuilder;
    }

    /**
     * Runs the agent with user messages and default settings.
     *
     * @param agent    the agent to run
     * @param messages the conversation messages
     * @return the agent response
     */
    public AgentResponse run(Agent agent, List<ChatMessage> messages) {
        return run(agent, messages, new AgentContext(), DEFAULT_MAX_TURNS);
    }

    /**
     * Runs the agent with full control over context and turn limits.
     *
     * @param agent    the starting agent
     * @param messages the conversation messages
     * @param context  shared context variables
     * @param maxTurns maximum number of LLM call turns before stopping
     * @return the agent response with final messages, active agent, and context
     */
    public AgentResponse run(Agent agent, List<ChatMessage> messages, AgentContext context, int maxTurns) {
        Agent activeAgent = agent;
        List<ChatMessage> history = new ArrayList<>(messages);
        int turns = 0;

        while (turns < maxTurns) {
            turns++;
            log.debug("Turn {}/{} with agent '{}'", turns, maxTurns, activeAgent.name());

            // Resolve model for the active agent
            ModelRouter.ResolvedModel resolved = modelRouter.resolve(activeAgent.resolvedModel());
            log.debug("Using provider '{}' with model '{}'", resolved.provider(), resolved.modelName());

            // Extract latest user message for memory context
            String latestUserMessage = history.stream()
                    .filter(m -> m.role() == ChatMessage.Role.USER)
                    .reduce((a, b) -> b)
                    .map(ChatMessage::content)
                    .orElse("");

            // Build the prompt with system instructions, identity, and memory context
            String systemInstructions = enrichInstructions(activeAgent.resolveInstructions(context.toMap()), activeAgent, latestUserMessage);
            List<Message> springMessages = toSpringMessages(systemInstructions, history);

            // Resolve tools: explicit list if specified, otherwise all registered tools
            List<String> tools = activeAgent.toolNames();
            var toolCallbackList = (tools != null && !tools.isEmpty())
                    ? toolRegistry.getToolCallbacks(tools)
                    : toolRegistry.getAllToolCallbacks();
            if (!toolCallbackList.isEmpty()) {
                log.debug("Passing {} tool callbacks to LLM: {}", toolCallbackList.size(),
                        toolCallbackList.stream().map(tc -> tc.getToolDefinition().name()).toList());
            }

            // Build chat options with tool callbacks embedded
            ChatOptions chatOptions = ToolCallingChatOptions.builder()
                    .model(resolved.modelName())
                    .maxTokens(4096)
                    .toolCallbacks(toolCallbackList)
                    .internalToolExecutionEnabled(true)
                    .build();

            // Configure and call LLM
            ChatClient.ChatClientRequestSpec requestSpec = ChatClient.builder(resolved.chatModel())
                    .build()
                    .prompt()
                    .messages(springMessages)
                    .options(chatOptions);

            // Call the LLM — Spring AI handles tool calls internally when internalToolExecutionEnabled=true
            ChatResponse response = requestSpec.call().chatResponse();
            String assistantContent = response.getResult().getOutput().getText();

            // Check for tool calls in the response
            var toolCalls = response.getResult().getOutput().getToolCalls();

            if (toolCalls == null || toolCalls.isEmpty()) {
                // No tool calls — agent is done speaking
                history.add(ChatMessage.assistant(assistantContent, activeAgent.name()));
                log.debug("Agent '{}' completed with response", activeAgent.name());
                break;
            }

            // Process tool calls
            history.add(ChatMessage.assistant(assistantContent, activeAgent.name()));

            for (var toolCall : toolCalls) {
                log.debug("Agent '{}' calling tool: {}", activeAgent.name(), toolCall.name());

                // Execute the tool via the registry
                AgentResult result = toolRegistry.executeTool(
                        toolCall.name(),
                        toolCall.arguments(),
                        context
                );

                // Merge any context variable updates
                context.merge(result.contextVariables());

                // Add tool result to history
                history.add(ChatMessage.toolResult(toolCall.id(), result.value()));

                // Handle agent handoff
                if (result.handoffAgent() != null) {
                    log.info("Handoff from '{}' to '{}'", activeAgent.name(), result.handoffAgent().name());
                    activeAgent = result.handoffAgent();
                }
            }
        }

        if (turns >= maxTurns) {
            log.warn("Agent '{}' reached max turns ({})", activeAgent.name(), maxTurns);
        }

        return new AgentResponse(history, activeAgent, context.toMap());
    }

    /**
     * Runs the agent in streaming mode, emitting tokens as they arrive.
     * Falls back to non-streaming if the model doesn't support streaming.
     *
     * @param agent    the agent to run
     * @param messages the conversation messages
     * @return a Flux of string tokens as they are generated
     */
    public Flux<String> runStreaming(Agent agent, List<ChatMessage> messages) {
        return runStreaming(agent, messages, new AgentContext(), DEFAULT_MAX_TURNS);
    }

    /**
     * Runs the agent in streaming mode with full control.
     *
     * @param agent    the starting agent
     * @param messages the conversation messages
     * @param context  shared context variables
     * @param maxTurns maximum number of LLM call turns
     * @return a Flux of string tokens
     */
    public Flux<String> runStreaming(Agent agent, List<ChatMessage> messages, AgentContext context, int maxTurns) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        Thread.startVirtualThread(() -> {
            try {
                Agent activeAgent = agent;
                List<ChatMessage> history = new ArrayList<>(messages);
                int turns = 0;

                while (turns < maxTurns) {
                    turns++;
                    log.debug("Stream turn {}/{} with agent '{}'", turns, maxTurns, activeAgent.name());

                    ModelRouter.ResolvedModel resolved = modelRouter.resolve(activeAgent.resolvedModel());

                    // Extract latest user message for memory context
                    String latestUserMessage = history.stream()
                            .filter(m -> m.role() == ChatMessage.Role.USER)
                            .reduce((a, b) -> b)
                            .map(ChatMessage::content)
                            .orElse("");

                    String systemInstructions = enrichInstructions(activeAgent.resolveInstructions(context.toMap()), activeAgent, latestUserMessage);
                    List<Message> springMessages = toSpringMessages(systemInstructions, history);

                    List<String> streamTools = activeAgent.toolNames();
                    var streamToolCallbackList = (streamTools != null && !streamTools.isEmpty())
                            ? toolRegistry.getToolCallbacks(streamTools)
                            : toolRegistry.getAllToolCallbacks();

                    ChatOptions streamChatOptions = ToolCallingChatOptions.builder()
                            .model(resolved.modelName())
                            .maxTokens(4096)
                            .toolCallbacks(streamToolCallbackList)
                            .internalToolExecutionEnabled(true)
                            .build();

                    ChatClient.ChatClientRequestSpec requestSpec = ChatClient.builder(resolved.chatModel())
                            .build()
                            .prompt()
                            .messages(springMessages)
                            .options(streamChatOptions);

                    // Try streaming
                    try {
                        var streamResponse = requestSpec.stream().chatResponse();
                        var content = new StringBuilder();

                        streamResponse.doOnNext(chunk -> {
                            if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                                String text = chunk.getResult().getOutput().getText();
                                if (text != null && !text.isEmpty()) {
                                    content.append(text);
                                    sink.tryEmitNext(text);
                                }
                            }
                        }).doOnComplete(() -> {
                            // done for this turn
                        }).blockLast();

                        // After streaming completes, check for tool calls in the full response
                        // Streaming mode typically doesn't include tool calls inline,
                        // so we treat this as a complete response
                        history.add(ChatMessage.assistant(content.toString(), activeAgent.name()));
                        break;

                    } catch (Exception e) {
                        // Fall back to non-streaming
                        log.debug("Streaming not available, falling back to non-streaming: {}", e.getMessage());
                        ChatResponse response = requestSpec.call().chatResponse();
                        String assistantContent = response.getResult().getOutput().getText();

                        var toolCalls = response.getResult().getOutput().getToolCalls();

                        if (toolCalls == null || toolCalls.isEmpty()) {
                            history.add(ChatMessage.assistant(assistantContent, activeAgent.name()));
                            if (assistantContent != null) {
                                sink.tryEmitNext(assistantContent);
                            }
                            break;
                        }

                        // Process tool calls (same as non-streaming)
                        history.add(ChatMessage.assistant(assistantContent, activeAgent.name()));
                        for (var toolCall : toolCalls) {
                            AgentResult result = toolRegistry.executeTool(
                                    toolCall.name(), toolCall.arguments(), context);
                            context.merge(result.contextVariables());
                            history.add(ChatMessage.toolResult(toolCall.id(), result.value()));
                            if (result.handoffAgent() != null) {
                                activeAgent = result.handoffAgent();
                            }
                        }
                    }
                }

                sink.tryEmitComplete();
            } catch (Exception e) {
                log.error("Streaming error", e);
                sink.tryEmitError(e);
            }
        });

        return sink.asFlux();
    }

    /**
     * Enriches system instructions with identity, memory context, tools, and safety rules.
     * Uses SystemPromptBuilder when available, falls back to basic enrichment.
     */
    private String enrichInstructions(String baseInstructions, Agent agent, String userMessage) {
        if (systemPromptBuilder != null) {
            return systemPromptBuilder.build(baseInstructions, agent.name(), userMessage);
        }

        // Fallback: basic enrichment without memory or identity
        var sb = new StringBuilder(baseInstructions);
        sb.append("\n\nYour name is ").append(agent.name()).append(".");

        List<String> allToolNames = toolRegistry.getAllToolNames();
        if (!allToolNames.isEmpty()) {
            sb.append("\n\nYou have the following tools available: ").append(String.join(", ", allToolNames)).append(".");
            sb.append(" Use them proactively when they can help answer the user's question.");
        }
        return sb.toString();
    }

    /**
     * Converts our ChatMessage list to Spring AI Message list.
     */
    private List<Message> toSpringMessages(String systemInstructions, List<ChatMessage> messages) {
        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(systemInstructions));

        for (ChatMessage msg : messages) {
            switch (msg.role()) {
                case USER -> springMessages.add(new UserMessage(msg.content()));
                case ASSISTANT -> springMessages.add(new AssistantMessage(msg.content()));
                case SYSTEM -> springMessages.add(new SystemMessage(msg.content()));
                case TOOL -> {
                    // Tool results are handled by Spring AI's internal mechanism
                    // We include them as user context for the next turn
                    springMessages.add(new UserMessage("[Tool result] " + msg.content()));
                }
            }
        }

        return springMessages;
    }
}
