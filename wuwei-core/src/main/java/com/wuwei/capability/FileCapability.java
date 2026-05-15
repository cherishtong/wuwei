package com.wuwei.capability;

import com.wuwei.gate.GateException;
import com.wuwei.skill.SkillManifest;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controlled filesystem access for Skills.
 * All paths are virtual — the kernel maps them into a per-skill sandbox
 * directory and prevents path traversal.
 */
public class FileCapability {

    private static final Logger log = LoggerFactory.getLogger(FileCapability.class);

    public ProxyObject forSkill(SkillManifest manifest) {
        String skillId = manifest.id();
        String home = System.getProperty("user.home");
        Path sandboxRoot = Paths.get(home, ".wuwei", "skills", skillId, "phenotype", "sandbox");

        // Ensure sandbox directory exists
        try {
            Files.createDirectories(sandboxRoot);
        } catch (IOException e) {
            log.error("Failed to create sandbox for {}: {}", skillId, e.getMessage());
        }

        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "read" -> (ProxyExecutable) args -> {
                        String virtualPath = args[0].asString();
                        Path resolved = resolveSandboxed(skillId, sandboxRoot, virtualPath);
                        try {
                            return Files.readString(resolved);
                        } catch (IOException e) {
                            throw new RuntimeException("File read failed: " + virtualPath + " — " + e.getMessage());
                        }
                    };
                    case "write" -> (ProxyExecutable) args -> {
                        String virtualPath = args[0].asString();
                        String data = args[1].asString();
                        Path resolved = resolveSandboxed(skillId, sandboxRoot, virtualPath);
                        try {
                            Files.createDirectories(resolved.getParent());
                            Files.writeString(resolved, data);
                        } catch (IOException e) {
                            throw new RuntimeException("File write failed: " + virtualPath + " — " + e.getMessage());
                        }
                        return null;
                    };
                    case "list" -> (ProxyExecutable) args -> {
                        String virtualDir = args[0].asString();
                        Path resolved = resolveSandboxed(skillId, sandboxRoot, virtualDir);
                        try {
                            if (!Files.isDirectory(resolved)) return new ProxyArray() {
                                @Override public Object get(long index) { return null; }
                                @Override public void set(long index, Value value) {}
                                @Override public long getSize() { return 0; }
                                @Override public boolean remove(long index) { return false; }
                            };
                            try (var stream = Files.list(resolved)) {
                                List<String> files = stream.map(p -> p.getFileName().toString())
                                    .collect(Collectors.toList());
                                return new ProxyArray() {
                                    @Override public Object get(long index) { return files.get((int) index); }
                                    @Override public void set(long index, Value value) {}
                                    @Override public long getSize() { return files.size(); }
                                    @Override public boolean remove(long index) { return false; }
                                };
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("File list failed: " + virtualDir + " — " + e.getMessage());
                        }
                    };
                    case "delete" -> (ProxyExecutable) args -> {
                        String virtualPath = args[0].asString();
                        Path resolved = resolveSandboxed(skillId, sandboxRoot, virtualPath);
                        try {
                            Files.deleteIfExists(resolved);
                        } catch (IOException e) {
                            throw new RuntimeException("File delete failed: " + virtualPath + " — " + e.getMessage());
                        }
                        return null;
                    };
                    default -> null;
                };
            }

            @Override
            public boolean hasMember(String key) {
                return Set.of("read", "write", "list", "delete").contains(key);
            }

            @Override
            public Set<String> getMemberKeys() { return Set.of("read", "write", "list", "delete"); }

            @Override
            public void putMember(String key, Value value) {}

            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    /**
     * Resolve a virtual path against the sandbox root, preventing path traversal.
     * All virtual paths are treated as relative and resolved within the sandbox.
     */
    static Path resolveSandboxed(String skillId, Path sandboxRoot, String virtualPath) {
        // Strip leading slash to make it relative
        String relative = virtualPath.startsWith("/") ? virtualPath.substring(1) : virtualPath;

        Path resolved = sandboxRoot.resolve(relative).normalize();

        // Boundary check: resolved path must start with sandbox root
        if (!resolved.startsWith(sandboxRoot)) {
            throw new GateException("PATH_TRAVERSAL",
                "路径穿越被拦截: " + virtualPath + " → " + resolved);
        }

        return resolved;
    }
}
