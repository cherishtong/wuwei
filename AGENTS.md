# AGENTS.md — Wuwei

This file provides everything an AI coding agent needs to know about the Wuwei project.

## Project Overview

Wuwei (无为) is a desktop AI skill platform. It consists of a Java 21 kernel that runs sandboxed JavaScript skills, and a React 19 frontend wrapped in a Tauri 2 desktop shell. Skills are dynamic, LLM-generated JavaScript applications that users can create, install, and run on the fly.

The kernel communicates with the frontend over WebSocket. The Tauri Rust backend spawns the kernel as a child process, monitors its health, and forwards stdout events to the frontend via Tauri IPC.

**Version:** 6.4.0 across all modules.

## Repository Layout

```
wuwei/
├── wuwei-core/          # Java 21 kernel (GraalVM native-image, Helidon WebSocket)
│   ├── src/main/java/com/wuwei/   # Kernel source code
│   ├── src/test/java/com/wuwei/   # JUnit 5 tests
│   ├── src/main/resources/        # Prompts, docs, schema.sql, acorn.min.js
│   ├── build.gradle               # Gradle build config
│   └── settings.gradle
├── wuwei-renderer/      # React 19 + TypeScript frontend + Tauri 2 shell
│   ├── src/                       # React SPA source
│   ├── src-tauri/src/main.rs      # Rust Tauri backend (spawns kernel)
│   ├── src-tauri/Cargo.toml       # Rust package config
│   ├── package.json               # npm dependencies
│   ├── vite.config.ts             # Vite dev server (port 5176)
│   ├── tailwind.config.ts         # Tailwind CSS config
│   └── components.json            # shadcn/ui configuration
├── wuwei-shell/         # Binary distribution placeholder
│   └── binaries/wuwei.json
├── wuwei.json           # Runtime kernel config (LLM, sandbox, store paths)
└── CLAUDE.md            # Detailed developer guide (270 lines)
```

## Technology Stack

### wuwei-core (Kernel)

- **Language:** Java 21
- **Build System:** Gradle 9.3.1 with wrapper (`gradlew.bat`)
- **Native Compilation:** GraalVM 25.0.2 + `native-image` plugin
- **Web Server:** Helidon SE 4.1.4 (virtual-thread based Níma) with WebSocket
- **LLM Integration:** LangChain4j 1.15.0 (OpenAI/DeepSeek compatible)
- **JS Sandbox:** GraalVM Polyglot SDK (`js-community`) with `HostAccess.NONE`
- **Database:** SQLite via `sqlite-jdbc` 3.45.1.0 (WAL mode)
- **JSON:** Jackson Databind 2.17.0
- **Testing:** JUnit Jupiter 5.10.2
- **Logging:** Custom `LogConfig` writing dated files to `~/.wuwei/logs/kernel/`

### wuwei-renderer (Frontend)

- **Framework:** React 19.2.6 + TypeScript 5.5
- **Build Tool:** Vite 5.4.0 (dev server on port 5176)
- **Desktop Shell:** Tauri 2.11.2 (Rust backend)
- **Styling:** Tailwind CSS 3.4.19 + PostCSS + Autoprefixer
- **UI Components:** shadcn/ui (46+ primitives in `src/wv-components/ui/`)
- **A2UI Framework:** `@a2ui/react` ^0.10.0 (custom UI framework for skill rendering)
- **Charts:** Recharts 2.15.4
- **3D:** Three.js 0.184.0
- **Icons:** Lucide React 1.16.0

### wuwei-shell (Tauri Rust Backend)

- **Language:** Rust 2021 edition
- **Dependencies:** `tauri = "2"`, `serde`, `serde_json`
- **Role:** Spawns kernel as child process, monitors health, auto-restarts on crash, exposes `get_kernel_port` and `pick_folder` commands.

## Build and Test Commands

### wuwei-core

Requires **Java 21** and **GraalVM 25.0.2+** (`JAVA_HOME` must point to GraalVM).

