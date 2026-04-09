package com.algotrading.backend.service;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.model.Candle;
import com.algotrading.backend.model.MarketTick;
import com.algotrading.backend.model.TradingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Handles incoming market tick data from the broker WebSocket.
 * Each tick is pushed here, aggregated into candles, and triggers strategy evaluation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataService {

    private final MarketDataCache cache;
    private final CandleAggregatorService candleAggregator;
    private final TradingStrategyService strategyService;

    /**
     * Main entry point: receives a tick from broker API/WebSocket.
     * Aggregates into candles and triggers strategy on 1-min candle close.
     */
    public void processTick(MarketTick tick) {
        if (tick.getTimestamp() == null) {
            tick.setTimestamp(LocalDateTime.now());
        }

        cache.updateTick(tick);

        TradingConfig config = cache.getConfig();
        if (config == null) return;

        // Only aggregate candles for configured instruments
        boolean isRelevant = tick.getInstrument().equals(config.getFuturesInstrument())
                || tick.getInstrument().equals(config.getCeInstrument())
                || tick.getInstrument().equals(config.getPeInstrument());

        if (!isRelevant) {
            // Still cache the tick for LTP lookups (option prices)
            return;
        }

        Candle closedCandle = candleAggregator.processTick(tick);

        // If this tick closed a 1-min candle for the futures instrument, run strategy
        if (closedCandle != null && closedCandle.getInstrument().equals(config.getFuturesInstrument())) {
            log.debug("Futures candle closed, triggering strategy: {}", closedCandle.getOpenTime());
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
