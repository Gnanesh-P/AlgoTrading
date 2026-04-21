package com.algotrading.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 *
 * Jackson note:
 *   @JsonIgnoreProperties(ignoreUnknown = true) is critical for crash recovery:
 *   if the JSON file was saved by an older version of the app, unknown fields
 *   are silently skipped rather than throwing an exception on deserialization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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
    private Candle thirdCandle;         // informational (entry happens at 3rd candle open)
    private Candle lastClosedCandle;    // Most recent 1-min candle closed

    // Locked strike (same for entire day including reversals)
    private String lockedCeInstrument;
    private String lockedPeInstrument;
    private int lockedCeStrike;
    private int lockedPeStrike;
    private String lockedExpiryLabel;  // e.g. "Current Week (29 Apr)" or "Next Week (06 May)"
    private LocalDate lockedExpiry;     // actual expiry date used for instrument lookup

    // Trade legs
    @Builder.Default
    private List<TradeEntry> tradeLegs = new ArrayList<>();

    // P&L tracking (cumulative across all legs)
    private double cumulativePnL;
    private double openPnL;             // P&L on current open position

    // Trailing profit state
    private boolean trailingActive;          // true once targetProfit is first breached
    private double  trailingHighWatermark;   // running max PnL since trailing activated

    // Session ownership — JWT username of whoever clicked Start
    private String startedBy;

    // Session timestamps
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String stopReason;

    // ── Computed helpers ─────────────────────────────────────────────────────
    // NOTE: currentOpenLeg is NOT a stored field — it is COMPUTED from tradeLegs.
    // Do NOT add a `private TradeEntry currentOpenLeg` field here. If you do,
    // Lombok will generate a `getCurrentOpenLeg()` getter that conflicts with the
    // custom method below and will prevent ALL Lombok-generated getters (including
    // getStartedBy(), getSessionId() etc.) from compiling.

    /** Returns the first open (unclosed) trade leg, or null if none. */
    public TradeEntry getCurrentOpenLeg() {
        if (tradeLegs == null || tradeLegs.isEmpty()) return null;
        return tradeLegs.stream()
                .filter(leg -> !leg.isClosed())
                .findFirst()
                .orElse(null);
    }

    /** Sum of P&L across all closed legs. */
    public double getTotalRealizedPnL() {
        if (tradeLegs == null) return 0;
        return tradeLegs.stream()
                .filter(TradeEntry::isClosed)
                .mapToDouble(TradeEntry::getPnl)
                .sum();
    }

    /** Realized P&L + open position P&L. */
    public double getTotalPnL() {
        return getTotalRealizedPnL() + openPnL;
    }
}