```bash
cd wuwei-core

# Full native image build (requires Visual Studio DevShell on Windows)
gradle.bat nativeCompile --no-daemon
# Output: build/native/nativeCompile/wuwei-kernel.exe

# Fat JAR + tests
gradle.bat build --no-daemon

# Run tests only
gradle.bat test --no-daemon

# Run a single test class
gradle.bat test --no-daemon --tests "com.wuwei.bus.EventBusTest"
```

**Windows native build note:** On Windows, native compilation requires the Visual Studio C++ toolchain. Enter the VS DevShell before running `nativeCompile`:
```powershell
Import-Module "C:\Program Files\Microsoft Visual Studio\18\Insiders\Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
Enter-VsDevShell -VsInstallPath "C:\Program Files\Microsoft Visual Studio\18\Insiders" -SkipAutomaticLocation -DevCmdArguments '-arch=x64'
```

### wuwei-renderer

```bash
cd wuwei-renderer

# Vite dev server (frontend only, connects to existing kernel on VITE_KERNEL_PORT)
npm run dev

# Production static build
npm run build

# Tauri desktop dev mode (spawns kernel as sidecar)
cargo tauri dev

# Tauri production build (installer)
cargo tauri build
```

### Combined Dev Workflow

1. Build the kernel native image first: `cd wuwei-core && gradle.bat nativeCompile --no-daemon`
2. In one terminal: `cd wuwei-renderer && cargo tauri dev` (this spawns the kernel automatically)
3. Or for frontend-only dev with an already-running kernel: `cd wuwei-renderer && npm run dev`

### Cloud Deployment

```bash
# Run kernel in cloud mode (serves SPA + WebSocket on same port)
cd wuwei-core && java -jar build/libs/wuwei-kernel.jar --profile cloud

# Build frontend for cloud (same-origin WebSocket)
cd wuwei-renderer && npm run build   # uses .env.production → VITE_KERNEL_URL=/ws

# CI/CD package
./scripts/package-cloud.sh           # → deploy/wuwei-cloud-v6.4.0.tar.gz

# Docker
docker build -t wuwei-cloud .
docker run -d -p 8080:8080 -e WUWEI_API_KEY=sk-xxx -v ~/.wuwei:/root/.wuwei wuwei-cloud
```

### GitHub Actions

| Workflow | Trigger | What |
|----------|---------|------|
| `ci.yml` | PR → main | Compile Java + test + frontend type-check + build |
| `release.yml` | Tag `v*` | Full matrix → GitHub Release |

**Release matrix:**

| Job | Platform | Output |
|-----|----------|--------|
| `jar` | ubuntu | Fat JAR (cloud) |
| `frontend` | ubuntu | SPA dist |
| `desktop-linux` | ubuntu | Native binary + Tauri (.deb, .AppImage) |
| `desktop-macos` | macos | Native binary + Tauri (.dmg) |
| `desktop-windows` | windows | Native binary + Tauri NSIS (.exe) |

Tauri build: GraalVM native → copy to `src-tauri/binaries/` → `cargo tauri build --bundles <target>`. Kernel bundled via `tauri.conf.json` `bundle.resources`.

**Kernel CLI reference:**
```
java -jar wuwei-kernel.jar [--profile local|cloud] [--host 0.0.0.0] [--port 8080] [--web-root ./dist] [--config wuwei.json]
```
- `--profile cloud` → 0.0.0.0:8080 + auto-detect web-root (`./dist` or `../wuwei-renderer/dist`)
- `--profile local` → 127.0.0.1:random port, no static serving (Vite dev server)
- `VITE_KERNEL_URL` (build-time env) → `/ws` for same-origin, or full `wss://host/ws` URL
- `VITE_KERNEL_PORT` (dev-time env) → local dev kernel port (default 49200)

See `CLAUDE.md` for full cloud deployment details (systemd, Docker, packaging scripts).

## Code Organization

### wuwei-core Packages

