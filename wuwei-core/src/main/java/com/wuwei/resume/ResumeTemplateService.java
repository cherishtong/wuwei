package com.wuwei.resume;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.policy.RenderPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * poi-tl template rendering service for resume generation.
 *
 * Templates are stored per-skill at ~/.wuwei/skills/resume-builder/phenotype/templates/.
 * Each template is a .docx file with {{placeholder}} markers.
 * LLM generates structured JSON that poi-tl fills into the template.
 */
@Service
public class ResumeTemplateService {

    private static final Logger log = LoggerFactory.getLogger(ResumeTemplateService.class);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private final Path templatesDir;
    private final ObjectMapper mapper;

    public ResumeTemplateService(ObjectMapper mapper) {
        this.mapper = mapper;
        String home = System.getProperty("user.home");
        this.templatesDir = Paths.get(home, ".wuwei", "skills", "resume-builder", "templates");
        try {
            Files.createDirectories(templatesDir);
        } catch (IOException e) {
            log.warn("Failed to create templates dir: {}", e.getMessage());
        }
    }

    /**
     * Render a .docx template with JSON data using poi-tl.
     *
     * @param templateId template file name (without path)
     * @param data       flat key-value map to fill placeholders
     * @return rendered .docx bytes
     */
    public byte[] render(String templateId, Map<String, Object> data) {
        Path templatePath = templatesDir.resolve(templateId);
        if (!Files.exists(templatePath)) {
            throw new IllegalArgumentException("Template not found: " + templateId);
        }

        try {
            // poi-tl render: compile template → render data → output bytes
            XWPFTemplate template = XWPFTemplate.compile(templatePath.toFile()).render(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            template.writeAndClose(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render template {}: {}", templateId, e.getMessage());
            throw new RuntimeException("Template render failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extract {{placeholder}} keys from a .docx template.
     *
     * @param templateId template file name
     * @return list of placeholder keys (e.g. ["name", "position", "experience"])
     */
    public List<String> parsePlaceholders(String templateId) {
        Path templatePath = templatesDir.resolve(templateId);
        if (!Files.exists(templatePath)) {
            throw new IllegalArgumentException("Template not found: " + templateId);
        }

        try {
            // poi-tl can give us the template's tag list via its config
            // For simplicity, we read the document XML and extract {{...}} patterns
            XWPFTemplate template = XWPFTemplate.compile(templatePath.toFile());
            // Use poi-tl's built-in inspection
            Set<String> keys = new LinkedHashSet<>();
            // Parse document.xml from the docx zip
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(templatePath.toFile())) {
                var entry = zip.getEntry("word/document.xml");
                if (entry != null) {
                    try (var is = zip.getInputStream(entry)) {
                        String xml = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        Matcher m = PLACEHOLDER_PATTERN.matcher(xml);
                        while (m.find()) {
                            keys.add(m.group(1));
                        }
                    }
                }
            }
            return new ArrayList<>(keys);
        } catch (Exception e) {
            log.error("Failed to parse template {}: {}", templateId, e.getMessage());
            return List.of();
        }
    }

    /** Read template file content. */
    public byte[] readTemplate(String name) {
        Path path = templatesDir.resolve(name);
        if (!Files.exists(path)) throw new IllegalArgumentException("Template not found: " + name);
        try { return Files.readAllBytes(path); } catch (IOException e) {
            throw new RuntimeException("Failed to read template: " + e.getMessage(), e);
        }
    }

    /**
     * Save an uploaded .docx template.
     *
     * @param name     template file name
     * @param content  raw bytes
     */
    public void saveTemplate(String name, byte[] content) {
        try {
            Path target = templatesDir.resolve(name);
            Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Saved template: {} ({} bytes)", name, content.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save template: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a template.
     */
    public void deleteTemplate(String name) {
        try {
            Files.deleteIfExists(templatesDir.resolve(name));
            log.info("Deleted template: {}", name);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete template: " + e.getMessage(), e);
        }
    }

    /**
     * List all available templates.
     */
    public List<Map<String, Object>> listTemplates() {
        try (var stream = Files.list(templatesDir)) {
            return stream
                .filter(f -> f.getFileName().toString().endsWith(".docx"))
                .map(f -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    String name = f.getFileName().toString();
                    info.put("name", name);
                    info.put("size", f.toFile().length());
                    info.put("placeholders", parsePlaceholders(name));
                    return info;
                })
                .sorted(Comparator.comparing(m -> m.get("name").toString()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to list templates: {}", e.getMessage());
            return List.of();
        }
    }
}
