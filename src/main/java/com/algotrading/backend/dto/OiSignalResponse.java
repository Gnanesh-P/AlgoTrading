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
    private String expiryLabel;          // weekly expiry (nearest Tuesday) used for PCR/momentum
    private String monthlyExpiryLabel;   // monthly expiry (last Tuesday of month), for the S/R levels below

    private double pcr;               // totalPutOi / totalCallOi
    private long totalCallOi;
    private long totalPutOi;
    private double callOiChangePct;   // vs the oldest snapshot in the rolling window
    private double putOiChangePct;

    private int supportStrike;        // strike with max Put OI (weekly ATM window) — legacy single-level fallback
    private int resistanceStrike;     // strike with max Call OI (weekly ATM window) — legacy single-level fallback

    private String signal;            // STRONG_BULLISH_REVERSAL | BULLISH | NEUTRAL | BEARISH | STRONG_BEARISH_REVERSAL
    private int signalScore;          // -100..100
    private List<String> reasoning;

    private List<StrikeOi> strikes;
    private LocalDateTime asOf;

    // OI-based Support/Resistance indicator: monthly OI (high priority, scored 100/80/60) merged
    // with weekly OI (secondary priority, scored 40/30/20) per strike, then filtered to strikes
    // below/above spot and sorted nearest-to-farthest. Recomputed at most every 30 minutes
    // (levelsAsOf) since OI shifts slowly — independent of the fast 30s PCR/signal refresh above.
    private List<LevelInfo> supportLevels;      // SUP1..SUP3, closest to farthest below spot
    private List<LevelInfo> resistanceLevels;   // RES1..RES3, closest to farthest above spot
    private LocalDateTime levelsAsOf;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelInfo {
        private int strike;
        private int score;
        private String strength;   // VERY_STRONG (>=120) | STRONG (80-119) | MEDIUM (40-79) | WEAK (<40)
    }
}