| Package | Key Classes | Purpose |
|---------|-------------|---------|
| `com.wuwei` | `Main` | Entry point. Manual dependency injection container. Wires all components, starts WebSocket server. |
| `com.wuwei.bus` | `WsServer`, `MessageRouter`, `EventBus` | Helidon WebSocket server, message routing, broadcast with rate-limiting. |
| `com.wuwei.bus.event` | `KernelEvent` | Event type definitions (SkillActivated, A2uiPatch, EventAck, etc.). |
| `com.wuwei.skill` | `SkillManager`, `SkillManifest`, `MdRuntime`, `BrowserSkillRuntime` | Skill lifecycle (load/activate/deactivate/uninstall), hot-reload file watcher. |
| `com.wuwei.sandbox` | `SkillRuntime`, `RuntimePool` | GraalJS isolated contexts with `HostAccess.NONE`, 10M statement limit, 5s timeout. |
| `com.wuwei.capability` | `CapabilityManager`, `NetworkCapability`, `FileCapability`, `AiCapability`, `CryptoCapability`, `DatabaseCapability`, `WebSearchCapability` | Capability injection + gate system. Skills declare required capabilities in manifest. |
| `com.wuwei.gate` | `AstAuditor`, `EcosystemGuardian` | Security: AST scan via acorn.js, ecosystem conflict detection. |
| `com.wuwei.a2ui` | `A2uiEngine` | Flat component tree registry + patch application. |
| `com.wuwei.llm` | `SkillGenerator`, `PlannerAgent`, `AgentFactory`, `SkillGenerateAgent`, `SkillRepairAgent`, `DriftAnalysisAgent`, `AiAskAgent` | LLM pipeline (Plan+Execute pattern). LangChain4j AiServices. |
| `com.wuwei.store` | `StoreService`, `SkillStateStore`, `ConversationService`, `OpLogService` | SQLite persistence (registry.db), conversation history, skill state. |
| `com.wuwei.log` | `LogConfig` | Dated file logging to `~/.wuwei/logs/kernel/` and `~/.wuwei/logs/render/`. |
| `com.wuwei.rag` | `SkillIndexer`, `SkillIndex` | LLM-based skill metadata extraction + retrieval. Index at `~/.wuwei/skill-index.json`. |
| `com.wuwei.snapshot` | `SnapshotService`, `SkillSnapshot` | UI tree snapshot save/restore for crash recovery. |

### wuwei-renderer Source Directories

| Directory | Contents |
|-----------|----------|
| `src/components/` | App shell: `WwShell`, `WwWorkbench`, `WwWorkspace`, `WwChat`, `WwSidebar`, `SkillsPage`, `SystemPage`, `SystemMonitor`, `LogViewer`, `WwGateDialog`, etc. |
| `src/wv-components/ui/` | 46 shadcn/ui primitives (Button, Card, Dialog, Table, Sidebar, etc.). |
| `src/wv-components/a2ui/` | ~50 A2UI catalog components via `createComponentImplementation`. |
| `src/stores/` | `SurfaceStore` (ui ns), `SystemStore` (sys ns), `ConsoleStore` (log ns), `ConversationStore`. |
| `src/runtime/` | `BrowserRuntime`, `LocalCapabilities`, `ProxyCapabilities`, `ThreeJsCapability`. |
| `src/contexts/` | `ThemeContext` — dark/light theme. |
| `src/kernel.ts` | WebSocket client singleton. Auto-reconnect, namespace routing, request/response with `correlationId`. |
| `src/bridge.ts` | Bridges WS messages to stores + `CustomEvent` dispatch by namespace. |

## Communication Architecture

```
Tauri Desktop Shell (Rust)
  └── Spawns wuwei-kernel.exe as child process
  └── Reads stdout for "WUWEI_PORT:" and "FORWARD:" lines
  └── Emits "kernel-ready" / "kernel-event" via Tauri IPC
        │
        ▼
React 19 SPA (Vite)
  └── kernel.ts ←→ WebSocket ←→ WsServer (Helidon, 127.0.0.1)
  └── bridge.ts → stores → components
        │
        ▼
Java 21 Kernel
  └── WsServer → MessageRouter → SkillManager / SkillGenerator
  └── EventBus ←→ A2uiEngine ←→ GraalJS sandbox
  └── CapabilityManager ←→ SQLite (StoreService)
```

