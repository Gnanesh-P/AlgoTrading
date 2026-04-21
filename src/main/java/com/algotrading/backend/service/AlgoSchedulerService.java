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
 * Automatically starts the trading algo at 9:10 AM IST every weekday.
 *
 * Controlled entirely via application.yml / env vars under app.scheduler.*
 * Set SCHEDULER_ENABLED=true (or app.scheduler.enabled=true) to activate.
 *
 * Config reference:
 *   app.scheduler.enabled       (default: false)
 *   app.scheduler.username      (default: gowtham)
 *   app.scheduler.lots          (default: 1)
 *   app.scheduler.stop-loss     (default: 5000)
 *   app.scheduler.target-profit (default: 10000)
 *   app.scheduler.max-reversals (default: 2)
 *   app.scheduler.trailing-profit (default: 0)
 *   app.scheduler.square-off-eod  (default: true)
 *   app.scheduler.paper-trade     (default: false)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlgoSchedulerService {

    private final TradingEngineRegistry  engineRegistry;
    private final KiteInstrumentService  kiteInstrumentService;
    private final UserRegistryService    userRegistry;
    private final OptionInstrumentService optionInstrumentService;

    @Value("${app.scheduler.enabled:false}")       private boolean enabled;
    @Value("${app.scheduler.username:gowtham}")    private String  username;
    @Value("${app.scheduler.lots:1}")              private int     lots;
    @Value("${app.scheduler.stop-loss:5000}")      private double  stopLoss;
    @Value("${app.scheduler.target-profit:10000}") private double  targetProfit;
    @Value("${app.scheduler.max-reversals:2}")     private int     maxReversals;
    @Value("${app.scheduler.trailing-profit:0}")   private double  trailingProfit;
    @Value("${app.scheduler.square-off-eod:true}") private boolean squareOffEod;
    @Value("${app.scheduler.paper-trade:false}")   private boolean paperTrade;

    /**
     * Fires at 9:10 AM IST every Monday–Friday.
     * Starts a session with AUTO_ATM strikes and the nearest NIFTY monthly future.
     */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void autoStartAlgo() {
        if (!enabled) {
            log.debug("AlgoScheduler: disabled — skipping auto-start");
            return;
        }

        if (engineRegistry.hasActiveEngine(username)) {
            log.info("AlgoScheduler: [{}] already has an active session — skipping", username);
            return;
        }

        PlatformUser user = userRegistry.findByUsername(username).orElse(null);
        if (user == null) {
            log.error("AlgoScheduler: user [{}] not found — cannot auto-start", username);
            return;
        }
        if (!user.isEnabled() || !user.isPlanActive()) {
            log.warn("AlgoScheduler: user [{}] is disabled or plan expired — skipping", username);
            return;
        }

        // Resolve the nearest NIFTY futures instrument (current month, or next if near expiry)
        String futuresSymbol = resolveNiftyFuturesSymbol();
        if (futuresSymbol == null) {
            log.error("AlgoScheduler: no NIFTY futures found in instrument cache — cannot auto-start");
            return;
        }

        TradingConfig config = TradingConfig.builder()
                .futuresInstrument(futuresSymbol)
                // CE/PE instruments are not needed for AUTO_ATM — resolved from first-candle close
                .ceInstrument(null)
                .peInstrument(null)
                .ceStrikePrice(0)
                .peStrikePrice(0)
                .expiryType(ExpiryType.CURRENT_WEEK)   // AUTO_ATM overrides this with smart logic
                .strikeMode(StrikeMode.AUTO_ATM)
                .startCandleTime(LocalTime.of(9, 15))  // entry after first 9:15 candle
                .lotQuantity(lots)
                .targetProfit(targetProfit)
                .stopLoss(stopLoss)
                .maxReversals(maxReversals)
                .trailingProfit(trailingProfit)
                .squareOffEod(squareOffEod)
                .tradeMode(paperTrade ? TradeMode.PAPER : TradeMode.LIVE)
                .build();

        // Resolve futures token from instrument cache for ticker subscription
        Map<Long, String> instruments = new LinkedHashMap<>();
        kiteInstrumentService.findBySymbol(futuresSymbol).ifPresentOrElse(
                i -> {
                    if (i.getInstrumentToken() > 0) {
                        instruments.put(i.getInstrumentToken(), i.getTradingsymbol());
                    }
                },
                () -> log.warn("AlgoScheduler: futures token not found for [{}]", futuresSymbol)
        );

        try {
            engineRegistry.startEngine(user, config, instruments, username);
            log.info("AlgoScheduler: session auto-started for [{}] | futures={} | lots={} | SL={} | target={} | mode={}",
                    username, futuresSymbol, lots, stopLoss, targetProfit,
                    paperTrade ? "PAPER" : "LIVE");
        } catch (Exception e) {
            log.error("AlgoScheduler: failed to auto-start session for [{}]: {}", username, e.getMessage(), e);
        }
    }

    /**
     * Returns the nearest-expiry NIFTY futures symbol from the Kite instrument cache.
     * Falls back to building the symbol from the current month if cache is empty.
     */
    private String resolveNiftyFuturesSymbol() {
        List<KiteInstrument> futures = kiteInstrumentService.getNiftyFutures();
        if (!futures.isEmpty()) {
            String symbol = futures.get(0).getTradingsymbol();
            log.info("AlgoScheduler: resolved NIFTY futures from cache → {}", symbol);
            return symbol;
        }

        // Cache not yet loaded — build symbol from current month
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        // NIFTY futures format: NIFTYyyMMMFUT e.g. NIFTY26APRFUT
        String symbol = "NIFTY" + now.format(DateTimeFormatter.ofPattern("yyMMM", Locale.ENGLISH)).toUpperCase() + "FUT";
        log.warn("AlgoScheduler: instrument cache empty — using derived symbol: {}", symbol);
        return symbol;
    }
}
