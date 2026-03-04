package io.agentrunr.cron;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.channel.AgentConfigurer;
import io.agentrunr.channel.ChannelRegistry;
import io.agentrunr.core.AgentContext;
import io.agentrunr.core.AgentResult;
import io.agentrunr.core.AgentRunner;
import io.agentrunr.core.ToolRegistry;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration test that uses a REAL InMemoryStorageProvider and JobScheduler
 * to verify jobs actually end up in JobRunr's recurring jobs storage.
 *
 * Unlike CronToolsTest (which mocks JobScheduler), this test catches the exact
 * bug we had: lambdas that fail to serialize and silently don't persist.
 */
class CronSchedulingIntegrationTest {

    private ToolRegistry toolRegistry;
    private StorageProvider storageProvider;
    private CronService cronService;
    private AgentContext ctx;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        toolRegistry = new ToolRegistry(objectMapper);

        // Real JobRunr components — no mocks
        InMemoryStorageProvider inMemory = new InMemoryStorageProvider();
        inMemory.setJobMapper(new JobMapper(new JacksonJsonMapper()));
        storageProvider = inMemory;
        JobScheduler jobScheduler = new JobScheduler(storageProvider);

        var agentRunner = mock(AgentRunner.class);
        var agentConfigurer = mock(AgentConfigurer.class);
        var channelRegistry = new ChannelRegistry();
        var cronJob = new CronJob(agentRunner, agentConfigurer, channelRegistry);
        cronService = new CronService(jobScheduler, storageProvider, cronJob, tempDir.toString());
        new CronTools(toolRegistry, cronService, objectMapper).registerTools();
        ctx = new AgentContext();
    }

    // ---- Verify jobs actually persist in storage ----

    @Test
    void scheduledCronJobShouldExistInStorageProvider() {
        executeTool("schedule_task", Map.of(
                "name", "weather-check",
                "message", "Check weather in Antwerp",
                "cron", "0 9 * * *"
        ));

        List<RecurringJob> jobs = storageProvider.getRecurringJobs();
        assertEquals(1, jobs.size(), "Expected 1 recurring job in storage");

        RecurringJob job = jobs.get(0);
        assertEquals("agent-cron-weather-check", job.getId());
        assertEquals("0 9 * * *", job.getScheduleExpression());
    }

    @Test
    void scheduledIntervalJobShouldExistInStorageProvider() {
        executeTool("schedule_task", Map.of(
                "name", "poller",
                "message", "Check status every minute",
                "interval_seconds", 60
        ));

        List<RecurringJob> jobs = storageProvider.getRecurringJobs();
        assertEquals(1, jobs.size(), "Expected 1 recurring job in storage");

        RecurringJob job = jobs.get(0);
        assertEquals("agent-cron-poller", job.getId());
        assertEquals("*/1 * * * *", job.getScheduleExpression());
    }

    @Test
    void jobParametersShouldContainTaskName() {
        executeTool("schedule_task", Map.of(
                "name", "daily-report",
                "message", "Generate daily report",
                "cron", "0 9 * * *"
        ));

        RecurringJob job = storageProvider.getRecurringJobs().get(0);
        Object[] params = job.getJobDetails().getJobParameterValues();
        assertEquals(1, params.length, "Should have exactly 1 parameter (task name)");
        assertEquals("daily-report", params[0], "Parameter should be the slugified task name");
    }

    @Test
    void taskFileShouldBeWrittenWithCorrectContent() throws IOException {
        executeTool("schedule_task", Map.of(
                "name", "weather-check",
                "message", "Check weather in Antwerp",
                "cron", "0 9 * * *"
        ));

        Path taskFile = tempDir.resolve("weather-check.md");
        assertTrue(Files.exists(taskFile), "Task file should exist at: " + taskFile);
        assertEquals("Check weather in Antwerp", Files.readString(taskFile));
    }

    @Test
    void multipleJobsShouldAllPersist() {
        executeTool("schedule_task", Map.of(
                "name", "task-a", "message", "First task", "cron", "0 9 * * *"
        ));
        executeTool("schedule_task", Map.of(
                "name", "task-b", "message", "Second task", "cron", "0 18 * * *"
        ));
        executeTool("schedule_task", Map.of(
                "name", "task-c", "message", "Third task", "interval_seconds", 300
        ));

        List<RecurringJob> jobs = storageProvider.getRecurringJobs();
        assertEquals(3, jobs.size(), "All 3 jobs should be in storage");

        List<String> jobIds = jobs.stream().map(RecurringJob::getId).sorted().toList();
        assertEquals(List.of("agent-cron-task-a", "agent-cron-task-b", "agent-cron-task-c"), jobIds);
    }

    // ---- Verify LLM alias params also persist ----

    @Test
    void jobScheduledWithPromptAliasShouldPersist() {
        executeTool("schedule_task", Map.of(
                "name", "weather",
                "prompt", "Check the weather",
                "interval_seconds", 60
        ));

        List<RecurringJob> jobs = storageProvider.getRecurringJobs();
        assertEquals(1, jobs.size());
        assertEquals("agent-cron-weather", jobs.get(0).getId());

        // Verify the file has the right content (from "prompt" alias)
        assertTrue(Files.exists(tempDir.resolve("weather.md")));
    }

    @Test
    void jobScheduledWithDescriptionAndContentShouldPersist() throws IOException {
        executeTool("schedule_task", Map.of(
                "description", "Weather Monitor",
                "content", "Check weather in Antwerp",
                "interval_seconds", 120
        ));

        List<RecurringJob> jobs = storageProvider.getRecurringJobs();
        assertEquals(1, jobs.size());
        assertEquals("agent-cron-weather-monitor", jobs.get(0).getId());
        assertEquals("Check weather in Antwerp", Files.readString(tempDir.resolve("weather-monitor.md")));
    }

    @Test
    void jobScheduledWithNameAndDescriptionShouldUseDescriptionAsMessage() throws IOException {
        // Exact params an LLM sent in production — name + description + interval_seconds + junk
        executeTool("schedule_task", Map.of(
                "name", "antwerp_weather_file_logger",
                "description", "Check Antwerp weather every minute and log to file",
                "schedule_type", "INTERVAL",
                "interval_seconds", 60,
                "task_data", Map.of("action", "check_weather_and_log", "location", "Antwerp, Belgium")
        ));

        List<RecurringJob> jobs = storageProvider.getRecurringJobs();
        assertEquals(1, jobs.size(), "Job should persist even with junk params mixed in");

        RecurringJob job = jobs.get(0);
        assertEquals("agent-cron-antwerp-weather-file-logger", job.getId());
        assertEquals("Check Antwerp weather every minute and log to file",
                Files.readString(tempDir.resolve("antwerp-weather-file-logger.md")));
    }

    @Test
    void jobScheduledWithTitleAndTaskShouldPersist() throws IOException {
        executeTool("schedule_task", Map.of(
                "title", "Antwerp Weather",
                "task", "Check the weather forecast for Antwerp",
                "cron", "*/5 * * * *"
        ));

        List<RecurringJob> jobs = storageProvider.getRecurringJobs();
        assertEquals(1, jobs.size());

        RecurringJob job = jobs.get(0);
        assertEquals("agent-cron-antwerp-weather", job.getId());
        assertEquals("*/5 * * * *", job.getScheduleExpression());
        assertEquals("Check the weather forecast for Antwerp",
                Files.readString(tempDir.resolve("antwerp-weather.md")));
    }

    // ---- Cancel should remove from storage ----

    @Test
    void cancelShouldRemoveJobFromStorage() {
        executeTool("schedule_task", Map.of(
                "name", "to-cancel", "message", "temp task", "cron", "0 * * * *"
        ));
        assertEquals(1, storageProvider.getRecurringJobs().size());

        executeTool("cancel_scheduled_task", Map.of("task_id", "to-cancel"));

        assertEquals(0, storageProvider.getRecurringJobs().size(), "Job should be removed from storage");
        assertFalse(Files.exists(tempDir.resolve("to-cancel.md")), "Task file should be deleted");
    }

    @Test
    void cancelShouldOnlyRemoveTargetJob() {
        executeTool("schedule_task", Map.of(
                "name", "keep-me", "message", "stay", "cron", "0 9 * * *"
        ));
        executeTool("schedule_task", Map.of(
                "name", "remove-me", "message", "go", "cron", "0 18 * * *"
        ));
        assertEquals(2, storageProvider.getRecurringJobs().size());

        executeTool("cancel_scheduled_task", Map.of("task_id", "remove-me"));

        List<RecurringJob> remaining = storageProvider.getRecurringJobs();
        assertEquals(1, remaining.size(), "Only one job should remain");
        assertEquals("agent-cron-keep-me", remaining.get(0).getId());
    }

    // ---- List should reflect actual storage ----

    @Test
    void listShouldShowJobsFromStorage() {
        executeTool("schedule_task", Map.of(
                "name", "task-1", "message", "First", "cron", "0 9 * * *"
        ));
        executeTool("schedule_task", Map.of(
                "name", "task-2", "message", "Second", "interval_seconds", 300
        ));

        AgentResult result = executeTool("list_scheduled_tasks", Map.of());

        assertTrue(result.value().contains("task-1"), "Should list task-1");
        assertTrue(result.value().contains("task-2"), "Should list task-2");
        assertTrue(result.value().contains("First"), "Should show task-1's message from file");
        assertTrue(result.value().contains("Second"), "Should show task-2's message from file");
    }

    @Test
    void listAfterCancelShouldNotShowRemovedJob() {
        executeTool("schedule_task", Map.of(
                "name", "visible", "message", "I exist", "cron", "0 9 * * *"
        ));
        executeTool("schedule_task", Map.of(
                "name", "gone", "message", "I was removed", "cron", "0 18 * * *"
        ));

        executeTool("cancel_scheduled_task", Map.of("task_id", "gone"));

        AgentResult result = executeTool("list_scheduled_tasks", Map.of());
        assertTrue(result.value().contains("visible"));
        assertFalse(result.value().contains("gone"), "Cancelled task should not appear in list");
    }

    // ---- Error cases should NOT create jobs ----

    @Test
    void missingMessageShouldNotCreateJob() {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "broken", "interval_seconds", 60
        ));

        assertTrue(result.value().contains("Error"));
        assertEquals(0, storageProvider.getRecurringJobs().size(),
                "No job should be created when message is missing");
    }

    @Test
    void missingScheduleShouldNotCreateJob() {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "broken", "message", "do something"
        ));

        assertTrue(result.value().contains("Error"));
        assertEquals(0, storageProvider.getRecurringJobs().size(),
                "No job should be created when schedule is missing");
    }

    // ---- helper ----

    private AgentResult executeTool(String toolName, Map<String, Object> args) {
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return toolRegistry.executeTool(toolName, json, ctx);
    }
}
