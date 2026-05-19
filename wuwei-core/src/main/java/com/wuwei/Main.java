package com.wuwei;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.wuwei.a2ui.A2uiEngine;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.MessageRouter;
import com.wuwei.bus.WsServer;
import com.wuwei.capability.AiCapability;
import com.wuwei.capability.CapabilityManager;
import com.wuwei.capability.FileCapability;
import com.wuwei.capability.NetworkCapability;
import com.wuwei.gate.AstAuditor;
import com.wuwei.gate.EcosystemGuardian;
import com.wuwei.llm.LlmClient;
import com.wuwei.llm.LlmConfig;
import com.wuwei.llm.Normalizer;
import com.wuwei.llm.SkillGenerator;
import com.wuwei.llm.PiMonoProcess;
import com.wuwei.llm.PiMonoClient;
import com.wuwei.llm.PiMonoAdapter;
import com.wuwei.sandbox.RuntimePool;
import com.wuwei.skill.SkillManager;
import com.wuwei.snapshot.SnapshotService;
import com.wuwei.store.OpLogService;
import com.wuwei.store.SkillMemoryService;
import com.wuwei.store.SkillStateStore;
import com.wuwei.store.StoreService;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static int PORT;

    private static String configPathOverride = null;

    public static void main(String[] args) throws Exception {
        // Parse --config argument
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPathOverride = args[i + 1];
                break;
            }
        }

        // ── Wire up all components manually ─────────────────────
        ObjectMapper mapper = new ObjectMapper();

        StoreService storeService = new StoreService(mapper);
        storeService.initSchema();

        SkillMemoryService memoryService = new SkillMemoryService(mapper);

        OpLogService opLog = new OpLogService(storeService);
        EventBus eventBus = new EventBus(mapper, opLog);

        A2uiEngine a2uiEngine = new A2uiEngine(mapper);
        NetworkCapability networkCap = new NetworkCapability();
        FileCapability fileCap = new FileCapability();
        SkillStateStore stateStore = new SkillStateStore();

        // ── LLM config loaded early ──
        LlmConfig llmConfig = loadLlmConfig(mapper);
        LlmClient llmClient = new LlmClient(llmConfig, mapper);

        // ── Pi AI pipeline (primary path, starts before skill init) ──
        PiMonoProcess piProcess = null;
        PiMonoClient piClient = null;
        PiMonoAdapter piAdapter = null;
        String piExePath = loadPiExePath(mapper);
        if (piExePath != null) {
            try {
                piProcess = new PiMonoProcess(piExePath);
                piProcess.start();
                piClient = new PiMonoClient(piProcess, eventBus, mapper);
                piClient.startReadLoop();
                piAdapter = new PiMonoAdapter(piClient, eventBus, mapper);
                System.out.println("[kernel] Pi AI pipeline initialized");
            } catch (Exception e) {
                System.out.println("[kernel] Pi AI pipeline init failed: " + e.getMessage()
                    + " — falling back to built-in LLM client");
                piProcess = null;
                piClient = null;
                piAdapter = null;
            }
        } else {
            System.out.println("[kernel] Pi exe not found, using built-in LLM client");
        }

        AiCapability aiCap = new AiCapability(llmClient, piAdapter, storeService);

        CapabilityManager capManager = new CapabilityManager(stateStore, networkCap, fileCap, aiCap, eventBus);
        RuntimePool runtimePool = new RuntimePool();
        AstAuditor astAuditor = new AstAuditor(mapper);
        EcosystemGuardian guardian = new EcosystemGuardian(eventBus);

        SnapshotService snapshotService = new SnapshotService(storeService, stateStore, mapper);

        SkillManager skillManager = new SkillManager(
            runtimePool, a2uiEngine, capManager, storeService,
            stateStore, eventBus, mapper, astAuditor, guardian, snapshotService
        );
        skillManager.startupLoad();

        // ── Set up LLM pipeline (existing path, kept for fallback) ──
        Normalizer normalizer = new Normalizer(mapper);
        SkillGenerator skillGenerator = new SkillGenerator(
            llmClient, piAdapter, memoryService, storeService,
            normalizer, astAuditor, guardian,
            skillManager, snapshotService, eventBus, mapper,
            loadMaxRepairAttempts(mapper)
        );

        MessageRouter router = new MessageRouter(mapper, eventBus, skillManager, capManager, skillGenerator, piAdapter, storeService);

        // ── Start WebSocket server (Helidon picks random port) ─
        WsServer wsServer = new WsServer(0, router, eventBus);
        eventBus.setWsServer(wsServer);
        wsServer.start();

        PORT = wsServer.getPort();
        System.out.println("WUWEI_PORT:" + PORT);

        // W9: Graceful shutdown hook — saves snapshots before exit
        PiMonoProcess piProcessFinal = piProcess;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[kernel] Shutdown signal received, saving snapshots...");
            skillManager.shutdown();
            if (piProcessFinal != null) piProcessFinal.stop();
            System.out.println("[kernel] Shutdown complete");
        }, "shutdown-hook"));

        // ── Keep main thread alive ─────────────────────────────
        Thread.currentThread().join();
    }

    private static LlmConfig loadLlmConfig(ObjectMapper mapper) {
        Path configPath = findConfigFile();
        if (configPath == null) {
            System.out.println("[kernel] No wuwei.json found, LLM disabled");
            return LlmConfig.DISABLED;
        }
        try {
            String content = Files.readString(configPath);
            JsonNode root = mapper.readTree(content);
            JsonNode llm = root.get("llm");
            if (llm == null) {
                System.out.println("[kernel] wuwei.json has no 'llm' section, LLM disabled");
                return LlmConfig.DISABLED;
            }
            return mapper.convertValue(llm, LlmConfig.class);
        } catch (Exception e) {
            System.out.println("[kernel] Failed to load wuwei.json: " + e.getMessage() + ", LLM disabled");
            return LlmConfig.DISABLED;
        }
    }

    private static int loadMaxRepairAttempts(ObjectMapper mapper) {
        Path configPath = findConfigFile();
        if (configPath == null) return 10;
        try {
            JsonNode root = mapper.readTree(configPath.toFile());
            return root.has("maxRepairAttempts") ? root.get("maxRepairAttempts").asInt() : 10;
        } catch (Exception e) {
            return 10;
        }
    }

    private static String loadPiExePath(ObjectMapper mapper) {
        Path configPath = findConfigFile();
        if (configPath != null) {
            try {
                JsonNode root = mapper.readTree(configPath.toFile());
                if (root.has("piMono") && root.get("piMono").has("exe")) {
                    String exePath = root.get("piMono").get("exe").asText();
                    // Resolve relative to config file's parent (must use absolute path)
                    Path configDir = configPath.toAbsolutePath().getParent();
                    if (configDir != null) {
                        Path resolved = configDir.resolve(exePath).normalize();
                        if (Files.exists(resolved)) return resolved.toString();
                    }
                    // Try as-is (relative to CWD)
                    if (Files.exists(Path.of(exePath))) return exePath;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        // Fallback 1: dev mode via bun (relative to project root, try from CWD and parent)
        Path devPath = Path.of("wuwei-pi", "src", "server.ts");
        if (Files.exists(devPath)) return "bun run wuwei-pi/src/server.ts";
        // Fallback 2: unified binaries folder (check relative to CWD, then ../wuwei-shell)
        Path binaryPath = Path.of("wuwei-pi.exe");
        if (Files.exists(binaryPath)) return binaryPath.toAbsolutePath().toString();
        Path binaryPath2 = Path.of("..", "wuwei-shell", "binaries", "wuwei-pi.exe");
        if (Files.exists(binaryPath2)) return binaryPath2.toAbsolutePath().normalize().toString();
        return null;
    }

    private static Path findConfigFile() {
        if (configPathOverride != null) {
            Path p = Path.of(configPathOverride);
            if (Files.exists(p)) return p;
            System.out.println("[kernel] --config path not found: " + configPathOverride);
        }
        // Search in order: working dir, project root, home
        Path[] candidates = {
            Path.of("wuwei.json"),
            Path.of("..", "wuwei.json"),
            Path.of(System.getProperty("user.home"), ".wuwei", "wuwei.json")
        };
        for (Path p : candidates) {
            if (Files.exists(p)) return p.toAbsolutePath().normalize();
        }
        return null;
    }
}
