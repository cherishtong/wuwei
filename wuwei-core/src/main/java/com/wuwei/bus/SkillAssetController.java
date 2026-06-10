package com.wuwei.bus;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves skill phenotype assets from ~/.wuwei/skills/{skillId}/phenotype/assets/...
 */
@Controller
public class SkillAssetController {

    private static final Logger log = LoggerFactory.getLogger(SkillAssetController.class);
    private static final Path SKILLS_DIR = Paths.get(System.getProperty("user.home"), ".wuwei", "skills");

    @GetMapping("/skills/{skillId}/assets/**")
    public ResponseEntity<Resource> serveAsset(HttpServletRequest request) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        // fullPath: /skills/{skillId}/assets/{rest...}
        String rest = fullPath.replaceFirst("^/skills/[^/]+/assets/", "");
        String skillId = fullPath.replaceFirst("^/skills/([^/]+)/assets/.*$", "$1");

        Path file = SKILLS_DIR.resolve(skillId).resolve("phenotype").resolve("assets").resolve(rest);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        String contentType = contentType(file.getFileName().toString());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .body(resource);
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }
}
