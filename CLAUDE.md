# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Layout

```
wuwei/
├── wuwei-core/          # Java 21 kernel — GraalVM native-image, Helidon WebSocket
├── wuwei-renderer/      # React 19 SPA — Tauri desktop shell, shadcn/ui + Tailwind
└── wuwei.json           # Kernel config (LLM provider/model/apiKey)
```

## Common commands

```bash
# wuwei-core (requires Java 21 + GraalVM 25.0.3)
cd wuwei-core
# Use Gradle 9.3.1 (not project gradlew): D:\codesoft\gradle-9.3.1\bin\gradle.bat
gradle.bat nativeCompile --no-daemon    # Full native image → build/native/nativeCompile/wuwei-kernel.exe
gradle.bat build --no-daemon            # Fat JAR + tests
gradle.bat test --no-daemon             # Run tests only
gradle.bat test --no-daemon --tests "com.wuwei.bus.EventBusTest"  # Single test class

# Native build requires VS 2026 DevShell:
# Import-Module "C:\Program Files\Microsoft Visual Studio\18\Insiders\Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
# Enter-VsDevShell -VsInstallPath "C:\Program Files\Microsoft Visual Studio\18\Insiders" -SkipAutomaticLocation -DevCmdArguments '-arch=x64'

# wuwei-renderer
cd wuwei-renderer
npm run dev                          # Vite dev server on :5176
npm run build                        # tsc && vite build → dist/
cargo tauri dev                      # Tauri desktop (spawns kernel as sidecar)
```

**Kernel config** (`wuwei.json`) resolved from CWD → parent dir → `~/.wuwei/wuwei.json`. **Skills** live in `~/.wuwei/skills/<id>/` with multi-file genome: `skill.json` + `genome/ui/index.json` + `genome/handlers/index.js` (or flat `ui.json`/`handlers.js` for simple skills).

## Architecture

Kernel is Helidon SE 4.1 WebSocket server on `127.0.0.1`. Renderer is Tauri 2 desktop shell hosting React 19 SPA. Communication over WebSocket.

### Data flow (frontend ↔ kernel)

```
Browser/Tauri ←→ Vite SPA ←→ kernel.ts ←→ WebSocket ←→ WsServer (Helidon)
                              ↑                       ↓
                          bridge.ts              MessageRouter
                         (CustomEvent              user-intent, handle-event,
                      dispatch to React)          activate-skill, install-skill,
                                                  refine-skill, list-skills,
                                                  get-skill-source...)
```

Kernel messages carry a `ns` field for namespace routing on the frontend:
| ns | Store | Purpose |
|----|-------|---------|
| `sys` | `SystemStore` | kernel-ready, skill-list, gate-request, system-notify, model-routing |
| `ui` | `SurfaceStore` | skill-activated, skill-deactivated |
| `log` | `ConsoleStore` | plan-step, repair-attempt, skill-log, pi-log |

`bridge.ts` dispatches all WS messages to the appropriate store's `dispatch()` method, which updates internal state and notifies listeners. Components subscribe via each store's `onChange()`.

## wuwei-core (kernel) packages

| Package | Role |
|---|---|
| `com.wuwei` | `Main` — manual DI, wires all components |
| `bus` | `WsServer` (Helidon WS), `MessageRouter` (type-based dispatch), `EventBus` (broadcast + token-bucket) |
| `bus.event` | `KernelEvent` — event type definitions |
| `skill` | `SkillManager` — lifecycle: load/activate/deactivate/uninstall + file watcher hot-reload. `MdRuntime` — blog/markdown runtime with sidebar config. `BrowserSkillRuntime` — browser-js runtime support. |
| `sandbox` | `SkillRuntime` — isolated GraalJS `Context`, `HostAccess.NONE`, 10M statement limit. `RuntimePool` for concurrency. No snapshot restore — disk is canonical. |
| `capability` | `CapabilityManager` + capabilities: `NetworkCapability`, `FileCapability`, `AiCapability`, `CryptoCapability`, `DatabaseCapability` (SQLite per skill, array params unwrap, results as ProxyArray+ProxyObject), `WebSearchCapability` |
| `gate` | `AstAuditor` (manifest validity → UI ID contract → acorn.js AST scan), `EcosystemGuardian` |
| `a2ui` | `A2uiEngine` — flat component tree, patch application. `register()` guards against empty-tree overwrite. |
| `llm` | `SkillGenerator` (Plan+Execute orchestrator), `PlannerAgent`, `AgentFactory`, `Normalizer`, `OutputParser`. Multiple specialized agents: `SkillGenerateAgent` (streaming), `SkillGenerateSyncAgent` (non-streaming, used by Plan+Execute), `SkillRepairAgent`, `DriftAnalysisAgent`, `AiAskAgent`. `PromptBuilder`, `SummarizingChatMemoryStore`. |
| `store` | `StoreService` (SQLite+WAL), `SkillStateStore`, `SkillMemoryService`, `ConversationService` (user-AI chat history in registry.db), `OpLogService` |
| `log` | `LogConfig` — zero-dependency dated file logging to `~/.wuwei/logs/kernel/` and `~/.wuwei/logs/render/`. stdout/stderr tee'd to daily files + console. |
| `rag` | `SkillIndexer` — LLM-based skill metadata extraction, builds `~/.wuwei/skill-index.json`. Supports search across installed skills by capabilities/patterns/functions. `SkillIndex` — data model for the index tree. |
| `snapshot` | `SnapshotService` — UI tree snapshot save/restore for crash recovery and hot reload. `SkillSnapshot` — snapshot data model. |

