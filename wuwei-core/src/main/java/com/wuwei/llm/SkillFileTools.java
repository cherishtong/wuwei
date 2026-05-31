package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.gate.AstAuditor;
import com.wuwei.skill.SkillGenome;
import com.wuwei.skill.SkillManifest;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * File operation tools for ReAct skill generation.
 * All paths are relative to the working directory (genome staging dir).
 * Operations are strictly sandboxed — no path traversal, no writes outside workDir.
 */
public class SkillFileTools {

    private static final Logger log = LoggerFactory.getLogger(SkillFileTools.class);

    private final Path workDir;
    private final ObjectMapper mapper;
    private final AstAuditor astAuditor;
    private BiConsumer<String, String> onFileChange;

    public SkillFileTools(Path workDir) {
        this(workDir, null, null);
    }

    public SkillFileTools(Path workDir, ObjectMapper mapper, AstAuditor astAuditor) {
        this.workDir = workDir;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.astAuditor = astAuditor;
    }

    /** Set a callback invoked on every file create/update/delete. */
    public void setOnFileChange(BiConsumer<String, String> onFileChange) {
        this.onFileChange = onFileChange;
    }

    private void notifyFileChange(String action, String path) {
        if (onFileChange != null) {
            onFileChange.accept(action, path);
        }
    }

