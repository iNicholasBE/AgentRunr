package io.agentrunr.cron;

import io.agentrunr.security.InputSanitizer;
import io.agentrunr.setup.CredentialStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CronController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(InputSanitizer.class)
class CronControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CredentialStore credentialStore;

    @MockitoBean
    private CronService cronService;

    @Test
    void shouldCreateCronJob() throws Exception {
        var task = ScheduledTask.cron("abc", "Daily", "report", "0 9 * * *");
        when(cronService.addJob("Daily", "report", "0 9 * * *")).thenReturn(task);

        mockMvc.perform(post("/api/cron")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Daily", "message": "report", "cron": "0 9 * * *"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc"))
                .andExpect(jsonPath("$.name").value("Daily"));
    }

    @Test
    void shouldCreateIntervalJob() throws Exception {
        var task = ScheduledTask.interval("def", "Poller", "check", 300);
        when(cronService.addJob("Poller", "check", 300)).thenReturn(task);

        mockMvc.perform(post("/api/cron")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Poller", "message": "check", "intervalSeconds": 300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("def"));
    }

    @Test
    void shouldRejectEmptyName() throws Exception {
        mockMvc.perform(post("/api/cron")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "message": "test", "cron": "* * * * *"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldListJobs() throws Exception {
        when(cronService.listJobs()).thenReturn(List.of(
                ScheduledTask.cron("a", "A", "msg-a", "0 * * * *"),
                ScheduledTask.interval("b", "B", "msg-b", 60)
        ));

        mockMvc.perform(get("/api/cron"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void shouldDeleteJob() throws Exception {
        when(cronService.removeJob("abc")).thenReturn(true);

        mockMvc.perform(delete("/api/cron/abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));
    }

    @Test
    void shouldReturn404ForNonexistentDelete() throws Exception {
        when(cronService.removeJob("nope")).thenReturn(false);

        mockMvc.perform(delete("/api/cron/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldTriggerJob() throws Exception {
        doNothing().when(cronService).triggerNow("abc");

        mockMvc.perform(post("/api/cron/abc/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("triggered"));
    }

    @Test
    void shouldReturn404WhenTriggeringNonexistentJob() throws Exception {
        doThrow(new IllegalArgumentException("not found")).when(cronService).triggerNow("nope");

        mockMvc.perform(post("/api/cron/nope/run"))
                .andExpect(status().isNotFound());
    }
}
