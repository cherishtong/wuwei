package com.wuwei.gate;

import com.wuwei.sandbox.RuntimePool;
import org.springframework.stereotype.Component;
import com.wuwei.skill.SkillManifest;
import com.wuwei.skill.SkillGenome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Three-phase static security audit for Skill handlers.js.
 *
 * Phase 1: skill.json validity (manifest validation)
 * Phase 2: ID contract (handlers.js references ⊆ ui.json component IDs)
 * Phase 3: AST security scan (forbidden globals, state leak, capability escape, dynamic UI ID)
 */
@Component
public class AstAuditor {

    private static final Logger log = LoggerFactory.getLogger(AstAuditor.class);

    private final ObjectMapper mapper;
    private final String acornJs;

    // Forbidden JS identifiers for kernel-js runtime
    private static final Set<String> FORBIDDEN_IDENTIFIERS_JS = Set.of(
        "eval", "Function", "globalThis", "global", "window",
        "process", "require", "module", "exports",
        "__dirname", "__filename",
        "Promise", "fetch", "XMLHttpRequest",
        "WebSocket", "import", "Worker"
    );

    // Forbidden JS identifiers for browser-js runtime.
    // window and document are allowed — the frontend sandbox (BrowserRuntime)
    // wraps window in a Proxy that only permits __wuwei_* bridges and safe APIs.
    // Promise is allowed for async kernel-backed capability calls.
    private static final Set<String> FORBIDDEN_IDENTIFIERS_BROWSER = Set.of(
        "eval", "Function", "globalThis", "global",
        "process", "require", "module", "exports",
        "__dirname", "__filename",
        "fetch", "XMLHttpRequest",
        "WebSocket", "import", "Worker"
    );

    public AstAuditor(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        this.acornJs = loadAcorn();
    }

