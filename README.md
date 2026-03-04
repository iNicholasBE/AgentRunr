<p align="center">
  <h1 align="center">AgentRunr</h1>
  <p align="center">
    A Java-native AI agent runtime powered by Spring Boot, Spring AI, and JobRunr.
    <br />
    <em>Enterprise-grade agent orchestration with persistent memory, MCP integration, and distributed scheduling.</em>
  </p>
</p>

---

## What Is AgentRunr?

AgentRunr is an AI agent framework for Java. It ports [OpenAI Swarm](https://github.com/openai/swarm)'s lightweight agent orchestration pattern to the JVM, backed by Spring Boot for dependency injection, Spring AI for LLM abstraction, and JobRunr for persistent distributed task scheduling.

Inspired by [OpenClaw](https://openclaw.ai) — a Node.js-based personal AI agent runtime — AgentRunr brings the same idea to the Java ecosystem: a self-hosted, file-aware agent that knows who it is, remembers what matters, and can schedule its own tasks.

**Key differentiators:**
- **Persistent memory** — SQLite with FTS5 full-text search, automatic fact extraction, and memory-aware system prompts
- **Soul & Identity** — Agents boot with personality files (SOUL.md, IDENTITY.md) assembled into rich system prompts
- **MCP integration** — Generic multi-server config supporting SSE and stdio transports with custom auth headers
- **Distributed scheduling** — JobRunr-powered cron jobs and heartbeat tasks that survive restarts
- **Multi-channel** — REST API, SSE streaming, Telegram bot, and web UI from a single codebase
- **Multi-model** — Route requests to OpenAI, Anthropic, Mistral, or Ollama per-request with automatic fallback
- **Claude Code OAuth** — Auto-detects Claude Code credentials from system keychain (no manual key needed)

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 25 | Virtual threads, pattern matching, records |
| Spring Boot | 4.0.3 | Application framework + DI (Jakarta EE 11) |
| Spring AI | 2.0.0-M2 | LLM abstraction, tool calling, MCP client |
| Jackson | 3.x | JSON processing (tools.jackson.* namespace) |
| JobRunr | 8.4.2 | Persistent background jobs + cron scheduling |
| MCP SDK | 0.17.x | Model Context Protocol client (SSE + stdio, Jackson 3) |
| SQLite | 3.47.2.0 | Memory store (brain.db) + JobRunr storage |
| Maven | 3.9+ | Build system |

## Quick Start

```bash
# 1. Clone and build
git clone https://github.com/iNicholasBE/AgentRunr.git
cd AgentRunr

# 2. Set Java 25 (required for Spring Boot 4)
export JAVA_HOME=/path/to/java-25
export PATH=$JAVA_HOME/bin:$PATH

# 3. Configure (choose one method)

# Option A: Interactive setup (CLI)
mvn spring-boot:run -Dspring-boot.run.arguments="--setup"

# Option B: Web setup
mvn spring-boot:run
# Visit http://localhost:8090/setup

# 4. Run
mvn spring-boot:run

# 5. Chat
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"messages": [{"role": "user", "content": "Hello!"}]}'
```

## File Layout

AgentRunr uses two separate directories so your workspace files stay clean and credentials stay private:

```
~/.agentrunr/
└── credentials.enc          # AES-256-GCM encrypted API keys and config

~/agentrunr-workspace/       # Default path, configurable during setup
├── SOUL.md                  # Agent personality and values
├── IDENTITY.md              # Technical capabilities and tool awareness
├── HEARTBEAT.md             # Periodic task checklist
├── tasks/                   # Scheduled task definitions
└── memory/                  # Session logs (managed by FileMemoryStore)
    └── {sessionId}/
        └── 2026-01-15.md    # Daily conversation log
```

The workspace path can be customised during setup. Credentials are always stored in `~/.agentrunr/credentials.enc` regardless of workspace location.

On first run, `WorkspaceInitializer` copies default `SOUL.md`, `IDENTITY.md`, and `HEARTBEAT.md` templates into the workspace (existing files are never overwritten).

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        AgentRunr Runtime                             │
├──────────────┬───────────────────────────────────────────────────────┤
│              │                                                       │
│  Channels    │   REST API (/api/chat, /api/chat/stream)             │
│              │   Telegram Bot (long-polling)                         │
│              │   Web UI (dark theme, model selector)                 │
│              │                                                       │
├──────────────┼───────────────────────────────────────────────────────┤
│              │                                                       │
│  Agent Core  │   AgentRunner ─── LLM loop ─── tool calls ─── recurse│
│  (Swarm)     │       │                                               │
│              │   ModelRouter ─── OpenAI / Anthropic / Mistral / Ollama│
│              │       │                                               │
│              │   SystemPromptBuilder ─── SOUL.md + IDENTITY.md       │
│              │       │                    + memory context            │
│              │       │                    + tool listing              │
│              │       │                    + safety rules              │
│              │       │                                               │
│              │   ToolRegistry ─── AgentTools (built-in)              │
│              │                    ToolCallbacks (@Tool beans)         │
│              │                    FunctionCallbacks (MCP servers)     │
│              │                                                       │
├──────────────┼───────────────────────────────────────────────────────┤
│              │                                                       │
│  Memory      │   SQLiteMemoryStore ─── brain.db (FTS5)              │
│              │       │                                               │
│              │   FileMemoryStore ─── daily logs per session          │
│              │       │                                               │
│              │   MemoryAutoSaver ─── passive fact extraction         │
│              │       │                                               │
│              │   MemoryTools ─── memory_store / recall / forget      │
│              │                                                       │
├──────────────┼───────────────────────────────────────────────────────┤
│              │                                                       │
│  MCP         │   McpClientManager ─── SSE + stdio transports        │
│              │       │                                               │
│              │   McpProperties ─── application.yml config            │
│              │       │                                               │
│              │   Dynamic servers ─── POST /api/mcp/servers           │
│              │                                                       │
├──────────────┼───────────────────────────────────────────────────────┤
│              │                                                       │
│  Scheduling  │   HeartbeatService ─── periodic HEARTBEAT.md polling  │
│  (JobRunr)   │       │                                               │
│              │   CronService ─── agent-managed cron + intervals      │
│              │       │                                               │
│              │   CronTools ─── schedule_task / list / cancel         │
│              │                                                       │
├──────────────┼───────────────────────────────────────────────────────┤
│              │                                                       │
│  Security    │   CredentialStore ─── AES-256-GCM encrypted keys     │
│              │   ApiKeyFilter ─── X-API-Key authentication           │
│              │   InputSanitizer ─── injection prevention             │
│              │                                                       │
└──────────────┴───────────────────────────────────────────────────────┘
         │                    │                     │
    SQLite (brain.db)    SQLite (jobrunr.db)    File system
```

## Features

### Agent Orchestration (Swarm Pattern)

The core loop follows OpenAI Swarm's design: send messages to an LLM, process tool calls, recurse until done.

```java
// Define an agent
Agent agent = new Agent("Assistant", "gpt-4.1",
    "You are a helpful assistant.", List.of("web_search", "file_read"));

// Run it
AgentResponse response = agentRunner.run(agent, messages);
```

- **Multi-turn tool calling** — Agents can chain multiple tool calls per conversation turn
- **Agent handoffs** — Tools can return a new `Agent` to transfer control
- **Context variables** — Shared state passed between tool calls via `AgentContext`
- **Configurable max turns** — Prevent runaway loops (default: 10)

### Multi-Model Support

Route requests to different LLM providers per-request:

```bash
# OpenAI (default)
{"model": "openai:gpt-4.1"}

# Anthropic
{"model": "anthropic:claude-sonnet-4-20250514"}

# Mistral
{"model": "mistral:mistral-medium-latest"}

# Ollama (local)
{"model": "ollama:llama3.2"}
```

`ModelRouter` uses `@Nullable` injection — each provider is optional. Configure only what you need.

#### Claude Code OAuth (Anthropic, no key needed)

If you have [Claude Code](https://claude.ai/code) installed and authenticated (`claude auth login`), AgentRunr will automatically pick up your OAuth token from the system keychain — no `ANTHROPIC_API_KEY` required.

- **macOS:** reads from the macOS Keychain via `security find-generic-password`
- **Linux:** reads from `~/.claude/.credentials.json`
- Tokens are refreshed automatically when they expire

### Memory System

AgentRunr has a dual-layer persistent memory system:

#### SQLite Memory Store (Primary)
- **FTS5 full-text search** — BM25-ranked recall across all stored memories
- **Three memory categories:**
  - `CORE` — Long-term facts and preferences (persists across sessions)
  - `DAILY` — Session-specific timestamped entries
  - `CONVERSATION` — Chat context within a session
- **Upsert semantics** — Storing to an existing key updates it
- **Session-aware** — Memories can be scoped to a session or global
- **Database:** `./data/memory/brain.db`

#### File Memory Store (Secondary)
- **Daily conversation logs** — Markdown files at `{workspace}/memory/{sessionId}/yyyy-MM-dd.md`
- **Context persistence** — Session variables saved as `context.json`

#### Automatic Fact Extraction

`MemoryAutoSaver` passively scans user messages and stores facts without explicit tool calls:

| User says... | Stored as |
|---|---|
| "My name is Alice" | `user_name: Alice` |
| "I work at Acme Corp" | `workplace: Acme Corp` |
| "I live in Berlin" | `location: Berlin` |
| "I prefer dark mode" | `preference_dark_mode: dark mode` |
| "Remember that the deploy key is XYZ" | `user_note_...: the deploy key is XYZ` |
| "Always use TypeScript" | `rule_use_typescript: Always use TypeScript` |

#### Memory Tools (Agent-Callable)

The agent can explicitly manage memory through four tools:

| Tool | Parameters | Description |
|------|-----------|-------------|
| `memory_store` | `key`, `content`, `category` | Store or update a memory |
| `memory_recall` | `query`, `limit` | FTS5 search with BM25 ranking |
| `memory_forget` | `key` | Delete a memory by key |
| `memory_list` | `category` | List all memories in a category |

### Soul & Identity System

At startup, `SystemPromptBuilder` assembles a rich system prompt from identity files in the workspace:

```
~/agentrunr-workspace/
├── SOUL.md       # Personality, values, behavioral guidelines
├── IDENTITY.md   # Technical capabilities, tool awareness
├── USER.md       # User-specific preferences and context
└── AGENTS.md     # Multi-agent configuration (if applicable)
```

The assembled system prompt includes:

1. **Identity** — Content from all four files
2. **Instructions** — The agent's configured instructions
3. **Relevant Memories** — FTS5 recall based on the user's current message
4. **Core Facts** — All stored CORE-category memories
5. **Available Tools** — Complete list from ToolRegistry
6. **Safety Guidelines** — Security rules (no secret leaking, etc.)
7. **Runtime** — Current timestamp and OS info

This means the agent is always context-aware — it knows who it is, what it remembers, and what tools it has.

### MCP Integration

AgentRunr supports the [Model Context Protocol](https://modelcontextprotocol.io/) for connecting to external tool servers.

#### Configuration

Define MCP servers in `application.yml`:

```yaml
agent:
  mcp:
    servers:
      # SSE transport with password shorthand
      - name: my-calendar
        url: ${CALENDAR_MCP_URL:}
        password: ${CALENDAR_MCP_PASSWORD:}
        enabled: ${CALENDAR_MCP_ENABLED:false}

      # SSE transport with custom headers
      - name: my-crm
        url: ${CRM_MCP_URL:}
        headers:
          Authorization: "Bearer ${CRM_MCP_TOKEN:}"
        enabled: ${CRM_MCP_ENABLED:false}

      # Stdio transport (local process)
      - name: playwright-browser
        transport: stdio
        command: npx
        args: ["-y", "@playwright/mcp", "--headless"]
        enabled: ${PLAYWRIGHT_ENABLED:false}
```

#### How It Works

1. `McpClientManager` connects to each enabled server at startup (SSE or stdio)
2. Tools from each server are registered as `FunctionCallbacks` in `ToolRegistry`
3. `AgentRunner` includes MCP tools in every LLM call automatically
4. Tool execution flows through: `ToolCallback.call(jsonArgs)` → MCP SDK → remote server

#### Dynamic Servers

Add MCP servers at runtime without restarting:

```bash
# Add a server
curl -X POST http://localhost:8090/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{"name": "my-server", "url": "https://mcp.example.com/sse",
       "authHeader": "Authorization", "authValue": "Bearer token123"}'

# List servers
curl http://localhost:8090/api/mcp/servers

# Remove a server
curl -X DELETE http://localhost:8090/api/mcp/servers/my-server
```

Dynamic servers are persisted in `CredentialStore` and survive restarts.

#### Tool Priority

When multiple sources provide tools, execution priority is:

1. **AgentTools** — Built-in tools (shell, file, web)
2. **ToolCallbacks** — Spring `@Tool` annotated methods
3. **FunctionCallbacks** — MCP server tools

### Built-in Tools

| Tool | Description |
|------|-------------|
| `shell_exec` | Execute shell commands with timeout, workspace sandboxing, and dangerous command blocking |
| `file_read` | Read file contents with path traversal prevention |
| `file_write` | Write files with automatic parent directory creation |
| `file_list` | List directory contents with file type and size info |
| `web_search` | Brave Search API integration (top 5 results) |
| `web_fetch` | Fetch URLs with redirect following and size limits |

**Security features:**
- Workspace sandboxing — tools restricted to the configured workspace directory
- Dangerous command blocking — prevents `rm -rf /`, fork bombs, `dd` to devices
- Path traversal prevention — validates all file paths
- Output size limits — prevents memory exhaustion (64KB default)

### Scheduling (JobRunr)

#### Heartbeat

A periodic file-based task system. The agent reads `HEARTBEAT.md` at configurable intervals and processes any unchecked tasks:

```markdown
<!-- HEARTBEAT.md -->
- [ ] Check disk space and alert if above 80%
- [ ] Summarize today's Telegram messages
- [x] Already done — skipped
```

Configuration:
```yaml
agent:
  heartbeat:
    enabled: true
    interval-minutes: 30
    file: ./workspace-defaults/HEARTBEAT.md
```

#### Cron Jobs

The agent can create its own scheduled tasks via natural language:

```
User: "Remind me to check my email every morning at 9am"
Agent: [calls schedule_task with cron "0 9 * * *"]
```

Three scheduling modes:
- **Cron expressions** — `0 9 * * *` (standard cron syntax)
- **Intervals** — Every N seconds (minimum 60 for recurring)
- **One-shot** — Execute once at a specific time

All powered by JobRunr — tasks survive restarts, have retry logic, and are visible in the JobRunr dashboard at `http://localhost:8000`.

### Channels

#### REST API

```bash
# Synchronous chat
POST /api/chat
{"messages": [...], "model": "openai:gpt-4.1", "sessionId": "optional-uuid"}

# Streaming SSE
POST /api/chat/stream
# Returns: event:session (sessionId), then token data events

# Health check
GET /api/health
```

#### Telegram Bot

Long-polling integration with:
- Allowed user filtering (comma-separated Telegram user IDs)
- Automatic message chunking (4000 char limit)
- Per-chat session persistence (`telegram-{chatId}`)
- Memory integration (both SQLite and file stores)

#### Web UI

Dark-themed chat interface with:
- Model selector dropdown
- Agent settings panel
- Real-time SSE streaming

### Credential Store

Interactive credential management inspired by Claude Code's setup flow:

- **AES-256-GCM encryption** — Keys stored in `~/.agentrunr/credentials.enc`
- **Machine-bound** — Derived from hostname + username via PBKDF2 (100k iterations)
- **Priority over env vars** — CredentialStore values take precedence
- **Three setup methods:**
  1. CLI interactive: `mvn spring-boot:run -Dspring-boot.run.arguments="--setup"`
  2. Web UI: visit `http://localhost:8090/setup`
  3. Environment variables: traditional `export OPENAI_API_KEY=...`

## Configuration Reference

### LLM Providers

| Variable | Description | Required |
|----------|-------------|----------|
| `OPENAI_API_KEY` | OpenAI API key | At least one provider |
| `ANTHROPIC_API_KEY` | Anthropic API key | Optional |
| `ANTHROPIC_ENABLED` | Enable Anthropic (`true`) | With API key |
| `MISTRAL_API_KEY` | Mistral AI API key | Optional |
| `MISTRAL_ENABLED` | Enable Mistral (`true`) | With API key |
| `OLLAMA_BASE_URL` | Ollama server URL | Optional |
| `OLLAMA_MODEL` | Default Ollama model | Optional |
| `OLLAMA_ENABLED` | Enable Ollama (`true`) | With base URL |

> **Claude Code OAuth:** If you have Claude Code installed and authenticated (`claude auth login`), Anthropic access is automatic — no `ANTHROPIC_API_KEY` needed.

### Channels

| Variable | Description |
|----------|-------------|
| `AGENT_API_KEY` | API key for REST authentication (X-API-Key header) |
| `TELEGRAM_ENABLED` | Enable Telegram bot |
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather |
| `TELEGRAM_ALLOWED_USERS` | Comma-separated allowed Telegram user IDs |

### Tools

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAVE_API_KEY` | — | Brave Search API key for `web_search` |
| `TOOLS_RESTRICT_WORKSPACE` | `true` | Sandbox file/shell tools to workspace |
| `TOOLS_WORKSPACE` | `~/agentrunr-workspace` | Workspace directory path |
| `TOOLS_SHELL_TIMEOUT` | `30` | Shell command timeout in seconds |
| `TOOLS_MAX_OUTPUT` | `65536` | Maximum tool output size in bytes |

### Memory

| Variable | Default | Description |
|----------|---------|-------------|
| `AGENT_MEMORY_PATH` | `./data/memory` | Memory storage directory |

### Scheduling

| Variable | Default | Description |
|----------|---------|-------------|
| `HEARTBEAT_ENABLED` | `true` | Enable heartbeat polling |
| `HEARTBEAT_INTERVAL` | `30` | Heartbeat check interval (minutes) |
| `HEARTBEAT_FILE` | `./workspace-defaults/HEARTBEAT.md` | Heartbeat task file path |

### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8090` | Application port |

## API Reference

### Chat

```bash
# Synchronous
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-key" \
  -d '{"messages": [{"role": "user", "content": "What time is it?"}]}'

# Streaming
curl -N -X POST http://localhost:8090/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-key" \
  -d '{"messages": [{"role": "user", "content": "Tell me a story"}]}'

# With specific model
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"messages": [{"role": "user", "content": "Hello"}],
       "model": "anthropic:claude-sonnet-4-20250514"}'
```

### Admin

```bash
# Agent settings
GET  /api/settings
PUT  /api/settings    {"agentName": "...", "model": "...", "instructions": "..."}

# Provider status
GET  /api/providers

# Memory status
GET  /api/memory/status

# Sessions
GET  /api/sessions

# MCP servers
GET    /api/mcp/servers
POST   /api/mcp/servers    {"name": "...", "url": "...", "authHeader": "...", "authValue": "..."}
DELETE /api/mcp/servers/{name}

# Cron jobs
GET    /api/cron
POST   /api/cron
DELETE /api/cron/{id}
POST   /api/cron/{id}/run

# Telegram settings
GET  /api/telegram/settings
PUT  /api/telegram/settings    {"token": "...", "allowedUsers": "..."}

# Health
GET  /api/health
```

## Development

### Prerequisites

- Java 21
- Maven 3.9+

### Build & Test

```bash
export JAVA_HOME=/path/to/java-21
export PATH=$JAVA_HOME/bin:$PATH

# Build and run all tests
mvn clean verify

# Run with debug logging
mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.io.agentrunr=DEBUG"
```

### Project Structure

```
io.agentrunr
├── AgentRunrApplication.java          # Entry point
├── core/
│   ├── Agent.java                     # Agent record (name, model, instructions, tools)
│   ├── AgentRunner.java               # Core loop: send → tool calls → recurse
│   ├── AgentContext.java              # Shared conversation state
│   ├── AgentResult.java               # Tool execution result
│   ├── AgentResponse.java             # Complete run response
│   ├── ChatMessage.java               # Message record (role + content)
│   ├── ConversationHistory.java       # Conversation context management
│   ├── SystemPromptBuilder.java       # Assembles identity + memory + tools + safety
│   └── ToolRegistry.java             # Central tool registration (3 tiers)
├── config/
│   ├── ModelRouter.java               # Per-request provider routing
│   ├── OpenAiConfig.java              # OpenAI provider config
│   ├── MistralConfig.java             # Mistral AI provider config
│   ├── JobRunrConfig.java             # JobRunr datasource config
│   ├── ClaudeCodeAnthropicConfig.java # Anthropic config with OAuth fallback
│   ├── ClaudeCodeOAuthProvider.java   # Reads Claude Code tokens from keychain
│   ├── McpProperties.java            # MCP server config binding
│   ├── McpClientManager.java         # MCP lifecycle management
│   └── McpConfig.java                # Spring AI MCP auto-discovery
├── setup/
│   ├── CredentialStore.java           # AES-256-GCM encrypted key store (~/.agentrunr/)
│   ├── WorkspaceInitializer.java      # Creates workspace dirs + copies default files
│   ├── SetupRunner.java              # CLI first-run setup
│   ├── SetupController.java          # Web setup API
│   ├── SetupInterceptor.java         # Redirects to /setup if unconfigured
│   └── SetupWebConfig.java           # Web config for setup flow
├── channel/
│   ├── Channel.java                   # Channel interface (extensible)
│   ├── RestChannel.java              # REST channel adapter
│   ├── ChatController.java           # REST /api/chat + SSE streaming
│   ├── TelegramChannel.java          # Telegram channel config
│   ├── TelegramBotChannel.java       # Telegram long-polling implementation
│   ├── AdminController.java          # Admin REST API
│   ├── AgentConfigurer.java          # Runtime agent configuration
│   └── ChannelRegistry.java          # Multi-channel management
├── heartbeat/
│   ├── HeartbeatService.java         # JobRunr periodic task polling
│   └── HeartbeatJob.java             # Reads HEARTBEAT.md, runs agent
├── cron/
│   ├── CronService.java              # Agent-managed cron scheduling
│   ├── CronJob.java                  # JobRunr job for scheduled tasks
│   ├── CronController.java           # REST /api/cron endpoints
│   ├── CronTools.java                # Agent tools for scheduling
│   └── ScheduledTask.java            # Task record
├── tool/
│   ├── BuiltInTools.java             # shell, file, web tools
│   ├── SampleTools.java              # Example tools (weather, time)
│   ├── MemoryTools.java              # Agent-callable memory tools
│   ├── JobRunrToolExecutor.java      # Tool execution via JobRunr
│   ├── ToolResultStore.java          # Stores async tool results
│   └── ToolExecutionService.java     # Tool execution orchestration
├── memory/
│   ├── Memory.java                    # Memory interface
│   ├── MemoryCategory.java           # CORE / DAILY / CONVERSATION
│   ├── MemoryEntry.java              # Memory record with BM25 score
│   ├── SQLiteMemoryStore.java        # FTS5 primary store (brain.db)
│   ├── FileMemoryStore.java          # Daily logs + context persistence
│   └── MemoryAutoSaver.java          # Passive fact extraction
└── security/
    ├── SecurityConfig.java            # Spring Security config
    ├── ApiKeyFilter.java             # X-API-Key authentication
    └── InputSanitizer.java           # Input validation
```

### Adding Custom Tools

#### Option 1: Spring @Tool Annotation

```java
@Component
public class MyTools {

    @Tool(description = "Look up a customer by email")
    public String customerLookup(String email) {
        return customerService.findByEmail(email);
    }
}
```

#### Option 2: AgentTool (Full Control)

```java
@Component
public class MyCustomTools {

    @Autowired
    private ToolRegistry toolRegistry;

    @PostConstruct
    void register() {
        toolRegistry.registerAgentTool("my_tool", (args, context) -> {
            String input = (String) args.get("input");
            // Access and modify shared context
            context.set("last_lookup", input);
            return AgentResult.of("Result for: " + input);
        });
    }
}
```

#### Option 3: MCP Server

Add an external MCP server — tools are auto-discovered and registered:

```yaml
agent:
  mcp:
    servers:
      - name: my-service
        url: https://my-mcp-server.example.com/sse
        headers:
          Authorization: "Bearer ${MY_TOKEN:}"
        enabled: true
```

## Inspiration

AgentRunr was inspired by [OpenClaw](https://openclaw.ai), a Node.js personal AI agent runtime. OpenClaw pioneered the concept of a self-hosted agent that reads personality files (SOUL.md, IDENTITY.md), maintains persistent memory, and runs scheduled background tasks — all from a single runtime you control.

AgentRunr brings those same ideas to the Java ecosystem: if you're running Spring Boot in production, you shouldn't need to drop into Node.js to get a capable local agent.

## Roadmap

- [ ] Semantic memory (Spring AI vector store + embeddings for hybrid search)
- [ ] Autonomy levels (readonly / supervised / full modes)
- [ ] Observability (Micrometer → Prometheus)
- [ ] Discord + Slack channels
- [ ] Config hot-reload + health diagnostics
- [ ] Encryption at rest for memory and workspace data
- [ ] End-to-end testing with real API keys

## License

TBD