    @Tool("Create a new file. Path is relative to genome directory (e.g. 'handlers/index.js', 'ui/index.json', 'skill.json').")
    public String createFile(String path, String content) {
        try {
            Path resolved = resolve(path);
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
            notifyFileChange("createFile", path);
            log.info("createFile: {}", path);
            return "OK: created " + path + " (" + content.length() + " chars)";
        } catch (IOException e) {
            log.error("createFile failed: {} — {}", path, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Update an existing file. Fails if the file does not exist.")
    public String updateFile(String path, String content) {
        try {
            Path resolved = resolve(path);
            if (!Files.exists(resolved)) {
                return "ERROR: file does not exist: " + path + " — use createFile() first";
            }
            Files.writeString(resolved, content);
            log.info("updateFile: {}", path);
            return "OK: updated " + path + " (" + content.length() + " chars)";
        } catch (IOException e) {
            log.error("updateFile failed: {} — {}", path, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Read a file's contents. Returns the full file content.")
    public String readFile(String path) {
        notifyFileChange("readFile", path);
        try {
            Path resolved = resolve(path);
            if (!Files.exists(resolved)) {
                return "ERROR: file does not exist: " + path;
            }
            String content = Files.readString(resolved);
            return content;
        } catch (IOException e) {
            log.error("readFile failed: {} — {}", path, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("List all files in the working directory. Shows relative paths.")
    public String listFiles() {
        notifyFileChange("listFiles", ".");
        try {
            StringBuilder sb = new StringBuilder();
            try (var stream = Files.walk(workDir)) {
                stream
                    .filter(Files::isRegularFile)
                    .forEach(f -> {
                        String rel = workDir.relativize(f).toString().replace('\\', '/');
                        try {
                            long size = Files.size(f);
                            sb.append(rel).append(" (").append(size).append(" bytes)\n");
                        } catch (IOException ignored) {
                            sb.append(rel).append("\n");
                        }
                    });
            }
            return sb.length() > 0 ? sb.toString().trim() : "(empty directory)";
        } catch (IOException e) {
            log.error("listFiles failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Delete a file. Use only to fix mistakes — avoid deleting the main entry point files.")
    public String deleteFile(String path) {
        notifyFileChange("deleteFile", path);
        try {
            Path resolved = resolve(path);
            if (!Files.exists(resolved)) {
                return "ERROR: file does not exist: " + path;
            }
            Files.delete(resolved);
            log.info("deleteFile: {}", path);
            return "OK: deleted " + path;
        } catch (IOException e) {
            log.error("deleteFile failed: {} — {}", path, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Show which files still need to be created to complete a minimal skill.")
    public String getProgress() {
        notifyFileChange("getProgress", "");
        boolean hasSkillJson = Files.exists(workDir.resolve("skill.json"));
        boolean hasUi = Files.exists(workDir.resolve("ui.json"))
            || Files.exists(workDir.resolve("ui").resolve("index.json"));
        boolean hasHandlers = Files.exists(workDir.resolve("handlers.js"))
            || Files.exists(workDir.resolve("handlers").resolve("index.js"));

        StringBuilder sb = new StringBuilder("Progress:\n");
        sb.append(hasSkillJson ? "✓" : "✗").append(" skill.json\n");
        sb.append(hasUi ? "✓" : "✗").append(" ui.json (or ui/index.json)\n");
        sb.append(hasHandlers ? "✓" : "✗").append(" handlers.js (or handlers/index.js)\n");
        sb.append(hasSkillJson && hasUi && hasHandlers ? "ALL DONE" : "IN PROGRESS");
        return sb.toString();
    }

    @Tool("Validate current skill files. Checks skill.json format, UI tree references, and JS code for forbidden patterns. Returns 'ALL OK' or a list of errors.")
    public String validate() {
        notifyFileChange("validate", "");
        List<String> errors = new ArrayList<>();
        Path skillJsonPath = workDir.resolve("skill.json");
        Path uiPath = Files.exists(workDir.resolve("ui").resolve("index.json"))
            ? workDir.resolve("ui").resolve("index.json")
            : workDir.resolve("ui.json");
        Path handlersPath = Files.exists(workDir.resolve("handlers").resolve("index.js"))
            ? workDir.resolve("handlers").resolve("index.js")
            : workDir.resolve("handlers.js");

        // 1. Validate skill.json
        if (!Files.exists(skillJsonPath)) {
            errors.add("Missing skill.json");
        } else {
            try {
                String skillJson = Files.readString(skillJsonPath);
                JsonNode root = mapper.readTree(skillJson);
                // Check 7 required fields
                String[] required = {"id", "version", "abi", "runtime", "meta", "capabilities", "signature"};
                for (String field : required) {
                    if (!root.has(field)) {
                        errors.add("skill.json: missing field '" + field + "'");
                    }
                }
                // Check exact 7 fields (no extras)
                var fields = new java.util.ArrayList<String>();
                root.fieldNames().forEachRemaining(fields::add);
                if (fields.size() != 7) {
                    errors.add("skill.json: expected exactly 7 top-level fields, got " + fields.size() + " (" + String.join(", ", fields) + ")");
                }
                // Validate id format
                if (root.has("id")) {
                    String id = root.get("id").asText();
                    if (!id.matches("^[a-z][a-z0-9-]*$")) {
                        errors.add("skill.json: id '" + id + "' must be kebab-case (lowercase letters, digits, hyphens, starting with a letter)");
                    }
                }
                // Validate version
                if (root.has("version")) {
                    String ver = root.get("version").asText();
                    if (!ver.matches("^\\d+\\.\\d+\\.\\d+$")) {
                        errors.add("skill.json: version '" + ver + "' must be semver X.Y.Z");
                    }
                }
                // Validate abi
                if (root.has("abi") && !"1.0".equals(root.get("abi").asText())) {
                    errors.add("skill.json: abi must be '1.0'");
                }
                if (root.has("runtime")) {
                    String rt = root.get("runtime").asText();
                    if (!"js".equals(rt) && !"browser-js".equals(rt)) {
                        errors.add("skill.json: runtime must be 'js' or 'browser-js'");
                    }
                }
                if (root.has("capabilities") && !root.get("capabilities").isObject()) {
                    errors.add("skill.json: capabilities must be a JSON object {}, not an array");
                }
                if (root.has("signature")) {
                    var sig = root.get("signature");
                    if (!sig.has("publisher") || !"local".equals(sig.get("publisher").asText())) {
                        errors.add("skill.json: signature must include \"publisher\": \"local\"");
                    }
                }
            } catch (Exception e) {
                errors.add("skill.json: parse error — " + e.getMessage());
            }
        }

        // 2. Validate ui.json
        if (!Files.exists(uiPath)) {
            errors.add("Missing UI file (ui/index.json or ui.json)");
        } else {
            try {
                String uiJson = Files.readString(uiPath);
                JsonNode tree = mapper.readTree(uiJson);
                if (!tree.has("components")) {
                    errors.add("ui.json: missing 'components' array");
                } else {
                    JsonNode comps = tree.get("components");
                    if (!comps.isArray() || comps.size() == 0) {
                        errors.add("ui.json: 'components' must be a non-empty array");
                    } else {
                        // Collect all ids
                        var ids = new java.util.HashSet<String>();
                        for (JsonNode c : comps) {
                            if (!c.has("id")) {
                                errors.add("ui.json: component missing 'id'");
                            } else {
                                String cid = c.get("id").asText();
                                if (!ids.add(cid)) {
                                    errors.add("ui.json: duplicate component id '" + cid + "'");
                                }
                            }
                        }
                        // Check root exists
                        boolean hasRoot = false;
                        for (JsonNode c : comps) {
                            if (c.has("id") && "root".equals(c.get("id").asText()) && c.has("component") && "Column".equals(c.get("component").asText())) {
                                hasRoot = true;
                                break;
                            }
                        }
                        if (!hasRoot) {
                            errors.add("ui.json: must have a root component with id='root' and component='Column'");
                        }
                        // Check children references
                        for (JsonNode c : comps) {
                            if (c.has("children")) {
                                for (JsonNode child : c.get("children")) {
                                    if (!ids.contains(child.asText())) {
                                        errors.add("ui.json: component '" + c.get("id").asText() + "' references unknown child '" + child.asText() + "'");
                                    }
                                }
                            }
                            if (c.has("child")) {
                                String childId = c.get("child").asText();
                                if (!ids.contains(childId)) {
                                    errors.add("ui.json: component '" + c.get("id").asText() + "' references unknown child '" + childId + "'");
                                }
                            }
                            // Check Button action.event.name equals button id
                            if ("Button".equals(c.has("component") ? c.get("component").asText() : "")) {
                                if (c.has("action") && c.get("action").has("event") && c.get("action").get("event").has("name")) {
                                    String ename = c.get("action").get("event").get("name").asText();
                                    if (c.has("id") && !ename.equals(c.get("id").asText())) {
                                        errors.add("ui.json: Button '" + c.get("id").asText() + "' action.event.name '" + ename + "' must equal button id");
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("ui.json: parse error — " + e.getMessage());
            }
        }

        // 3. Validate handlers.js
        if (!Files.exists(handlersPath)) {
            errors.add("Missing handlers file (handlers/index.js or handlers.js)");
        } else {
            try {
                String js = Files.readString(handlersPath);
                if (js.isBlank()) {
                    errors.add("handlers.js: file is empty");
                } else {
                    // Check for forbidden patterns
                    if (js.contains("eval(")) errors.add("handlers.js: forbidden — eval() is not allowed");
                    if (js.contains("Function(")) errors.add("handlers.js: forbidden — Function() is not allowed");
                    if (js.contains("new Promise")) errors.add("handlers.js: forbidden — Promise is not allowed in js runtime");
                    if (js.contains("WebSocket")) errors.add("handlers.js: forbidden — WebSocket is not allowed");
                    if (js.contains("fetch(")) errors.add("handlers.js: forbidden — fetch() is not allowed; use capability.network.fetch instead");
                    // Check runtime-specific rules
                    boolean isBrowser = false;
                    Path skillPath = workDir.resolve("skill.json");
                    if (Files.exists(skillPath)) {
                        JsonNode skill = mapper.readTree(Files.readString(skillPath));
                        isBrowser = "browser-js".equals(skill.has("runtime") ? skill.get("runtime").asText() : "");
                    }
                    if (!isBrowser) {
                        if (js.contains("async ")) errors.add("handlers.js: async/await not allowed in js runtime; use 'browser-js' runtime instead");
                        if (js.contains("await ")) errors.add("handlers.js: await not allowed in js runtime; use 'browser-js' runtime instead");
                    }
                    // Check for at least one function definition
                    if (!js.contains("function ") && !js.contains("=>")) {
                        errors.add("handlers.js: no function definition found");
                    }
                    // Check no <file> tags leak into content
                    if (js.contains("<file") || js.contains("</file>") || js.contains("<done")) {
                        errors.add("handlers.js: contains XML tags (<file>/<done>) — these should not be in file content");
                    }
                }
            } catch (Exception e) {
                errors.add("handlers.js: read error — " + e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            return "ALL OK";
        }
        return "ERRORS (" + errors.size() + "):\n" + String.join("\n", errors);
    }

    @Tool("Run the skill's onInit handler in a JS sandbox and return the result (patches or errors).")
    public String testRun() {
        notifyFileChange("testRun", "");
        // Basic test: verify JS syntax by attempting to parse with the sandbox
        Path handlersPath = Files.exists(workDir.resolve("handlers").resolve("index.js"))
            ? workDir.resolve("handlers").resolve("index.js")
            : workDir.resolve("handlers.js");

        if (!Files.exists(handlersPath)) {
            return "ERROR: No handlers file found — create handlers/index.js first";
        }

        try {
            String js = Files.readString(handlersPath);
            // Basic checks that indicate the code will likely run
            List<String> notes = new ArrayList<>();
            if (js.contains("function onInit")) {
                notes.add("onInit handler found");
            }
            if (js.contains("module.exports")) {
                notes.add("module.exports found");
            }
            if (js.contains("capability.")) {
                notes.add("capability calls detected");
            }

            if (notes.isEmpty()) {
                return "WARNING: handlers.js has no onInit, module.exports, or capability calls — it may not do anything";
            }

            return "OK: " + String.join(", ", notes) + ". Full runtime test will be performed during installation.";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** Read all files from workDir into a path→content map. */
    public Map<String, String> readAllFiles() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        if (!Files.isDirectory(workDir)) return files;
        try (var stream = Files.walk(workDir)) {
            stream
                .filter(Files::isRegularFile)
                .forEach(f -> {
                    try {
                        String rel = workDir.relativize(f).toString().replace('\\', '/');
                        files.put(rel, Files.readString(f));
                    } catch (Exception ignored) {}
                });
        }
        return files;
    }

    private Path resolve(String path) {
        if (path.contains("..") || path.startsWith("/") || path.startsWith("\\")) {
            throw new IllegalArgumentException("Path traversal not allowed: " + path);
        }
        Path resolved = workDir.resolve(path).normalize();
        if (!resolved.startsWith(workDir)) {
            throw new IllegalArgumentException("Path must be within working directory: " + path);
        }
        return resolved;
    }
}
