package com.wuwei;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.wuwei.a2ui.A2uiEngine;
import com.wuwei.bus.EventBus;
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

        // Seed model routing from wuwei.json if the table is empty
        Path configPath = findConfigFile();
        if (configPath != null) {
            try {
                JsonNode cfg = mapper.readTree(configPath.toFile());
                if (cfg.has("llm")) {
                    JsonNode llm = cfg.get("llm");
                    String baseUrl = (llm.has("baseUrl") && !llm.get("baseUrl").isNull()) ? llm.get("baseUrl").asText() : "";
                    String apiKey = (llm.has("apiKey") && !llm.get("apiKey").isNull()) ? llm.get("apiKey").asText() : "";
                    storeService.seedDefaultRouting(Map.of(
                        "provider", llm.has("provider") ? llm.get("provider").asText() : "deepseek",
                        "model", llm.has("model") ? llm.get("model").asText() : "deepseek-chat",
                        "apiUrl", baseUrl,
                        "apiKey", apiKey,
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

        SkillManager skillManager = new SkillManager(
            runtimePool, a2uiEngine, capManager, storeService,
            stateStore, eventBus, mapper, astAuditor, guardian, snapshotService,
            conversationService
        );
        skillManager.startupLoad();

        // ── Set up LLM pipeline ──
        Normalizer normalizer = new Normalizer(mapper);

        SkillGenerator skillGenerator = new SkillGenerator(
            agentFactory, memoryService, storeService,
            normalizer, astAuditor, guardian,
            skillManager, snapshotService, eventBus, mapper,
            conversationService, null,
            loadMaxRepairAttempts(mapper)
        );

        MessageRouter router = new MessageRouter(mapper, eventBus, skillManager, capManager, skillGenerator, agentFactory, storeService, conversationService);

        // Wire the message-update callback: SkillGenerator aggregates steps → push single message
        skillGenerator.setOnMessageUpdate((threadId, msgRecord) -> router.pushMessageUpdate(threadId, msgRecord));
        skillManager.setOnConvUpdate(threadId -> router.pushConversationUpdate(threadId));

        // ── Start WebSocket server (Helidon picks random port) ─
        WsServer wsServer = new WsServer(0, router, eventBus);
        eventBus.setWsServer(wsServer);
        wsServer.start();

        PORT = wsServer.getPort();
        System.out.println("WUWEI_PORT:" + PORT);

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
