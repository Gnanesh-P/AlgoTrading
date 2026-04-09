package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.model.MarketTick;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.models.Tick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the KiteTicker WebSocket connection for real-time tick data.
 * Subscribes to instruments by token and routes ticks to MarketDataService.
 */
@Service
@Slf4j
public class KiteTickerService {

    private final KiteProperties kite;
    private final MarketDataService marketDataService;

    // token → symbol mapping (maintained for lifetime of subscription)
    private final Map<Long, String> tokenToSymbol = new ConcurrentHashMap<>();
    // Currently subscribed tokens (also used to re-subscribe on reconnect)
    private final Set<Long> subscribedTokens = ConcurrentHashMap.newKeySet();

    private volatile KiteTicker ticker;
    private volatile boolean connected = false;

    public KiteTickerService(KiteProperties kite,
                             @Lazy MarketDataService marketDataService) {
        this.kite = kite;
        this.marketDataService = marketDataService;
    }

    // ---- Lifecycle ----

    /**
     * Called after a successful Kite access token exchange.
     * Creates a fresh KiteTicker WebSocket connection.
     */
    public synchronized void onTokenExchanged() {
        disconnectExisting();
        initAndConnect();
    }

    private void initAndConnect() {
        try {
            ticker = new KiteTicker(kite.getAccessToken(), kite.getApiKey());
            ticker.setTryReconnection(true);
            ticker.setMaximumRetries(10);
            ticker.setMaximumRetryInterval(30);

            ticker.setOnConnectedListener(() -> {
                log.info("KiteTicker WebSocket connected");
                connected = true;
                // Re-subscribe all tokens (handles reconnect scenario)
                if (!subscribedTokens.isEmpty()) {
                    doSubscribe(new ArrayList<>(subscribedTokens));
                }
            });

            ticker.setOnDisconnectedListener(() -> {
                log.warn("KiteTicker WebSocket disconnected");
                connected = false;
            });

//            ticker.setOnErrorListener((ex, message, errorType) ->
//                    log.error("KiteTicker error [{}]: {} {}",
//                            errorType, message, ex != null ? "- " + ex : "")
//            );

            ticker.setOnTickerArrivalListener(this::processTicks);

//            ticker.setTryReconnection((reconnectIntervalInMilliSeconds, retryCount) ->
//                log.info("KiteTicker reconnecting... attempt={} interval={}ms", retryCount, reconnectIntervalInMilliSeconds)
//            );
//
            ticker.setOnDisconnectedListener(() ->
                    System.out.println("WebSocket Disconnected ❌")
            );

            ticker.connect();
            log.info("KiteTicker WebSocket connecting to Zerodha...");
        } catch (Exception | KiteException e) {
            log.error("Failed to initialize KiteTicker: {}", e.getMessage());
        }
    }

    private void disconnectExisting() {
        if (ticker != null) {
            try {
                ticker.disconnect();
            } catch (Exception e) {
                log.debug("Error disconnecting old ticker: {}", e.getMessage());
            }
            connected = false;
            ticker = null;
        }
    }

    // ---- Subscription ----

    /**
     * Subscribe instruments using their token→symbol mapping.
     * Call this from AlgoController after algo start with the selected tokens.
     */
    public void subscribe(Map<Long, String> instruments) {
        if (instruments == null || instruments.isEmpty()) return;

        tokenToSymbol.putAll(instruments);
        subscribedTokens.addAll(instruments.keySet());

        log.info("Registering {} instruments for KiteTicker: {}", instruments.size(), instruments.values());

        if (connected && ticker != null) {
            doSubscribe(new ArrayList<>(instruments.keySet()));
        } else {
            log.info("KiteTicker not yet connected — tokens queued, will subscribe on connect");
        }
    }

    /**
     * Subscribe a set of tokens (when symbol mapping is already registered).
     */
    public void subscribeTokens(Collection<Long> tokens) {
        if (tokens == null || tokens.isEmpty()) return;
        subscribedTokens.addAll(tokens);
        if (connected && ticker != null) {
            doSubscribe(new ArrayList<>(tokens));
        }
    }

    /**
     * Subscribe based on current algo config symbols.
     * Looks up tokens from instrument cache mapping.
     */
    public void subscribeFromConfig() {
        // Tokens must be registered via subscribe(Map) from AlgoController.
        // This method re-triggers subscription of already-known tokens.
        if (!subscribedTokens.isEmpty() && connected && ticker != null) {
            doSubscribe(new ArrayList<>(subscribedTokens));
        }
    }

    public void unsubscribeAll() {
        if (ticker != null && connected && !subscribedTokens.isEmpty()) {
            try {
                ticker.unsubscribe(new ArrayList<>(subscribedTokens));
            } catch (Exception e) {
                log.debug("Unsubscribe error: {}", e.getMessage());
            }
        }
        subscribedTokens.clear();
        tokenToSymbol.clear();
        log.info("Cleared all KiteTicker subscriptions");
    }

    private void doSubscribe(ArrayList<Long> tokens) {
        try {
            ticker.subscribe(tokens);
            ticker.setMode(tokens, KiteTicker.modeLTP);
            log.info("KiteTicker subscribed {} tokens in LTP mode", tokens.size());
        } catch (Exception e) {
            log.error("KiteTicker subscribe failed: {}", e.getMessage());
        }
    }

    // ---- Tick processing ----

    private void processTicks(ArrayList<Tick> ticks) {
        if (ticks == null) return;
        for (Tick kiteTick : ticks) {
            try {
                long token = kiteTick.getInstrumentToken();
                String symbol = tokenToSymbol.get(token);
                if (symbol == null) {
                    log.debug("Received tick for unknown token {}, skipping", token);
                    continue;
                }
                double ltp = kiteTick.getLastTradedPrice();
                MarketTick tick = MarketTick.builder()
                        .instrument(symbol)
                        .lastPrice(ltp)
                        .timestamp(LocalDateTime.now())
                        .build();
                marketDataService.processTick(tick);
            } catch (Exception e) {
                log.warn("Error processing KiteTicker tick: {}", e.getMessage());
            }
        }
    }

    // ---- Status ----

    public boolean isActive() {
        return connected && ticker != null;
    }

    public Set<String> getSubscribedInstruments() {
        return Collections.unmodifiableSet(new HashSet<>(tokenToSymbol.values()));
    }

    public Set<Long> getSubscribedTokens() {
        return Collections.unmodifiableSet(subscribedTokens);
    }

    public Map<Long, String> getTokenToSymbolMap() {
        return Collections.unmodifiableMap(tokenToSymbol);
    }
}
