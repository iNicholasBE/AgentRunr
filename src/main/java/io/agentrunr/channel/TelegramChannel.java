package io.agentrunr.channel;

import io.agentrunr.core.Agent;
import io.agentrunr.core.AgentContext;
import io.agentrunr.core.AgentResponse;
import io.agentrunr.core.AgentRunner;
import io.agentrunr.core.ChatMessage;
import io.agentrunr.memory.FileMemoryStore;
import io.agentrunr.memory.SQLiteMemoryStore;
import io.agentrunr.setup.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Telegram bot channel using long polling.
 *
 * <p>Connects the agent to a Telegram bot. Messages from allowed users
 * are forwarded to the agent, and responses are sent back.</p>
 *
 * <p>Configure in application.yml:</p>
 * <pre>
 * agent:
 *   telegram:
 *     enabled: true
 *     token: ${TELEGRAM_BOT_TOKEN}
 *     allowed-users: 123456789,987654321
 * </pre>
 */
@Component
public class TelegramChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final String API_BASE = "https://api.telegram.org/bot";

    private final AgentRunner agentRunner;
    private final AgentConfigurer agentConfigurer;
    private final FileMemoryStore memoryStore;
    private final SQLiteMemoryStore sqliteMemory;
    private final ObjectMapper objectMapper;
    private final CredentialStore credentialStore;
    private final ChannelRegistry channelRegistry;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private String botToken;
    private String allowedUsers;
    private long lastUpdateId = 0;
    private volatile long lastChatId = 0;

    public TelegramChannel(AgentRunner agentRunner, AgentConfigurer agentConfigurer,
                           FileMemoryStore memoryStore, SQLiteMemoryStore sqliteMemory,
                           ObjectMapper objectMapper, CredentialStore credentialStore,
                           ChannelRegistry channelRegistry) {
        this.agentRunner = agentRunner;
        this.agentConfigurer = agentConfigurer;
        this.memoryStore = memoryStore;
        this.sqliteMemory = sqliteMemory;
        this.objectMapper = objectMapper;
        this.credentialStore = credentialStore;
        this.channelRegistry = channelRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        botToken = credentialStore.getApiKey("telegram_token");
        allowedUsers = credentialStore.getApiKey("telegram_allowed_users");
        if (allowedUsers == null) allowedUsers = "";

        if (botToken == null || botToken.isBlank()) {
            log.info("Telegram channel disabled — no bot token configured");
            return;
        }

        log.info("Telegram channel starting with long polling...");
        channelRegistry.register(this);
        executor.scheduleWithFixedDelay(this::pollUpdates, 0, 1, TimeUnit.SECONDS);
    }

    private void pollUpdates() {
        try {
            String url = API_BASE + botToken + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30";
            RestClient client = RestClient.create();
            String response = client.get().uri(url).retrieve().body(String.class);

            JsonNode root = objectMapper.readTree(response);
            if (!root.path("ok").booleanValue()) {
                log.warn("Telegram API returned not OK: {}", response);
                return;
            }

            JsonNode results = root.path("result");
            for (JsonNode update : results) {
                lastUpdateId = update.path("update_id").longValue();
                handleUpdate(update);
            }
        } catch (Exception e) {
            log.error("Error polling Telegram updates: {}", e.getMessage());
        }
    }

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) return;

        long chatId = message.path("chat").path("id").longValue();
        long userId = message.path("from").path("id").longValue();
        String text = message.path("text").stringValue() != null ? message.path("text").stringValue() : "";
        String userName = message.path("from").path("first_name").stringValue() != null ? message.path("from").path("first_name").stringValue() : "User";

        if (text.isBlank()) return;

        // Check allowed users
        if (!allowedUsers.isBlank()) {
            boolean allowed = false;
            for (String id : allowedUsers.split(",")) {
                if (id.trim().equals(String.valueOf(userId))) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                log.warn("Unauthorized Telegram user: {} ({})", userName, userId);
                sendMessage(chatId, "⛔ Unauthorized. Your user ID is not in the allowed list.");
                return;
            }
        }

        this.lastChatId = chatId;
        channelRegistry.markActive(getName());

        log.info("Telegram message from {} ({}): {}", userName, userId, text);

        // Store in memory
        String sessionId = "telegram-" + chatId;
        memoryStore.appendMessage(sessionId, "user", userName + ": " + text);
        sqliteMemory.storeConversationMessage(sessionId, "user", userName + ": " + text);

        // Load context
        var contextVars = memoryStore.loadContext(sessionId);
        contextVars.put("user_name", userName);
        contextVars.put("telegram_chat_id", String.valueOf(chatId));
        var context = new AgentContext(contextVars);
        context.set("session_id", sessionId);

        // Run agent
        try {
            Agent agent = agentConfigurer.getDefaultAgent();
            AgentResponse agentResponse = agentRunner.run(agent, List.of(ChatMessage.user(text)), context, 10);

            String reply = agentResponse.lastMessage();
            if (reply.isBlank()) reply = "(No response)";

            sendMessage(chatId, reply);
            memoryStore.appendMessage(sessionId, "assistant", reply);
            memoryStore.saveContext(sessionId, agentResponse.contextVariables());
            sqliteMemory.storeConversationMessage(sessionId, "assistant", reply);

        } catch (Exception e) {
            log.error("Error processing Telegram message: {}", e.getMessage(), e);
            sendMessage(chatId, "❌ Error: " + e.getMessage());
        }
    }

    @Override
    public void sendMessage(String message) {
        if (lastChatId == 0) {
            log.warn("No Telegram chat ID available — no messages received yet");
            return;
        }
        sendMessage(lastChatId, message);
    }

    @Override
    public String getName() {
        return "telegram";
    }

    /**
     * Sends a message to a Telegram chat.
     */
    public void sendMessage(long chatId, String text) {
        try {
            // Telegram has a 4096 char limit per message
            List<String> chunks = splitMessage(text, 4000);
            RestClient client = RestClient.create();

            for (String chunk : chunks) {
                String url = API_BASE + botToken + "/sendMessage";
                String body = objectMapper.writeValueAsString(new TelegramSendMessage(chatId, chunk, "Markdown"));
                client.post().uri(url)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
            }
        } catch (Exception e) {
            log.error("Failed to send Telegram message to chat {}: {}", chatId, e.getMessage());
        }
    }

    private List<String> splitMessage(String text, int maxLen) {
        if (text.length() <= maxLen) return List.of(text);

        List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            // Try to split on newline
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start) end = lastNewline;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    record TelegramSendMessage(long chat_id, String text, String parse_mode) {}
}
