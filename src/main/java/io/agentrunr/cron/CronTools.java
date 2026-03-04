package io.agentrunr.cron;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.core.AgentResult;
import io.agentrunr.core.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Registers cron/scheduling tools with the ToolRegistry so the LLM
 * can schedule tasks via natural language.
 */
@Component
public class CronTools {

    private static final Logger log = LoggerFactory.getLogger(CronTools.class);

    private final ToolRegistry toolRegistry;
    private final CronService cronService;
    private final ObjectMapper objectMapper;

    public CronTools(ToolRegistry toolRegistry, CronService cronService, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.cronService = cronService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void registerTools() {
        toolRegistry.registerAgentTool("schedule_task",
                "Schedule a recurring or one-shot task. Provide 'name' and 'message' (the prompt to execute), plus one of: 'cron' (cron expression), 'interval_seconds' (repeat interval), or 'execute_at' (ISO-8601 timestamp for one-shot).",
                """
                {"type":"object","properties":{"name":{"type":"string","description":"Task name"},"message":{"type":"string","description":"The prompt/instruction to execute on schedule"},"cron":{"type":"string","description":"Cron expression (e.g. */5 * * * *)"},"interval_seconds":{"type":"integer","description":"Repeat every N seconds"},"execute_at":{"type":"string","description":"ISO-8601 timestamp for one-shot execution"}},"required":["name","message"]}""",
                this::scheduleTask);
        toolRegistry.registerAgentTool("list_scheduled_tasks",
                "List all currently scheduled recurring tasks.",
                """
                {"type":"object","properties":{}}""",
                this::listScheduledTasks);
        toolRegistry.registerAgentTool("cancel_scheduled_task",
                "Cancel a scheduled task by its ID.",
                """
                {"type":"object","properties":{"task_id":{"type":"string","description":"The task ID to cancel"}},"required":["task_id"]}""",
                this::cancelScheduledTask);
        log.info("Registered 3 cron tools");
    }

    private AgentResult scheduleTask(Map<String, Object> args, io.agentrunr.core.AgentContext ctx) {
        // Accept common aliases — LLMs guess different parameter names without a schema
        String name = firstString(args, "name", "description", "task_name", "title");
        if (name == null || name.isBlank()) name = "Unnamed Task";

        String message = firstString(args, "message", "prompt", "task", "content", "instruction", "description");
        if (message == null) message = "";

        String cron = firstString(args, "cron", "cron_expression", "schedule");
        Integer intervalSeconds = firstInt(args, "interval_seconds", "interval", "every_seconds", "frequency_seconds");
        String executeAtStr = firstString(args, "execute_at", "run_at", "scheduled_time");

        if (message.isBlank()) {
            return AgentResult.of("Error: 'message' is required for scheduling a task.");
        }

        try {
            ScheduledTask task;
            if (cron != null) {
                task = cronService.addJob(name, message, cron);
            } else if (intervalSeconds != null) {
                task = cronService.addJob(name, message, intervalSeconds);
            } else if (executeAtStr != null) {
                Instant executeAt = Instant.parse(executeAtStr);
                task = cronService.addOneShot(name, message, executeAt);
            } else {
                return AgentResult.of("Error: Provide one of 'cron', 'interval_seconds', or 'execute_at'.");
            }

            return AgentResult.of("Scheduled task '%s' (id: %s, schedule: %s)".formatted(
                    task.name(), task.id(), task.scheduleDescription()));
        } catch (Exception e) {
            return AgentResult.of("Error scheduling task: " + e.getMessage());
        }
    }

    private AgentResult listScheduledTasks(Map<String, Object> args, io.agentrunr.core.AgentContext ctx) {
        var tasks = cronService.listJobs();
        if (tasks.isEmpty()) {
            return AgentResult.of("No scheduled tasks.");
        }

        String listing = tasks.stream()
                .map(t -> "- %s (id: %s, schedule: %s): %s".formatted(
                        t.name(), t.id(), t.scheduleDescription(), t.message()))
                .collect(Collectors.joining("\n"));

        return AgentResult.of("Scheduled tasks:\n" + listing);
    }

    private AgentResult cancelScheduledTask(Map<String, Object> args, io.agentrunr.core.AgentContext ctx) {
        String taskId = firstString(args, "task_id", "id", "job_id", "name");
        if (taskId == null) {
            return AgentResult.of("Error: 'task_id' is required.");
        }

        boolean removed = cronService.removeJob(taskId);
        return removed
                ? AgentResult.of("Cancelled scheduled task: " + taskId)
                : AgentResult.of("Task not found: " + taskId);
    }

    /**
     * Returns the first non-null string value found for any of the given keys.
     */
    private String firstString(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object val = args.get(key);
            if (val != null) return val.toString();
        }
        return null;
    }

    /**
     * Returns the first non-null integer value found for any of the given keys.
     */
    private Integer firstInt(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object val = args.get(key);
            if (val == null) continue;
            if (val instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(val.toString());
            } catch (NumberFormatException e) {
                // try next key
            }
        }
        return null;
    }
}
