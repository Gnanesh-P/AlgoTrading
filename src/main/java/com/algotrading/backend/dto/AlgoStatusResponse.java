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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleInfo {
        private double close;
        private String time;             // "HH:mm"
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
