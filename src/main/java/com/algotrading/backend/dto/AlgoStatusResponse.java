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
public class AlgoStatusResponse {

    private String  strategyKey;          // NIFTY_SCALP | BANKNIFTY_SCALP | NIFTY_BREAKOUT
    private String  tradeDirection;       // BUY | SELL
    private boolean active;
    private String  status;               // RUNNING | WAITING | TARGET_HIT | SL_HIT | STOPPED
    private String  startedBy;           // JWT username of whoever started this session
    private String  currentPosition;     // CE | PE
    private Double  currentEntryPrice;
    private Double  currentOptionPrice;
    private double  currentLegUnrealizedPnL;
    private double  cumulativePnL;       // realized P&L across all closed legs
    private double  totalPnL;            // cumulativePnL + open P&L
    private String  currentSymbol;
    private String  futureSymbol;
    private int     reversalCount;
    private int     maxReversals;
    private double  targetPnL;
    private double  stopLossPoints;
    private double  trailingProfit;      // step size (0 = disabled)
    private boolean trailingActive;      // true once targetProfit was first hit
    private double  trailingHighWatermark;
    private boolean squareOffEod;
    private boolean paperTrade;
    // Active config summary (displayed at top of UI so user knows what's running)
    private String  entryStartTime;      // "09:50" — start candle time
    private String  strikeMode;          // "MANUAL" | "AUTO_ATM"
    private int     lotQuantity;         // number of lots (1 lot = 65 qty)
    private int     totalQuantity;       // lotQuantity × 65
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String  stopReason;
    private CandleInfo firstCandle;
    private CandleInfo secondCandle;
    private CandleInfo thirdCandle;
    private List<HistoryRow> history;
    private String  lockedCeInstrument;
    private String  lockedPeInstrument;
    private String  lockedExpiryLabel;   // "Current Week (29 Apr)" | "Next Week (06 May)"
    private int     lockedCeStrike;      // e.g. 21000
    private int     lockedPeStrike;      // e.g. 20000
    // Per-leg breakout reference candle (the 1st 5-min candle after entryStartTime) —
    // populated once each leg's own reference candle has closed. Distinct from the
    // generic firstCandle/secondCandle/thirdCandle above (which reflect whichever leg
    // closed most recently) so the UI can show CE and PE High/Low side by side.
    private CandleInfo ceReferenceCandle;
    private CandleInfo peReferenceCandle;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleInfo {
        private double close;
        private String time;             // "HH:mm"
        private Double high;
        private Double low;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryRow {
        private int    legNumber;
        private String position;         // CE | PE
        private String symbol;
        private double entryPrice;
        private double exitPrice;
        private double pnlPoints;        // exitPrice - entryPrice
        private double pnlAmount;        // pnlPoints × quantity
        private LocalDateTime entryTime;
        private LocalDateTime exitTime;
        private String exitReason;
    }
}
