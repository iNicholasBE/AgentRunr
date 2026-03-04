package io.agentrunr.channel;

import io.agentrunr.core.*;
import io.agentrunr.memory.FileMemoryStore;
import io.agentrunr.setup.CredentialStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@org.springframework.context.annotation.Import(io.agentrunr.security.InputSanitizer.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CredentialStore credentialStore;

    @MockitoBean
    private AgentRunner agentRunner;

    @MockitoBean
    private AgentConfigurer agentConfigurer;

    @MockitoBean
    private FileMemoryStore memoryStore;

    @MockitoBean
    private io.agentrunr.memory.SQLiteMemoryStore sqliteMemory;

    @MockitoBean
    private io.agentrunr.memory.MemoryAutoSaver memoryAutoSaver;

    @Test
    void shouldReturnHealthStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("agentrunr"));
    }

    @Test
    void shouldHandleChatRequest() throws Exception {
        var agent = new Agent("Assistant", "You are helpful.");
        when(agentConfigurer.getDefaultAgent()).thenReturn(agent);

        var response = new AgentResponse(
                List.of(
                        ChatMessage.user("Hello"),
                        ChatMessage.assistant("Hi there!", "Assistant")
                ),
                agent,
                Map.of()
        );
        when(agentRunner.run(any(Agent.class), anyList(), any(AgentContext.class), anyInt()))
                .thenReturn(response);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "messages": [{"role": "user", "content": "Hello"}],
                                    "contextVariables": {},
                                    "maxTurns": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("Hi there!"))
                .andExpect(jsonPath("$.agent").value("Assistant"));
    }
}