### Skill generation pipeline (Plan+Execute)

Replaced the old ReAct/function-calling agent with Plan+Execute pattern:

```
Phase 1: PLAN — PlannerAgent (1 LLM call, plain text, no tools)
  User intent → LLM → structured JSON plan with file list

Phase 2: EXECUTE — One LLM call per file in plan
  For each file: focused prompt → LLM generates content → createFile

Phase 3: AUDIT+INSTALL — AstAuditor → repair loop → install → activate
```

Key files for generation:
- `PlannerAgent.java` — AiServices interface with inline wuwei technical context
- `Plan.java` — record(skillId, runtime, capabilities, files)
- `SkillGenerator.java` — `generateViaLlm()` orchestrates Plan→Execute→Audit
- `SkillGenerateSyncAgent.java` — synchronous AiServices agent (avoids streaming tool-call bugs)
- Deleted: `SkillReActAgent.java`, `DeepSeekToolCallFixChatModel.java`, `ReasoningContentChatModel.java`, `ToolCallLoopException.java`

### Skill index / RAG system

On install/update, `SkillIndexer` reads skill genome files and uses LLM to extract structured metadata: capabilities, design patterns, function summaries, component summaries. The master index is stored at `~/.wuwei/skill-index.json`. On startup, `addQuick()` registers skills with name+capabilities only (no LLM call); `rebuildAll()` fills in full metadata. `search()` uses LLM to find top-K relevant skills for a user query.

### Logging system

`LogConfig.init()` is called on kernel startup:
- Redirects `java.util.logging` to daily files in `~/.wuwei/logs/kernel/YYYY-MM-DD.log`
- stdout/stderr tee'd via `TeeStream` — writes to both original console AND daily log
- Frontend `LogViewer` reads logs via `kernel.getLog(source, date)` → `log-content` CustomEvent
- Frontend console.log/warn/error forwarded to kernel via `kernel.sendRenderLog()` → `~/.wuwei/logs/render/YYYY-MM-DD.log`
- Log viewer uses virtual scrolling (line-height 18px, 20-line overscan) with auto-refresh for today's logs

### Snapshot / crash recovery

`SnapshotService` persists UI tree + state summary to `snapshot.db` on skill deactivation/errors. On startup/restore, the last snapshot is loaded and sent to the frontend to restore workspace state.

## wuwei-renderer (frontend) layers

| Layer | Path | What |
|-------|------|------|
| shadcn/ui | `src/wv-components/ui/` | 46 primitives (Button, Card, Dialog, Pagination, Accordion...) |
| A2UI catalog | `src/wv-components/a2ui/` | Components via `createComponentImplementation`. **Pagination** supports A2UI `action` dispatch + DataModel binding |
| App shell | `src/components/` | WwShell, WwSidebar, WwWorkbench, WwWorkspace, WwChat, SkillsPage, SystemPage, LogViewer, SystemMonitor, SkillPanel |
| Runtime | `src/runtime/` | BrowserRuntime, ProxyCapabilities, LocalCapabilities, ThreeJsCapability |
| Stores | `src/stores/` | SurfaceStore (ui ns), SystemStore (sys ns), ConsoleStore (log ns), ConversationStore (chat messages) |
| Contexts | `src/contexts/` | ThemeContext — dark/light theme resolved from system preference + user toggle |

