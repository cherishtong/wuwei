package com.wuwei.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.event.KernelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Spring WebSocket handler — replaces the old Helidon WsListener.
 */
@Component
public class WebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHandler.class);

    private final MessageRouter router;
    private final EventBus eventBus;
    private final ObjectMapper mapper;

    public WebSocketHandler(MessageRouter router, EventBus eventBus, ObjectMapper mapper) {
        this.router = router;
        this.eventBus = eventBus;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        eventBus.addSession(session);
        int port = session.getUri().getPort();
        String json = eventBus.serialize(
            new KernelEvent.KernelReady("0.0.1-beta", port));
        try { session.sendMessage(new TextMessage(json)); } catch (Exception ignored) {}
        log.info("WS connected: {} (total: {})", session.getId(), eventBus.getSessionCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            router.route(session, message.getPayload());
        } catch (Exception e) {
            log.error("Route error: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        eventBus.removeSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable t) {
        eventBus.removeSession(session);
        log.warn("WS error: {}", t.getMessage());
    }
}
