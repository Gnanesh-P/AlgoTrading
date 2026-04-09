package com.algotrading.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Represents a single instrument record from Kite's instruments CSV.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KiteInstrument {
    private long instrumentToken;
    private long exchangeToken;
    private String tradingsymbol;    // e.g. NIFTY25APR25000CE
    private String name;             // e.g. NIFTY 50
    private double lastPrice;
    private LocalDate expiry;
    private double strike;
    private double tickSize;
    private int lotSize;
    private String instrumentType;   // FUT, CE, PE, EQ
    private String segment;          // NFO-FUT, NFO-OPT
    private String exchange;         // NFO, NSE

    public String getKiteKey() {
        return exchange + ":" + tradingsymbol;
    }
}
