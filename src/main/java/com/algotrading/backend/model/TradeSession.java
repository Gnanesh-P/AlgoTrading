package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the entire trading session for one day.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSession {
    private String sessionId;
    private LocalDate tradeDate;
    private TradingConfig config;

    // Strategy state
    private StrategyState state;
    private int reversalCount;
    private int currentLegNumber;

    // Candle tracking
    private Candle firstCandle;
    private Candle secondCandle;
    private Candle thirdCandle;         // reserved / informational (entry happens at 3rd candle open)
    private Candle lastClosedCandle;    // Most recent 1-min candle closed

    // Locked strike (same for entire day including reversals)
    private String lockedCeInstrument;
    private String lockedPeInstrument;
    private int lockedCeStrike;
    private int lockedPeStrike;

    // Trade legs
    @Builder.Default
    private List<TradeEntry> tradeLegs = new ArrayList<>();

    // P&L tracking (cumulative across all legs)
    private double cumulativePnL;
    private double openPnL;             // P&L on current open position

    // Trailing profit state
    private boolean trailingActive;          // true once targetProfit is first breached
    private double  trailingHighWatermark;   // running max PnL since trailing activated

    // Session timestamps
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String stopReason;          // Why session was stopped

    // Current open leg
    private TradeEntry currentOpenLeg;

    public TradeEntry getCurrentOpenLeg() {
        if (tradeLegs == null || tradeLegs.isEmpty()) return null;
        return tradeLegs.stream()
                .filter(leg -> !leg.isClosed())
                .findFirst()
                .orElse(null);
    }

    public double getTotalRealizedPnL() {
        if (tradeLegs == null) return 0;
        return tradeLegs.stream()
                .filter(TradeEntry::isClosed)
                .mapToDouble(TradeEntry::getPnl)
                .sum();
    }

    public double getTotalPnL() {
        return getTotalRealizedPnL() + openPnL;
    }
}
