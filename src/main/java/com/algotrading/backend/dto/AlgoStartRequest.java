package com.algotrading.backend.dto;

import lombok.Data;

@Data
public class AlgoStartRequest {
    private String futureSymbol;
    private long   futureToken;
    private String ceSymbol;
    private long   ceToken;
    private String peSymbol;
    private long   peToken;
    private int    lotSize;        // treated as total quantity
    private double targetPrice;
    private double stopLoss;
    private String entryStartTime; // "09:15" | "09:16" | "09:17"
    private int    maxReversals;
    private boolean paperTrade;
    private String strikeMode;      // "MANUAL" | "AUTO"
    private String expiryType;      // "CURRENT_WEEK" | "NEXT_WEEK"
    private double trailingProfit;  // 0 = disabled; step size after target is first hit
    private boolean squareOffEod;   // Auto exit all positions at 15:29
}
