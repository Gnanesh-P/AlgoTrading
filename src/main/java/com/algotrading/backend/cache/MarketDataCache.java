package com.algotrading.backend.cache;

import com.algotrading.backend.model.MarketTick;
import com.algotrading.backend.model.TradingConfig;
import com.algotrading.backend.model.TradeSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single in-memory store for all runtime trading data.
 * Thread-safe using ConcurrentHashMap. Single instance only.
 */
@Component
@Slf4j
public class MarketDataCache {

    // Latest tick per instrument: instrument -> MarketTick
    private final Map<String, MarketTick> latestTicks = new ConcurrentHashMap<>();

    // Historical ticks per instrument (last 200 ticks for candle building)
    private final Map<String, List<MarketTick>> tickHistory = new ConcurrentHashMap<>();

    // Current trading configuration
    private volatile TradingConfig currentConfig;

    // Current trading session (one per day)
    private volatile TradeSession currentSession;

    // ---- Tick operations ----

    public void updateTick(MarketTick tick) {
        latestTicks.put(tick.getInstrument(), tick);
        tickHistory.computeIfAbsent(tick.getInstrument(), k -> new ArrayList<>());
        List<MarketTick> history = tickHistory.get(tick.getInstrument());
        synchronized (history) {
            history.add(tick);
            // Keep last 1000 ticks to bound memory
            if (history.size() > 1000) {
                history.remove(0);
            }
        }
        log.debug("Tick updated: {} @ {}", tick.getInstrument(), tick.getLastPrice());
    }

    public MarketTick getLatestTick(String instrument) {
        return latestTicks.get(instrument);
    }

    public List<MarketTick> getTickHistory(String instrument) {
        return tickHistory.getOrDefault(instrument, new ArrayList<>());
    }

    public double getLastPrice(String instrument) {
        MarketTick tick = latestTicks.get(instrument);
        return tick != null ? tick.getLastPrice() : 0.0;
    }

    // ---- Config operations ----

    public void setConfig(TradingConfig config) {
        this.currentConfig = config;
        log.info("Trading config updated: {}", config);
    }

    public TradingConfig getConfig() {
        return currentConfig;
    }

    // ---- Session operations ----

    public void setSession(TradeSession session) {
        this.currentSession = session;
    }

    public TradeSession getSession() {
        return currentSession;
    }

    // ---- Utility ----

    public void clearDayData() {
        tickHistory.clear();
        latestTicks.clear();
        currentSession = null;
        currentConfig = null;
        log.info("Day data cleared from cache");
    }

    public Map<String, MarketTick> getAllLatestTicks() {
        return Map.copyOf(latestTicks);
    }
}
