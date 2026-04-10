package com.algotrading.backend.service;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.engine.TradingEngineRegistry;
import com.algotrading.backend.model.Candle;
import com.algotrading.backend.model.MarketTick;
import com.algotrading.backend.model.TradingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Handles incoming market tick data from the global KiteTicker (admin's Kite connection).
 *
 * Every tick is:
 *  1. Stored in the global MarketDataCache (for LTP lookups, option chain, UI display).
 *  2. Routed to ALL active PAPER-mode engines via TradingEngineRegistry.
 *     (LIVE-mode engines receive ticks directly from their own KiteTicker.)
 *  3. Processed by the legacy singleton TradingStrategyService if it has an active session
 *     (retained for backward compat / admin single-user mode).
 */
@Service
@Slf4j
public class MarketDataService {

    private final MarketDataCache cache;
    private final CandleAggregatorService candleAggregator;
    private final TradingStrategyService strategyService;
    private final TradingEngineRegistry engineRegistry;

    public MarketDataService(MarketDataCache cache,
                             CandleAggregatorService candleAggregator,
                             TradingStrategyService strategyService,
                             @Lazy TradingEngineRegistry engineRegistry) {
        this.cache = cache;
        this.candleAggregator = candleAggregator;
        this.strategyService = strategyService;
        this.engineRegistry = engineRegistry;
    }

    /**
     * Main entry point: receives a tick from the global KiteTicker (admin's Kite connection).
     *
     * Actions:
     *  1. Update global price cache.
     *  2. Route to all PAPER-mode engines in TradingEngineRegistry (each engine
     *     aggregates its own candles internally — no shared state).
     *  3. Run the legacy singleton strategy (for admin/single-user mode compatibility).
     */
    public void processTick(MarketTick tick) {
        if (tick.getTimestamp() == null) {
            tick.setTimestamp(LocalDateTime.now());
        }

        // 1. Update global cache — used for LTP lookups, option chain, status UI
        cache.updateTick(tick);

        // 2. Real-time SL / Target check on EVERY option tick (singleton strategy).
        //    This fires BEFORE candle aggregation so exits happen at the exact tick price,
        //    not delayed until the next candle close (which could be up to 60 s later).
        strategyService.checkSLTargetOnTick(tick.getInstrument(), tick.getLastPrice());

        // 3. Route to all active PAPER-mode engines in the multi-tenant registry
        //    Each engine filters for its own instruments internally.
        engineRegistry.routeTickToPaperEngines(tick);

        // 4. Legacy singleton strategy — candle aggregation + reversal logic
        TradingConfig config = cache.getConfig();
        if (config == null) return;

        boolean isRelevant = tick.getInstrument().equals(config.getFuturesInstrument())
                || tick.getInstrument().equals(config.getCeInstrument())
                || tick.getInstrument().equals(config.getPeInstrument());
        if (!isRelevant) return;

        Candle closedCandle = candleAggregator.processTick(tick);
        if (closedCandle != null && closedCandle.getInstrument().equals(config.getFuturesInstrument())) {
            log.debug("Futures candle closed, triggering reversal check: {}", closedCandle.getOpenTime());
            strategyService.onCandleClose(closedCandle);
        }
    }

    /**
     * Directly inject a completed 1-min candle (for testing or manual candle feed).
     * Triggers strategy evaluation immediately.
     */
    public void injectCandle(Candle candle) {
        candleAggregator.injectCompletedCandle(candle.getInstrument(), candle);
        TradingConfig config = cache.getConfig();
        if (config != null && candle.getInstrument().equals(config.getFuturesInstrument())) {
            strategyService.onCandleClose(candle);
        }
    }
}
