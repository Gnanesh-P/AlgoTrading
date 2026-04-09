package com.algotrading.backend.service;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.model.Candle;
import com.algotrading.backend.model.MarketTick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CandleAggregatorServiceTest {

    @Autowired
    private CandleAggregatorService aggregator;

    @BeforeEach
    void setUp() {
        aggregator.resetAll();
    }

    @Test
    @DisplayName("First tick starts new candle, no close yet")
    void firstTickStartsNewCandle() {
        MarketTick tick = buildTick("NIFTY", 22000, "09:15:30");
        Candle result = aggregator.processTick(tick);
        assertThat(result).isNull();  // No candle closed yet
    }

    @Test
    @DisplayName("Tick in same minute updates candle OHLC, returns null")
    void tickInSameMinuteUpdatesCandle() {
        aggregator.processTick(buildTick("NIFTY", 22000, "09:15:00"));
        aggregator.processTick(buildTick("NIFTY", 22100, "09:15:20"));
        aggregator.processTick(buildTick("NIFTY", 21950, "09:15:40"));

        Candle result = aggregator.processTick(buildTick("NIFTY", 22050, "09:15:55"));
        assertThat(result).isNull();  // Still same minute
    }

    @Test
    @DisplayName("Tick crossing minute boundary closes previous candle")
    void tickCrossingMinuteBoundaryClosesCandle() {
        aggregator.processTick(buildTick("NIFTY", 22000, "09:15:00"));
        aggregator.processTick(buildTick("NIFTY", 22100, "09:15:20"));
        aggregator.processTick(buildTick("NIFTY", 21950, "09:15:40"));
        aggregator.processTick(buildTick("NIFTY", 22050, "09:15:55"));

        // Next minute tick closes the 09:15 candle
        Candle closed = aggregator.processTick(buildTick("NIFTY", 22060, "09:16:00"));

        assertThat(closed).isNotNull();
        assertThat(closed.getOpen()).isEqualTo(22000);
        assertThat(closed.getHigh()).isEqualTo(22100);
        assertThat(closed.getLow()).isEqualTo(21950);
        assertThat(closed.getClose()).isEqualTo(22050);
    }

    @Test
    @DisplayName("Multiple candles complete correctly")
    void multipleCandles() {
        // 09:15 candle
        aggregator.processTick(buildTick("NIFTY", 22000, "09:15:00"));
        aggregator.processTick(buildTick("NIFTY", 22050, "09:15:30"));
        Candle first = aggregator.processTick(buildTick("NIFTY", 22080, "09:16:00"));

        // 09:16 candle
        aggregator.processTick(buildTick("NIFTY", 22080, "09:16:10"));
        aggregator.processTick(buildTick("NIFTY", 21900, "09:16:45"));
        Candle second = aggregator.processTick(buildTick("NIFTY", 21950, "09:17:00"));

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getOpen()).isEqualTo(22080);
        assertThat(second.getLow()).isEqualTo(21900);

        assertThat(aggregator.getCompletedCandles("NIFTY")).hasSize(2);
    }

    @Test
    @DisplayName("Injected candle appears in completed candles list")
    void injectedCandleAppearsInList() {
        Candle candle = Candle.builder()
                .instrument("NIFTY").open(22000).high(22100).low(21950).close(22080)
                .openTime(LocalDateTime.now()).build();

        aggregator.injectCompletedCandle("NIFTY", candle);

        assertThat(aggregator.getCompletedCandles("NIFTY")).hasSize(1);
        assertThat(aggregator.getLastCompletedCandle("NIFTY").getClose()).isEqualTo(22080);
    }

    @Test
    @DisplayName("Reset clears all candle data for instrument")
    void resetClearsData() {
        aggregator.processTick(buildTick("NIFTY", 22000, "09:15:00"));
        aggregator.processTick(buildTick("NIFTY", 22050, "09:16:00")); // closes 09:15

        aggregator.reset("NIFTY");
        assertThat(aggregator.getCompletedCandles("NIFTY")).isEmpty();
    }

    private MarketTick buildTick(String instrument, double price, String time) {
        String[] parts = time.split(":");
        LocalDateTime dt = LocalDateTime.now()
                .withHour(Integer.parseInt(parts[0]))
                .withMinute(Integer.parseInt(parts[1]))
                .withSecond(Integer.parseInt(parts[2]))
                .withNano(0);
        return MarketTick.builder()
                .instrument(instrument)
                .lastPrice(price)
                .timestamp(dt)
                .volume(100)
                .build();
    }
}
