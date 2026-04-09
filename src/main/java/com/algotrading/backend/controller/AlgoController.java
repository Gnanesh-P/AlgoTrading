package com.algotrading.backend.controller;

import com.algotrading.backend.broker.BrokerServiceFactory;
import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.AlgoStartRequest;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.KiteInstrumentService;
import com.algotrading.backend.service.KiteTickerService;
import com.algotrading.backend.service.TradingStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Algo control endpoints that match the UI template's expected API shape.
 *
 * Multi-user safety rules (all 4 users share one Zerodha account):
 *   • Only ONE session may be WAITING or IN_POSITION at any time.
 *   • A second user calling /algo/start while a session is active gets HTTP 409.
 *   • All users can see who started the session via the "startedBy" field in /algo/status.
 *   • Any user may call /algo/stop to halt the running session.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AlgoController {

    private final MarketDataCache        cache;
    private final TradingStrategyService strategyService;
    private final KiteTickerService      tickerService;
    private final BrokerServiceFactory   brokerFactory;
    private final KiteInstrumentService  kiteInstrumentService;

    // ---- Start ----

    @PostMapping("/algo/start")
    public ResponseEntity<String> startAlgo(@RequestBody AlgoStartRequest req,
                                             Principal principal) {

        String startedBy = principal != null ? principal.getName() : "unknown";

        // ── Multi-user conflict guard ──────────────────────────────────────────
        // Only one session may be running at a time — all users share one Zerodha account.
        TradeSession existing = cache.getSession();
        if (existing != null && (existing.getState() == StrategyState.WAITING_FOR_CANDLES
                               || existing.getState() == StrategyState.IN_POSITION)) {
            String owner = existing.getStartedBy() != null ? existing.getStartedBy() : "another user";
            log.warn("Start rejected for [{}]: session already active, started by [{}]", startedBy, owner);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("A session is already running (started by: " + owner + "). "
                        + "Please wait for it to finish or ask them to stop it.");
        }
        // ──────────────────────────────────────────────────────────────────────

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
                .lotSize(req.getLotSize())
                .targetProfit(req.getTargetPrice())
                .stopLoss(req.getStopLoss())
                .maxReversals(req.getMaxReversals())
                .trailingProfit(req.getTrailingProfit())   // 0 = disabled
                .squareOffEod(req.isSquareOffEod())
                .tradeMode(req.isPaperTrade() ? TradeMode.PAPER : TradeMode.LIVE)
                .build();

        cache.setConfig(config);
        log.info("Algo config saved by [{}]: {}", startedBy, config);

        // ── Subscribe instruments for live WebSocket ticks ──────────────────
        // Build the token→symbol map. If the UI sent futureToken=0 (because Kite
        // was not connected when the dropdown loaded), fall back to the instrument
        // cache so futures ticks are always subscribed — this is the root cause of
        // the "no candles at mid-day" bug.
        Map<Long, String> instruments = new LinkedHashMap<>();

        if (req.getFutureToken() > 0) {
            instruments.put(req.getFutureToken(), req.getFutureSymbol());
        } else if (req.getFutureSymbol() != null && !req.getFutureSymbol().isBlank()) {
            kiteInstrumentService.findBySymbol(req.getFutureSymbol())
                    .filter(i -> i.getInstrumentToken() > 0)
                    .ifPresentOrElse(
                            i -> {
                                instruments.put(i.getInstrumentToken(), i.getTradingsymbol());
                                log.info("Futures token auto-resolved from cache: {} → token={}",
                                        i.getTradingsymbol(), i.getInstrumentToken());
                            },
                            () -> log.warn("Futures token=0 and NOT found in instrument cache for [{}]. " +
                                    "KiteTicker will NOT receive futures ticks → candles will never close. " +
                                    "Ensure Kite is connected and instruments are loaded before starting.",
                                    req.getFutureSymbol())
                    );
        }

        // CE / PE tokens — only needed for MANUAL mode (AUTO_ATM resolves them at 2nd candle close)
        if (req.getCeToken() > 0) instruments.put(req.getCeToken(), req.getCeSymbol());
        if (req.getPeToken() > 0) instruments.put(req.getPeToken(), req.getPeSymbol());

        if (!instruments.isEmpty()) {
            tickerService.subscribe(instruments);
        } else {
            log.warn("No instruments subscribed to KiteTicker — strategy will not receive any ticks!");
        }

        TradeSession session = strategyService.startStrategy(startedBy);
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
        Double currentPrice = null;
        double liveOpenPnL  = 0;
        if (openLeg != null) {
            double ltp = cache.getLastPrice(openLeg.getInstrument());
            if (ltp <= 0) {
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
                .startedBy(session.getStartedBy())
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
