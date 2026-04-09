package com.algotrading.backend.dto;

import com.algotrading.backend.model.StrategyState;
import com.algotrading.backend.model.TradeEntry;
import com.algotrading.backend.model.TradingConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSessionResponse {
    private String sessionId;
    private LocalDate tradeDate;
    private StrategyState state;
    private int reversalCount;
    private int maxReversals;
    private double cumulativePnL;
    private double openPnL;
    private double totalPnL;
    private double targetProfit;
    private double stopLoss;
    private String stopReason;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<TradeLegResponse> tradeLegs;
    private CandleInfo firstCandle;
    private CandleInfo secondCandle;
    private CandleInfo thirdCandle;
    private String lockedCeInstrument;
    private String lockedPeInstrument;
    private int lockedCeStrike;
    private int lockedPeStrike;
    private String tradeMode;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeLegResponse {
        private int legNumber;
        private String optionType;
        private String instrument;
        private int strikePrice;
        private int quantity;
        private double entryPrice;
        private LocalDateTime entryTime;
        private double exitPrice;
        private LocalDateTime exitTime;
        private String exitReason;
        private double pnl;
        private boolean closed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleInfo {
        private LocalDateTime openTime;
        private LocalDateTime closeTime;
        private double open;
        private double high;
        private double low;
        private double close;
    }
}
