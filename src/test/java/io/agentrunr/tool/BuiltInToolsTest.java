package io.agentrunr.tool;

import tools.jackson.databind.ObjectMapper;
import io.agentrunr.core.AgentContext;
import io.agentrunr.core.AgentResult;
import io.agentrunr.core.ToolRegistry;
import io.agentrunr.setup.CredentialStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltInToolsTest {

    @TempDir
    Path workspaceDir;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new ToolRegistry(new ObjectMapper());
        var credentialStore = mock(CredentialStore.class);
        when(credentialStore.getApiKey("brave_api_key")).thenReturn(null);
        var appContext = mock(ConfigurableApplicationContext.class);
        var tools = new BuiltInTools(registry, credentialStore, appContext);

        // Use reflection to set the @Value fields for testing
        setField(tools, "shellTimeoutSeconds", 5);
        setField(tools, "maxOutputSize", 65536);
        setField(tools, "restrictToWorkspace", true);
        setField(tools, "workspaceDir", workspaceDir.toString());
        setField(tools, "braveApiKey", "");

        tools.registerTools();
    }

    // --- shell_exec ---

    @Test
    void shouldExecuteShellCommand() {
        var result = registry.executeTool("shell_exec",
                "{\"command\": \"echo hello\"}",
                new AgentContext());

        assertTrue(result.value().contains("hello"));
        assertTrue(result.value().contains("Exit code: 0"));
    }

    @Test
    void shouldRejectEmptyCommand() {
        var result = registry.executeTool("shell_exec",
                "{\"command\": \"\"}",
                new AgentContext());

        assertTrue(result.value().contains("Error"));
    }

    @Test
    void shouldBlockDangerousCommands() {
        var result = registry.executeTool("shell_exec",
                "{\"command\": \"rm -rf /\"}",
                new AgentContext());

        assertTrue(result.value().contains("blocked"));
    }

    @Test
    void shouldHandleCommandFailure() {
        var result = registry.executeTool("shell_exec",
                "{\"command\": \"exit 1\"}",
                new AgentContext());

        assertTrue(result.value().contains("Exit code: 1"));
    }

    // --- file_read ---

    @Test
    void shouldReadFile() throws IOException {
        Path testFile = workspaceDir.resolve("test.txt");
        Files.writeString(testFile, "Hello, World!");

        var result = registry.executeTool("file_read",
                "{\"path\": \"test.txt\"}",
                new AgentContext());

        assertEquals("Hello, World!", result.value());
    }

    @Test
    void shouldRejectPathTraversal() {
        var result = registry.executeTool("file_read",
                "{\"path\": \"../../etc/passwd\"}",
                new AgentContext());

        assertTrue(result.value().contains("outside the workspace"));
    }

    @Test
    void shouldHandleNonexistentFile() {
        var result = registry.executeTool("file_read",
                "{\"path\": \"nonexistent.txt\"}",
                new AgentContext());

        assertTrue(result.value().contains("not found"));
    }

    // --- file_write ---

    @Test
    void shouldWriteFile() {
        var result = registry.executeTool("file_write",
                "{\"path\": \"output.txt\", \"content\": \"written content\"}",
                new AgentContext());

        assertTrue(result.value().contains("Written"));
        assertTrue(Files.exists(workspaceDir.resolve("output.txt")));
    }

    @Test
    void shouldCreateParentDirectories() {
        var result = registry.executeTool("file_write",
                "{\"path\": \"sub/dir/file.txt\", \"content\": \"nested\"}",
                new AgentContext());

        assertTrue(result.value().contains("Written"));
        assertTrue(Files.exists(workspaceDir.resolve("sub/dir/file.txt")));
    }

    @Test
    void shouldRejectPathTraversalOnWrite() {
        var result = registry.executeTool("file_write",
                "{\"path\": \"../../../tmp/evil.txt\", \"content\": \"bad\"}",
                new AgentContext());

        assertTrue(result.value().contains("outside the workspace"));
    }

    // --- file_list ---

    @Test
    void shouldListDirectory() throws IOException {
        Files.writeString(workspaceDir.resolve("a.txt"), "a");
        Files.writeString(workspaceDir.resolve("b.txt"), "b");
        Files.createDirectories(workspaceDir.resolve("subdir"));

        var result = registry.executeTool("file_list",
                "{\"path\": \".\"}",
                new AgentContext());

        assertTrue(result.value().contains("a.txt"));
        assertTrue(result.value().contains("b.txt"));
        assertTrue(result.value().contains("subdir"));
        assertTrue(result.value().contains("DIR"));
    }

    @Test
    void shouldHandleEmptyDirectory() throws IOException {
        Path emptyDir = workspaceDir.resolve("empty");
        Files.createDirectories(emptyDir);

        var result = registry.executeTool("file_list",
                "{\"path\": \"empty\"}",
                new AgentContext());

        assertTrue(result.value().contains("empty directory"));
    }

    // --- web_search ---

    @Test
    void shouldReportMissingApiKey() {
        var result = registry.executeTool("web_search",
                "{\"query\": \"test query\"}",
                new AgentContext());

        assertTrue(result.value().contains("BRAVE_API_KEY not configured"));
    }

    @Test
    void shouldRejectEmptyQuery() {
        var result = registry.executeTool("web_search",
                "{\"query\": \"\"}",
                new AgentContext());

        assertTrue(result.value().contains("Error"));
    }

    // --- web_fetch ---

    @Test
    void shouldRejectInvalidUrl() {
        var result = registry.executeTool("web_fetch",
                "{\"url\": \"not-a-url\"}",
                new AgentContext());

        assertTrue(result.value().contains("Error"));
    }

    @Test
    void shouldRejectEmptyUrl() {
        var result = registry.executeTool("web_fetch",
                "{\"url\": \"\"}",
                new AgentContext());

        assertTrue(result.value().contains("Error"));
    }

    // --- dangerous command detection ---

    @Test
    void shouldDetectDangerousCommands() {
        assertTrue(BuiltInTools.containsDangerousCommand("rm -rf /"));
        assertTrue(BuiltInTools.containsDangerousCommand("sudo rm -rf /"));
        assertTrue(BuiltInTools.containsDangerousCommand("mkfs.ext4 /dev/sda"));
        assertFalse(BuiltInTools.containsDangerousCommand("rm -rf ./build"));
        assertFalse(BuiltInTools.containsDangerousCommand("ls -la"));
        assertFalse(BuiltInTools.containsDangerousCommand("echo hello"));
    }

    // --- path validation ---

    @Test
    void shouldResolveRelativePaths() {
        var validator = new BuiltInTools.PathValidator();
        Path resolved = validator.resolve("test.txt", workspaceDir.toString(), true);
        assertNotNull(resolved);
        assertTrue(resolved.startsWith(workspaceDir));
    }

    @Test
    void shouldBlockTraversalPaths() {
        var validator = new BuiltInTools.PathValidator();
        Path resolved = validator.resolve("../../etc/passwd", workspaceDir.toString(), true);
        assertNull(resolved);
    }

    @Test
    void shouldAllowAbsolutePathsWhenNotRestricted() {
        var validator = new BuiltInTools.PathValidator();
        Path resolved = validator.resolve("/tmp/test.txt", workspaceDir.toString(), false);
        assertNotNull(resolved);
    }

    @Test
    void shouldBlockAbsolutePathsWhenRestricted() {
        var validator = new BuiltInTools.PathValidator();
        Path resolved = validator.resolve("/etc/passwd", workspaceDir.toString(), true);
        assertNull(resolved);
    }

    @Test
    void shouldRejectNullBytes() {
        var validator = new BuiltInTools.PathValidator();
        Path resolved = validator.resolve("test\0evil.txt", workspaceDir.toString(), true);
        assertNull(resolved);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