**Message namespaces (`ns` field):**
- `sys` → `SystemStore` — kernel-ready, skill-list, gate-request, system-notify, model-routing
- `ui` → `SurfaceStore` — skill-activated, skill-deactivated, a2ui-patch
- `log` → `ConsoleStore` — plan-step, repair-attempt, skill-log, pi-log
- `conv` → `ConversationStore` — conversation-update, messages

## Code Style Guidelines

### Java (wuwei-core)

- **Source/Target:** Java 21. Use modern features (`var`, records, switch expressions, virtual threads where appropriate).
- **Encoding:** UTF-8 (configured in `build.gradle`).
- **Imports:** Group by package; no wildcard imports. Static imports allowed for test assertions.
- **DI Pattern:** Manual dependency injection in `Main.java` — no Spring/CDI. Wire dependencies explicitly.
- **Naming:**
  - Classes: `PascalCase` (e.g., `SkillManager`, `A2uiEngine`)
  - Methods/fields: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Event types: `kebab-case` in JSON (converted by `EventBus.toKebabCase()`)
- **Records:** Prefer Java records for event/data classes (e.g., `KernelEvent.SkillActivated`, `Plan`).
- **Logging:** Use `System.out.println` with `[kernel]` prefix for operational logs. `LogConfig` redirects stdout/stderr to dated files.
- **Comments:** Use `//` for inline comments. Section dividers use `// ── ... ──` pattern.

### TypeScript / React (wuwei-renderer)

- **TypeScript:** Strict mode enabled. Use explicit types for function parameters and returns.
- **Path Alias:** `@/` maps to `./src/`. Import components as `@/components/WwShell`.
- **Components:**
  - App shell: `Ww` prefix (e.g., `WwShell`, `WwWorkspace`)
  - shadcn/ui: located in `src/wv-components/ui/`, imported via `@/wv-components/ui/button`
  - A2UI catalog: located in `src/wv-components/a2ui/`
- **Styling:** Tailwind CSS utility classes. Dark mode supported via CSS variables. CJK-first font stack: `'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei'`.
- **State Management:** Store pattern — each store is a singleton with `dispatch()` and `onChange()` methods. Components subscribe via `onChange()`.
- **Kernel Communication:** Use `kernel.ts` API. For request/response patterns, use `kernel` methods that return `Promise` (they use `correlationId` internally).

### Rust (wuwei-shell)

- Standard Rust 2021 edition conventions.
- `snake_case` for functions/variables, `PascalCase` for types/structs.
- Error handling: use `map_err(|e| e.to_string())` for Tauri command errors.

## Testing Instructions

### Backend Tests (wuwei-core)

Tests are in `src/test/java/com/wuwei/` using JUnit Jupiter 5.10.2.

| Test Class | What It Tests |
|------------|---------------|
| `com.wuwei.bus.EventBusTest` | Event serialization, kebab-case conversion, event field validation. |
| `com.wuwei.bus.KernelServerIntegrationTest` | WebSocket server integration (start, connect, message exchange). |
| `com.wuwei.snapshot.SnapshotServiceTest` | Snapshot save/restore logic. |
| `com.wuwei.store.SkillStateStoreTest` | Skill state persistence in SQLite. |

Run all tests:
```bash
cd wuwei-core
gradle.bat test --no-daemon
```

Run a single test class:
```bash
gradle.bat test --no-daemon --tests "com.wuwei.bus.EventBusTest"
```

### Frontend Tests

**No frontend test suite currently exists.** If adding tests, the project uses Vite — consider Vitest + React Testing Library.

### Integration Testing

