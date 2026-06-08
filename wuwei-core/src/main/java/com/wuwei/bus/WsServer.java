package com.wuwei.bus;

import com.wuwei.bus.event.KernelEvent;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.staticcontent.StaticContentService;
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
import java.util.function.Consumer;

public class WsServer {

    private static final Logger log = LoggerFactory.getLogger(WsServer.class);

    private int wsPort;
    private final String host;
    private final Path webRoot;
    private final MessageRouter router;
    private final EventBus eventBus;
    private final Set<WsSession> sessions = ConcurrentHashMap.newKeySet();

    public WsServer(String host, int port, MessageRouter router, EventBus eventBus, Path webRoot) {
        this.host = host;
        this.wsPort = port;
        this.router = router;
        this.eventBus = eventBus;
        this.webRoot = webRoot;
    }

    public void start() {
        WsListener listener = new WsListener() {
            @Override public void onOpen(WsSession session) {
                sessions.add(session);
                session.send(eventBus.serialize(
                    new KernelEvent.KernelReady("0.0.1-beta", wsPort)), true);
                System.out.println("[WsServer] WS connected (total: " + sessions.size() + ")");
            }
            @Override public void onMessage(WsSession session, String text, boolean last) {
                try { router.route(session, text); }
                catch (Exception e) { System.out.println("[WsServer] Route error: " + e.getMessage()); }
            }
            @Override public void onClose(WsSession session, int statusCode, String reason) {
                sessions.remove(session);
                System.out.println("[WsServer] WS disconnected (total: " + sessions.size() + ")");
            }
            @Override public void onError(WsSession session, Throwable throwable) {
                sessions.remove(session);
                System.out.println("[WsServer] WS error: " + throwable.getMessage());
            }
        };

        Path skillsAssetsDir = Paths.get(System.getProperty("user.home"), ".wuwei", "skills");

        // ── WebSocket server (dedicated port) ──
        WebServer.builder()
            .host(host)
            .port(wsPort)
            .routing(r -> r.any("/ws", (req, res) -> { /* WsRouting handles upgrade */ }))
            .addRouting(WsRouting.builder().endpoint("/ws", listener))
            .build()
            .start();
        System.out.println("[WsServer] WebSocket on port " + wsPort);

        // ── HTTP server (SPA, separate port) ──
        if (webRoot != null) {
            int httpPort = (wsPort == 8080) ? 8081 : 8080;
            Path indexHtml = webRoot.resolve("index.html");
            Path assetsDir = webRoot.resolve("assets");
            Consumer<io.helidon.webserver.http.ServerResponse> sendIndex = res -> {
                try {
                    res.header("Content-Type", "text/html; charset=utf-8");
                    res.send(Files.readAllBytes(indexHtml));
                } catch (Exception e) { res.status(500).send("index.html read error"); }
            };

            var http = WebServer.builder().host(host).port(httpPort);
            http.routing(r -> {
                r.get("/skills/{skillId}/assets/{+path}", (req, res) -> {
                    String skillId = req.path().pathParameters().get("skillId");
                    String path = req.path().pathParameters().get("path");
                    Path file = skillsAssetsDir.resolve(skillId)
                            .resolve("phenotype").resolve("assets").resolve(path);
                    if (Files.exists(file) && !Files.isDirectory(file)) serveFile(res, file);
                    else res.status(404).send("Not found");
                });
                r.get("/", (req, res) -> sendIndex.accept(res));
                if (Files.isDirectory(assetsDir)) {
                    r.register("/assets", StaticContentService.create(assetsDir));
                }
                r.get("/favicon.ico", (req, res) -> {
                    Path f = webRoot.resolve("favicon.ico");
                    if (Files.exists(f)) serveFile(res, f); else res.status(404).send();
                });
                r.get("/{+path}", (req, res) -> {
                    String path = req.path().pathParameters().get("path");
                    if (path != null && path.contains(".")) {
                        Path file = webRoot.resolve(path);
                        if (Files.exists(file) && !Files.isDirectory(file)) {
                            serveFile(res, file);
                            return;
                        }
                    }
                    sendIndex.accept(res);
                });
            });
            http.build().start();
            System.out.println("[WsServer] HTTP SPA on port " + httpPort
                + " (web root: " + webRoot.toAbsolutePath() + ")");
        }
    }

    public void broadcast(String json) {
        for (WsSession session : sessions) {
            try { session.send(json, true); } catch (Exception e) {}
        }
    }

    public void sendTo(WsSession session, String json) {
        try { session.send(json, true); } catch (Exception e) {}
    }

    public int getPort() { return wsPort; }
    public int getSessionCount() { return sessions.size(); }

    private static void serveFile(io.helidon.webserver.http.ServerResponse res, Path file) {
        if (!Files.exists(file)) { res.status(404).send("Not found"); return; }
        try {
            byte[] bytes = Files.readAllBytes(file);
            res.header("Content-Type", contentType(file));
            res.send(bytes);
        } catch (java.io.IOException e) { res.status(500).send("Internal error"); }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".wasm")) return "application/wasm";
        return "text/html; charset=utf-8";
    }
}
