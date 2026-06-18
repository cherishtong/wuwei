package com.wuwei.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Exposes resume operations to browser-js skills via capability proxy.
 *
 * JS API:
 *   // Templates
 *   capability.resume.list() → [{name, size, placeholders}]
 *   capability.resume.read(name) → base64 docx
 *   capability.resume.upload(name, base64) → {name, size, placeholders}
 *   capability.resume.delete(name) → void
 *   capability.resume.parse(name) → {placeholders}
 *
 *   // Data
 *   capability.resume.dataList() → [{name, createdAt, updatedAt}]
 *   capability.resume.dataLoad(name) → {name, dataJson, mappingJson}
 *   capability.resume.dataSave(name, dataJson, mappingJson) → void
 *   capability.resume.dataDelete(name) → void
 *
 *   // AI
 *   capability.resume.optimize(dataJson, mappingJson, suggestion) → optimized dataJson
 *   capability.resume.generateMapping(templateName, dataJson) → mappingJson
 *
 *   // Render
 *   capability.resume.render(templateName, data) → base64 docx
 */
@Component
public class ResumeCapability {

    private static final Logger log = LoggerFactory.getLogger(ResumeCapability.class);

    private final ResumeTemplateService templateService;
    private final ResumeDataService dataService;

    public ResumeCapability(ResumeTemplateService templateService, ResumeDataService dataService) {
        this.templateService = templateService;
        this.dataService = dataService;
    }

    public ProxyObject forSkill() {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    // ── Template ──
                    case "list" -> (ProxyExecutable) args -> templateService.listTemplates();
                    case "read" -> (ProxyExecutable) args -> {
                        try { return Base64.getEncoder().encodeToString(
                            templateService.readTemplate(args[0].asString()));
                        } catch (Exception e) { return Map.of("error", e.getMessage()); }
                    };
                    case "upload" -> (ProxyExecutable) args -> {
                        try {
                            String name = args[0].asString();
                            byte[] content = Base64.getDecoder().decode(args[1].asString());
                            templateService.saveTemplate(name, content);
                            return Map.of("name", name, "size", content.length,
                                "placeholders", templateService.parsePlaceholders(name));
                        } catch (Exception e) { return Map.of("error", e.getMessage()); }
                    };
                    case "delete" -> (ProxyExecutable) args -> {
                        try { templateService.deleteTemplate(args[0].asString()); return Map.of("ok", true); }
                        catch (Exception e) { return Map.of("error", e.getMessage()); }
                    };
                    case "parse" -> (ProxyExecutable) args -> {
                        try { return Map.of("placeholders", templateService.parsePlaceholders(args[0].asString())); }
                        catch (Exception e) { return Map.of("placeholders", List.of()); }
                    };

                    // ── Data CRUD ──
                    case "dataList" -> (ProxyExecutable) args -> dataService.list();
                    case "dataLoad" -> (ProxyExecutable) args -> {
                        var r = dataService.load(args[0].asString());
                        return r != null ? r : Map.of("error", "Not found");
                    };
                    case "dataSave" -> (ProxyExecutable) args -> {
                        dataService.save(args[0].asString(), args[1].asString(),
                            args.length > 2 ? args[2].asString() : null);
                        return Map.of("ok", true);
                    };
                    case "dataDelete" -> (ProxyExecutable) args -> {
                        dataService.delete(args[0].asString()); return Map.of("ok", true);
                    };

                    // ── AI ──
                    case "optimize" -> (ProxyExecutable) args -> {
                        try {
                            return dataService.optimize(
                                args[0].asString(), args[1].asString(),
                                args.length > 2 ? args[2].asString() : "");
                        } catch (Exception e) { return Map.of("error", e.getMessage()); }
                    };
                    case "generateMapping" -> (ProxyExecutable) args -> {
                        try {
                            String tplName = args[0].asString();
                            String dataJson = args[1].asString();
                            var placeholders = templateService.parsePlaceholders(tplName);
                            return dataService.generateMapping(tplName, placeholders, dataJson);
                        } catch (Exception e) { return Map.of("error", e.getMessage()); }
                    };

                    // ── Render ──
                    case "render" -> (ProxyExecutable) args -> {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = new ObjectMapper().readValue(args[1].asString(), Map.class);
                            return Base64.getEncoder().encodeToString(
                                templateService.render(args[0].asString(), data));
                        } catch (Exception e) { return Map.of("error", e.getMessage()); }
                    };

                    default -> null;
                };
            }

            @Override public boolean hasMember(String key) {
                return Set.of("list","read","upload","delete","parse",
                    "dataList","dataLoad","dataSave","dataDelete",
                    "optimize","generateMapping","render").contains(key);
            }
            @Override public Set<String> getMemberKeys() {
                return Set.of("list","read","upload","delete","parse",
                    "dataList","dataLoad","dataSave","dataDelete",
                    "optimize","generateMapping","render");
            }
            @Override public void putMember(String key, Value value) {}
            @Override public boolean removeMember(String key) { return false; }
        };
    }
}
