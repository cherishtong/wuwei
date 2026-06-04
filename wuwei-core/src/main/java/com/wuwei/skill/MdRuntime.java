package com.wuwei.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Markdown runtime — sidebar.json config → frontend shadcn Sidebar + A2UI content Text.
 *
 * The kernel generates ONLY the content area (blog-text Text component).
 * The sidebar is rendered by the frontend using shadcn/ui Sidebar components,
 * reading sidebarConfig from the SkillActivated event.
 *
 * sidebar.json supports arbitrary nesting:
 * {
 *   "home": { "label": "首页", "file": "index.md" },
 *   "menu": [
 *     { "label": "Frontend", "children": [
 *       { "label": "React", "children": [
 *         { "label": "Hooks", "file": "frontend/react/hooks.md" }
 *       ]}
 *     ]}
 *   ]
 * }
 *
 * Handlers store .md content in c.storage on init, switch content on click.
 * Active state is managed by the React sidebar (not A2UI buttons).
 */
public class MdRuntime {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Generate minimal A2UI tree — root Column wrapping blog-text. */
    public static JsonNode buildUiTree(Path genomeDir) throws IOException {
        ArrayNode components = mapper.createArrayNode();

        // Root Column (required by A2UI framework)
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "root");
        root.put("component", "Column");
        ArrayNode rootChildren = mapper.createArrayNode();
        rootChildren.add("blog-text");
        root.set("children", rootChildren);
        components.add(root);

        // Content Text
        ObjectNode blogText = mapper.createObjectNode();
        blogText.put("id", "blog-text");
        blogText.put("component", "Text");
        blogText.put("text", "请从左侧菜单选择文章");
        blogText.put("variant", "body");
        components.add(blogText);

        return mapper.createObjectNode().set("components", components);
    }

    /** Generate handlers — store md content in c.storage, single onNav handler. */
    public static String buildHandlers(Path genomeDir) throws IOException {
        Map<String, String> fileContents = readAllMd(genomeDir);

        StringBuilder sb = new StringBuilder();

        // onInit: store all md content → load home
        sb.append("function onInit(_,c){");
        for (var e : fileContents.entrySet()) {
            sb.append("c.storage.put(\"md:");
            sb.append(e.getKey());
            sb.append("\",\"");
            sb.append(e.getValue());
            sb.append("\");");
        }
        sb.append("var h=c.storage.get(\"md:index.md\");");
        sb.append("if(h)c.ui.set(\"blog-text\",\"text\",h);");
        sb.append("}");

        // Single nav handler — file path passed as inputs.file
        sb.append("function onNav(inputs,c){");
        sb.append("var t=c.storage.get(\"md:\"+inputs.file);");
        sb.append("if(t)c.ui.set(\"blog-text\",\"text\",t);");
        sb.append("}");

        return sb.toString();
    }

    private static Map<String, String> readAllMd(Path genomeDir) throws IOException {
        Map<String, String> fileContents = new LinkedHashMap<>();
        try (var walk = Files.walk(genomeDir)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                .forEach(md -> {
                    String rel = genomeDir.relativize(md).toString().replace('\\', '/');
                    try {
                        fileContents.put(rel, Files.readString(md)
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", ""));
                    } catch (IOException ignored) {}
                });
        }
        return fileContents;
    }

    // ── Asset copy ─────────────────────────────────────────────────

    public static void copyAssets(Path genomeDir, Path phenotypeDir) throws IOException {
        Path assetsDir = phenotypeDir.resolve("assets");
        Files.createDirectories(assetsDir);
        for (String folder : List.of("images", "videos")) {
            Path src = genomeDir.resolve(folder);
            if (Files.isDirectory(src)) {
                try (var files = Files.walk(src)) { files.filter(Files::isRegularFile).forEach(f -> {
                    try {
                        Path rel = src.relativize(f);
                        Path dest = assetsDir.resolve(rel);
                        Files.createDirectories(dest.getParent());
                        Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {}
                });}
            }
        }
    }

    /** Build default sidebar config by scanning directory structure. */
    public static Map<String, Object> buildDefaultSidebarConfig(Path genomeDir) throws IOException {
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (Files.exists(genomeDir.resolve("index.md"))) {
            Map<String, String> home = new LinkedHashMap<>();
            home.put("label", "首页");
            home.put("file", "index.md");
            cfg.put("home", home);
        }
        List<Map<String, Object>> menu = new ArrayList<>();
        try (var entries = Files.list(genomeDir)) {
            entries.filter(Files::isDirectory)
                .filter(d -> !d.getFileName().toString().startsWith("."))
                .filter(d -> !List.of("images","videos","phenotype").contains(d.getFileName().toString()))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(dir -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("label", formatDirName(dir.getFileName().toString()));
                    List<Map<String, Object>> children = new ArrayList<>();
                    buildDefaultTreeConfig(children, dir, genomeDir);
                    if (!children.isEmpty()) {
                        item.put("children", children);
                        menu.add(item);
                    }
                });
        } catch (IOException ignored) {}
        cfg.put("menu", menu);
        return cfg;
    }

    private static void buildDefaultTreeConfig(List<Map<String, Object>> parent, Path dir, Path genomeDir) {
        try (var files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".md"))
                .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                .forEach(md -> {
                    Map<String, Object> child = new LinkedHashMap<>();
                    child.put("label", formatFileName(md.getFileName().toString()));
                    child.put("file", genomeDir.relativize(md).toString().replace('\\', '/'));
                    parent.add(child);
                });
        } catch (IOException ignored) {}
        try (var dirs = Files.list(dir)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> !d.getFileName().toString().startsWith("."))
                .filter(d -> !List.of("images","videos","phenotype").contains(d.getFileName().toString()))
                .sorted(Comparator.comparing(d -> d.getFileName().toString()))
                .forEach(subDir -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("label", formatDirName(subDir.getFileName().toString()));
                    List<Map<String, Object>> children = new ArrayList<>();
                    buildDefaultTreeConfig(children, subDir, genomeDir);
                    if (!children.isEmpty()) {
                        item.put("children", children);
                        parent.add(item);
                    }
                });
        } catch (IOException ignored) {}
    }

    private static String formatDirName(String name) {
        String[] words = name.replace('-', ' ').replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static String formatFileName(String name) {
        return name.replace(".md", "").replace('-', ' ').replace('_', ' ');
    }
}
