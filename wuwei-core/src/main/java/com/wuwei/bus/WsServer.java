package com.wuwei.bus;

import com.wuwei.bus.event.KernelEvent;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.websocket.WsRouting;
import io.helidon.websocket.WsListener;
import io.helidon.websocket.WsSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WsServer {

    private static final Logger log = LoggerFactory.getLogger(WsServer.class);

    private int port;
    private final String host;
    private final Path webRoot;
    private final MessageRouter router;
    private final EventBus eventBus;
    private final Set<WsSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile WebServer server;

    /**
     * @param host    bind address (127.0.0.1 for local dev, 0.0.0.0 for cloud)
     * @param port    0 for random port, or fixed for cloud
     * @param router  message router
     * @param eventBus event bus
     * @param webRoot null to skip static file serving; non-null to serve SPA from this dir
     */
    public WsServer(String host, int port, MessageRouter router, EventBus eventBus, Path webRoot) {
        this.host = host;
        this.port = port;
        this.router = router;
        this.eventBus = eventBus;
        this.webRoot = webRoot;
    }

    public void start() {
        WsListener listener = new WsListener() {
            @Override
            public void onOpen(WsSession session) {
                sessions.add(session);

                KernelEvent.KernelReady ready =
                    new KernelEvent.KernelReady("0.0.1-beta", port);
                session.send(eventBus.serialize(ready), true);
                System.out.println("[WsServer] WS connected (total: " + sessions.size() + ")");
            }

            @Override
            public void onMessage(WsSession session, String text, boolean last) {
                System.out.println("[WsServer] WS <- " + text);
                try {
                    router.route(session, text);
                } catch (Exception e) {
                    System.out.println("[WsServer] Route error: " + e.getMessage());
                }
            }

            @Override
            public void onClose(WsSession session, int statusCode, String reason) {
                sessions.remove(session);
                System.out.println("[WsServer] WS disconnected (total: " + sessions.size() + ")");
            }

            @Override
            public void onError(WsSession session, Throwable throwable) {
                sessions.remove(session);
                System.out.println("[WsServer] WS error: " + throwable.getMessage());
            }
        };

        // Serve static skill asset files from ~/.wuwei/skills/*/phenotype/assets/
        Path skillsAssetsDir = Paths.get(System.getProperty("user.home"), ".wuwei", "skills");

        server = WebServer.builder()
            .host(host)
            .port(port)
            .routing(b -> {
                // Specific routes MUST come before catch-all
                b.any("/ws", (req, res) -> { /* handled by WS upgrade */ });
                b.get("/skills/{skillId}/assets/{+path}", (req, res) -> {
                    String skillId = req.path().pathParameters().get("skillId");
                    String path = req.path().pathParameters().get("path");
                    Path file = skillsAssetsDir.resolve(skillId).resolve("phenotype").resolve("assets").resolve(path);
                    if (Files.exists(file) && !Files.isDirectory(file)) {
                        res.send(file);
                    } else {
                        res.status(404).send("Not found");
                    }
                });
                // Static SPA serving (cloud mode)
                if (webRoot != null) {
                    // Root path — serve index.html
                    b.get("/", (req, res) -> {
                        Path indexFile = webRoot.resolve("index.html");
                        if (Files.exists(indexFile)) res.send(indexFile);
                        else res.status(404).send("Not found");
                    });
                    // Catch-all for SPA assets and routing
                    b.get("/{+path}", (req, res) -> {
                        String path = req.path().pathParameters().get("path");
                        // If path is empty or has no extension, serve index.html (SPA routing)
                        Path file = (path == null || path.isEmpty() || !path.contains("."))
                            ? webRoot.resolve("index.html")
                            : webRoot.resolve(path);
                        // SPA fallback: if asset not found, serve index.html
                        if (!Files.exists(file) || Files.isDirectory(file)) {
                            file = webRoot.resolve("index.html");
                        }
                        if (Files.exists(file)) {
                            res.send(file);
                        } else {
                            res.status(404).send("Not found");
                        }
                    });
                    System.out.println("[WsServer] Static web root: " + webRoot.toAbsolutePath());
                }
            })
            .addRouting(WsRouting.builder()
                .endpoint("/ws", listener))
            .build()
            .start();

        this.port = server.port();
        System.out.println("[WsServer] WebSocket server listening on port " + this.port);
    }

    public void broadcast(String json) {
        for (WsSession session : sessions) {
            try {
                session.send(json, true);
            } catch (Exception e) {
                log.warn("Broadcast failed: {}", e.getMessage());
            }
        }
    }

    public void sendTo(WsSession session, String json) {
        try {
            session.send(json, true);
        } catch (Exception e) {
            log.warn("Send failed: {}", e.getMessage());
        }
    }

    public int getPort() {
        return port;
    }
    public int getSessionCount() {
        return sessions.size();
    }
}
