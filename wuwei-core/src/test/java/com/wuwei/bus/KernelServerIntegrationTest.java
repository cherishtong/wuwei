package com.wuwei.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.a2ui.A2uiEngine;
import com.wuwei.capability.AiCapability;
import com.wuwei.capability.CapabilityManager;
import com.wuwei.capability.CryptoCapability;
import com.wuwei.capability.DatabaseCapability;
import com.wuwei.capability.FileCapability;
import com.wuwei.capability.NetworkCapability;
import com.wuwei.capability.WebSearchCapability;
import com.wuwei.gate.AstAuditor;
import com.wuwei.gate.EcosystemGuardian;
import com.wuwei.llm.Normalizer;
import com.wuwei.llm.SkillGenerator;
import com.wuwei.sandbox.RuntimePool;
import com.wuwei.skill.SkillManager;
import com.wuwei.snapshot.SnapshotService;
import com.wuwei.store.OpLogService;
import com.wuwei.store.SkillMemoryService;
import com.wuwei.store.ConversationService;
import com.wuwei.store.SkillStateStore;
import com.wuwei.store.StoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KernelServerIntegrationTest {

    private ObjectMapper mapper;
    private WsServer wsServer;
    private HttpClient httpClient;
    private BlockingQueue<String> received;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        httpClient = HttpClient.newHttpClient();
        received = new ArrayBlockingQueue<>(10);

        // Wire minimal components
        StoreService storeService = new StoreService(mapper);
        storeService.initSchema();
        SkillMemoryService memoryService = new SkillMemoryService(mapper);
        OpLogService opLog = new OpLogService(storeService);
        EventBus eventBus = new EventBus(mapper, opLog);
        A2uiEngine a2uiEngine = new A2uiEngine(mapper);
        NetworkCapability networkCap = new NetworkCapability();
        FileCapability fileCap = new FileCapability();
        SkillStateStore stateStore = new SkillStateStore();
        AiCapability aiCap = new AiCapability(null, storeService);
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
            conversationService, null
        );
        skillManager.startupLoad();
        Normalizer normalizer = new Normalizer(mapper);
        SkillGenerator skillGenerator = new SkillGenerator(
            null, memoryService, storeService,
            normalizer, astAuditor, guardian,
            skillManager, null, snapshotService, eventBus, mapper,
            conversationService, null,
            2
        );
        MessageRouter router = new MessageRouter(mapper, eventBus, skillManager, capManager, skillGenerator, null, storeService, conversationService);

        // Start WsServer on random port
        wsServer = new WsServer("127.0.0.1", 0, router, eventBus, null);
        eventBus.setWsServer(wsServer);
        wsServer.start();
    }

    @AfterEach
    void tearDown() {
        // Server will be cleaned up by GC
    }

    @Test
    void connect_shouldReceiveKernelReady() throws Exception {
        WebSocket ws = httpClient.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + wsServer.getPort() + "/ws"),
                new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket,
                                                        CharSequence data, boolean last) {
                        received.offer(data.toString());
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                })
            .get(5, TimeUnit.SECONDS);

        // 1. Verify kernel-ready on connect
        String ready = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(ready, "Should receive kernel-ready on connect");
        JsonNode readyNode = mapper.readTree(ready);
        assertEquals("kernel-ready", readyNode.get("type").asText());
        assertEquals("0.0.1-beta", readyNode.get("version").asText());
        assertTrue(readyNode.get("port").asInt() > 0);

        // 2. Send list-skills, verify correct response
        ws.sendText("{\"type\":\"list-skills\"}", true);

        String listResp = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(listResp, "Should receive skill-list response");
        JsonNode listNode = mapper.readTree(listResp);
        assertEquals("skill-list", listNode.get("type").asText());
        assertTrue(listNode.get("skills").isArray());

        ws.sendClose(1000, "done");
    }
}
