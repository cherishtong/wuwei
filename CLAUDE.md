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
- Deleted: `SkillReActAgent.java`, `DeepSeekToolCallFixChatModel.java`, `ReasoningContentChatModel.java`, `ToolCallLoopException.java`

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

### wuwei-core (kernel) packages

| Package | Role |
|---|---|
| `com.wuwei` | `Main` — manual DI, wires all components |
| `bus` | `WsServer` (Helidon WS), `MessageRouter` (type-based dispatch), `EventBus` (broadcast + token-bucket) |
| `bus.event` | `KernelEvent` — event type definitions |
| `skill` | `SkillManager` — lifecycle: load/activate/deactivate/uninstall + file watcher hot-reload. Lifecycle hooks: `onInstall` (once), `onActivate`/`onInit`, `onDeactivate`, `onUninstall` |
| `sandbox` | `SkillRuntime` — isolated GraalJS `Context`, `HostAccess.NONE`, 10M statement limit. No snapshot restore — disk is canonical. |
| `capability` | `CapabilityManager` + capabilities: `NetworkCapability`, `FileCapability`, `AiCapability`, `CryptoCapability`, `DatabaseCapability` (SQLite per skill, array params unwrap, results as ProxyArray+ProxyObject) |
| `gate` | `AstAuditor` (manifest validity → UI ID contract → acorn.js AST scan), `EcosystemGuardian` |
| `a2ui` | `A2uiEngine` — flat component tree, patch application. `register()` guards against empty-tree overwrite. |
| `llm` | `SkillGenerator` (Plan+Execute), `PlannerAgent`, `AgentFactory`, `Normalizer`, `OutputParser` |
| `store` | `StoreService` (SQLite+WAL), `SkillStateStore`, `SkillMemoryService` |

### wuwei-renderer (frontend) layers

| Layer | Path | What |
|-------|------|------|
| shadcn/ui | `src/wv-components/ui/` | 46 primitives (Button, Card, Dialog, Pagination, Accordion...) |
| A2UI catalog | `src/wv-components/a2ui/` | Components via `createComponentImplementation`. **Pagination** supports A2UI `action` dispatch + DataModel binding |
| App shell | `src/components/` | WwShell, WwSidebar, WwWorkspace, SkillsPage, SystemPage |
| Runtime | `src/runtime/` | BrowserRuntime, ProxyCapabilities, ThreeJsCapability |

### Key files

- **`src/kernel.ts`** — WebSocket client singleton. `sendIntent`, `activateSkill`, `handleEvent` helpers.
- **`src/bridge.ts`** — WS message → `window.dispatchEvent(CustomEvent)`.
- **`src/components/WwWorkspace.tsx`** — Core A2UI renderer. `ActionListener` routes component actions → `kernel.handleEvent`.
- **`src/wv-components/a2ui/Pagination.tsx`** — Pagination A2UI component with action dispatch + DataModel path binding.
- **`src/stores/ConversationStore.ts`** — Message store with streaming `log` entries for GenerationCard.

### Key patterns

- **A2UI components** use `createComponentImplementation(ComponentApi, RenderComponent)` from `@a2ui/react/v0_9`.
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

### Tauri

- Config: `src-tauri/tauri.conf.json` — `devUrl: http://localhost:5176`, `decorations: false`, identifier `com.wuwei.shell`.
- Kernel dev path: `../../wuwei-core/build/native/nativeCompile/wuwei-kernel.exe`.
- Vite dev server on port 5176 (configured in `vite.config.ts`).