### Browser runtime sandboxing

`BrowserRuntime` loads skill `handlers.js` in-browser using a `Function`-scoped sandbox. Dangerous globals (`document`, `fetch`, `WebSocket`, `Worker`, `process`, `require`) are shadowed as `undefined`. A safe `document` proxy allows only `getElementById`/`createElement`/`querySelector`. A safe `window` proxy allows only `__wuwei_*` bridge properties, `ResizeObserver`, and `requestAnimationFrame`. THREE.js is injected via `__setT__` for 3D skills.

`LocalCapabilities` provides `c.ui.set`/`c.ui.get` with a local patch buffer; patches are flushed to kernel after each handler call. `ProxyCapabilities` bridges database/file/network calls to kernel via `capability-proxy` WebSocket messages. `ThreeJsCapability` wraps THREE.js for 3D rendering skills.

### Key files

- **`src/kernel.ts`** — WebSocket client singleton. `sendIntent`, `activateSkill`, `handleEvent` helpers. Namespace routing via `nsHandlers` Map. Request/response pattern with `correlationId` + timeout.
- **`src/bridge.ts`** — WS message → `CustomEvent` dispatch + store dispatch by `ns` field.
- **`src/components/WwWorkbench.tsx`** — Main layout: sidebar + workspace. Manages skill loading state, gate dialogs, system notifications.
- **`src/components/WwWorkspace.tsx`** — Core A2UI renderer. `ActionListener` routes component actions → `kernel.handleEvent`. `applyPatches` merges component patches.
- **`src/components/WwShell.tsx`** — Root shell: titlebar + resizable layout + floating menu.
- **`src/components/LogViewer.tsx`** — Virtual-scrolling log viewer for kernel/render logs with date picker and auto-refresh.
- **`src/components/SystemMonitor.tsx`** — Real-time system dashboard: heap chart, GC stats, WS sessions, skill capabilities, process list.
- **`src/stores/ConversationStore.ts`** — Message store with streaming `log` entries for GenerationCard.

### Key patterns

- **A2UI components** use `createComponentImplementation(ComponentApi, RenderComponent)` from `@a2ui/react/v0_10`.
- **DataModel binding**: Components accept `{"path": "/key"}` for value bindings. `z.union([z.number(), z.object({path: z.string()})])` in schemas.
- **Action dispatch**: Interactive components use `context.dispatchAction({event: {name, context}})` — A2UI framework auto-adds `surfaceId` and `sourceComponentId`.
- **GenerationCard**: Shows streaming log entries (createFile, readFile, validate...) in real-time via `genLog()` → `pushGenerationCard()`.
- **Multi-file genome**: Skills use `genome/ui/index.json` + `genome/handlers/index.js`. `get-skill-source` checks both multi-file and flat paths.
- **Database seed**: Executor supports `sql:seed` plan steps — LLM generates SQL, executes against temp SQLite DB, copies to phenotype on install.
- **Font stack:** `'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei'` — CJK-first sans-serif.

### Skill lifecycle hooks

| Hook | When | Signature |
|------|------|-----------|
| `onInstall(capability)` | First load (once) | One arg: capability object |
| `onInit(__inputs__, capability)` | Each activation | Two args: inputs + capability |
| `onDeactivate(capability)` | Deactivation | One arg: capability |
| `onUninstall(capability)` | Before uninstall | One arg: capability |

### Skill CRUD patterns (critical gotchas)

These patterns were hardened through the jiduobao (记多宝) password manager skill. **Always follow these rules when building CRUD skills.**

#### 1. DataModel binding preservation

```javascript
// ❌ WRONG — c.ui.set replaces the DataModel binding {path:"/x"} with a literal value.
// The TextField no longer syncs user input to DataModel. Save reads stale data.
c.ui.set("modal-title-input", "value", oldValue);
c.data.set("etitle", oldValue);

// ✅ CORRECT — only c.data.set. The TextField's {path:"/etitle"} binding
// resolves the new DataModel value automatically.
c.data.set("etitle", oldValue);
```

**Rule**: Never use `c.ui.set` on any component property that has a DataModel binding (`{"path": "/..."}`). Always use `c.data.set` to update the DataModel value, letting the binding resolve naturally.

