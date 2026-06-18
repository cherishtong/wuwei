package com.wuwei.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.entity.ResumeDataEntity;
import com.wuwei.llm.AgentFactory;
import com.wuwei.repo.ResumeDataRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Resume data management with Spring AI-native LLM optimization.
 * Uses {@link AgentFactory#stream(String, String, String)} for real-time
 * streaming optimization feedback via {@code Flux<String>}.
 */
@Service
public class ResumeDataService {

    private static final Logger log = LoggerFactory.getLogger(ResumeDataService.class);

    private final ResumeDataRepo repo;
    private final ObjectMapper mapper;
    private final AgentFactory agentFactory;
    private final EventBus eventBus;

    public ResumeDataService(ResumeDataRepo repo, ObjectMapper mapper,
                             AgentFactory agentFactory, EventBus eventBus) {
        this.repo = repo;
        this.mapper = mapper;
        this.agentFactory = agentFactory;
        this.eventBus = eventBus;
    }

    public List<Map<String, Object>> list() {
        return repo.findAll().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getName());
            m.put("createdAt", e.getCreatedAt());
            m.put("updatedAt", e.getUpdatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    @Transactional
    public void save(String name, String dataJson, String mappingJson) {
        var e = repo.findById(name).orElse(new ResumeDataEntity(name, dataJson));
        e.setDataJson(dataJson);
        if (mappingJson != null) e.setMappingJson(mappingJson);
        e.setUpdatedAt(System.currentTimeMillis() / 1000);
        repo.save(e);
    }

    public Map<String, Object> load(String name) {
        return repo.findById(name).map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getName());
            m.put("dataJson", e.getDataJson());
            m.put("mappingJson", e.getMappingJson());
            m.put("createdAt", e.getCreatedAt());
            m.put("updatedAt", e.getUpdatedAt());
            return m;
        }).orElse(null);
    }

    @Transactional
    public void delete(String name) { repo.deleteById(name); }

    /**
     * Spring AI-native streaming optimization.
     * Uses {@code Flux<String>} from {@code ChatClient.stream().content()}
     * for real token-by-token streaming, publishing progress via EventBus.
     */
    public String optimize(String dataJson, String mappingJson, String suggestion) {
        log.info("optimize called: dataLen={} mappingLen={}",
            dataJson != null ? dataJson.length() : 0,
            mappingJson != null ? mappingJson.length() : 0);
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一位专业简历优化顾问。请根据模板映射关系，优化用户的简历数据。\n\n");
            prompt.append("## 模板映射关系\n").append(mappingJson).append("\n\n");
            prompt.append("## 用户原始数据\n").append(dataJson).append("\n\n");
            if (suggestion != null && !suggestion.isBlank()) {
                prompt.append("## 用户优化建议\n").append(suggestion).append("\n\n");
            }
            prompt.append("## 要求\n")
                .append("1. 对每个字段进行专业优化（STAR法则量化、措辞专业化）\n")
                .append("2. 保持数据结构不变，只优化内容\n")
                .append("3. 返回完整JSON，不要额外文字和markdown标记\n");

            log.info("optimize starting stream...");
            var future = new CompletableFuture<String>();
            var resultBuffer = new StringBuilder();

            String taskType = "resume/optimize";
            Flux<String> flux = agentFactory.stream(null, prompt.toString(), taskType);

            flux.doOnNext(chunk -> {
                resultBuffer.append(chunk);
                if (resultBuffer.length() % 80 < chunk.length()) {
                    log.info("optimize streaming: {} chars", resultBuffer.length());
                }
            }).doOnComplete(() -> {
                String text = resultBuffer.toString().trim();
                log.info("optimize stream complete: {} chars", text.length());
                future.complete(text);
            }).doOnError(error -> {
                log.error("optimize stream error", error);
                future.completeExceptionally(error);
            }).subscribe();

            log.info("optimize waiting for result...");
            String result = future.get();
            log.info("optimize done: {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.error("Optimization failed", e);
            return dataJson;
        }
    }

    /**
     * Generate template mapping via LLM.
     * Synchronous call — mapping generation is fast and needs the result immediately.
     */
    public String generateMapping(String templateName, List<String> placeholders, String dataJson) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("根据模板占位符和用户数据结构，生成映射关系 JSON。\n\n");
            prompt.append("## 模板占位符\n")
                .append(String.join(", ", placeholders)).append("\n\n");
            prompt.append("## 用户数据结构\n").append(dataJson).append("\n\n");
            prompt.append("返回 JSON: { \"占位符名\": \"data字段路径\", ... }\n只输出 JSON。");

            String result = agentFactory.rawChat(prompt.toString());
            return result != null ? result.trim() : "{}";
        } catch (Exception e) {
            log.error("Mapping generation failed", e);
            return "{}";
        }
    }
}
