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
    private String  status;                 // RUNNING | WAITING | TARGET_HIT | SL_HIT | STOPPED
    private String  startedBy;             // username of the user who started this session
    private String  currentPosition;        // CE | PE
    private Double  currentEntryPrice;
    private Double  currentOptionPrice;
    private double  currentLegUnrealizedPnL;
    private double  cumulativePnL;          // realized across closed legs
    private double  totalPnL;              // cumulative + open
    private String  currentSymbol;
    private String  futureSymbol;
    private int     reversalCount;
    private int     maxReversals;
    private double  targetPnL;
    private double  stopLossPoints;         // SL amount in ₹
    private double  trailingProfit;         // step size (0 = disabled)
    private boolean trailingActive;         // true once target was first hit
    private double  trailingHighWatermark;  // highest PnL seen since trailing activated
    private boolean squareOffEod;
    private boolean paperTrade;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String  stopReason;
    private CandleInfo firstCandle;
    private CandleInfo secondCandle;
    private CandleInfo thirdCandle;
    private List<HistoryRow> history;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleInfo {
        private double close;
        private String time;   // "HH:mm" formatted
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryRow {
        private int    legNumber;
        private String position;    // CE | PE
        private String symbol;
        private double entryPrice;
        private double exitPrice;
        private double pnlPoints;   // exitPrice - entryPrice
        private double pnlAmount;   // pnlPoints * quantity
        private LocalDateTime entryTime;
        private LocalDateTime exitTime;
        private String exitReason;  // REVERSAL | TARGET | STOPLOSS | MANUAL_STOP | OPEN
    }
}
