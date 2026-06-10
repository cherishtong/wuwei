package com.wuwei.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SPA static file serving + skill asset resources.
 * Replaces the old Helidon HttpRouting + StaticContentService logic.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    @Value("${wuwei.web-root:#{null}}")
    private String webRoot;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Resolve web root: explicit config → auto-detect ./dist → ../wuwei-renderer/dist
        Path distDir = resolveWebRoot();
        if (distDir != null) {
            log.info("Serving SPA from: {}", distDir.toAbsolutePath());
            registry.addResourceHandler("/**")
                .addResourceLocations("file:" + distDir.toAbsolutePath() + "/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource r = location.createRelative(resourcePath);
                        if (r.exists() && r.isReadable()) return r;
                        // SPA fallback: return index.html for non-file paths
                        if (!resourcePath.contains(".")) {
                            Resource index = location.createRelative("index.html");
                            if (index.exists()) return index;
                        }
                        return null;
                    }
                });
        }

        // Skill assets handled by SkillAssetController (needs phenotype/ injection)
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Root → forward to index.html (handled by resource resolver above)
        registry.addViewController("/").setViewName("forward:index.html");
    }

    private Path resolveWebRoot() {
        // ① Explicit config
        if (webRoot != null && !webRoot.isBlank()) {
            Path p = Path.of(webRoot);
            if (Files.isDirectory(p)) return p;
        }
        // ② Auto-detect
        Path[] candidates = {
            Path.of("dist"),
            Path.of("..", "wuwei-renderer", "dist")
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p.toAbsolutePath().normalize();
        }
        return null;
    }
}
