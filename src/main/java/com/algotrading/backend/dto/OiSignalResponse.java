package com.algotrading.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OiSignalResponse {

    private boolean dataAvailable;
    private String message;          // set when dataAvailable=false (e.g. "Connect Kite to see live OI data")

    private String index;            // "NIFTY"
    private double ltp;
    private String expiryLabel;

    private double pcr;               // totalPutOi / totalCallOi
    private long totalCallOi;
    private long totalPutOi;
    private double callOiChangePct;   // vs the oldest snapshot in the rolling window
    private double putOiChangePct;

    private int supportStrike;        // strike with max Put OI
    private int resistanceStrike;     // strike with max Call OI

    private String signal;            // STRONG_BULLISH_REVERSAL | BULLISH | NEUTRAL | BEARISH | STRONG_BEARISH_REVERSAL
    private int signalScore;          // -100..100
    private List<String> reasoning;

    private List<StrikeOi> strikes;
    private LocalDateTime asOf;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrikeOi {
        private int strike;
        private long ceOi;
        private long peOi;
        private boolean atm;
    }
}