For end-to-end testing:
1. Build native kernel: `gradle.bat nativeCompile --no-daemon`
2. Start Tauri dev: `cargo tauri dev`
3. Test skill generation, activation, and WebSocket message flow via the UI.

## Security Considerations

### Skill Sandbox

- **GraalJS Sandbox:** `HostAccess.NONE` — JavaScript skills have zero access to Java host objects.
- **Statement Limit:** 10 million statements per skill execution.
- **Timeout:** 5 seconds per execution.
- **Memory Limit:** 128 MB per sandbox context.
- **Max Concurrent:** 30 active runtimes in the pool.

### Gate System

- **AstAuditor:** Scans skill `handlers.js` with acorn.js AST parser before activation. Checks for dangerous patterns and validates manifest → UI ID contracts.
- **EcosystemGuardian:** Detects ecosystem conflicts between skills.
- **Capability Gate:** Skills must declare required capabilities in `skill.json`. Users must approve capabilities like `network`, `file`, `database` before activation.
- **Rate Limiting:** Token-bucket rate limiting on event emission (`10/sec` default).

### Frontend Sandbox (Browser Runtime)

- `BrowserRuntime` loads skill JS in a `Function`-scoped sandbox.
- Dangerous globals (`document`, `fetch`, `WebSocket`, `Worker`, `process`, `require`) are shadowed as `undefined`.
- A safe `document` proxy allows only `getElementById`/`createElement`/`querySelector`.
- A safe `window` proxy allows only `__wuwei_*` bridge properties, `ResizeObserver`, and `requestAnimationFrame`.

### Configuration Security

- `wuwei.json` at the project root contains the LLM API key in plaintext. **Do not commit this file** if the key is sensitive — though it is currently tracked.
- The kernel searches for config in this order: `--config` argument → working dir → parent dir → `~/.wuwei/wuwei.json`.

## Development Conventions

### Skill Structure

Skills live in `~/.wuwei/skills/<id>/` and use a multi-file genome:

```
~/.wuwei/skills/<skill-id>/
├── skill.json              # Manifest (id, name, runtime, capabilities)
├── genome/
│   ├── ui/
│   │   └── index.json      # A2UI component tree
│   ├── handlers/
│   │   └── index.js        # Event handlers
│   └── sidebar.json        # Optional blog-style sidebar config
└── phenotype/              # Runtime output assets
```

For simple skills, flat files (`ui.json`, `handlers.js`) are also supported.

### Skill Lifecycle Hooks

| Hook | When | Signature |
|------|------|-----------|
| `onInstall(capability)` | First load (once) | One arg: capability object |
| `onInit(__inputs__, capability)` | Each activation | Two args: inputs + capability |
| `onDeactivate(capability)` | Deactivation | One arg: capability |
| `onUninstall(capability)` | Before uninstall | One arg: capability |

### Skill Generation Pipeline (Plan+Execute)

The kernel generates skills via a three-phase LLM pipeline:

1. **PLAN** — `PlannerAgent` generates a structured JSON plan with file list (1 LLM call).
2. **EXECUTE** — One LLM call per file in the plan. `SkillGenerateSyncAgent` generates file contents.
3. **AUDIT+INSTALL** — `AstAuditor` validates → repair loop (max 10 attempts) → install → activate.

Key files:
- `PlannerAgent.java` — AiServices interface with inline technical context
- `Plan.java` — record(skillId, runtime, capabilities, files)
- `SkillGenerator.java` — orchestrates Plan → Execute → Audit
- `SkillGenerateSyncAgent.java` — synchronous agent for file generation

### A2UI Patch System

- Kernel accumulates patches (`c.ui.set(id, prop, val)`) → merges into stored tree → broadcasts to frontend.
- Frontend `WwWorkspace` applies patches via `applyPatches`, merging with `surface.componentsModel`.
- `EventAck` carries patches since W6.4+ (two channels: `A2uiPatch` broadcast + `EventAck` direct response).
- **CRUD Rule:** Never use `c.ui.set` on DataModel-bound properties (`{"path": "/..."}`). Always use `c.data.set` to update DataModel values.
- **Modal Rule:** Use literal boolean for `open` property, not DataModel binding. Toggle with `c.ui.set("modal-id", "open", true/false)`.

