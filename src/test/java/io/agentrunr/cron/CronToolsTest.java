package io.agentrunr.cron;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.channel.AgentConfigurer;
import io.agentrunr.channel.ChannelRegistry;
import io.agentrunr.core.AgentContext;
import io.agentrunr.core.AgentResult;
import io.agentrunr.core.AgentRunner;
import io.agentrunr.core.ToolRegistry;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.JobParameter;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.RecurringJobsResult;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the full scheduling flow: tool call → file written → JobRunr job registered.
 * Simulates various parameter names that different LLMs might use.
 */
class CronToolsTest {

    private ToolRegistry toolRegistry;
    private JobScheduler jobScheduler;
    private StorageProvider storageProvider;
    private CronService cronService;
    private AgentContext ctx;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(new ObjectMapper());
        jobScheduler = mock(JobScheduler.class);
        storageProvider = mock(StorageProvider.class);
        var agentRunner = mock(AgentRunner.class);
        var agentConfigurer = mock(AgentConfigurer.class);
        var channelRegistry = new ChannelRegistry();
        var cronJob = new CronJob(agentRunner, agentConfigurer, channelRegistry);
        cronService = new CronService(jobScheduler, storageProvider, cronJob, tempDir.toString());
        new CronTools(toolRegistry, cronService, new ObjectMapper()).registerTools();
        ctx = new AgentContext();
    }

    // ---- schedule_task with different param names ----

    @Test
    void shouldScheduleWithStandardParams() throws IOException {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "weather-check",
                "message", "Check weather in Antwerp",
                "interval_seconds", 60
        ));

        assertScheduleSuccess(result, "weather-check", "Check weather in Antwerp");
    }

    @Test
    void shouldScheduleWhenLlmSendsPromptInsteadOfMessage() throws IOException {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "weather-check",
                "prompt", "Check weather in Antwerp",
                "interval_seconds", 60
        ));

        assertScheduleSuccess(result, "weather-check", "Check weather in Antwerp");
    }

    @Test
    void shouldScheduleWhenLlmSendsDescriptionAndContent() throws IOException {
        AgentResult result = executeTool("schedule_task", Map.of(
                "description", "Weather Monitor",
                "content", "Check weather in Antwerp and report in 1 sentence",
                "interval_seconds", 60
        ));

        assertScheduleSuccess(result, "weather-monitor", "Check weather in Antwerp and report in 1 sentence");
    }

    @Test
    void shouldScheduleWhenLlmSendsTaskAsMessage() throws IOException {
        AgentResult result = executeTool("schedule_task", Map.of(
                "title", "Antwerp Weather",
                "task", "Check the weather forecast for Antwerp, Belgium",
                "interval_seconds", 120
        ));

        assertScheduleSuccess(result, "antwerp-weather", "Check the weather forecast for Antwerp, Belgium");
    }

    @Test
    void shouldScheduleWhenLlmSendsNameAndDescriptionWithJunkParams() throws IOException {
        // Real-world case: LLM sent name + description + schedule_type + task_data
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "antwerp_weather_file_logger",
                "description", "Check Antwerp weather every minute and log to file",
                "schedule_type", "INTERVAL",
                "interval_seconds", 60,
                "task_data", Map.of("action", "check_weather_and_log")
        ));

        assertScheduleSuccess(result, "antwerp-weather-file-logger",
                "Check Antwerp weather every minute and log to file");
    }

    @Test
    void shouldScheduleWithCronExpression() throws IOException {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "daily-report",
                "message", "Generate daily report",
                "cron", "0 9 * * *"
        ));

        assertScheduleSuccess(result, "daily-report", "Generate daily report");
        assertTrue(result.value().contains("cron: 0 9 * * *"));
    }

    @Test
    void shouldScheduleWithCronExpressionAlias() throws IOException {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "daily-report",
                "message", "Generate daily report",
                "cron_expression", "0 9 * * *"
        ));

        assertScheduleSuccess(result, "daily-report", "Generate daily report");
    }

    @Test
    void shouldFailWhenNoMessageProvided() {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "broken-task",
                "interval_seconds", 60
        ));

        assertTrue(result.value().contains("Error"));
        assertTrue(result.value().contains("'message' is required"));
    }

    @Test
    void shouldFailWhenNoScheduleProvided() {
        AgentResult result = executeTool("schedule_task", Map.of(
                "name", "broken-task",
                "message", "Do something"
        ));

        assertTrue(result.value().contains("Error"));
        assertTrue(result.value().contains("'cron'"));
    }

    @Test
    void shouldFailWhenOnlyIrrelevantParamsSent() {
        // Simulate an LLM that sends completely wrong params
        AgentResult result = executeTool("schedule_task", Map.of(
                "schedule_type", "INTERVAL",
                "task_data", Map.of("action", "check_weather")
        ));

        assertTrue(result.value().contains("Error"), "Should fail with error, got: " + result.value());
    }

    // ---- list_scheduled_tasks ----

    @Test
    void shouldListTasksWithFileContent() throws IOException {
        Files.writeString(tempDir.resolve("weather.md"), "Check Antwerp weather");

        var result = new RecurringJobsResult(List.of(
                makeRecurringJob("agent-cron-weather", "weather", "*/1 * * * *")
        ));
        when(storageProvider.getRecurringJobs()).thenReturn(result);

        AgentResult listResult = executeTool("list_scheduled_tasks", Map.of());

        assertTrue(listResult.value().contains("weather"));
        assertTrue(listResult.value().contains("Check Antwerp weather"));
    }

    @Test
    void shouldReturnEmptyWhenNoTasks() {
        when(storageProvider.getRecurringJobs()).thenReturn(new RecurringJobsResult());

        AgentResult result = executeTool("list_scheduled_tasks", Map.of());

        assertEquals("No scheduled tasks.", result.value());
    }

    // ---- cancel_scheduled_task ----

    @Test
    void shouldCancelWithTaskId() throws IOException {
        Files.writeString(tempDir.resolve("old-task.md"), "content");
        when(storageProvider.deleteRecurringJob("agent-cron-old-task")).thenReturn(1);

        AgentResult result = executeTool("cancel_scheduled_task", Map.of("task_id", "old-task"));

        assertTrue(result.value().contains("Cancelled"));
        assertFalse(Files.exists(tempDir.resolve("old-task.md")));
    }

    @Test
    void shouldCancelWithIdAlias() throws IOException {
        Files.writeString(tempDir.resolve("task-x.md"), "content");
        when(storageProvider.deleteRecurringJob("agent-cron-task-x")).thenReturn(1);

        AgentResult result = executeTool("cancel_scheduled_task", Map.of("id", "task-x"));

        assertTrue(result.value().contains("Cancelled"));
    }

    // ---- helper methods ----

    private AgentResult executeTool(String toolName, Map<String, Object> args) {
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return toolRegistry.executeTool(toolName, json, ctx);
    }

    private void assertScheduleSuccess(AgentResult result, String expectedId, String expectedContent) throws IOException {
        assertFalse(result.value().startsWith("Error"), "Expected success but got: " + result.value());
        assertTrue(result.value().contains("Scheduled task"), "Expected 'Scheduled task' in: " + result.value());

        // Verify task file was written
        Path taskFile = tempDir.resolve(expectedId + ".md");
        assertTrue(Files.exists(taskFile), "Task file should exist: " + taskFile);
        assertEquals(expectedContent, Files.readString(taskFile));

        // Verify JobRunr was called with IoC lambda (resolves bean from Spring context)
        verify(jobScheduler, atLeastOnce()).scheduleRecurrently(
                eq("agent-cron-" + expectedId), anyString(), any(org.jobrunr.jobs.lambdas.IocJobLambda.class));
    }

    private RecurringJob makeRecurringJob(String id, String taskName, String cron) {
        var details = new JobDetails(
                CronJob.class.getName(),
                null,
                "executeTask",
                List.of(new JobParameter(String.class, taskName))
        );
        return new RecurringJob(id, details, cron, "UTC", RecurringJob.CreatedBy.API);
    }
}
