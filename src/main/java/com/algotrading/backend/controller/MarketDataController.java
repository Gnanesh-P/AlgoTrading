package com.algotrading.backend.controller;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.MarketTickRequest;
import com.algotrading.backend.model.Candle;
import com.algotrading.backend.model.MarketTick;
import com.algotrading.backend.service.CandleAggregatorService;
import com.algotrading.backend.service.MarketDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint to push market data ticks from your broker API/WebSocket.
 * In production, the broker WebSocket client pushes ticks directly to MarketDataService.
 */
@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final MarketDataCache cache;
    private final CandleAggregatorService candleAggregator;

    /**
     * Push a single tick from broker.
     * Use this endpoint from your broker's WebSocket listener.
     */
    @PostMapping("/tick")
    public ResponseEntity<String> receiveTick(@Valid @RequestBody MarketTickRequest req) {
        MarketTick tick = MarketTick.builder()
                .instrument(req.getInstrument())
                .lastPrice(req.getLastPrice())
                .openPrice(req.getOpenPrice())
                .highPrice(req.getHighPrice())
                .lowPrice(req.getLowPrice())
                .volume(req.getVolume())
                .timestamp(req.getTimestamp() != null ? req.getTimestamp() : LocalDateTime.now())
                .build();
        marketDataService.processTick(tick);
        return ResponseEntity.ok("Tick received");
    }

    /**
     * Push multiple ticks in batch (useful for backtesting or bulk replay).
     */
    @PostMapping("/ticks/batch")
    public ResponseEntity<String> receiveBatchTicks(@RequestBody List<MarketTickRequest> requests) {
        requests.forEach(req -> {
            MarketTick tick = MarketTick.builder()
                    .instrument(req.getInstrument())
                    .lastPrice(req.getLastPrice())
                    .openPrice(req.getOpenPrice())
                    .highPrice(req.getHighPrice())
                    .lowPrice(req.getLowPrice())
                    .volume(req.getVolume())
                    .timestamp(req.getTimestamp() != null ? req.getTimestamp() : LocalDateTime.now())
                    .build();
            marketDataService.processTick(tick);
        });
        return ResponseEntity.ok("Batch of " + requests.size() + " ticks received");
    }

    /**
     * Inject a pre-built 1-minute candle directly (for testing / paper trading with historical data).
     */
    @PostMapping("/candle")
    public ResponseEntity<String> injectCandle(@RequestBody Candle candle) {
        marketDataService.injectCandle(candle);
        return ResponseEntity.ok("Candle injected for " + candle.getInstrument());
    }

    /** Get latest tick data for all instruments */
    @GetMapping("/ticks")
    public ResponseEntity<Map<String, MarketTick>> getAllTicks() {
        return ResponseEntity.ok(cache.getAllLatestTicks());
    }

    /** Get latest tick for a specific instrument */
    @GetMapping("/tick/{instrument}")
    public ResponseEntity<MarketTick> getTick(@PathVariable String instrument) {
        MarketTick tick = cache.getLatestTick(instrument);
        if (tick == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(tick);
    }

    /** Get completed candles for an instrument */
    @GetMapping("/candles/{instrument}")
    public ResponseEntity<List<Candle>> getCandles(@PathVariable String instrument) {
        return ResponseEntity.ok(candleAggregator.getCompletedCandles(instrument));
    }
}
