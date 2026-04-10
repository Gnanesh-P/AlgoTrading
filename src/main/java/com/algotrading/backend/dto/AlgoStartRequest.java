package com.algotrading.backend.dto;

import lombok.Data;

/**
 * Request body for POST /algo/start.
 *
 * Quantity note:
 *   NIFTY lot size is always 65 units (fixed by NSE).
 *   Only {@code lotQuantity} (number of lots) is configurable.
 *   Total order qty = lotQuantity × 65.
 */
@Data
public class AlgoStartRequest {
    private String  futureSymbol;
    private long    futureToken;
    private String  ceSymbol;
    private long    ceToken;
    private String  peSymbol;
    private long    peToken;
    private int     lotQuantity;     // Number of lots to trade (e.g. 1 → 65 qty, 2 → 130 qty)
    private double  targetPrice;
    private double  stopLoss;
    private String  entryStartTime;  // "HH:mm", 09:15 – 15:27
    private int     maxReversals;
    private boolean paperTrade;
    private String  strikeMode;      // "MANUAL" | "AUTO"
    private String  expiryType;      // "CURRENT_WEEK" | "NEXT_WEEK"
    private double  trailingProfit;  // 0 = disabled; step size after target is first hit
    private boolean squareOffEod;    // Auto exit all positions at 15:29
}
