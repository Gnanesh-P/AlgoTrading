package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Pre-market configuration set by the user before trading starts.
 *
 * Quantity note:
 *   NIFTY lot size is fixed at 65 units (as per NSE specification).
 *   Users configure the NUMBER OF LOTS they want to trade (lotQuantity).
 *   Total order quantity = lotQuantity × NIFTY_LOT_SIZE (65).
 *   There is no user-configurable "lot size" field — it is always 65.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingConfig {

    /** NIFTY options lot size — fixed by NSE. Always 65. */
    public static final int NIFTY_LOT_SIZE = 65;

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
    private int lotQuantity;              // Number of NIFTY lots (1 lot = 65 qty)
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

    /**
     * Total order quantity = lots × 65.
     * e.g. 1 lot = 65 qty, 2 lots = 130 qty.
     */
    public int getTotalQuantity() {
        return lotQuantity * NIFTY_LOT_SIZE;
    }
}
