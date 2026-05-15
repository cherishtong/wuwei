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
# wuwei-core
cd wuwei-core
./gradlew nativeCompile              # Full native image build → build/native/nativeCompile/wuwei-kernel.exe
./gradlew run                        # JVM dev run (faster iteration)
./gradlew build                      # Fat JAR

# wuwei-renderer
cd wuwei-renderer
npm run dev                          # Vite dev server on :5173
npm run build                        # tsc --noEmit + vite build → dist/
cargo tauri dev                      # Tauri desktop (spawns kernel as sidecar)
```

**Kernel config** (`wuwei.json`) is resolved from CWD → parent dir → `~/.wuwei/wuwei.json`. **Skills** live in `~/.wuwei/skills/<id>/` (`skill.json` + `genome/ui.json` + `genome/handlers.js`).

## Architecture

The kernel (`wuwei-kernel.exe`) is a Helidon SE 4.1 virtual-thread WebSocket server. The renderer is a Tauri 2 desktop shell hosting a React 19 SPA. They communicate over WebSocket.

### Data flow (frontend ↔ kernel)

```
Browser/Tauri ←→ Vite SPA ←→ kernel.ts ←→ WebSocket ←→ WsServer (Helidon)
                              ↑                       ↓
                          bridge.ts              MessageRouter
                         (CustomEvent             (12 message types:
                      dispatch to React)          user-intent, handle-event,
                                                  activate-skill, install-skill,
                                                  refine-skill, confirm-gate,
                                                  list-skills, get-skill-source...)
```

### wuwei-core (kernel) packages

| Package | Role |
|---|---|
| `com.wuwei` | `Main` — manual DI, wires all components |
| `bus` | `WsServer` (Helidon WS on `127.0.0.1`), `MessageRouter` (type-based dispatch), `EventBus` (broadcast via WS + token-bucket rate limiting) |
| `skill` | `SkillManager` (lifecycle: load/activate/deactivate/uninstall/hot-reload), `SkillManifest` (skill.json), `SkillGenome` (ui.json + handlers.js) |
| `sandbox` | `SkillRuntime` (isolated GraalJS `Context` per skill, single-threaded event loop, `HostAccess.NONE`, 10M statement limit, custom timer API) |
| `capability` | `CapabilityManager` (dynamic permission gates), capabilities: `NetworkCapability` (HttpClient + allowlist + quota), `FileCapability` (virtual FS in `phenotype/sandbox/`), `AiCapability` (LLM ask/askStream via SSE) |
| `gate` | `AstAuditor` (3-phase: manifest validity → UI ID contract → acorn.js AST scan for forbidden globals/state leak/capability escape), `EcosystemGuardian` (cross-skill event name collision) |
| `a2ui` | `A2uiEngine` — flat component tree, A2UI-native patch application |
| `llm` | `SkillGenerator` (intent→skill pipeline with audit+repair loop), `LlmClient` (OpenAI-compatible SSE), `Normalizer` |
| `store` | `StoreService` (SQLite + WAL), `SkillStateStore`, `SnapshotService` (crash recovery) |

**Skill sandbox:** Each skill runs in an isolated GraalJS `Context` with zero Java access. Capabilities are injected as GraalVM `ProxyObject`s. Python/Node skills run as subprocess in phenotype staging.

### wuwei-renderer (frontend) layers

| Layer | Path | What |
|-------|------|------|
| shadcn/ui | `src/wv-components/ui/` | 11 primitives (Button, Input, Card, Dialog, Tabs, Separator, Checkbox, Slider, ScrollArea, Badge, Label) |
| A2UI catalog | `src/wv-components/a2ui/` | 18 components wrapping shadcn/ui via `createComponentImplementation(api, renderFn)`, implementing the A2UI v0.9 basic catalog |
| App shell | `src/components/` | 7 React FCs (WwShell, WwSidebar, WwWorkspace, WwIntent, WwTerminal, WwGateDialog, WwWorkbench) |

### Key files (renderer)

- **`src/kernel.ts`** — WebSocket client singleton. Dev: reads `VITE_KERNEL_PORT` env. Tauri: calls `invoke('get_kernel_port')`. Sends `list-skills` on connect. Exports semantic helpers (`sendIntent`, `activateSkill`, `handleEvent`, etc.).
- **`src/bridge.ts`** — WS message → `window.dispatchEvent(CustomEvent)`. Framework-agnostic.
- **`src/main.tsx`** — React entry: `kernel.init()` → `initBridge()` → `ReactDOM.createRoot`.
- **`src/components/WwWorkspace.tsx`** — Core A2UI renderer. Creates `MessageProcessor` with `wvCatalog`, handles `skill-activated`/`a2ui-patch`/`skill-deactivated` events, renders via `<A2uiSurface>` + `<MarkdownContext.Provider>`.
- **`src-tauri/src/main.rs`** — Spawns kernel as child process, parses `WUWEI_PORT:N` from stdout, emits Tauri `kernel-ready` event, monitors and auto-restarts kernel on crash.

### Key patterns

- **A2UI components** use `createComponentImplementation(ComponentApi, RenderComponent)` from `@a2ui/react/v0_9`. Factory auto-binds props via Zod schema. `buildChild(id)` recursively renders child components.
- **Theme:** Tailwind 3 + shadcn/ui CSS variables (neutral base) in `src/index.css`. Use semantic tokens only (`bg-background`, `text-foreground`, `bg-muted`, `text-muted-foreground`, `bg-accent`, `border`). No hardcoded hex colors.
- **`cn()`** from `@/wv-components/ui/lib/utils` merges Tailwind classes (`clsx` + `tailwind-merge`).
- **`@/`** alias → `src/`.
- **HMR caveat:** Changes to `kernel.ts` require a full page reload (entry dependency), Tauri restart recommended.

### Tauri

- Config: `src-tauri/tauri.conf.json` — `devUrl: http://localhost:5173`, `frontendDist: ../dist`.
- Kernel dev path: `../../wuwei-core/build/native/nativeCompile/wuwei-kernel.exe`.
- `src-tauri/build.rs` waits for kernel binary to be fully written (30 retries × 2s).
- On window destroy, sends graceful `taskkill` first, then force-kills after 5s.
