package com.algotrading.backend.service;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.model.Candle;
import com.algotrading.backend.model.MarketTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates raw market ticks into 1-minute OHLC candles.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleAggregatorService {

    private final MarketDataCache cache;

    // Current forming candle per instrument
    private final Map<String, Candle> formingCandles = new ConcurrentHashMap<>();

    // Completed candles per instrument (keep last 20 candles)
    private final Map<String, List<Candle>> completedCandles = new ConcurrentHashMap<>();

    /**
     * Process a new tick and return a completed candle if a minute boundary was crossed.
     */
    public Candle processTick(MarketTick tick) {
        String instrument = tick.getInstrument();
        LocalDateTime tickTime = tick.getTimestamp() != null
                ? tick.getTimestamp()
                : LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime candleMinute = tickTime.truncatedTo(ChronoUnit.MINUTES);

        Candle forming = formingCandles.get(instrument);

        if (forming == null) {
            // Start new candle
            formingCandles.put(instrument, buildNewCandle(instrument, tick, candleMinute));
            return null;
        }

        if (candleMinute.isAfter(forming.getOpenTime())) {
            // Minute boundary crossed — close the previous candle
            forming.setCloseTime(forming.getOpenTime().plusMinutes(1).minusNanos(1));
            storeCompletedCandle(instrument, forming);
            log.debug("Candle closed: {} OHLC={}/{}/{}/{}", instrument,
                    forming.getOpen(), forming.getHigh(), forming.getLow(), forming.getClose());

            // Start fresh candle for this minute
            formingCandles.put(instrument, buildNewCandle(instrument, tick, candleMinute));
            return forming;
        }

        // Update current candle
        if (tick.getLastPrice() > forming.getHigh()) forming.setHigh(tick.getLastPrice());
        if (tick.getLastPrice() < forming.getLow()) forming.setLow(tick.getLastPrice());
        forming.setClose(tick.getLastPrice());
        forming.setVolume(forming.getVolume() + tick.getVolume());
        return null;
    }

    private Candle buildNewCandle(String instrument, MarketTick tick, LocalDateTime candleMinute) {
        return Candle.builder()
                .instrument(instrument)
                .openTime(candleMinute)
                .open(tick.getLastPrice())
                .high(tick.getLastPrice())
                .low(tick.getLastPrice())
                .close(tick.getLastPrice())
                .volume(tick.getVolume())
                .build();
    }

    private void storeCompletedCandle(String instrument, Candle candle) {
        completedCandles.computeIfAbsent(instrument, k -> new ArrayList<>());
        List<Candle> list = completedCandles.get(instrument);
        synchronized (list) {
            list.add(candle);
            if (list.size() > 20) list.remove(0);
        }
    }

    public List<Candle> getCompletedCandles(String instrument) {
        return completedCandles.getOrDefault(instrument, new ArrayList<>());
    }

    public Candle getLastCompletedCandle(String instrument) {
        List<Candle> candles = getCompletedCandles(instrument);
        if (candles.isEmpty()) return null;
        return candles.get(candles.size() - 1);
    }

    /**
     * Directly inject a completed candle — used in paper trading / testing.
     */
    public void injectCompletedCandle(String instrument, Candle candle) {
        storeCompletedCandle(instrument, candle);
        log.info("Injected candle for {}: close={}", instrument, candle.getClose());
    }

    public void reset(String instrument) {
        formingCandles.remove(instrument);
        completedCandles.remove(instrument);
    }

    public void resetAll() {
        formingCandles.clear();
        completedCandles.clear();
    }
}
