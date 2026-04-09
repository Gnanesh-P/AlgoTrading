package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a single trade leg (entry + exit) in the strategy.
 * Multiple legs can occur due to reversals.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeEntry {
    private int legNumber;           // 1st leg, 2nd leg (after reversal), etc.
    private OptionType optionType;   // CE or PE
    private String instrument;       // Option instrument key (e.g. NIFTY24APR22000CE)
    private int strikePrice;
    private ExpiryType expiryType;
    private int quantity;            // Number of lots * lot size

    private double entryPrice;       // Option price at entry
    private LocalDateTime entryTime;
    private double exitPrice;        // Option price at exit (0 if still open)
    private LocalDateTime exitTime;
    private String exitReason;       // TARGET, STOPLOSS, REVERSAL, MAX_REVERSALS, EOD

    private double pnl;              // (exitPrice - entryPrice) * quantity (for CE buy)
                                     // (entryPrice - exitPrice) * quantity (for PE buy)
    private boolean closed;
}
