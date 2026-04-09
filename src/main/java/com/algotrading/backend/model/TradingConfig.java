package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Pre-market configuration set by the user before trading starts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingConfig {

    // Instruments
    private String futuresInstrument;     // e.g. "NIFTY25APRFUT"
    private String ceInstrument;          // e.g. "NIFTY25APR22000CE" (used in MANUAL mode)
    private String peInstrument;          // e.g. "NIFTY25APR22000PE" (used in MANUAL mode)
    private int ceStrikePrice;
    private int peStrikePrice;
    private ExpiryType expiryType;

    // Strategy timing
    private LocalTime startCandleTime;    // Any 1-min slot from 09:15 to 15:27

    // Strike selection
    private StrikeMode strikeMode;        // MANUAL or AUTO_ATM

    // Trade parameters
    private int lotQuantity;              // Number of lots
    private int lotSize;                  // NIFTY lot size (default 75)
    private double targetProfit;          // Cumulative target in rupees
    private double stopLoss;              // Cumulative stop loss in rupees (positive number)
    private int maxReversals;             // Max number of reversals allowed

    // Trailing profit (0 = disabled)
    // Once targetProfit is first hit, the exit target trails up in trailingProfit steps.
    // Exit fires when PnL drops trailingProfit below the running high-watermark.
    private double trailingProfit;

    // End-of-day auto square-off at 15:29 (regardless of P&L)
    private boolean squareOffEod;

    // Mode
    private TradeMode tradeMode;          // LIVE or PAPER

    public int getTotalQuantity() {
        return lotQuantity * lotSize;
    }
}
