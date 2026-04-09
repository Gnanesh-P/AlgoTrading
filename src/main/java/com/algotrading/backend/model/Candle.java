package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {
    private LocalDateTime openTime;
    private LocalDateTime closeTime;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private String instrument;
    private int candleIndex;  // 1st, 2nd, 3rd candle index for strategy
}
