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
# wuwei-core (requires Java 21 + GraalVM 25.0.2)
cd wuwei-core
./gradlew nativeCompile              # Full native image build → build/native/nativeCompile/wuwei-kernel.exe
./gradlew run                        # JVM dev run (faster iteration)
./gradlew build                      # Fat JAR (custom merge task, not Shadow)
./gradlew test                       # Run JUnit Jupiter 5 tests

# wuwei-renderer (no test infrastructure yet)
cd wuwei-renderer
npm run dev                          # Vite dev server on :5173
npm run build                        # tsc && vite build → dist/
cargo tauri dev                      # Tauri desktop (spawns kernel as sidecar)
```

**Kernel config** (`wuwei.json`) is resolved from CWD → parent dir → `~/.wuwei/wuwei.json`. **Skills** live in `~/.wuwei/skills/<id>/` (`skill.json` + `genome/ui.json` + `genome/handlers.js`).

## Architecture

The kernel (`wuwei-kernel.exe`) is a Helidon SE 4.1 virtual-thread WebSocket server. The renderer is a Tauri 2 desktop shell hosting a React 19 SPA. They communicate over WebSocket.

Kernel LLM calls use LangChain4j 1.15.0 (OpenAI-compatible API). Web UI uses `@a2ui/react` 0.10.

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
| `bus.event` | `KernelEvent` — event type definitions |
| `skill` | `SkillManager` (lifecycle: load/activate/deactivate/uninstall/hot-reload), `SkillManifest` (skill.json), `SkillGenome` (ui.json + handlers.js) |
| `sandbox` | `SkillRuntime` (isolated GraalJS `Context` per skill, single-threaded event loop, `HostAccess.NONE`, 10M statement limit, custom timer API) |
| `capability` | `CapabilityManager` (dynamic permission gates), capabilities: `NetworkCapability` (HttpClient + allowlist + quota), `FileCapability` (virtual FS in `phenotype/sandbox/`), `AiCapability` (LLM ask/askStream via SSE) |
| `gate` | `AstAuditor` (3-phase: manifest validity → UI ID contract → acorn.js AST scan for forbidden globals/state leak/capability escape), `EcosystemGuardian` (cross-skill event name collision) |
| `a2ui` | `A2uiEngine` — flat component tree, A2UI-native patch application |
| `llm` | `SkillGenerator` (intent→skill pipeline with audit+repair loop), `LlmClient` (OpenAI-compatible SSE via LangChain4j), `Normalizer` |
| `snapshot` | `SkillSnapshot`, `SnapshotService` — crash recovery snapshot/restore |
| `store` | `StoreService` (SQLite + WAL), `SkillStateStore`, `OpLogService`, `SkillMemoryService` |

**Skill sandbox:** Each skill runs in an isolated GraalJS `Context` with zero Java access. Capabilities are injected as GraalVM `ProxyObject`s. Python/Node skills run as subprocess in phenotype staging.

### wuwei-renderer (frontend) layers

| Layer | Path | What |
|-------|------|------|
| shadcn/ui | `src/wv-components/ui/` | 46 primitives (Button, Input, Card, Dialog, Tabs, ScrollArea, Slider, Tooltip, Select, DropdownMenu, Command, Sheet, Popover, Accordion, Table, Chart...) |
| A2UI catalog | `src/wv-components/a2ui/` | 50 components wrapping shadcn/ui via `createComponentImplementation(api, renderFn)`, implementing the A2UI v0.10 basic catalog |
| App shell | `src/components/` | 15 React FCs (WwShell, WwSidebar, WwWorkspace, WwIntent, WwTerminal, WwGateDialog, WwWorkbench, WwNavbar, WwTitleBar, WwLoading, WwModelConfig, WwResizableLayout, WelcomeScreen, SkillsPage, SystemPage) |
| Runtime | `src/runtime/` | Browser capability layer — `BrowserRuntime.ts`, `LocalCapabilities.ts`, `ProxyCapabilities.ts`, `ThreeJsCapability.ts`, `types.ts` |

### Key files (renderer)

- **`src/kernel.ts`** — WebSocket client singleton. Dev: reads `VITE_KERNEL_PORT` env. Tauri: calls `invoke('get_kernel_port')`. Sends `list-skills` on connect. Exports semantic helpers (`sendIntent`, `activateSkill`, `handleEvent`, etc.).
- **`src/bridge.ts`** — WS message → `window.dispatchEvent(CustomEvent)`. Framework-agnostic.
- **`src/main.tsx`** — React entry: `kernel.init()` → `initBridge()` → `ReactDOM.createRoot`.
- **`src/components/WwWorkspace.tsx`** — Core A2UI renderer. Creates `MessageProcessor` with `wvCatalog`, handles `skill-activated`/`a2ui-patch`/`skill-deactivated` events, renders via `<A2uiSurface>` + `<MarkdownContext.Provider>`.
- **`src/components/WwResizableLayout.tsx`** — Resizable sidebar/workspace split via `react-resizable-panels`.
- **`src/components/WwIntent.tsx`** — Command palette for user intent input, built with `cmdk`.
- **`src/components/WwNavbar.tsx`** — Navigation between Skills / System pages.
- **`src/contexts/ThemeContext.tsx`** — Dark/light theme state via `next-themes`.
- **`src/runtime/BrowserRuntime.ts`** — Client-side capability execution, including `ThreeJsCapability` for 3D rendering.
- **`src-tauri/src/main.rs`** — Spawns kernel as child process, parses `WUWEI_PORT:N` from stdout, emits Tauri `kernel-ready` event, monitors and auto-restarts kernel on crash. Exports `get_kernel_port` and `pick_folder` Tauri commands.

### Key patterns

- **A2UI components** use `createComponentImplementation(ComponentApi, RenderComponent)` from `@a2ui/react/v0_10`. Factory auto-binds props via Zod schema. `buildChild(id)` recursively renders child components.
- **Theme:** Tailwind 3 + shadcn/ui CSS variables (neutral base) in `src/index.css`. Use semantic tokens only (`bg-background`, `text-foreground`, `bg-muted`, `text-muted-foreground`, `bg-accent`, `border`). Custom token families: `--sidebar-*` (sidebar color scheme), `--titlebar-height`. No hardcoded hex colors.
- **Dark mode:** `next-themes` via `ThemeContext`, class-based toggle, synced with `darkMode: ['class']` in Tailwind config.
- **Custom titlebar:** Tauri `decorations: false` (chromeless window). `WwTitleBar` renders a custom drag region styled with `-webkit-app-region: drag` in `index.css`.
- **`cn()`** from `@/wv-components/ui/lib/utils` merges Tailwind classes (`clsx` + `tailwind-merge`).
- **`@/`** alias → `src/`.
- **HMR caveat:** Changes to `kernel.ts` require a full page reload (entry dependency), Tauri restart recommended.
- **Font stack:** `'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei'` — CJK-first sans-serif.

### Tauri

- Config: `src-tauri/tauri.conf.json` — `devUrl: http://localhost:5173`, `frontendDist: ../dist`, `decorations: false`, identifier `com.wuwei.shell`.
- Kernel dev path: `../../wuwei-core/build/native/nativeCompile/wuwei-kernel.exe`.
- `src-tauri/build.rs` waits for kernel binary to be fully written (30 retries × 2s).
- On window destroy, sends graceful `taskkill` first, then force-kills after 5s.
- Window: 1200×800, resizable, chromeless (custom titlebar via `WwTitleBar` + CSS drag region).
