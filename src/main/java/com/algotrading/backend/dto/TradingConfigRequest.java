package com.algotrading.backend.dto;

import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.StrikeMode;
import com.algotrading.backend.model.TradeMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TradingConfigRequest {

    @NotBlank
    private String futuresInstrument;

    // Used in MANUAL mode only
    private String ceInstrument;
    private String peInstrument;
    private int ceStrikePrice;
    private int peStrikePrice;

    @NotNull
    private ExpiryType expiryType;

    /** Start candle time: "09:15", "09:16", or "09:17" */
    @NotBlank
    private String startCandleTime;

    @NotNull
    private StrikeMode strikeMode;

    @Min(1)
    private int lotQuantity;

    private int lotSize = 75;  // NIFTY default lot size

    @Min(1)
    private double targetProfit;

    @Min(1)
    private double stopLoss;

    @Min(0)
    private int maxReversals;

    @NotNull
    private TradeMode tradeMode;
}
