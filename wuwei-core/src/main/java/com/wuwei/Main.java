package com.wuwei;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.wuwei.a2ui.A2uiEngine;
import com.wuwei.bus.EventBus;
import com.wuwei.log.LogConfig;
import com.wuwei.rag.SkillIndexer;
import com.wuwei.bus.MessageRouter;
import com.wuwei.bus.WsServer;
import com.wuwei.capability.AiCapability;
import com.wuwei.capability.CapabilityManager;
import com.wuwei.capability.CryptoCapability;
import com.wuwei.capability.DatabaseCapability;
import com.wuwei.capability.FileCapability;
import com.wuwei.capability.NetworkCapability;
import com.wuwei.capability.WebSearchCapability;
import com.wuwei.gate.AstAuditor;
import com.wuwei.gate.EcosystemGuardian;
import com.wuwei.llm.AgentFactory;
import com.wuwei.llm.AiAskAgent;
import com.wuwei.llm.Normalizer;
import com.wuwei.llm.SkillGenerator;
import com.wuwei.llm.SummarizingChatMemoryStore;
import com.wuwei.sandbox.RuntimePool;
import com.wuwei.skill.SkillManager;
import com.wuwei.snapshot.SnapshotService;
import com.wuwei.store.OpLogService;
import com.wuwei.store.SkillMemoryService;
import com.wuwei.store.SkillStateStore;
import com.wuwei.store.StoreService;
import com.wuwei.store.ConversationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class Main {

    public static int PORT;

    private static String configPathOverride = null;
    private static String host = "127.0.0.1";
    private static int portOverride = 0;
    private static String webRoot = null;

    /** Deployment profiles for different environments. */
    public enum Profile {
        /** Local development: 127.0.0.1, random port, no static serving (Vite dev server) */
        LOCAL,
        /** Cloud production: 0.0.0.0:8080, serve SPA from ./dist, WS on same origin */
        CLOUD
    }

    private static void applyProfile(Profile profile) {
        switch (profile) {
            case LOCAL:
                // defaults already set — no change
                break;
            case CLOUD:
                host = "0.0.0.0";
                portOverride = portOverride > 0 ? portOverride : 8080;
                if (webRoot == null) {
                    // Auto-detect: try ./dist (same dir as jar), then ../wuwei-renderer/dist
                    Path dist = Path.of("dist");
                    if (Files.isDirectory(dist)) {
                        webRoot = dist.toAbsolutePath().toString();
                    } else {
                        Path rendererDist = Path.of("..", "wuwei-renderer", "dist");
                        if (Files.isDirectory(rendererDist)) {
                            webRoot = rendererDist.toAbsolutePath().toString();
                        }
                    }
                }
                break;
        }
    }

    public static void main(String[] args) throws Exception {
        // Parse CLI arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config": configPathOverride = args[++i]; break;
                case "--host": host = args[++i]; break;
                case "--port": portOverride = Integer.parseInt(args[++i]); break;
                case "--web-root": webRoot = args[++i]; break;
                case "--profile":
                    applyProfile("local".equalsIgnoreCase(args[++i]) ? Profile.LOCAL : Profile.CLOUD);
                    break;
            }
        }

        // Init logging to dated files before anything else
        LogConfig.init();

        // ── Wire up all components manually ─────────────────────
        ObjectMapper mapper = new ObjectMapper();

        StoreService storeService = new StoreService(mapper);
        storeService.initSchema();

        // Seed model routing from wuwei.json if the table is empty
        Path configPath = findConfigFile();
        if (configPath != null) {
            try {
                JsonNode cfg = mapper.readTree(configPath.toFile());
                if (cfg.has("llm")) {
                    JsonNode llm = cfg.get("llm");
                    String baseUrl = (llm.has("baseUrl") && !llm.get("baseUrl").isNull()) ? llm.get("baseUrl").asText() : "";
                    storeService.seedDefaultRouting(Map.of(
                        "provider", llm.has("provider") ? llm.get("provider").asText() : "deepseek",
                        "model", llm.has("model") ? llm.get("model").asText() : "deepseek-chat",
                        "apiUrl", baseUrl,
                        "params", "{}"
                    ));
                }
            } catch (Exception e) {
                System.out.println("[kernel] Failed to read LLM config from wuwei.json: " + e.getMessage());
            }
        }

        SkillMemoryService memoryService = new SkillMemoryService(mapper);

        OpLogService opLog = new OpLogService(storeService);
        EventBus eventBus = new EventBus(mapper, opLog);

        A2uiEngine a2uiEngine = new A2uiEngine(mapper);
        NetworkCapability networkCap = new NetworkCapability();
        FileCapability fileCap = new FileCapability();
        SkillStateStore stateStore = new SkillStateStore();

        // ── LLM pipeline (LangChain4j AiServices + ChatMemory) ─
        // SummarizingChatMemoryStore needs a cheap model for summarization.
        Map<String, String> defaultRouting = storeService.getModelRouting("ai/ask");
        AiAskAgent summaryAgent = AgentFactory.createAskAgentStatic(defaultRouting);
        SummarizingChatMemoryStore memoryStore = new SummarizingChatMemoryStore(storeService, summaryAgent);

        AgentFactory agentFactory = new AgentFactory(storeService, memoryStore);
        System.out.println("[kernel] LLM service initialized (LangChain4j AiServices + ChatMemory)");

        AiCapability aiCap = new AiCapability(agentFactory, storeService);
        CryptoCapability cryptoCap = new CryptoCapability();
        DatabaseCapability databaseCap = new DatabaseCapability();
        WebSearchCapability webSearchCap = new WebSearchCapability(storeService, mapper);

        CapabilityManager capManager = new CapabilityManager(stateStore, networkCap, fileCap,
            aiCap, cryptoCap, databaseCap, webSearchCap, eventBus);
        RuntimePool runtimePool = new RuntimePool();
        AstAuditor astAuditor = new AstAuditor(mapper);
        EcosystemGuardian guardian = new EcosystemGuardian(eventBus);

        SnapshotService snapshotService = new SnapshotService(storeService, stateStore, mapper);
        ConversationService conversationService = new ConversationService(storeService, mapper);

        // RAG: PageIndex-style skill indexer for LLM-based retrieval
        SkillIndexer skillIndexer = new SkillIndexer(mapper, agentFactory);

        SkillManager skillManager = new SkillManager(
            runtimePool, a2uiEngine, capManager, storeService,
            stateStore, eventBus, mapper, astAuditor, guardian, snapshotService,
            conversationService, skillIndexer
        );
        skillManager.startupLoad();

        // ── Set up LLM pipeline ──
        Normalizer normalizer = new Normalizer(mapper);

        SkillGenerator skillGenerator = new SkillGenerator(
            agentFactory, memoryService, storeService,
            normalizer, astAuditor, guardian,
            skillManager, skillIndexer, snapshotService,
            eventBus, mapper,
            conversationService, null,
            loadMaxRepairAttempts(mapper)
        );

        MessageRouter router = new MessageRouter(mapper, eventBus, skillManager, capManager, skillGenerator, agentFactory, storeService, conversationService);

        // Wire the message-update callback: SkillGenerator aggregates steps → push single message
        skillGenerator.setOnMessageUpdate((threadId, msgRecord) -> router.pushMessageUpdate(threadId, msgRecord));
        skillManager.setOnConvUpdate(threadId -> router.pushConversationUpdate(threadId));

        // ── Start WebSocket server (Helidon picks random port) ─
        WsServer wsServer = new WsServer(
            host, portOverride,
            router, eventBus,
            webRoot != null ? Path.of(webRoot) : null
        );
        eventBus.setWsServer(wsServer);
        wsServer.start();

        PORT = wsServer.getPort();
        System.out.println("WUWEI_PORT:" + PORT);

        // Rebuild skill index (PageIndex-style) in background
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            System.out.println("[kernel] Starting skill index rebuild...");
            skillIndexer.rebuildAll();
        }, "rag-rebuild").start();

        // W9: Graceful shutdown hook — saves snapshots before exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[kernel] Shutdown signal received, saving snapshots...");
            skillManager.shutdown();
            System.out.println("[kernel] Shutdown complete");
        }, "shutdown-hook"));

        // ── Keep main thread alive ─────────────────────────────
        Thread.currentThread().join();
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
