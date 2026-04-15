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

    public int getTotalQuantity() {
        return lotQuantity * NIFTY_LOT_SIZE;
    }
}
