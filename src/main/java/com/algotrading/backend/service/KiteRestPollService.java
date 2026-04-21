package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.engine.TradingEngineRegistry;
import com.algotrading.backend.model.MarketTick;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.LTPQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * REST API polling fallback for paper-mode tick delivery.
 *
 * <p>Problem this solves: The primary tick source is the global {@link KiteTickerService}
 * WebSocket. On platforms like Render (free tier), idle WebSocket connections are dropped
 * after 30 s. When KiteTicker disconnects, PAPER-mode engines stop receiving ticks and
 * candles stop forming — even though the user's strategy is still "running".
 *
 * <p>This service polls the Kite REST LTP endpoint every {@value #POLL_INTERVAL_MS} ms
 * whenever:
 * <ol>
 *   <li>The global KiteTicker WebSocket is NOT active (disconnected / never connected).
 *   <li>At least one active PAPER-mode engine has subscribed instruments.
 *   <li>The Kite access token is present (i.e. {@code kite.isConnected() == true}).
 *   <li>The current time is within market hours (09:15 – 15:30 IST).
 * </ol>
 *
 * <p>When conditions are met it calls {@code kiteConnect.getLTP()} for all subscribed
 * instruments and feeds each result to {@link MarketDataService#processTick(MarketTick)},
 * exactly as the WebSocket ticker would. This ensures candle aggregation and real-time
 * SL/Target checks keep working even when the WebSocket path is down.
 *
 * <p>If the WebSocket IS active this service stays idle to avoid duplicate tick processing.
 */
@Service
@Slf4j
public class KiteRestPollService {

    private static final int POLL_INTERVAL_MS = 2_000; // 2-second poll
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final KiteProperties       kiteProperties;
    private final KiteConnect          kiteConnect;
    private final KiteTickerService    kiteTickerService;
    private final MarketDataService    marketDataService;
    private final TradingEngineRegistry engineRegistry;

    // Tracks consecutive REST errors so we don't flood logs
    private volatile int consecutiveErrors = 0;

    public KiteRestPollService(KiteProperties kiteProperties,
                               KiteConnect kiteConnect,
                               @Lazy KiteTickerService kiteTickerService,
                               @Lazy MarketDataService marketDataService,
                               @Lazy TradingEngineRegistry engineRegistry) {
        this.kiteProperties    = kiteProperties;
        this.kiteConnect       = kiteConnect;
        this.kiteTickerService = kiteTickerService;
        this.marketDataService = marketDataService;
        this.engineRegistry    = engineRegistry;
    }

    /**
     * Poll Kite REST API for LTP every {@value #POLL_INTERVAL_MS} ms.
     * Spring's scheduler guarantees at most one invocation at a time
     * (fixedDelay = next run starts only after current one finishes).
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void pollLtpIfNeeded() {
        // Skip if global WebSocket is up — it delivers ticks already
        if (kiteTickerService.isActive()) return;

        // Skip outside market hours
        LocalTime now = LocalTime.now();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) return;

        // Skip if no Kite access token is set (can't call REST API either)
        if (!kiteProperties.isConnected()) return;

        // Collect instruments that are subscribed (global KiteTickerService keeps this list)
        Map<Long, String> tokenToSymbol = kiteTickerService.getTokenToSymbolMap();
        if (tokenToSymbol.isEmpty()) {
            // Also check if any engine has subscribed instruments directly
            boolean anyActiveEngine = engineRegistry.getAllEngines().values().stream()
                    .anyMatch(e -> e.getSession() != null && e.isActive());
            if (!anyActiveEngine) return;
            // If engines exist but no global tokens registered, nothing to poll
            return;
        }

        // Build Kite-format keys: "NFO:NIFTY26MAYFUT", "NFO:NIFTY26MAY24900CE", …
        // All NIFTY futures and option instruments trade on the NFO exchange.
        String[] kiteKeys = tokenToSymbol.values().stream()
                .map(sym -> "NFO:" + sym)
                .distinct()
                .toArray(String[]::new);

        if (kiteKeys.length == 0) return;

        try {
            Map<String, LTPQuote> ltpMap = kiteConnect.getLTP(kiteKeys);
            if (ltpMap == null || ltpMap.isEmpty()) return;

            LocalDateTime ts = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

            for (Map.Entry<String, LTPQuote> entry : ltpMap.entrySet()) {
                String kiteKey = entry.getKey();             // "NFO:NIFTY26MAYFUT"
                LTPQuote quote  = entry.getValue();
                if (quote == null || quote.lastPrice <= 0) continue;

                // Strip exchange prefix to get our internal symbol
                String symbol = kiteKey.contains(":") ? kiteKey.split(":", 2)[1] : kiteKey;

                MarketTick tick = MarketTick.builder()
                        .instrument(symbol)
                        .lastPrice(quote.lastPrice)
                        .timestamp(ts)
                        .build();

                marketDataService.processTick(tick);
            }

            if (consecutiveErrors > 0) {
                log.info("KiteRestPoll: recovered after {} errors, polling {} instruments via REST",
                        consecutiveErrors, kiteKeys.length);
                consecutiveErrors = 0;
            }

        } catch (KiteException ke) {
            consecutiveErrors++;
            if (consecutiveErrors == 1 || consecutiveErrors % 30 == 0) {
                log.warn("KiteRestPoll: Kite API error (attempt #{}) [{}]: {}",
                        consecutiveErrors, ke.code, ke.getMessage());
            }
        } catch (Exception e) {
            consecutiveErrors++;
            // Log first error and every 30th after that (avoids log spam)
            if (consecutiveErrors == 1 || consecutiveErrors % 30 == 0) {
                log.warn("KiteRestPoll: LTP fetch failed (attempt #{}): {}", consecutiveErrors, e.getMessage());
            }
        }
    }
}
