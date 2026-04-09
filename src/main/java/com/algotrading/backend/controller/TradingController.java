package com.algotrading.backend.controller;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.TradeSessionResponse;
import com.algotrading.backend.model.TradeEntry;
import com.algotrading.backend.model.TradeSession;
import com.algotrading.backend.service.TradingStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
public class TradingController {

    private final TradingStrategyService strategyService;
    private final MarketDataCache cache;

    /** Start the trading strategy */
    @PostMapping("/start")
    public ResponseEntity<TradeSessionResponse> startStrategy(Principal principal) {
        String startedBy = principal != null ? principal.getName() : "unknown";
        TradeSession session = strategyService.startStrategy(startedBy);
        return ResponseEntity.ok(toResponse(session));
    }

    /** Stop the trading strategy manually */
    @PostMapping("/stop")
    public ResponseEntity<TradeSessionResponse> stopStrategy() {
        TradeSession session = strategyService.stopStrategy();
        return ResponseEntity.ok(toResponse(session));
    }

    /** Get current session status */
    @GetMapping("/session")
    public ResponseEntity<TradeSessionResponse> getSession() {
        TradeSession session = cache.getSession();
        if (session == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(toResponse(session));
    }

    /** Get trade history (all legs) */
    @GetMapping("/session/legs")
    public ResponseEntity<List<TradeSessionResponse.TradeLegResponse>> getTradeLegs() {
        TradeSession session = cache.getSession();
        if (session == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(session.getTradeLegs().stream()
                .map(this::toLegResponse)
                .collect(Collectors.toList()));
    }

    private TradeSessionResponse toResponse(TradeSession session) {
        TradeSessionResponse.TradeSessionResponseBuilder builder = TradeSessionResponse.builder()
                .sessionId(session.getSessionId())
                .tradeDate(session.getTradeDate())
                .state(session.getState())
                .reversalCount(session.getReversalCount())
                .maxReversals(session.getConfig().getMaxReversals())
                .cumulativePnL(session.getCumulativePnL())
                .openPnL(session.getOpenPnL())
                .totalPnL(session.getTotalPnL())
                .targetProfit(session.getConfig().getTargetProfit())
                .stopLoss(session.getConfig().getStopLoss())
                .stopReason(session.getStopReason())
                .startTime(session.getStartTime())
                .endTime(session.getEndTime())
                .lockedCeInstrument(session.getLockedCeInstrument())
                .lockedPeInstrument(session.getLockedPeInstrument())
                .lockedCeStrike(session.getLockedCeStrike())
                .lockedPeStrike(session.getLockedPeStrike())
                .tradeMode(session.getConfig().getTradeMode().name())
                .tradeLegs(session.getTradeLegs().stream()
                        .map(this::toLegResponse).collect(Collectors.toList()));

        if (session.getFirstCandle() != null) {
            builder.firstCandle(toCandleInfo(session.getFirstCandle()));
        }
        if (session.getSecondCandle() != null) {
            builder.secondCandle(toCandleInfo(session.getSecondCandle()));
        }
        if (session.getThirdCandle() != null) {
            builder.thirdCandle(toCandleInfo(session.getThirdCandle()));
        }

        return builder.build();
    }

    private TradeSessionResponse.TradeLegResponse toLegResponse(TradeEntry leg) {
        return TradeSessionResponse.TradeLegResponse.builder()
                .legNumber(leg.getLegNumber())
                .optionType(leg.getOptionType().name())
                .instrument(leg.getInstrument())
                .strikePrice(leg.getStrikePrice())
                .quantity(leg.getQuantity())
                .entryPrice(leg.getEntryPrice())
                .entryTime(leg.getEntryTime())
                .exitPrice(leg.getExitPrice())
                .exitTime(leg.getExitTime())
                .exitReason(leg.getExitReason())
                .pnl(leg.getPnl())
                .closed(leg.isClosed())
                .build();
    }

    private TradeSessionResponse.CandleInfo toCandleInfo(com.algotrading.backend.model.Candle c) {
        return TradeSessionResponse.CandleInfo.builder()
                .openTime(c.getOpenTime())
                .closeTime(c.getCloseTime())
                .open(c.getOpen())
                .high(c.getHigh())
                .low(c.getLow())
                .close(c.getClose())
                .build();
    }
}
