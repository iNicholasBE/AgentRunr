package io.agentrunr.channel;

import io.agentrunr.core.*;
import io.agentrunr.memory.FileMemoryStore;
import io.agentrunr.setup.CredentialStore;
import io.agentrunr.security.InputSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(InputSanitizer.class)
class ChatControllerStreamTest {

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
    void shouldReturnStreamingResponse() throws Exception {
        var agent = new Agent("Assistant", "You are helpful.");
        when(agentConfigurer.getDefaultAgent()).thenReturn(agent);

        when(agentRunner.runStreaming(any(Agent.class), anyList(), any(AgentContext.class), anyInt()))
                .thenReturn(Flux.just("Hello", " ", "World"));

        var mvcResult = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "messages": [{"role": "user", "content": "Hi"}],
                                    "contextVariables": {},
                                    "maxTurns": 10
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());
    }

    @Test
    void shouldHandleEmptyStream() throws Exception {
        var agent = new Agent("Assistant", "You are helpful.");
        when(agentConfigurer.getDefaultAgent()).thenReturn(agent);

        when(agentRunner.runStreaming(any(Agent.class), anyList(), any(AgentContext.class), anyInt()))
                .thenReturn(Flux.empty());

        var mvcResult = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "messages": [{"role": "user", "content": "Hi"}],
                                    "contextVariables": {},
                                    "maxTurns": 10
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());
    }
}
