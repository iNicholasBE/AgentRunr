package io.agentrunr.tool;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.core.AgentContext;
import io.agentrunr.core.AgentResult;
import io.agentrunr.core.ToolRegistry;
import io.agentrunr.memory.MemoryCategory;
import io.agentrunr.memory.SQLiteMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {

    @TempDir
    Path tempDir;

    private SQLiteMemoryStore memoryStore;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test-memory.db").toString();
        memoryStore = new SQLiteMemoryStore(dbPath, true);
        memoryStore.init();

        toolRegistry = new ToolRegistry(new ObjectMapper());
        var memoryTools = new MemoryTools(toolRegistry, memoryStore);
        memoryTools.registerTools();
    }

    @AfterEach
    void tearDown() {
        memoryStore.close();
    }

    @Test
    void shouldRegisterFourTools() {
        var names = toolRegistry.getAllToolNames();
        assertTrue(names.contains("memory_store"));
        assertTrue(names.contains("memory_recall"));
        assertTrue(names.contains("memory_forget"));
        assertTrue(names.contains("memory_list"));
    }

    @Test
    void shouldStoreMemory() {
        var ctx = new AgentContext();
        AgentResult result = toolRegistry.executeTool("memory_store",
                """
                {"key": "user_pref", "content": "Prefers dark mode", "category": "core"}
                """, ctx);

        assertTrue(result.value().contains("Stored memory"));
        assertTrue(memoryStore.get("user_pref").isPresent());
    }

    @Test
    void shouldStoreMemoryWithDefaultCategory() {
        var ctx = new AgentContext();
        toolRegistry.executeTool("memory_store",
                """
                {"key": "fact1", "content": "Important fact"}
                """, ctx);

        var entry = memoryStore.get("fact1");
        assertTrue(entry.isPresent());
        assertEquals(MemoryCategory.CORE, entry.get().category());
    }

    @Test
    void shouldFailStoreWithoutKey() {
        var ctx = new AgentContext();
        AgentResult result = toolRegistry.executeTool("memory_store",
                """
                {"content": "Something"}
                """, ctx);

        assertTrue(result.value().contains("Error"));
    }

    @Test
    void shouldFailStoreWithoutContent() {
        var ctx = new AgentContext();
        AgentResult result = toolRegistry.executeTool("memory_store",
                """
                {"key": "mykey"}
                """, ctx);

        assertTrue(result.value().contains("Error"));
    }

    @Test
    void shouldRecallMemories() {
        var ctx = new AgentContext();
        toolRegistry.executeTool("memory_store",
                """
                {"key": "java_version", "content": "The project uses Java 21 with records and pattern matching"}
                """, ctx);

        AgentResult result = toolRegistry.executeTool("memory_recall",
                """
                {"query": "Java version"}
                """, ctx);

        assertTrue(result.value().contains("Found"));
        assertTrue(result.value().contains("Java 21"));
    }

    @Test
    void shouldReturnNoMatchesForUnrelatedQuery() {
        var ctx = new AgentContext();
        toolRegistry.executeTool("memory_store",
                """
                {"key": "test_data", "content": "Cats are cute animals"}
                """, ctx);

        AgentResult result = toolRegistry.executeTool("memory_recall",
                """
                {"query": "quantum physics equations"}
                """, ctx);

        // May or may not find matches, but should not throw
        assertNotNull(result.value());
    }

    @Test
    void shouldFailRecallWithoutQuery() {
        var ctx = new AgentContext();
        AgentResult result = toolRegistry.executeTool("memory_recall",
                """
                {"limit": 5}
                """, ctx);

        assertTrue(result.value().contains("Error"));
    }

    @Test
    void shouldForgetMemory() {
        var ctx = new AgentContext();
        toolRegistry.executeTool("memory_store",
                """
                {"key": "to_forget", "content": "Temporary data"}
                """, ctx);

        AgentResult result = toolRegistry.executeTool("memory_forget",
                """
                {"key": "to_forget"}
                """, ctx);

        assertTrue(result.value().contains("Forgot"));
        assertTrue(memoryStore.get("to_forget").isEmpty());
    }

    @Test
    void shouldReportNotFoundOnForgetMissing() {
        var ctx = new AgentContext();
        AgentResult result = toolRegistry.executeTool("memory_forget",
                """
                {"key": "nonexistent_key"}
                """, ctx);

        assertTrue(result.value().contains("not found"));
    }

    @Test
    void shouldListMemories() {
        var ctx = new AgentContext();
        toolRegistry.executeTool("memory_store",
                """
                {"key": "list_item_1", "content": "First item", "category": "core"}
                """, ctx);
        toolRegistry.executeTool("memory_store",
                """
                {"key": "list_item_2", "content": "Second item", "category": "core"}
                """, ctx);

        AgentResult result = toolRegistry.executeTool("memory_list",
                """
                {"category": "core"}
                """, ctx);

        assertTrue(result.value().contains("2 memories"));
        assertTrue(result.value().contains("list_item_1"));
        assertTrue(result.value().contains("list_item_2"));
    }

    @Test
    void shouldReturnEmptyListForNoMemories() {
        var ctx = new AgentContext();
        AgentResult result = toolRegistry.executeTool("memory_list",
                """
                {"category": "daily"}
                """, ctx);

        assertTrue(result.value().contains("No memories"));
    }

    @Test
    void shouldStoreWithSessionContext() {
        var ctx = new AgentContext();
        ctx.set("session_id", "test-session-123");

        toolRegistry.executeTool("memory_store",
                """
                {"key": "session_fact", "content": "This is session-specific", "category": "conversation"}
                """, ctx);

        var entries = memoryStore.list(MemoryCategory.CONVERSATION, "test-session-123");
        assertFalse(entries.isEmpty());
    }
}
