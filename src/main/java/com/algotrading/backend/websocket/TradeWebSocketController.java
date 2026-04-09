package com.algotrading.backend.websocket;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.model.TradeSession;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

/**
 * WebSocket controller for real-time trade updates.
 * Clients subscribe to /topic/trade-updates for live session state.
 * Clients subscribe to /topic/pnl-updates for live P&L.
 */
@Controller
@RequiredArgsConstructor
public class TradeWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final MarketDataCache cache;

    /**
     * Client can request a session snapshot by sending to /app/session
     */
    @MessageMapping("/session")
    @SendTo("/topic/trade-updates")
    public TradeSession getSession() {
        return cache.getSession();
    }

    /**
     * Scheduled broadcast of current session state every 5 seconds.
     * Ensures UI stays in sync even without price updates.
     */
    @Scheduled(fixedDelay = 5000)
    public void broadcastSessionState() {
        TradeSession session = cache.getSession();
        if (session != null) {
            messagingTemplate.convertAndSend("/topic/trade-updates", session);
        }
    }

    /**
     * Broadcast a custom message (called by strategy service on trade events).
     */
    public void broadcastTradeEvent(String event, Object payload) {
        messagingTemplate.convertAndSend("/topic/trade-events", payload);
    }
}