### Hot Reload

- `SkillManager` polls `~/.wuwei/skills/` every 2 seconds.
- Changed skills are reloaded without kernel restart.
- Disk is the canonical source of truth — no snapshot restore on reload.

### Logging

- **Kernel logs:** `~/.wuwei/logs/kernel/YYYY-MM-DD.log`
- **Renderer logs:** `~/.wuwei/logs/render/YYYY-MM-DD.log`
- Frontend `console.log/warn/error` is forwarded to kernel via `kernel.sendRenderLog()`.
- `LogViewer` component reads logs via `kernel.getLog(source, date)` with virtual scrolling.

## Configuration

### wuwei.json (Root Level)

```json
{
  "version": "7.0.0",
  "llm": {
    "provider": "deepseek",
    "model": "deepseek-v4-pro",
    "apiKeyEnv": "OPENAI_API_KEY",
    "apiKey": "...",
    "baseUrl": null,
    "timeoutSeconds": 60,
    "maxRetries": 3
  },
  "sandbox": {
    "statementLimit": 10000000,
    "timeoutSeconds": 5,
    "memoryLimitMb": 128,
    "maxActiveConcurrent": 30
  },
  "gate": {
    "networkDefaultQuota": "100/min",
    "eventEmitRateLimit": "10/sec",
    "maxQueueDepth": 1000,
    "autoApproveOrigins": ["local"],
    "requireConfirmOrigins": ["community", "network"]
  },
  "store": {
    "registryDb": "~/.wuwei/data/registry.db",
    "snapshotDb": "~/.wuwei/data/snapshot.db",
    "skillStateDir": "~/.wuwei/skills"
  },
  "skills": {
    "dir": "~/.wuwei/skills",
    "maxLineageVersions": 10,
    "traceLogMaxPerSkill": 200,
    "traceLogRetentionDays": 30,
    "registryUrls": []
  },
  "maxRepairAttempts": 10,
  "logLevel": "info"
}
```

### Environment Variables

- `JAVA_HOME` — Must point to GraalVM 25.0.2+ for native compilation.
- `OPENAI_API_KEY` / `WUWEI_API_KEY` — LLM API key for the kernel. Read from `apiKeyEnv` in wuwei.json, or read directly from wuwei.json's `apiKey` field.
- `VITE_KERNEL_URL` — Cloud mode WebSocket URL (e.g. `/ws` for same-origin, or `wss://host/ws`). Set in `.env.production` for `npm run build`.
- `VITE_KERNEL_PORT` — Dev mode WebSocket port (default 49200). Set in `.env.development` for `npm run dev`.

## Important Notes for Agents

1. **Always read `CLAUDE.md`** for the most detailed and up-to-date architectural guidance. It contains critical patterns (CRUD rules, A2UI patch flow, data model binding) that are not repeated here.
2. **Skill developer docs** inside `wuwei-core/src/main/resources/docs/` are written in **Chinese** — they are fed to the LLM context during skill generation.
3. **GitHub Actions CI/CD** is set up: `ci.yml` on PR, `release.yml` on tag `v*`. Release builds cloud packages (fat JAR + dist), desktop native binaries (Linux/macOS), and Tauri NSIS installer (Windows). See `.github/workflows/`.
4. **Two deployment modes:** Tauri desktop (native-image + Windows Tauri installer) and cloud (JVM + browser). See `CLAUDE.md` → "Cloud deployment" section.
5. **Tauri is cross-platform:** `main.rs` uses `#[cfg(target_os)]` guards for platform-specific code — `CommandExt`/`taskkill` on Windows, `libc::kill` on Unix, `osascript` on macOS, `zenity` on Linux.
6. The `.claude/settings.json` and `.claude/settings.local.json` files contain approved PowerShell command allowlists for Claude Code — they are not build configs.
