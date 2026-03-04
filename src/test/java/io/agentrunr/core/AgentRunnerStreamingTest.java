package io.agentrunr.core;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.config.ModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentRunnerStreamingTest {

    private AgentRunner agentRunner;
    private ChatModel chatModel;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        var modelRouter = mock(ModelRouter.class);
        toolRegistry = new ToolRegistry(new ObjectMapper());

        when(modelRouter.resolve(any())).thenReturn(
                new ModelRouter.ResolvedModel(chatModel, "test-model", "test"));

        agentRunner = new AgentRunner(modelRouter, toolRegistry, null);
    }

    @Test
    void shouldStreamFallbackToNonStreaming() {
        // Set up a non-streaming response (streaming will fail, fallback works)
        var assistantMessage = new AssistantMessage("Hello from fallback!");
        var generation = new Generation(assistantMessage);
        var chatResponse = new ChatResponse(List.of(generation));

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        var agent = new Agent("Test", "You are a test agent.");
        var messages = List.of(ChatMessage.user("Hi"));

        Flux<String> stream = agentRunner.runStreaming(agent, messages);

        StepVerifier.create(stream)
                .expectNext("Hello from fallback!")
                .verifyComplete();
    }

    @Test
    void shouldHandleStreamingError() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM unavailable"));

        var agent = new Agent("Test", "You are a test agent.");
        var messages = List.of(ChatMessage.user("Hi"));

        Flux<String> stream = agentRunner.runStreaming(agent, messages);

        StepVerifier.create(stream)
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void shouldRunStreamingWithDefaultParams() {
        var assistantMessage = new AssistantMessage("Streamed!");
        var generation = new Generation(assistantMessage);
        var chatResponse = new ChatResponse(List.of(generation));

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        var agent = new Agent("Test", "You are a test agent.");
        var messages = List.of(ChatMessage.user("Hi"));

        // The two-arg version should work too
        Flux<String> stream = agentRunner.runStreaming(agent, messages);

        StepVerifier.create(stream)
                .expectNext("Streamed!")
                .verifyComplete();
    }
}
