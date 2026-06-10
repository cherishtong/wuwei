package com.wuwei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.llm.AgentFactory;
import com.wuwei.llm.AiAskAgent;
import com.wuwei.llm.SummarizingChatMemoryStore;
import com.wuwei.log.LogConfig;
import com.wuwei.rag.SkillIndexer;
import com.wuwei.skill.SkillManager;
import com.wuwei.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Value("${wuwei.config.path:}")
    private String configPathOverride;

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    // ── LLM dependency chain (manual wiring for LangChain4j) ──────

    @Bean
    AiAskAgent aiAskAgent(StoreService storeService) {
        var routing = storeService.getModelRouting("ai/ask");
        return AgentFactory.createAskAgentStatic(routing);
    }

    // ── Startup sequence ─────────────────────────────────────────

    @Bean
    ApplicationRunner startupRunner(SkillManager skillManager, SkillIndexer skillIndexer,
                                    StoreService storeService, ObjectMapper mapper) {
        return args -> {
            // Init log directories
            LogConfig.init();

            // Seed model routing from wuwei.json if present
            Path configPath = findConfigFile();
            if (configPath != null) {
                try {
                    JsonNode cfg = mapper.readTree(configPath.toFile());
                    if (cfg.has("llm")) {
                        JsonNode llm = cfg.get("llm");
                        String baseUrl = (llm.has("baseUrl") && !llm.get("baseUrl").isNull())
                            ? llm.get("baseUrl").asText() : "";
                        storeService.seedDefaultRouting(Map.of(
                            "provider", llm.has("provider") ? llm.get("provider").asText() : "deepseek",
                            "model", llm.has("model") ? llm.get("model").asText() : "deepseek-chat",
                            "apiUrl", baseUrl,
                            "params", "{}"
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Failed to read LLM config from wuwei.json: {}", e.getMessage());
                }
            }

            // Startup-load all skills
            skillManager.startupLoad();
            log.info("Skills loaded: {}", skillManager.getLoadedCount());

            // Rebuild skill index in background after startup
            new Thread(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                log.info("Starting skill index rebuild...");
                skillIndexer.rebuildAll();
            }, "rag-rebuild").start();
        };
    }

    private Path findConfigFile() {
        if (configPathOverride != null && !configPathOverride.isEmpty()) {
            Path p = Path.of(configPathOverride);
            if (Files.exists(p)) return p;
        }
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
