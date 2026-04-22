package com.algotrading.backend.service;

import com.algotrading.backend.engine.TradingEngineRegistry;
import com.algotrading.backend.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles scheduled and on-demand auto-start of the trading algo in AUTO_ATM mode.
 *
 * The morning cron fires at 9:10 AM IST Mon–Fri when enabled.
 * The same logic is also callable on-demand via AlgoController.triggerScheduler().
 *
 * Config (application.yml / env vars):
 *   app.scheduler.enabled         (default: false)  — controls morning cron only, not API trigger
 *   app.scheduler.username        (default: gowtham)
 *   app.scheduler.lots            (default: 1)
 *   app.scheduler.stop-loss       (default: 5000)
 *   app.scheduler.target-profit   (default: 10000)
 *   app.scheduler.max-reversals   (default: 2)
 *   app.scheduler.trailing-profit (default: 0)
 *   app.scheduler.square-off-eod  (default: true)
 *   app.scheduler.paper-trade     (default: false)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlgoSchedulerService {

    private final TradingEngineRegistry    engineRegistry;
    private final KiteInstrumentService    kiteInstrumentService;
    private final KiteTickerService        kiteTickerService;
    private final CandleAggregatorService  candleAggregator;
    private final UserRegistryService      userRegistry;

    @Value("${app.scheduler.enabled:false}")        private boolean enabled;
    @Value("${app.scheduler.username:gowtham}")     private String  schedulerUsername;
    @Value("${app.scheduler.lots:1}")               private int     lots;
    @Value("${app.scheduler.stop-loss:5000}")       private double  stopLoss;
    @Value("${app.scheduler.target-profit:10000}")  private double  targetProfit;
    @Value("${app.scheduler.max-reversals:2}")      private int     maxReversals;
    @Value("${app.scheduler.trailing-profit:0}")    private double  trailingProfit;
    @Value("${app.scheduler.square-off-eod:true}")  private boolean squareOffEod;
    @Value("${app.scheduler.paper-trade:false}")    private boolean paperTrade;

    // ─────────────────────────────────────────────────────────────────────────
    // Morning cron — 9:10 AM IST, Mon–Fri
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void autoStartAlgo() {
        if (!enabled) {
            log.debug("AlgoScheduler: cron disabled — skipping morning auto-start");
            return;
        }
        String result = triggerAutoStart(schedulerUsername);
        log.info("AlgoScheduler morning cron: {}", result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core logic — shared by cron and on-demand API trigger
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts an AUTO_ATM session for the configured username.
     * Safe to call at any time — returns a human-readable result string.
     * Used by both the morning cron and the on-demand API endpoint.
     */
    public String triggerAutoStart(String targetUsername) {
        if (engineRegistry.hasActiveEngine(targetUsername)) {
            return "SKIPPED: [" + targetUsername + "] already has an active session";
        }

        PlatformUser user = userRegistry.findByUsername(targetUsername).orElse(null);
        if (user == null) {
            return "ERROR: user [" + targetUsername + "] not found";
        }
        if (!user.isEnabled() || !user.isPlanActive()) {
            return "ERROR: user [" + targetUsername + "] is disabled or plan expired";
        }

        String futuresSymbol = resolveNiftyFuturesSymbol();
        if (futuresSymbol == null) {
            return "ERROR: no NIFTY futures found in instrument cache — is Kite connected?";
        }

        TradingConfig config = TradingConfig.builder()
                .futuresInstrument(futuresSymbol)
                .ceInstrument(null)
                .peInstrument(null)
                .ceStrikePrice(0)
                .peStrikePrice(0)
                .expiryType(ExpiryType.CURRENT_WEEK)  // AUTO_ATM uses smart expiry logic regardless
                .strikeMode(StrikeMode.AUTO_ATM)
                .startCandleTime(LocalTime.of(9, 15))
                .lotQuantity(lots)
                .targetProfit(targetProfit)
                .stopLoss(stopLoss)
                .maxReversals(maxReversals)
                .trailingProfit(trailingProfit)
                .squareOffEod(squareOffEod)
                .tradeMode(paperTrade ? TradeMode.PAPER : TradeMode.LIVE)
                .build();

        // Resolve futures token for global ticker subscription
        Map<Long, String> instruments = new LinkedHashMap<>();
        kiteInstrumentService.findBySymbol(futuresSymbol).ifPresentOrElse(
                i -> {
                    if (i.getInstrumentToken() > 0) {
                        instruments.put(i.getInstrumentToken(), i.getTradingsymbol());
                    }
                },
                () -> log.warn("AlgoScheduler: futures token not in cache for [{}]", futuresSymbol)
        );

        // Mirror exactly what AlgoController.startAlgo() does:
        // reset candle state, unsubscribe stale tokens, subscribe futures
        candleAggregator.resetAll();
        kiteTickerService.unsubscribeAll();
        if (!instruments.isEmpty()) {
            kiteTickerService.subscribe(instruments);
            log.info("AlgoScheduler: global KiteTicker subscribed futures: {}", instruments.values());
        } else {
            log.warn("AlgoScheduler: futures token not resolved — candle ticks may not arrive for {}", futuresSymbol);
        }

        try {
            engineRegistry.startEngine(user, config, instruments, targetUsername);
            String msg = String.format(
                    "STARTED: [%s] futures=%s lots=%d SL=%.0f target=%.0f mode=%s",
                    targetUsername, futuresSymbol, lots, stopLoss, targetProfit,
                    paperTrade ? "PAPER" : "LIVE");
            log.info("AlgoScheduler: {}", msg);
            return msg;
        } catch (Exception e) {
            log.error("AlgoScheduler: failed to start session for [{}]: {}", targetUsername, e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the nearest-expiry NIFTY futures tradingsymbol from the Kite instrument cache.
     * Falls back to a derived name if the cache is empty (Kite not yet connected).
     */
    public String resolveNiftyFuturesSymbol() {
        List<KiteInstrument> futures = kiteInstrumentService.getNiftyFutures();
        if (!futures.isEmpty()) {
            String symbol = futures.get(0).getTradingsymbol();
            log.info("AlgoScheduler: NIFTY futures resolved from cache → {}", symbol);
            return symbol;
        }
        // Instrument cache empty — derive from current month (format: NIFTY26APRFUT)
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String symbol = "NIFTY"
                + today.format(DateTimeFormatter.ofPattern("yyMMM", Locale.ENGLISH)).toUpperCase()
                + "FUT";
        log.warn("AlgoScheduler: instrument cache empty — using derived symbol: {}", symbol);
        return symbol;
    }

    // Expose config for API response
    public String getSchedulerUsername() { return schedulerUsername; }
    public boolean isEnabled()           { return enabled; }
}
