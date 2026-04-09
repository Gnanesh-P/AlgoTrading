package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Real-time market tick data pushed by the broker WebSocket.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketTick {
    private String instrument;
    private double lastPrice;
    private double openPrice;
    private double highPrice;
    private double lowPrice;
    private long volume;
    private LocalDateTime timestamp;
}