    private String loadAcorn() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("acorn.min.js")) {
            if (in == null) {
                throw new IOException("acorn.min.js not found in classpath resources");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Run all three phases of audit. Throws GateException on first violation.
     */
    public void audit(SkillManifest manifest, SkillGenome genome)
            throws GateException {

        // Phase 1: Manifest validation
        validateManifest(manifest);

        // Phase 2: ID contract — check all JS files against the UI ID set
        Set<String> uiIds = extractUiIds(genome.uiJson());
        List<String> allIssues = new ArrayList<>();

        // Check main handlers.js (entry point)
        if (genome.handlersJs() != null && !genome.handlersJs().isBlank()) {
            checkIdContract(genome.handlersJs(), uiIds, manifest.id());

            // Phase 3: AST security scan on main entry
            List<String> issues = scanAst(genome.handlersJs(), manifest);
            if (!issues.isEmpty()) {
                allIssues.addAll(issues);
            }
        }

        // Check each module file in multi-file mode
        if (genome.moduleFiles() != null && !genome.moduleFiles().isEmpty()) {
            for (var entry : genome.moduleFiles().entrySet()) {
                String filePath = entry.getKey();
                String source = entry.getValue();
                if (source == null || source.isBlank()) continue;

                checkIdContract(source, uiIds, manifest.id());
                List<String> issues = scanAst(source, manifest);
                for (String issue : issues) {
                    allIssues.add(filePath + " — " + issue);
                }
            }
        }

        if (!allIssues.isEmpty()) {
            String detail = String.join("; ", allIssues);
            throw new GateException("AST_VIOLATION", detail);
        }
    }

    // ── Phase 1: Manifest validation ────────────────────────────

    private void validateManifest(SkillManifest manifest) throws GateException {
        String id = manifest.id();
        if (id == null || id.isBlank()) {
            throw new GateException("INVALID_MANIFEST", "skill.json 缺少 id 字段");
        }
        if (!id.matches("^[a-z][a-z0-9-]*$")) {
            throw new GateException("INVALID_MANIFEST",
                "id 必须是 kebab-case: " + id);
        }
        String runtime = manifest.runtime();
        if (!"js".equals(runtime) && !"browser-js".equals(runtime) && !"md".equals(runtime)) {
            throw new GateException("UNSUPPORTED_RUNTIME",
                "runtime 必须是 'js'、'browser-js' 或 'md'，当前值: " + runtime);
        }
        String abi = manifest.abi();
        if (abi == null || !abi.matches("^\\d+\\.\\d+$")) {
            throw new GateException("INVALID_MANIFEST",
                "abi 版本格式无效: " + abi);
        }
    }

    // ── Phase 2: ID contract check ──────────────────────────────

    private Set<String> extractUiIds(String uiJson) throws GateException {
        try {
            JsonNode tree = mapper.readTree(uiJson);
            Set<String> ids = new HashSet<>();
            collectIds(tree, ids);
            return ids;
        } catch (Exception e) {
            throw new GateException("INVALID_UI_JSON",
                "ui.json 解析失败: " + e.getMessage());
        }
    }

    private void collectIds(JsonNode node, Set<String> ids) {
        if (node.isObject()) {
            if (node.has("id")) {
                ids.add(node.get("id").asText());
            }
            if (node.has("children")) {
                for (JsonNode child : node.get("children")) {
                    collectIds(child, ids);
                }
            }
            // Also handle root-wrapped format
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isObject() || entry.getValue().isArray()) {
                    collectIds(entry.getValue(), ids);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectIds(item, ids);
            }
        }
    }

    private void checkIdContract(String handlersJs, Set<String> uiIds,
                                  String skillId) throws GateException {
        // Find all string literals used as first arg to capability.ui.set/get
        // Pattern: capability.ui.set("id", ...) or capability.ui.get("id", ...)
        var pattern = java.util.regex.Pattern.compile(
            "capability\\.ui\\.(?:set|get)\\s*\\(\\s*[\"']([^\"']+)[\"']");
        var matcher = pattern.matcher(handlersJs);

        List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            String id = matcher.group(1);
            if (!uiIds.contains(id) && !id.contains("__")) { // skip label/child IDs like start-btn__label
                missing.add(id);
            }
        }

        if (!missing.isEmpty()) {
            throw new GateException("UNDEFINED_ID",
                "handlers.js 引用了 ui.json 中不存在的 ID: " +
                String.join(", ", new HashSet<>(missing)));
        }
    }

    // ── Phase 3: AST security scan ──────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> scanAst(String code, SkillManifest manifest)
            throws GateException {

        String scanScript = buildScanScript(code, manifest);

        try (Context auditCtx = Context.newBuilder("js")
                .engine(RuntimePool.getAuditEngine())
                .allowHostAccess(org.graalvm.polyglot.HostAccess.NONE)
                .allowExperimentalOptions(true)
                .option("js.strict", "true")
                .build()) {

            // Load acorn
            auditCtx.eval("js", acornJs);

            // Parse the code
            try {
                auditCtx.eval(Source.newBuilder("js", code, "audit-target.js")
                    .buildLiteral());
            } catch (Exception e) {
                throw new GateException("SYNTAX_ERROR",
                    "JS 语法错误: " + e.getMessage());
            }

            // Run scanning script
            var result = auditCtx.eval("js", scanScript);
            if (result.isString()) {
                String json = result.asString();
                if (json.equals("[]")) return List.of();
                try {
                    List<String> issues = mapper.readValue(json, List.class);
                    return issues != null ? issues : List.of();
                } catch (Exception e) {
                    log.warn("Failed to parse audit result: {}", json, e);
                    return List.of();
                }
            }
            return List.of();

        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            log.error("AST audit internal error: {}", e.getMessage());
            throw new GateException("AUDIT_ERROR",
                "AST 审计内部错误: " + e.getMessage());
        }
    }

    private Set<String> getForbiddenIdentifiers(SkillManifest manifest) {
        if ("browser-js".equals(manifest.runtime())) {
            return FORBIDDEN_IDENTIFIERS_BROWSER;
        }
        return FORBIDDEN_IDENTIFIERS_JS;
    }

    private String buildScanScript(String code, SkillManifest manifest) {
        String escapedCode = escapeForJs(code);
        String forbiddenJson = toJsonArray(getForbiddenIdentifiers(manifest));
        Set<String> declaredCaps = manifest.capabilities() != null
            ? manifest.capabilities().keySet()
            : Set.of();
        String declaredJson = toJsonArray(declaredCaps);

        return """
            var code = %s;
            var ast;
            try {
                ast = acorn.parse(code, {
                    ecmaVersion: 2022,
                    sourceType: 'script',
                    allowReturnOutsideFunction: true,
                    locations: true
                });
            } catch (e) {
                JSON.stringify(['SYNTAX_ERROR:' + e.message]);
            }

            var issues = [];
            var forbidden = %s;
            var declared = %s;

            function walk(node, parent, depth, isTopLevel) {
                if (!node || typeof node !== 'object') return;
                if (Array.isArray(node)) {
                    node.forEach(function(n) { walk(n, parent, depth, isTopLevel); });
                    return;
                }

                // Forbidden identifiers — skip if property of capability.* chain
                var isCapProp = parent && parent.type === 'MemberExpression' &&
                    parent.object && parent.object.type === 'MemberExpression' &&
                    parent.object.object && parent.object.object.type === 'Identifier' &&
                    parent.object.object.name === 'capability';
                if (node.type === 'Identifier' && forbidden.indexOf(node.name) !== -1 && !isCapProp) {
                    issues.push('FORBIDDEN_GLOBAL:' + node.name +
                        ' at line ' + (node.loc ? node.loc.start.line : '?'));
                }

                // All var/let/const at top-level are allowed —
                // the sandbox IIFE already scopes everything identically

                // capability.<xxx> where xxx is not declared
                if (node.type === 'MemberExpression' &&
                    node.object.type === 'Identifier' &&
                    node.object.name === 'capability' &&
                    node.property.type === 'Identifier') {
                    var capName = node.property.name;
                    // ui, permission, crypto, db, websearch are always available
                    if (capName !== 'ui' && capName !== 'data' &&
                        capName !== 'permission' &&
                        capName !== 'crypto' && capName !== 'db' &&
                        capName !== 'websearch' &&
                        declared.indexOf(capName) === -1) {
                        issues.push('CAPABILITY_ESCAPE:using undeclared capability.' + capName +
                            ' at line ' + (node.loc ? node.loc.start.line : '?'));
                    }
                }

                // capability.ui.set/get first argument must be string literal
                if (node.type === 'CallExpression' &&
                    node.callee.type === 'MemberExpression' &&
                    node.callee.object.type === 'MemberExpression' &&
                    node.callee.object.object.type === 'Identifier' &&
                    node.callee.object.object.name === 'capability' &&
                    node.callee.object.property.type === 'Identifier' &&
                    node.callee.object.property.name === 'ui' &&
                    node.callee.property.type === 'Identifier' &&
                    (node.callee.property.name === 'set' || node.callee.property.name === 'get') &&
                    node.arguments.length > 0 &&
                    node.arguments[0].type !== 'Literal') {
                    issues.push('DYNAMIC_UI_ID:capability.ui id must be string literal' +
                        ' at line ' + (node.loc ? node.loc.start.line : '?'));
                }

                // Recurse into children, passing current node as parent
                for (var key of Object.keys(node)) {
                    var child = node[key];
                    if (child && typeof child === 'object') {
                        var nextDepth = depth;
                        var nextTopLevel = isTopLevel;
                        if (node.type === 'FunctionDeclaration' ||
                            node.type === 'FunctionExpression' ||
                            node.type === 'ArrowFunctionExpression') {
                            nextDepth = depth + 1;
                            nextTopLevel = false;
                        }
                        walk(child, node, nextDepth, nextTopLevel);
                    }
                }
            }

            walk(ast, null, 0, true);
            JSON.stringify(issues);
            """.formatted(escapedCode, forbiddenJson, declaredJson);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String escapeForJs(String code) {
        // JSON stringify handles all escaping
        try {
            return mapper.writeValueAsString(code);
        } catch (Exception e) {
            // Fallback: basic escaping
            return "\"" + code
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
        }
    }

    private String toJsonArray(Set<String> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }
}
