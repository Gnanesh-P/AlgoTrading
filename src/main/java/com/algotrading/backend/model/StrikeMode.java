package com.algotrading.backend.model;

public enum StrikeMode {
    MANUAL,   // User selects specific strike
    AUTO_ATM  // System selects ATM strike based on NIFTY Futures price
}
