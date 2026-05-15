package com.wuwei.bus;

import com.wuwei.bus.event.KernelEvent;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.websocket.WsRouting;
import io.helidon.websocket.WsListener;
import io.helidon.websocket.WsSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WsServer {

    private static final Logger log = LoggerFactory.getLogger(WsServer.class);

    private int port;
    private final MessageRouter router;
    private final EventBus eventBus;
    private final Set<WsSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile WebServer server;

    public WsServer(int port, MessageRouter router, EventBus eventBus) {
        this.port = port;
        this.router = router;
        this.eventBus = eventBus;
    }

    public void start() {
        WsListener listener = new WsListener() {
            @Override
            public void onOpen(WsSession session) {
                sessions.add(session);

                KernelEvent.KernelReady ready =
                    new KernelEvent.KernelReady("6.4.0", port);
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

        server = WebServer.builder()
            .host("127.0.0.1")
            .port(port)
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
}