#### 2. Modal open/close — use literal boolean, not DataModel binding

```javascript
// In page render:
{"id":"edit-modal", "component":"Modal", "open":false, ...}  // literal false

// Open: c.ui.set component patch (WwWorkspace auto-merges, preserves other props)
c.ui.set("edit-modal", "open", true);

// Close:
c.ui.set("edit-modal", "open", false);
```

**Why**: DataModel reactivity (`open: {"path": "/mopen"}`) doesn't reliably trigger Modal re-render in custom WV components. Literal boolean + `c.ui.set` component patches are reliable.

#### 3. surfaceUpdate (c.ui.render) ONLY for page transitions

```javascript
// ✅ Page TRANSITION (different root children):
// unlock page (msg, unlock-row) → manage page (top-card, table-card, edit-modal)
c.ui.render([...completely different component tree...]);

// ❌ Same-page refresh after save — surfaceUpdate may not trigger re-render:
c.ui.render([...same component tree with updated rows...]);

// ✅ Same-page update — use individual c.ui.set patches:
refreshTable(c);  // → c.ui.set("pwd-table","rows",...), c.ui.set("page-nav",...)
```

#### 4. Table refresh — individual component patches

```javascript
function refreshTable(c) {
    var d = buildRows(c);
    c.ui.set("pwd-table", "rows", d.rows);        // WwWorkspace merges with existing columns
    c.ui.set("page-nav", "totalPages", d.pages);
    c.ui.set("page-nav", "totalItems", d.total);
}
```

WwWorkspace's `applyPatches` automatically merges individual patches with existing component properties (read from `surface.componentsModel`), preserving `columns`, `component` type, etc.

#### 5. A2uiEngine.applyPatches replaces, not merges

`MessageProcessor.processUpdateComponentsMessage` does `existing.properties = properties` — full replacement. The kernel's `A2uiEngine.applyPatches` merges individual patches into the stored tree before sending to frontend. WwWorkspace's `applyPatches` does another merge for individual patches that arrive without `component` type.

#### 6. EventAck carries patches since W6.4+

`KernelEvent.EventAck` includes a `patches` field. The frontend `bridge.ts` checks `event-ack` messages with patches and dispatches a synthetic `a2ui-patch` CustomEvent. Patches reach the frontend through TWO channels: `A2uiPatch` event (broadcast) and `EventAck` (direct response to `handle-event`).

#### 7. Data flow: individual patch from kernel to React re-render

```
kernel: c.ui.set("id","prop",val)
  → pendingPatches.add({id, prop:val})
  → drainPatches() → emit() returns patches
  → a2uiEngine.applyPatches() merges with stored tree → full component
  → EventBus.publish(A2uiPatch) + EventAck.patches
  → WebSocket/STDOUT → frontend bridge.ts
  → dispatch('a2ui-patch') → WwWorkspace.applyPatches()
  → compPatchMap accumulates → merge with surface.componentsModel
  → processor.processMessages([{updateComponents}])
  → MessageProcessor: existing.properties = properties
  → ComponentModel.onUpdated → GenericBinder.rebuildAllBindings()
  → React useSyncExternalStore → re-render
```

#### 8. Browser-runtime patch flow (browser-js skills)

```
c.ui.set("id","prop",val)
  → LocalCapability: componentCache[id][prop] = val, patchBuffer.push({id, prop:val})
  → handler returns → BrowserRuntime.flushPatches() sends patches via WS to kernel
  → kernel: same pipeline as above
```

### Tauri

- Config: `src-tauri/tauri.conf.json` — `devUrl: http://localhost:5176`, `decorations: false`, identifier `com.wuwei.shell`.
- Kernel dev path: `../../wuwei-core/build/native/nativeCompile/wuwei-kernel.exe`.
- Vite dev server on port 5176 (configured in `vite.config.ts`).
- Dev mode: frontend connects to kernel via `VITE_KERNEL_PORT` env var (default 49200). Prod mode: uses Tauri `invoke('get_kernel_port')`.

### MdRuntime (blog/markdown skills)

`MdRuntime` enables blog-like skills with markdown content. The kernel generates only the content area (Text component). The sidebar is rendered by the frontend using shadcn/ui Sidebar components, configured via `sidebar.json` in the skill genome. Supports arbitrary nesting of menu items. Handlers store `.md` content in `c.storage` on init and switch content on sidebar clicks.
