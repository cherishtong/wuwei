package com.wuwei.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API for resume template management and rendering.
 *
 * Endpoints:
 *   GET    /api/resume/templates        — list templates
 *   POST   /api/resume/templates        — upload .docx template
 *   DELETE /api/resume/templates/{name} — delete template
 *   POST   /api/resume/render           — render template with data → .docx
 *   POST   /api/resume/parse            — extract placeholder keys
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeTemplateService service;

    public ResumeController(ResumeTemplateService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> listTemplates() {
        return ResponseEntity.ok(service.listTemplates());
    }

    @PostMapping("/templates")
    public ResponseEntity<Map<String, Object>> uploadTemplate(@RequestParam(value = "file", required = false) MultipartFile file,
                                                               @RequestBody(required = false) Map<String, String> jsonBody) {
        try {
            String name;
            byte[] content;

            if (file != null && !file.isEmpty()) {
                // Multipart upload (browser direct)
                name = file.getOriginalFilename();
                content = file.getBytes();
            } else if (jsonBody != null && jsonBody.containsKey("fileName")) {
                // Base64 upload via c.network proxy
                name = jsonBody.get("fileName");
                content = java.util.Base64.getDecoder().decode(jsonBody.get("content"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
            }

            if (name == null || !name.endsWith(".docx")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only .docx files allowed"));
            }
            service.saveTemplate(name, content);
            List<String> placeholders = service.parsePlaceholders(name);
            return ResponseEntity.ok(Map.of(
                "name", name,
                "size", content.length,
                "placeholders", placeholders
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/templates/{name}")
    public ResponseEntity<Map<String, Object>> deleteTemplate(@PathVariable String name) {
        service.deleteTemplate(name);
        return ResponseEntity.ok(Map.of("deleted", name));
    }

    @PostMapping("/render")
    public ResponseEntity<?> render(@RequestBody Map<String, Object> request) {
        try {
            String templateId = (String) request.get("templateId");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) request.getOrDefault("data", Map.of());

            byte[] docxBytes = service.render(templateId, data);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"resume-" + System.currentTimeMillis() + ".docx\"")
                .body(docxBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Render failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parse(@RequestBody Map<String, String> request) {
        String templateId = request.get("templateId");
        List<String> placeholders = service.parsePlaceholders(templateId);
        return ResponseEntity.ok(Map.of("templateId", templateId, "placeholders", placeholders));
    }
}
