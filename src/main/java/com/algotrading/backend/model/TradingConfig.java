package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingConfig {

    public static final int NIFTY_LOT_SIZE = 65;
    public static final int BANKNIFTY_LOT_SIZE = 30;

    private String futuresInstrument;
    private String ceInstrument;
    private String peInstrument;
    private int ceStrikePrice;
    private int peStrikePrice;
    private ExpiryType expiryType;

    private LocalTime startCandleTime;

    private StrikeMode strikeMode;

    private int lotQuantity;
    private double targetProfit;
    private double stopLoss;
    private int maxReversals;

    private double trailingProfit;

    private boolean squareOffEod;

    private TradeMode tradeMode;

    // Strategy key this config belongs to (NIFTY_SCALP / BANKNIFTY_SCALP / NIFTY_BREAKOUT)
    private StrategyKey strategyKey;

    // BUY (long options) or SELL (write/short options). Default BUY preserves legacy behavior.
    @Builder.Default
    private TradeDirection tradeDirection = TradeDirection.BUY;

    // Strategy 3 only: whether reversal between CE/PE legs is allowed. Ignored by Strategy 1/2
    // (which always use maxReversals directly, unbounded by this flag).
    private boolean reversalEnabled;

    public int lotSizeForInstrument() {
        return (futuresInstrument != null && futuresInstrument.toUpperCase().contains("BANKNIFTY"))
                ? BANKNIFTY_LOT_SIZE
                : NIFTY_LOT_SIZE;
    }

    public int getTotalQuantity() {
        return lotQuantity * lotSizeForInstrument();
    }
}
