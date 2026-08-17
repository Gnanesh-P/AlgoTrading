package com.algotrading.backend.dto;

import lombok.Data;

@Data
public class AlgoStartRequest {
    private String  strategyKey;          // NIFTY_SCALP | BANKNIFTY_SCALP | NIFTY_BREAKOUT
    private String  tradeDirection;        // BUY | SELL (default BUY if omitted)
    private String  futureSymbol;
    private long    futureToken;
    private String  ceSymbol;
    private long    ceToken;
    private int     ceStrikePrice;          // Manual mode: numeric strike, e.g. 21000
    private String  peSymbol;
    private long    peToken;
    private int     peStrikePrice;          // Manual mode: numeric strike, e.g. 20000
    private int     lotQuantity;
    private double  targetPrice;
    private double  stopLoss;
    private String  entryStartTime;
    private int     maxReversals;
    private boolean reversalEnabled;       // Strategy 3 (breakout) only
    private boolean paperTrade;
    private String  strikeMode;
    private String  expiryType;
    private double  trailingProfit;
    private boolean squareOffEod;
    private double  breakoutPoints;         // NIFTY Breakout V2 only: retest offset (default 5)
    private double  maxChasePoints;         // NIFTY Breakout V2 only: runaway abandon offset (default 15)
}
