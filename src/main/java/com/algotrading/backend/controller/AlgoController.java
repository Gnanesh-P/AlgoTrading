package com.algotrading.backend.controller;

import com.algotrading.backend.broker.BrokerServiceFactory;
import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.AlgoStartRequest;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.KiteTickerService;
import com.algotrading.backend.service.TradingStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Algo control endpoints that match the UI template's expected API shape.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AlgoController {

    private final MarketDataCache cache;
    private final TradingStrategyService strategyService;
    private final KiteTickerService tickerService;
    private final BrokerServiceFactory brokerFactory;

    // ---- Start ----

    @PostMapping("/algo/start")
    public ResponseEntity<String> startAlgo(@RequestBody AlgoStartRequest req) {
        TradingConfig config = TradingConfig.builder()
                .futuresInstrument(req.getFutureSymbol())
                .ceInstrument(req.getCeSymbol())
                .peInstrument(req.getPeSymbol())
                .ceStrikePrice(0)   // locked at entry for AUTO_ATM
                .peStrikePrice(0)
                .expiryType(req.getExpiryType() != null
                        ? ExpiryType.valueOf(req.getExpiryType())
                        : ExpiryType.CURRENT_WEEK)
                .startCandleTime(LocalTime.parse(req.getEntryStartTime()))
                .strikeMode("AUTO".equalsIgnoreCase(req.getStrikeMode())
                        ? StrikeMode.AUTO_ATM : StrikeMode.MANUAL)
                .lotQuantity(1)
                .lotSize(req.getLotSize())      // total qty = lotSize * 1
                .targetProfit(req.getTargetPrice())
                .stopLoss(req.getStopLoss())
                .maxReversals(req.getMaxReversals())
                .trailingProfit(req.getTrailingProfit())   // 0 = disabled
                .squareOffEod(req.isSquareOffEod())
                .tradeMode(req.isPaperTrade() ? TradeMode.PAPER : TradeMode.LIVE)
                .build();

        cache.setConfig(config);
        log.info("Algo config saved via /algo/start: {}", config);

        // Subscribe instruments for live WebSocket ticks using tokens
        Map<Long, String> instruments = new LinkedHashMap<>();
        if (req.getFutureToken() > 0) instruments.put(req.getFutureToken(), req.getFutureSymbol());
        if (req.getCeToken() > 0)     instruments.put(req.getCeToken(),     req.getCeSymbol());
        if (req.getPeToken() > 0)     instruments.put(req.getPeToken(),     req.getPeSymbol());
        if (!instruments.isEmpty()) tickerService.subscribe(instruments);

        TradeSession session = strategyService.startStrategy();
        return ResponseEntity.ok(session.getSessionId());
    }

    // ---- Stop ----

    @PostMapping("/algo/stop")
    public ResponseEntity<String> stopAlgo() {
        TradeSession session = strategyService.stopStrategy();
        return ResponseEntity.ok("Stopped: " + session.getSessionId());
    }

    // ---- Status ----

    @GetMapping("/algo/status")
    public ResponseEntity<AlgoStatusResponse> getStatus() {
        TradeSession session = cache.getSession();
        if (session == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(buildStatusResponse(session));
    }

    // ---- Mapping ----

    private AlgoStatusResponse buildStatusResponse(TradeSession session) {
        TradingConfig cfg = session.getConfig();
        TradeEntry openLeg = session.getCurrentOpenLeg();

        // Map strategy state → template status string
        String status = switch (session.getState()) {
            case WAITING_FOR_CANDLES -> "WAITING";
            case IN_POSITION         -> "RUNNING";
            case STOPPED             -> resolveStopStatus(session.getStopReason());
            default                  -> "STOPPED";
        };

        // Current option LTP — check tick cache first, fall back to broker HTTP call.
        // This ensures the live P&L shown in the UI is never stale by more than one poll cycle,
        // regardless of when the last futures candle closed.
        Double currentPrice = null;
        double liveOpenPnL  = 0;
        if (openLeg != null) {
            double ltp = cache.getLastPrice(openLeg.getInstrument());
            if (ltp <= 0) {
                // Cache cold (e.g. AUTO_ATM before first tick for this strike): call broker REST.
                ltp = brokerFactory.getBrokerService(cfg.getTradeMode()).getLtp(openLeg.getInstrument());
            }
            if (ltp > 0) {
                currentPrice = ltp;
                liveOpenPnL  = (ltp - openLeg.getEntryPrice()) * openLeg.getQuantity();
            }
        }
        double liveTotalPnL = session.getTotalRealizedPnL() + liveOpenPnL;

        // Build history rows
        List<AlgoStatusResponse.HistoryRow> history = session.getTradeLegs().stream()
                .map(leg -> {
                    double pnlPoints = leg.isClosed()
                            ? leg.getExitPrice() - leg.getEntryPrice() : 0;
                    return AlgoStatusResponse.HistoryRow.builder()
                            .legNumber(leg.getLegNumber())
                            .position(leg.getOptionType().name())
                            .symbol(leg.getInstrument())
                            .entryPrice(leg.getEntryPrice())
                            .exitPrice(leg.getExitPrice())
                            .pnlPoints(pnlPoints)
                            .pnlAmount(leg.getPnl())
                            .entryTime(leg.getEntryTime())
                            .exitTime(leg.getExitTime())
                            .exitReason(leg.isClosed() ? leg.getExitReason() : "OPEN")
                            .build();
                })
                .collect(Collectors.toList());

        return AlgoStatusResponse.builder()
                .active(session.getState() == StrategyState.WAITING_FOR_CANDLES
                        || session.getState() == StrategyState.IN_POSITION)
                .status(status)
                .firstCandle(toCandleInfo(session.getFirstCandle()))
                .secondCandle(toCandleInfo(session.getSecondCandle()))
                .thirdCandle(toCandleInfo(session.getThirdCandle()))
                .currentPosition(openLeg != null ? openLeg.getOptionType().name() : null)
                .currentEntryPrice(openLeg != null ? openLeg.getEntryPrice() : null)
                .currentOptionPrice(currentPrice)
                .currentLegUnrealizedPnL(liveOpenPnL)
                .cumulativePnL(session.getCumulativePnL())
                .totalPnL(liveTotalPnL)
                .currentSymbol(openLeg != null ? openLeg.getInstrument() : null)
                .futureSymbol(cfg.getFuturesInstrument())
                .reversalCount(session.getReversalCount())
                .maxReversals(cfg.getMaxReversals())
                .targetPnL(cfg.getTargetProfit())
                .stopLossPoints(cfg.getStopLoss())
                .trailingProfit(cfg.getTrailingProfit())
                .trailingActive(session.isTrailingActive())
                .trailingHighWatermark(session.getTrailingHighWatermark())
                .squareOffEod(cfg.isSquareOffEod())
                .paperTrade(cfg.getTradeMode() == TradeMode.PAPER)
                .startTime(session.getStartTime())
                .endTime(session.getEndTime())
                .stopReason(session.getStopReason())
                .history(history)
                .build();
    }

    private AlgoStatusResponse.CandleInfo toCandleInfo(Candle candle) {
        if (candle == null) return null;
        String time = candle.getOpenTime() != null
                ? candle.getOpenTime().toLocalTime().toString().substring(0, 5) : null;
        return AlgoStatusResponse.CandleInfo.builder()
                .close(candle.getClose())
                .time(time)
                .build();
    }

    private String resolveStopStatus(String reason) {
        if (reason == null) return "STOPPED";
        String r = reason.toLowerCase();
        if (r.contains("trailing stop"))  return "TRAILING_STOP";
        if (r.contains("target"))         return "TARGET_HIT";
        if (r.contains("stop loss") || r.contains("sl")) return "SL_HIT";
        if (r.contains("max reversal"))   return "MAX_REVERSALS";
        if (r.contains("end of day") || r.contains("eod")) return "EOD";
        return "STOPPED";
    }
}
