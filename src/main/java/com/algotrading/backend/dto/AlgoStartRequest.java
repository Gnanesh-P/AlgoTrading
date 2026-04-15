package com.algotrading.backend.dto;

import lombok.Data;

@Data
public class AlgoStartRequest {
    private String  futureSymbol;
    private long    futureToken;
    private String  ceSymbol;
    private long    ceToken;
    private String  peSymbol;
    private long    peToken;
    private int     lotQuantity;
    private double  targetPrice;
    private double  stopLoss;
    private String  entryStartTime;
    private int     maxReversals;
    private boolean paperTrade;
    private String  strikeMode;
    private String  expiryType;
    private double  trailingProfit;
    private boolean squareOffEod;
}
